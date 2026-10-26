package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository.AlarmQuery;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.database.MigrationLineage;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;
import com.uav.lowaltitude.platform.report.BusinessReportSource.*;
import com.uav.lowaltitude.platform.time.AppClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-model integration against actual migrations and PostGIS, isolated from acceptance data. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class UavRiskCaseReadPostgresTest {
    private static final Database DATABASE=new Database();
    private static final AccessDecision ALL=new AccessDecision("isolated-case-reader",ScopeMode.ALL);
    @BeforeAll static void initialize(){DATABASE.initialize(null);}
    @AfterAll static void close(){DATABASE.close();}

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void twoAreasShareEventButNotAttentionProgressOrPermissions(String mode){
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle active=f.predict(id(),f.now-86400000),unknown=f.predict(id(),f.now-1000);
        f.confirm(active);f.confirm(unknown);
        String observed=f.enter(List.of(active,unknown));
        f.jdbc.update("UPDATE prediction_episode SET entered_evidence_valid=FALSE WHERE episode_id=?",unknown.episode());
        // Only this region loses its current spatial basis; the other measured case remains valid.
        f.jdbc.update("UPDATE airspace_version SET valid_to=? WHERE airspace_id=?",at(f.now),unknown.airspace());
        String actor=actor(f),authorization=authorization(f,active.episode(),active.event(),actor,"EXECUTING");
        // Execution on the first case must not pull the unknown second area into current work.
        assertThat(f.reader().count(query(f,null,List.of("CURRENT"),false),ALL)).isEqualTo(1);
        assertThat(f.reader().count(query(f,null,List.of("AWAITING_CONFIRMATION"),false),ALL)).isEqualTo(1);
        var ordered=f.reader().list(query(f,null,null,false),ALL,0,10,null,null);
        assertThat(ordered).extracting(AlarmReadRepository.AlarmRow::riskCaseId).containsExactly(active.episode(),unknown.episode());
        assertThat(ordered).extracting(AlarmReadRepository.AlarmRow::eventId).containsOnly(observed);
        assertThat(f.reader().list(query(f,null,null,false),ALL,1,1,null,null)).extracting(AlarmReadRepository.AlarmRow::riskCaseId).containsExactly(unknown.episode());
        assertThat(f.reader().listForExport(query(f,null,null,false),ALL,10,null,null)).containsExactlyElementsOf(ordered);
        var predictedOrigin=new AlarmQuery(null,null,f.target,null,null,null,null,f.mode,null,null,null,null,"PREDICTED_AIRSPACE_ENTRY",null,false,true,null);
        assertThat(f.reader().count(predictedOrigin,ALL)).isEqualTo(2);
        assertThat(f.reader().listForExport(predictedOrigin,ALL,10,null,null)).containsExactlyElementsOf(ordered);
        assertThat(f.identities().summary(active.episode())).doesNotContainKeys("counter","target_id");
        assertThat(f.reader().targetReadable(f.target,new AccessDecision(actor,ScopeMode.NONE))).isFalse();
        assertThat(f.identities().summary(active.episode(),new AccessDecision(actor,ScopeMode.NONE))).doesNotContainKey("counter");
        assertThat(f.identities().summary(active.episode(),new AccessDecision(id(),ScopeMode.ASSIGNED))).doesNotContainKey("counter");
        var ownCounter=(Map<?,?>)f.identities().summary(active.episode(),new AccessDecision(actor,ScopeMode.ASSIGNED)).get("counter");
        assertThat(ownCounter.get("authorization_id")).isEqualTo(authorization);
        assertThat(ownCounter.get("status")).isEqualTo("EXECUTING");
        assertThat(f.identities().summary(unknown.episode(),ALL)).doesNotContainKey("counter");
        var service=new AlarmReadService(mock(AccessControlService.class),f.reader(),mock(AuditService.class),f.clock(),new ObjectMapper(),mock(UavAdvisoryService.class));
        service.riskCases(f.identities());
        assertThat(service.riskProgress(active.episode(),observed,ALL,ALL)).isEqualTo("反制执行中");
        assertThat(service.riskProgress(unknown.episode(),observed,ALL,ALL)).isEqualTo("依据不足");
        assertThat(service.riskProgress(unknown.episode(),observed,ALL,null)).isEqualTo("依据不足");
    }

    @Test void unknownAndEndedFiltersStayIndependentOfOriginalEventVerdicts(){
        Fixtures f=new Fixtures(DATABASE,"live");
        Cycle entered=f.predict(id(),f.now-2000),cleared=f.predict(id(),f.now-1000);
        f.enter(List.of(entered));
        f.jdbc.update("UPDATE prediction_episode SET valid_until=? WHERE episode_id=?",f.now,entered.episode());
        // Expired future lines alone do not invalidate an established measured entry.
        f.jdbc.update("UPDATE target_degradation SET determined=FALSE WHERE target_id=?",f.target);
        f.jdbc.update("UPDATE prediction_episode SET status='CLEARED',active_key=NULL WHERE episode_id=?",cleared.episode());
        assertThat(f.identities().find(entered.episode()).stage()).isEqualTo("UNKNOWN");
        assertThat(f.reader().count(query(f,"UNKNOWN",null,false),ALL)).isEqualTo(1);
        assertThat(f.reader().count(query(f,"CLEARED",List.of("HISTORY"),false),ALL)).isEqualTo(1);
        assertThat(f.reader().count(query(f,null,null,true),ALL)).isEqualTo(1);
        assertThat(f.reader().count(query(f,null,null,false),new AccessDecision(id(),ScopeMode.NONE))).isZero();
        assertThat(f.jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,entered.event())).isEqualTo("PENDING_VERIFICATION");
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void alarmAndEventReportsCountFirstRiskDateOnceAcrossMeasuredTransition(String mode){
        Fixtures f=new Fixtures(DATABASE,mode);
        long yesterday=f.now-86400000;
        Cycle first=f.predict(id(),yesterday);f.confirm(first);
        String observed=f.enter(List.of(first));
        Cycle second=f.predict(id(),yesterday+1000);
        var day=Instant.ofEpochMilli(yesterday).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        var range=new Range(day,day,mode.equals("live")?SourceScope.LIVE:SourceScope.SIMULATED);
        var report=new ReportDatasetReader(f.jdbc);
        for(var dataset:List.of(f.reader().reportDataset(report,range,ALL),new UavEventRepository(f.jdbc).reportDataset(report,range,ALL))){
            var scoped=report.rows(dataset,range.sourceScope()).stream().filter(row->Set.of(first.episode(),second.episode()).contains(row.id())).toList();
            assertThat(scoped).hasSize(2);
            assertThat(scoped.stream().filter(row->row.id().equals(first.episode())).findFirst().orElseThrow().related()).isEqualTo(observed);
            assertThat(scoped).extracting(Row::occurredAt).containsExactlyInAnyOrder(yesterday,yesterday+1000);
        }
        AlarmQuery byDay=new AlarmQuery(null,null,f.target,at(range.start()),at(range.end()),null,null,mode,null,null,null,null,null,null,false,true,null);
        assertThat(f.reader().count(byDay,ALL)).isEqualTo(2);
        assertThat(f.reader().count(query(f,null,List.of("CURRENT"),false),ALL)).isEqualTo(2);
    }

    @Test void realDepartureAndCompletionCloseOnlyTheProvenObservedCase(){
        Fixtures f=new Fixtures(DATABASE,"replay");
        String event=f.event("RULE_LEGALITY",f.now-2000),areaA=id(),areaB=id();
        f.actualEntry(event,areaA);f.actualEntry(event,areaB);
        var cases=f.identities().cases(event);
        assertThat(cases).hasSize(2);
        String first=cases.stream().filter(c->areaA.equals(c.airspaceId())).findFirst().orElseThrow().riskCaseId();
        String second=cases.stream().filter(c->areaB.equals(c.airspaceId())).findFirst().orElseThrow().riskCaseId();
        f.jdbc.update("INSERT INTO uav_auto_voice_task(event_id,risk_scope,status,policy_code,reason,updated_at,provider_key,departure_channel,departure_started_at,departure_deadline_at,departure_evaluated_at,departure_observed_at,departure_longitude,departure_latitude,departure_basis) VALUES(?,?,'WAITING','test','真实撤离依据',?,?,'SMS',?,?,?,?,118,37,'OUTSIDE_AIRSPACE')",event,first,f.now,id(),f.now-1000,f.now,f.now,f.now);
        assertThat(f.identities().find(first).stage()).isEqualTo("CLEARED");
        assertThat(f.identities().find(second).stage()).isEqualTo("ENTERED");
        authorization(f,second,event,actor(f),"COMPLETED");
        assertThat(f.identities().find(second).stage()).isEqualTo("ENDED");
        assertThat(f.reader().count(query(f,null,List.of("HISTORY"),false),ALL)).isEqualTo(2);
        f.jdbc.update("UPDATE target_latest_state SET observed_at=? WHERE target_id=?",at(f.now-180000),f.target);
        assertThat(f.identities().find(first).stage()).isEqualTo("CLEARED");
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void legacyUnscopedDepartureCannotClearSharedEventAreasButExactScopeClearsOnlyItsCase(String mode){
        Fixtures f=new Fixtures(DATABASE,mode);
        String event=f.event("RULE_LEGALITY",f.now-2000),areaA=id(),areaB=id();
        f.actualEntry(event,areaA);f.actualEntry(event,areaB);
        var cases=f.identities().cases(event);
        assertThat(cases).hasSize(2);
        String first=cases.stream().filter(c->areaA.equals(c.airspaceId())).findFirst().orElseThrow().riskCaseId();
        String second=cases.stream().filter(c->areaB.equals(c.airspaceId())).findFirst().orElseThrow().riskCaseId();
        departure(f,event,"");
        // Even an old channel mapping does not identify an area when its event now has multiple cases.
        for(String risk:List.of(first,second)){
            f.jdbc.update("INSERT INTO uav_risk_channel(risk_case_id,channel,event_id,task_scope,claimed_at) VALUES(?,'VOICE',?,'',?)",risk,event,f.now);
        }
        assertThat(f.identities().find(first).stage()).isEqualTo("ENTERED");
        assertThat(f.identities().find(second).stage()).isEqualTo("ENTERED");
        assertThat(f.reader().count(query(f,"ENTERED",List.of("CURRENT"),false),ALL)).isEqualTo(2);
        assertThat(f.reader().count(query(f,"CLEARED",null,false),ALL)).isZero();

        departure(f,event,first);
        assertThat(f.identities().find(first).stage()).isEqualTo("CLEARED");
        assertThat(f.identities().find(second).stage()).isEqualTo("ENTERED");
        assertThat(f.reader().list(query(f,"CLEARED",List.of("HISTORY"),false),ALL,0,10,null,null))
                .extracting(AlarmReadRepository.AlarmRow::riskCaseId).containsExactly(first);
        assertThat(f.reader().list(query(f,"ENTERED",List.of("CURRENT"),false),ALL,0,10,null,null))
                .extracting(AlarmReadRepository.AlarmRow::riskCaseId).containsExactly(second);
        assertThat(f.jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,event)).isEqualTo("PENDING_VERIFICATION");
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void summaryBatchHasConstantQueryCountAndKeepsAreaPermissionsAndFreshState(String mode){
        Fixtures f=new Fixtures(DATABASE,mode);
        var cycles=new ArrayList<Cycle>();
        for(int index=0;index<12;index++)cycles.add(f.predict(id(),f.now-12000+index*1000));
        Cycle first=cycles.get(0),second=cycles.get(1);
        f.confirm(first);
        String observed=f.enter(List.of(first,second));
        String reader=actor(f),authorization=authorization(f,first.episode(),observed,reader,"EXECUTING");
        f.jdbc.update("INSERT INTO uav_risk_channel(risk_case_id,channel,event_id,task_scope,claimed_at) VALUES(?,'SMS',?,?,?)",
                first.episode(),first.event(),first.episode(),f.now);
        var counted=new CountingDataSource(DATABASE.dataSource());
        var identities=qa(new UavRiskCaseIdentity(new JdbcTemplate(counted),f.clock()));
        counted.statements.set(0);
        var one=identities.summaries(List.of(first.episode()),ALL);
        int singleQueries=counted.statements.get();
        counted.statements.set(0);
        var ids=cycles.stream().map(Cycle::episode).toList();
        var all=identities.summaries(ids,ALL);
        assertThat(counted.statements.get()).as("query count is bounded by a batch, not multiplied by page rows")
                .isEqualTo(singleQueries).isLessThanOrEqualTo(12);
        assertThat(all).hasSize(12);
        assertThat(all.get(first.episode())).isEqualTo(one.get(first.episode()));
        assertThat(all.get(first.episode())).containsEntry("stage","ENTERED").containsEntry("verification_status","CONFIRMED")
                .containsEntry("verification_basis_kind","PREDICTION").containsEntry("manual_counter_only",true)
                .containsEntry("notification_event_id",first.event());
        assertThat((List<?>)all.get(first.episode()).get("related_event_ids")).hasSize(2)
                .anyMatch(first.event()::equals).anyMatch(observed::equals);
        assertThat(((Map<?,?>)all.get(first.episode()).get("counter")).get("authorization_id")).isEqualTo(authorization);
        assertThat(all.get(second.episode())).containsEntry("stage","ENTERED").containsEntry("verification_status","PENDING_VERIFICATION")
                .doesNotContainKeys("counter","target_id");
        assertThat(all.get(cycles.get(2).episode())).containsEntry("stage","PREDICTED");
        for(var denied:Arrays.asList(null,new AccessDecision(reader,ScopeMode.NONE),new AccessDecision(id(),ScopeMode.ASSIGNED)))
            assertThat(identities.summaries(ids,denied).values()).allSatisfy(row->assertThat(row).doesNotContainKey("counter"));
        assertThat(identities.summaries(ids,new AccessDecision(reader,ScopeMode.ASSIGNED)).get(first.episode())).containsKey("counter");
        f.jdbc.update("UPDATE prediction_episode SET current_evidence_valid=FALSE WHERE episode_id=?",cycles.get(2).episode());
        assertThat(identities.summaries(ids,ALL).get(cycles.get(2).episode())).containsEntry("stage","UNKNOWN");
        counted.statements.set(0);
        assertThat(identities.summaries(List.of(),ALL)).isEmpty();
        assertThat(counted.statements.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void overviewMatchesScopedCountsAcrossAreaStagesDatesAndSources(String mode){
        Fixtures f=new Fixtures(DATABASE,mode);
        long from=Instant.ofEpochMilli(f.now).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        long to=from+86_400_000;
        Cycle yesterday=f.predict(id(),from-1),entered=f.predict(id(),from+1),unknown=f.predict(id(),from+2);
        for(Cycle cycle:List.of(yesterday,entered,unknown))f.confirm(cycle);
        String sharedEvent=f.enter(List.of(yesterday,entered,unknown));
        f.jdbc.update("UPDATE airspace_version SET valid_to=? WHERE airspace_id=?",at(f.now),unknown.airspace());
        Cycle pending=f.predict(id(),from+3),pendingUnknown=f.predict(id(),from+4),clearedPending=f.predict(id(),from+5);
        Cycle clearedConfirmed=f.predict(id(),from+6),stopping=f.predict(id(),from+7),falsePositive=f.predict(id(),from+8);
        f.predict(id(),from); // Inclusive start of the requested day.
        f.predict(id(),to);   // Exclusive end, even when the current prediction is available.
        f.confirm(clearedConfirmed);f.confirm(stopping);
        falsePositive(f,falsePositive);
        f.jdbc.update("UPDATE prediction_episode SET current_evidence_valid=FALSE WHERE episode_id=?",pendingUnknown.episode());
        for(Cycle cycle:List.of(clearedPending,clearedConfirmed,stopping))
            f.jdbc.update("UPDATE prediction_episode SET status='CLEARED',active_key=NULL WHERE episode_id=?",cycle.episode());
        String user=actor(f);
        authorization(f,stopping.episode(),stopping.event(),user,"EXECUTING");

        Fixtures mock=new Fixtures(DATABASE,"mock");
        mock.predict(id(),from+10);
        Cycle mockConfirmed=mock.predict(id(),from+11),mockFalse=mock.predict(id(),from+12),mockCleared=mock.predict(id(),from+13);
        mock.confirm(mockConfirmed);falsePositive(mock,mockFalse);
        mock.jdbc.update("UPDATE prediction_episode SET status='CLEARED',active_key=NULL WHERE episode_id=?",mockCleared.episode());
        f.jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES (?,?,?)",user,mock.org,mock.district);

        Fixtures forbidden=new Fixtures(DATABASE,"live".equals(mode)?"replay":"live");
        Cycle foreign=forbidden.predict(id(),from+20);forbidden.confirm(foreign);
        // Two partial grants must not be combined into a grant for the foreign pair.
        f.jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES (?,?,?)",user,forbidden.org,f.district);
        f.jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES (?,?,?)",user,f.org,forbidden.district);

        var reader=f.reader();
        AccessDecision own=new AccessDecision(user,ScopeMode.ASSIGNED);
        var overview=assertOverviewMatchesCounts(reader,own,from,to);
        assertThat(overview.today()).isEqualTo(new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.TodayCountsDto(13,4,5,2));
        assertThat(overview.excludedMock()).isEqualTo(new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.TodayCountsDto(4,1,1,1));
        assertThat(overview.pendingDisposal()).as("includes yesterday, unknown and unconfirmed device stop, not cleared settled risks")
                .isEqualTo(5);
        assertThat(f.identities().find(unknown.episode()).stage()).isEqualTo("UNKNOWN");
        assertThat(f.identities().find(entered.episode()).actionEventId()).isEqualTo(sharedEvent);
        assertThat(f.identities().find(pending.episode()).stage()).isEqualTo("PREDICTED");
        for(AccessDecision denied:List.of(new AccessDecision(user,ScopeMode.NONE),new AccessDecision(id(),ScopeMode.ASSIGNED))){
            var empty=assertOverviewMatchesCounts(reader,denied,from,to);
            var zero=new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.TodayCountsDto(0,0,0,0);
            assertThat(empty.today()).isEqualTo(zero);
            assertThat(empty.excludedMock()).isEqualTo(zero);
            assertThat(empty.pendingDisposal()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void legacyEventWithoutRiskCaseKeepsObservedAndCurrentPendingCounts(String mode){
        Fixtures f=new Fixtures(DATABASE,mode);
        String pending=f.event("RULE_LEGALITY",f.now-2000),confirmed=f.event("RULE_LEGALITY",f.now-1000);
        // Represent pre-link compatible records in this disposable schema only.
        // The live trigger and all existing business histories remain unchanged.
        for(String event:List.of(pending,confirmed)){
            f.jdbc.update("DELETE FROM uav_risk_case_member WHERE risk_case_id=?",event);
            f.jdbc.update("DELETE FROM uav_risk_case WHERE risk_case_id=?",event);
        }
        f.jdbc.update("UPDATE uav_event SET state_code='CONFIRMED' WHERE event_id=?",confirmed);
        AccessDecision own=new AccessDecision(actor(f),ScopeMode.ASSIGNED);
        var reader=f.reader();
        var observed=query(f,"OBSERVED",null,false);
        assertThat(reader.count(observed,own)).isEqualTo(2);
        assertThat(reader.list(observed,own,0,10,null,null)).extracting(AlarmReadRepository.AlarmRow::eventId)
                .containsExactlyInAnyOrder(pending,confirmed);
        assertThat(reader.list(observed,own,0,10,null,null)).allSatisfy(row->assertThat(row.riskCaseId()).isNull());
        for(String state:List.of("PENDING_VERIFICATION","CONFIRMED")){
            var raw=new AlarmQuery(state,null,f.target,null,null,null,null,mode,null,null,null,null,null,null,true,false,null);
            var risk=new AlarmQuery(state,null,f.target,null,null,null,null,mode,null,null,null,null,null,null,true,true,"OBSERVED");
            assertThat(reader.count(raw,own)).isEqualTo(1);
            assertThat(reader.count(risk,own)).as("unlinked %s uses its original event qualification",state)
                    .isEqualTo(reader.count(raw,own));
        }
        long from=Instant.ofEpochMilli(f.now).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        var overview=assertOverviewMatchesCounts(reader,own,from,from+86_400_000);
        assertThat(overview.today()).isEqualTo(new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.TodayCountsDto(2,1,1,0));
        assertThat(overview.pendingDisposal()).isEqualTo(1);
        // Observation expiry changes attention, not the compatibility stage or verification.
        f.jdbc.update("UPDATE target_latest_state SET observed_at=? WHERE target_id=?",at(f.now-180000),f.target);
        assertThat(reader.count(query(f,"OBSERVED",List.of("AWAITING_CONFIRMATION"),false),own)).isEqualTo(2);
        var stale=assertOverviewMatchesCounts(reader,own,from,from+86_400_000);
        assertThat(stale.today()).isEqualTo(overview.today());
        assertThat(stale.pendingDisposal()).isEqualTo(overview.pendingDisposal());
    }

    private static com.uav.lowaltitude.modules.alarm.api.AlarmDtos.OverviewDto assertOverviewMatchesCounts(
            AlarmReadRepository reader,AccessDecision scope,long from,long to){
        var overview=reader.overview(scope,from,to);
        assertThat(overview.occurredFrom()).isEqualTo(from);
        assertThat(overview.occurredTo()).isEqualTo(to);
        assertThat(overview.today()).isEqualTo(overviewDayCounts(reader,scope,from,to,null));
        assertThat(overview.excludedMock()).isEqualTo(overviewDayCounts(reader,scope,from,to,"mock"));
        var pending=new AlarmQuery("CONFIRMED",null,null,null,null,null,null,null,null,
                List.of("CURRENT","AWAITING_CONFIRMATION"),null,null,null,null,true,true,null);
        assertThat(overview.pendingDisposal()).isEqualTo(reader.count(pending,scope));
        return overview;
    }

    private static com.uav.lowaltitude.modules.alarm.api.AlarmDtos.TodayCountsDto overviewDayCounts(
            AlarmReadRepository reader,AccessDecision scope,long from,long to,String source){
        var counts=new long[4];
        String[] states={null,"PENDING_VERIFICATION","CONFIRMED","FALSE_POSITIVE"};
        for(int index=0;index<states.length;index++){
            var query=new AlarmQuery(states[index],null,null,at(from),at(to),null,null,source,null,null,
                    null,null,null,null,index==1,true,null);
            counts[index]=reader.count(query,scope);
        }
        return new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.TodayCountsDto(counts[0],counts[1],counts[2],counts[3]);
    }

    private static void falsePositive(Fixtures f,Cycle cycle){
        f.jdbc.update("INSERT INTO uav_risk_case_verification(verification_id,risk_case_id,case_version,event_id,event_version,conclusion,basis_kind,prediction_id,actor_id,note,created_at) VALUES(?,?,1,?,0,'FALSE_POSITIVE','PREDICTION',?,?,'isolated false positive fixture',?)",
                id(),cycle.episode(),cycle.event(),cycle.snapshot(),id(),f.now);
        f.jdbc.update("UPDATE uav_risk_case SET version=1 WHERE risk_case_id=?",cycle.episode());
    }

    private static final class CountingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        final java.util.concurrent.atomic.AtomicInteger statements=new java.util.concurrent.atomic.AtomicInteger();
        CountingDataSource(DataSource delegate){super(delegate);}
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException{return count(super.getConnection());}
        @Override public java.sql.Connection getConnection(String username,String password) throws java.sql.SQLException{return count(super.getConnection(username,password));}
        private java.sql.Connection count(java.sql.Connection connection){
            return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                if("prepareStatement".equals(method.getName()))statements.incrementAndGet();
                try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
        }
    }

    private static void departure(Fixtures f,String event,String scope){
        f.jdbc.update("INSERT INTO uav_auto_voice_task(event_id,risk_scope,status,policy_code,reason,updated_at,provider_key,departure_channel,departure_started_at,departure_deadline_at,departure_evaluated_at,departure_observed_at,departure_longitude,departure_latitude,departure_basis) VALUES(?,?,'WAITING','test','Recorded departure fixture',?,?,'SMS',?,?,?,?,?,?,'OUTSIDE_AIRSPACE')",
                event,scope,f.now,id(),f.now-1000,f.now,f.now,f.now,f.longitude,f.latitude);
    }

    @Test void snapshotNameAndEntryTimeAreNotInventedOrKeptAsCurrentAfterExpiry(){
        Fixtures f=new Fixtures(DATABASE,"live");Cycle cycle=f.predict(id(),f.now-1000);
        f.jdbc.update("UPDATE prediction_episode SET warning_json=CAST(? AS JSON) WHERE episode_id=?","{\"airspace_name\":\"Scoped example\",\"entry_at\":"+(f.now+20000)+"}",cycle.episode());
        assertThat(f.identities().summary(cycle.episode())).containsEntry("airspace_name","Scoped example").containsEntry("entry_at",f.now+20000);
        f.jdbc.update("UPDATE prediction_episode SET current_evidence_valid=FALSE WHERE episode_id=?",cycle.episode());
        assertThat(f.identities().summary(cycle.episode())).containsEntry("airspace_name","Scoped example").containsEntry("entry_at",null);
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void sharedMeasuredEventKeepsEachCasesOwnPredictionEvidenceAndHistory(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle first=f.predict(id(),f.now-2000),second=f.predict(id(),f.now-1000);
        String observed=f.enter(List.of(first,second));
        ObjectMapper json=new ObjectMapper();
        for(Cycle cycle:List.of(first,second)){
            f.jdbc.update("INSERT INTO prediction_episode_history(history_id,episode_id,prediction_id,version,state,reason,created_at) VALUES(?,?,?,1,'ACTIVE','STABLE_ENTRY_PREDICTION',?)",id(),cycle.episode(),cycle.snapshot(),f.now);
        }
        var identities=f.identities();
        var reader=new com.uav.lowaltitude.modules.prediction.application.PredictionReadService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(f.jdbc),mock(AccessControlService.class),json,f.clock());
        reader.riskCases(identities);
        var listFirst=alarm(f,observed).withRiskCase(identities.summary(first.episode()));
        var listSecond=alarm(f,observed).withRiskCase(identities.summary(second.episode()));
        reader.enrichList(List.of(listFirst,listSecond));
        assertThat(listFirst.getPrediction()).containsEntry("prediction_id",first.snapshot());
        assertThat(listSecond.getPrediction()).containsEntry("prediction_id",second.snapshot());
        // Request scope wins even if a caller still holds an old display summary.
        var detail=identities.withCase(first.episode(),()->reader.enrich(alarm(f,observed).withRiskCase(identities.summary(second.episode())),observed,true));
        assertThat(detail.getPredictionSnapshot()).containsEntry("prediction_id",first.snapshot());
        assertThat(((Map<?,?>)detail.getPredictionSnapshot().get("warning")).get("airspace_id")).isEqualTo(first.airspace());
        assertThat(detail.getPredictionHistory()).extracting(row->row.get("episode_id")).containsExactly(first.episode());
        var redacted=identities.withCase(second.episode(),()->reader.enrich(alarm(f,observed),observed,false));
        assertThat(redacted.getPrediction()).doesNotContainKey("target_id");
        assertThat(redacted.getPredictionSnapshot()).containsEntry("prediction_id",second.snapshot());
        assertThat(redacted.getPredictionHistory()).extracting(row->row.get("episode_id")).containsExactly(second.episode());
        assertThat(reader.enrich(alarm(f,observed),observed,true).getPredictionHistory()).hasSize(2);
    }

    private static com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto alarm(Fixtures f,String event){
        return new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto(id(),event,"PENDING_VERIFICATION","RULE_LEGALITY","HIGH",f.now,
                f.now,"source",f.mode,f.org,f.district,f.target,"ALARM","source","org","district","target","HIGH",List.of(),0,null,"CURRENT","CURRENT",null);
    }

    private static AlarmQuery query(Fixtures f,String stage,List<String> attention,boolean pending){
        return new AlarmQuery(pending?"PENDING_VERIFICATION":null,null,f.target,null,null,null,null,f.mode,null,attention,null,null,null,null,pending,true,stage);
    }
    private static String actor(Fixtures f){
        String actor=id(),role="RISK-"+id().replace("-", "").substring(0, 24);
        f.jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,?,'',FALSE,TRUE,0,0,0,FALSE)",role,role);
        f.jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",actor,"read-"+id(),"Isolated reader",role);
        f.jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES (?,?,?)",actor,f.org,f.district);
        return actor;
    }
    private static String authorization(Fixtures f,String risk,String event,String actor,String state){
        String id=id(),policy=f.jdbc.queryForObject("SELECT policy_code FROM disposal_policy ORDER BY policy_code FETCH FIRST 1 ROW ONLY",String.class);
        f.jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,policy_version,owner_org_id,district_id,source_mode,created_at,updated_at,risk_case_id) VALUES (?,?,'COUNTERMEASURE','UAV_EVENT',?,?,?,'COUNTERMEASURE_4CH','Isolated test',?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,"AUTH-"+id.replace("-", "").substring(0, 24),event,f.target,id(),actor,at(f.now),actor,at(f.now),at(f.now-1000),at(f.now+600000),state,policy,f.org,f.district,f.mode,at(f.now),at(f.now),risk);
        return id;
    }

    private record Cycle(String episode,String event,String airspace,String snapshot) { }

    /** Direct inserts are isolated fixture facts, not a shortcut through runtime verification or notification APIs. */
    private static final class Fixtures {
        final Database database;
        final JdbcTemplate jdbc;
        final String target=id(),org=id(),district=id(),source=id(),parameter=id(),ruleSet=id(),ruleVersion=id(),run=id(),mode;
        final long now=Instant.now().toEpochMilli();
        String measuredDevice,measuredTrack,measuredSession,measuredExternal;
        final double longitude=117+Math.random(),latitude=36+Math.random();
        Fixtures(Database database,String mode) {
            this.database=database;this.jdbc=database.jdbc();this.mode=mode;
            jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",org,org,"Isolated risk owner");
            jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",district,district,"Isolated risk district");
            jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES(?,?,?,TRUE,?,?,?,0)",source,source,"Isolated measured source",mode,at(now),at(now));
            jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,'UAV',?,?,?,?,?)",target,"RISK-"+target,mode,org,district,at(now),at(now));
            jdbc.update("INSERT INTO target_latest_state(target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at) VALUES(?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?,CAST('[]' AS JSON),?,?)",target,longitude,latitude,at(now),at(now),at(now),at(now));
            jdbc.update("INSERT INTO prediction_parameter_version(version_id,parameters_json,created_at) VALUES(?,CAST('{}' AS JSON),?)",parameter,now);
            jdbc.update("INSERT INTO rule_set(rule_set_id,rule_set_code,name,created_at,updated_at) VALUES(?,?,'Isolated risk rule',?,?)",ruleSet,ruleSet,at(now),at(now));
            jdbc.update("INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,source_mode,created_at) VALUES(?,?,1,'DRAFT','DEMO',?,?,?)",ruleVersion,ruleSet,at(now),mode,at(now));
            if(jdbc.queryForObject("SELECT COUNT(*) FROM rule_param p JOIN rule_set s ON s.active_version_id=p.rule_set_version_id WHERE p.rule_code='C03' AND p.param_key='fresh_seconds'",Long.class)==0){
                jdbc.update("INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,param_status) VALUES (?,?,'C03','fresh_seconds','120','INTEGER','DEMO')",id(),ruleVersion);
                jdbc.update("UPDATE rule_set SET active_version_id=? WHERE rule_set_id=?",ruleVersion,ruleSet);
            }
            jdbc.update("INSERT INTO rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,finished_at,status,source_mode,created_at) VALUES(?,?,?,'ACTIVE','MANUAL',?,?,?,'DONE',?,?)",run,ruleSet,ruleVersion,at(now),at(now),at(now),mode,at(now));
        }
        AppClock clock() { return new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)); }
        UavRiskCaseIdentity identities() { return qa(new UavRiskCaseIdentity(jdbc,clock())); }
        AlarmReadRepository reader() {
            FusionConfigService fusion=mock(FusionConfigService.class);
            FusionParams params=mock(FusionParams.class);
            when(fusion.params(null)).thenReturn(params);
            when(params.integer("identity","terminate_after_ms")).thenReturn(60_000);
            var reader=new AlarmReadRepository(jdbc,clock(),fusion);
            var env=qaEnvironment();reader.simulation(new com.uav.lowaltitude.platform.config.SimulationPolicy(env),env);return reader;
        }
        <T> T tx(Supplier<T> operation) {
            TransactionTemplate transaction=new TransactionTemplate(new DataSourceTransactionManager(database.dataSource()));
            transaction.setTimeout(8);
            return transaction.execute(status -> operation.get());
        }
        String event(String type,long received) {
            return event(type,received,org,mode);
        }
        String event(String type,long received,String eventOrg,String eventMode) {
            return event(type,received,eventOrg,eventMode,"{}");
        }
        String event(String type,long received,String eventOrg,String eventMode,String frozenDetail) {
            String alarm=id(),event=id(),number="RISK-"+id().replace("-", "").substring(0, 24);
            String eventSource=source;
            if(!eventMode.equals(mode)) {
                eventSource=id();
                jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES(?,?,?,TRUE,?,?,?,0)",eventSource,eventSource,"Other isolated source",eventMode,at(now),at(now));
            }
            jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_no,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at,detail) VALUES(?,?,?,?,?,?,'HIGH',?,?,?,?,?,?,CAST(? AS JSON))",
                    alarm,target,eventSource,number,number,type,at(received),at(received),eventMode,eventOrg,district,at(received),frozenDetail);
            jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'PENDING_VERIFICATION',?,?,?,?,0)",event,alarm,eventOrg,district,at(received),at(received));
            return event;
        }
        Cycle predict(String airspace,long started) {
            String episode=id(),snapshot=id();
            // Evidence is complete at insertion; the fixture obeys the same immutable rows as production.
            String result=json(Map.of("prediction_id",snapshot,"target_id",target,"status","READY","valid_until",now+60_000));
            String frozen=json(Map.of("prediction_id",snapshot,"prediction",Map.of("airspace_id",airspace,"airspace_name",airspace)));
            String event=event("PREDICTED_AIRSPACE_ENTRY",started,org,mode,frozen);
            jdbc.update("INSERT INTO prediction_snapshot(prediction_id,target_id,generated_at,based_on_observed_at,valid_until,status,reason,model_version,rule_version,input_json,config_json,result_json,owner_org_id,district_id,source_mode,simulated) VALUES(?,?,?,?,?,'READY','READY','K03_ISOLATED',?,CAST('{}' AS JSON),CAST('{}' AS JSON),CAST(? AS JSON),?,?,?,?)",
                    snapshot,target,now,now,now+60_000,parameter,result,org,district,mode,"replay".equals(mode));
            jdbc.update("INSERT INTO prediction_episode(episode_id,target_id,airspace_id,active_key,source_mode,owner_org_id,district_id,status,latest_prediction_id,first_prediction_id,last_observed_at,valid_until,current_evidence_valid,entered_evidence_valid,reason,started_at,stability_json,warning_json) VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,TRUE,FALSE,'ISOLATED_EVIDENCE',?,CAST('{}' AS JSON),CAST('{}' AS JSON))",
                    episode,target,airspace,target+":"+airspace,mode,org,district,snapshot,snapshot,now,now+60_000,started);
            jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES(?,?,'PREDICTED',?)",event,episode,started);
            return new Cycle(episode,event,airspace,snapshot);
        }
        void confirm(Cycle cycle) {
            tx(() -> {
                identities().lockCases(cycle.event());
                long version=identities().find(cycle.episode()).version()+1;
                jdbc.update("INSERT INTO uav_risk_case_verification(verification_id,risk_case_id,case_version,event_id,event_version,conclusion,basis_kind,prediction_id,actor_id,note,created_at) VALUES(?,?,?,?,0,'CONFIRMED','PREDICTION',?,?,'isolated human confirmation fixture',?)",
                        id(),cycle.episode(),version,cycle.event(),cycle.snapshot(),id(),now);
                jdbc.update("UPDATE uav_risk_case SET version=? WHERE risk_case_id=?",version,cycle.episode());
                return null;
            });
        }
        String enter(List<Cycle> cycles) {
            String observed=event("RULE_LEGALITY",now);
            for(Cycle cycle:cycles) {
                actualEntry(observed,cycle.airspace());
                jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES(?,?,'OBSERVED',?)",observed,cycle.episode(),now);
                jdbc.update("UPDATE prediction_episode SET status='ENTERED',entered_evidence_valid=TRUE WHERE episode_id=?",cycle.episode());
            }
            return observed;
        }
        void actualEntry(String event,String airspace) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM track WHERE target_id=?",Long.class,target)==0)measuredPoint(now,longitude,100);
            String evaluation=id();
            var existing=jdbc.queryForList("SELECT evaluation_id FROM rule_evaluation WHERE alarm_id=(SELECT alarm_id FROM uav_event WHERE event_id=?) ORDER BY evaluated_at DESC,evaluation_id DESC FETCH FIRST 1 ROW ONLY",String.class,event);
            if(!existing.isEmpty())evaluation=existing.get(0);
            else jdbc.update("INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons) VALUES(?,?,?,'ACTIVE','TARGET',?,?,?,?,'FRESH','NONE','ILLEGAL',90,'HIGH',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),(SELECT alarm_id FROM uav_event WHERE event_id=?),?,?,?,?,'SUFFICIENT','isolated-measured',CAST('[]' AS JSON))",
                    evaluation,run,ruleVersion,target,at(now),at(now),at(now),event,org,district,mode,at(now));
            String version=id();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM airspace WHERE airspace_id=?",Long.class,airspace)==0){
                jdbc.update("INSERT INTO airspace(airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,'Isolated measured region',?,?,?,?,?)",airspace,airspace,mode,org,district,at(now),at(now));
                jdbc.update("INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) VALUES (?,?,1,'PROHIBITED',ST_Multi(ST_MakeEnvelope(?,?,?,?,4326)),0,200,'AMSL',?,?)",version,airspace,longitude-.01,latitude-.01,longitude+.01,latitude+.01,at(now-60_000),at(now));
            }else version=jdbc.queryForObject("SELECT airspace_version_id FROM airspace_version WHERE airspace_id=? ORDER BY version_no DESC FETCH FIRST 1 ROW ONLY",String.class,airspace);
            jdbc.update("INSERT INTO prediction_actual_entry_link(event_id,target_id,airspace_id,observed_at,evaluation_id,airspace_version_id) VALUES(?,?,?,?,?,?)",event,target,airspace,now,evaluation,version);
        }
        void measuredPoint(long observed,double lon,double amsl){measuredPoint(observed,lon,amsl,"{}");}
        void measuredPoint(long observed,double lon,double amsl,String quality){
            if(measuredDevice==null){
                measuredDevice=id();measuredTrack=id();measuredSession=id();measuredExternal=id();
                jdbc.update("INSERT INTO target_degradation(target_id,level,available_source_ids,confidence_deficit,determined,since,updated_at) VALUES(?,'SINGLE_SOURCE',CAST('[]' AS JSON),0,TRUE,?,?)",target,at(observed),at(observed));
                jdbc.update("UPDATE integration_source SET protocol_code='SIM_NORMALIZED' WHERE source_id=?",source);
                jdbc.update("INSERT INTO device(device_id,source_id,external_device_id,device_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,?,?,TRUE,?,?,?,?,?)",measuredDevice,source,measuredDevice,measuredDevice,"Isolated measured input",mode,org,district,at(observed),at(observed));
                if("replay".equals(mode))jdbc.update("INSERT INTO simulator_observation_source(source_id,device_id,message_id,owner_org_id,district_id,request_hash,created_at) VALUES (?,?,?,?,?,REPEAT('a',64),?)",source,measuredDevice,id(),org,district,at(observed));
                jdbc.update("INSERT INTO target_source_link(link_id,target_id,source_id,device_id,source_session_key,external_target_id,created_at) VALUES(?,?,?,?,?,?,?)",id(),target,source,measuredDevice,measuredSession,measuredExternal,at(observed));
                jdbc.update("INSERT INTO track(track_id,target_id,external_track_id,layer,started_at,created_at) VALUES(?,?,?,'FUSED',?,?)",measuredTrack,target,measuredTrack,at(observed),at(observed));
            }
            String inbox=id(),observation=id();
            jdbc.update("INSERT INTO inbox_message(inbox_id,source,source_msg_id,received_at,processed_at,source_id,payload_hash,payload,status) VALUES(?,?,?,?,?,?,REPEAT('a',64),CAST('{}' AS JSON),'DONE')",inbox,"sim-normalized:"+source,id(),observed,observed,source);
            jdbc.update("INSERT INTO source_observation(observation_id,inbox_id,source_id,device_id,source_session_key,external_target_id,observed_at,received_at,location,position_accuracy_m,altitude_amsl_m,quality,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,?,?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326),2,?,CAST(? AS JSON),?,?,?,?)",observation,inbox,source,measuredDevice,measuredSession,measuredExternal,at(observed),at(observed),lon,latitude,amsl,quality,mode,org,district,at(observed));
            long sequence=jdbc.queryForObject("SELECT COUNT(*)+1 FROM track_point WHERE track_id=?",Long.class,measuredTrack);
            jdbc.update("INSERT INTO track_point(point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,point_kind,observation_id,position_accuracy_m,position_source_id,created_at) VALUES(?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326),?,'MEAS',?,2,?,?)",id(),measuredTrack,sequence,at(observed),at(observed),lon,latitude,amsl,observation,source,at(observed));
            jdbc.update("UPDATE target_latest_state SET location=ST_SetSRID(ST_MakePoint(?,?),4326),altitude_amsl_m=?,observed_at=?,received_at=? WHERE target_id=?",lon,latitude,amsl,at(observed),at(observed),target);
        }

    }

    /** Same isolation guard as DeviceMonitoringPostgresFixture, with an old-version migration stage for upgrade tests. */
    private static final class Database implements AutoCloseable {
        private final String schema="risk_read_"+id().replace("-", "");
        private boolean created;
        private DataSource scopedDataSource;
        void initialize(String target) {
            verifySchema();
            JdbcTemplate root=new JdbcTemplate(rootDataSource());
            String name=root.queryForObject("SELECT current_database()",String.class);
            if(name==null||!name.matches("stage456_verify_[a-z0-9_]+"))throw new IllegalStateException("Isolated database required");
            root.execute("CREATE SCHEMA "+schema);created=true;
            try { migrate(target); } catch(RuntimeException failure) { close();throw failure; }
        }
        void migrate(String target) {
            var config=Flyway.configure().dataSource(rootDataSource()).schemas(schema).defaultSchema(schema)
                    .createSchemas(false).cleanDisabled(true).locations("classpath:db/migration","classpath:db/postgresql");
            if(target!=null)config.target(target);
            MigrationLineage.configure(config);
            config.load().migrate();
        }
        synchronized DataSource dataSource() {
            // Spring binds JDBC transactions to this exact DataSource instance, including worker threads.
            if(scopedDataSource==null)scopedDataSource=new DriverManagerDataSource(url()+"?currentSchema="+schema+",public",env("POSTGRES_TEST_USER"),env("POSTGRES_TEST_PASSWORD"));
            return scopedDataSource;
        }
        DataSource rootDataSource() { return new DriverManagerDataSource(url(),env("POSTGRES_TEST_USER"),env("POSTGRES_TEST_PASSWORD")); }
        JdbcTemplate jdbc() { return new JdbcTemplate(dataSource()); }
        String url() {
            verifySchema();String url=env("POSTGRES_TEST_URL");
            if(!url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))throw new IllegalStateException("Unsafe PostgreSQL test URL");
            return url;
        }
        void verifySchema() { if(!schema.matches("risk_read_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe disposable schema"); }
        @Override public void close() {
            if(!created)return;verifySchema();
            new JdbcTemplate(rootDataSource()).execute("DROP SCHEMA "+schema+" CASCADE");created=false;
        }
        static String env(String name) { String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value; }
    }
    private static org.springframework.mock.env.MockEnvironment qaEnvironment(){
        var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("local","qa");
        env.setProperty("app.flight-device-check.simulator-device-bridge-enabled","true");return env;
    }
    private static UavRiskCaseIdentity qa(UavRiskCaseIdentity identity){
        var env=qaEnvironment();identity.simulation(new com.uav.lowaltitude.platform.config.SimulationPolicy(env),env);return identity;
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static String json(Object value) {
        try { return new ObjectMapper().writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid isolated fixture",invalid); }
    }
    private static OffsetDateTime at(long millis) { return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC); }
}
