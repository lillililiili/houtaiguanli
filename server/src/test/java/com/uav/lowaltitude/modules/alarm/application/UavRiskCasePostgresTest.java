package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository.AlarmQuery;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.database.MigrationLineage;
import com.uav.lowaltitude.platform.time.AppClock;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Real migrations, PostgreSQL locks and list SQL in disposable schemas. No Spring app, jobs or business seed. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class UavRiskCasePostgresTest {
    private static final Database DATABASE = new Database();
    private static final AccessDecision ALL = new AccessDecision("isolated-risk-reader", ScopeMode.ALL);

    @BeforeAll static void initialize() { DATABASE.initialize(null); }
    @AfterAll static void close() { DATABASE.close(); }
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void predictionHandsOffToAutomaticMeasuredVerificationOnceWithoutChangingRawVerdictsOrChannels(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);Cycle cycle=f.predict(id(),f.now-20_000);
        AutoVerification automatic=new AutoVerification(f);
        assertThat(automatic.evaluate(cycle,cycle.event())).isEqualTo("PAUSED");
        automatic.confirm(cycle,cycle.event());
        assertThat(f.identities().find(cycle.episode()).confirmed()).isFalse();
        String measured=f.enter(List.of(cycle));
        assertThat(automatic.evaluate(cycle,cycle.event())).as("old prediction alias is never measured evidence").isEqualTo("PAUSED");
        assertThat(automatic.evaluate(cycle,measured)).isEqualTo("PASS");
        automatic.confirm(cycle,measured);automatic.confirm(cycle,measured);
        assertThat(f.identities().find(cycle.episode()).confirmed()).isTrue();
        assertThat(f.identities().find(cycle.episode()).manualCounterOnly()).isTrue();
        assertThat(f.reader().count(query(f,true,null,null),ALL)).isEqualTo(1);
        var ledger=f.jdbc.queryForMap("SELECT trigger_mode,actor_id,event_id,basis_kind,automation_run_id FROM uav_risk_case_verification WHERE risk_case_id=?",cycle.episode());
        assertThat(ledger).containsEntry("trigger_mode","AUTO").containsEntry("actor_id",null)
                .containsEntry("event_id",measured).containsEntry("basis_kind","OBSERVED");
        assertThat(ledger.get("automation_run_id")).isNotNull();
        String frozen=f.jdbc.queryForObject("SELECT CAST(basis_json AS VARCHAR) FROM uav_risk_case_verification WHERE risk_case_id=?",String.class,cycle.episode());
        assertThat(frozen).contains("OBSERVED","ENTERED",cycle.airspace(),"disposition_only", "true", "evaluation_id", "airspace_version_id");
        for(String event:List.of(cycle.event(),measured)) {
            assertThat(f.jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,event)).isEqualTo("PENDING_VERIFICATION");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_verification WHERE event_id=?",Long.class,event)).isZero();
        }
        assertThat(selected(cycle.episode(),()->automatic.gate.blocksAutomatic("counter",measured))).isTrue();
        for(String channel:List.of("SMS","VOICE")) {
            assertThat(f.tx(()->selected(cycle.episode(),()->f.identities().claimChannel(cycle.event(),channel)))).isTrue();
            assertThat(f.tx(()->selected(cycle.episode(),()->f.identities().claimChannel(measured,channel)))).isFalse();
            assertThat(f.identities().channelOwner(cycle.episode(),channel)).isEqualTo(cycle.event());
        }
        automatic.restart();automatic.confirm(cycle,measured);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id=?",Long.class,cycle.episode())).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"CONFIRMED","FALSE_POSITIVE"})
    void priorHumanVerdictIsNeverReplacedWhenMeasuredEntryArrives(String conclusion) {
        Fixtures f=new Fixtures(DATABASE,"live");Cycle cycle=f.predict(id(),f.now-20_000);f.verdict(cycle,conclusion);
        String measured=f.enter(List.of(cycle));AutoVerification automatic=new AutoVerification(f);
        automatic.evaluate(cycle,measured);automatic.confirm(cycle,measured);
        assertThat(f.jdbc.queryForList("SELECT conclusion FROM uav_risk_case_verification WHERE risk_case_id=?",String.class,cycle.episode())).containsExactly(conclusion);
        assertThat(f.jdbc.queryForObject("SELECT trigger_mode FROM uav_risk_case_verification WHERE risk_case_id=?",String.class,cycle.episode())).isEqualTo("MANUAL");
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void automaticVerificationFreezesLatestSameObservationReevaluationAndEffectiveAirspaceVersion(String mode) throws Exception {
        Fixtures f=new Fixtures(DATABASE,mode);Cycle cycle=f.predict(id(),f.now-20_000);String measured=f.enter(List.of(cycle));
        var original=f.jdbc.queryForMap("SELECT evaluation_id,airspace_version_id,observed_at FROM prediction_actual_entry_link WHERE event_id=? AND airspace_id=?",measured,cycle.airspace());
        String originalEvaluation=original.get("evaluation_id").toString(),originalVersion=original.get("airspace_version_id").toString();
        // The same observation may be reevaluated at the same millisecond. Exercise the explicit
        // evaluation-ID descending tie-break with an independently generated string identifier.
        String latestEvaluation="review-"+id().replace("-","").substring(0,29),latestVersion=id();
        assertThat(latestEvaluation.compareTo(originalEvaluation)).isPositive();
        f.jdbc.update("UPDATE airspace_version SET valid_to=? WHERE airspace_version_id=?",at(f.now),originalVersion);
        f.jdbc.update("INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) SELECT ?,airspace_id,version_no+1,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,?,? FROM airspace_version WHERE airspace_version_id=?",latestVersion,at(f.now),at(f.now),originalVersion);
        f.jdbc.update("INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons) SELECT ?,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons FROM rule_evaluation WHERE evaluation_id=?",latestEvaluation,originalEvaluation);
        f.jdbc.update("INSERT INTO prediction_actual_entry_link(event_id,target_id,airspace_id,observed_at,evaluation_id,airspace_version_id) VALUES(?,?,?,?,?,?)",measured,f.target,cycle.airspace(),f.now,latestEvaluation,latestVersion);
        assertThat(f.identities().currentMeasuredArea(f.identities().find(cycle.episode()))).isTrue();
        AutoVerification automatic=new AutoVerification(f);assertThat(automatic.evaluate(cycle,measured)).isEqualTo("PASS");automatic.confirm(cycle,measured);
        var snapshot=new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.jdbc.queryForObject("SELECT CAST(basis_json AS VARCHAR) FROM uav_risk_case_verification WHERE risk_case_id=?",String.class,cycle.episode()));
        var entries=snapshot.path("measured_entries");assertThat(entries.size()).isEqualTo(1);
        assertThat(entries.get(0).path("evaluation_id").asText()).isEqualTo(latestEvaluation);
        assertThat(entries.get(0).path("airspace_version_id").asText()).isEqualTo(latestVersion);
        assertThat(entries.get(0).path("event_id").asText()).isEqualTo(measured);
        assertThat(entries.get(0).path("observed_at").asLong()).isEqualTo(((Number)original.get("observed_at")).longValue());
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prediction_actual_entry_link WHERE event_id=?",Long.class,measured)).isEqualTo(2);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"live,inside","replay,inside","live,outside","replay,outside",
            "live,expired","replay,expired","live,source-changed","replay,source-changed"})
    void finalMeasuredSnapshotRechecksCommittedChangesAfterRuleEligibility(String mode,String change) throws Exception {
        Fixtures f=new Fixtures(DATABASE,mode);Cycle cycle=f.predict(id(),f.now-20_000);String measured=f.enter(List.of(cycle));
        var wallTime=new java.util.concurrent.atomic.AtomicLong(f.now);
        AppClock clock=mock(AppClock.class);
        when(clock.nowMillis()).thenAnswer(ignored->wallTime.get());
        when(clock.now()).thenAnswer(ignored->Instant.ofEpochMilli(wallTime.get()));
        var identities=spy(qa(new UavRiskCaseIdentity(f.jdbc,clock)));
        AutoVerification automatic=new AutoVerification(f,clock,identities);
        assertThat(automatic.evaluate(cycle,measured)).isEqualTo("PASS");
        String run=selected(cycle.episode(),()->automatic.runs.state("verify",measured).runId());
        var eligibility=spy(automatic.eligibility);
        var rulePassed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{
            boolean allowed=(boolean)invocation.callRealMethod();
            if(allowed)rulePassed.set(true);
            return allowed;
        }).when(eligibility).allowsRun("verify",measured,run);
        automatic.verification=new AlarmRuleVerification(f.jdbc,new com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository(f.jdbc),
                mock(com.uav.lowaltitude.platform.audit.AuditService.class),clock,eligibility);
        automatic.verification.cases(identities);automatic.verification.setPredictionEventGate(automatic.gate);

        var writer=Executors.newSingleThreadExecutor();
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        var frozenEntries=new java.util.concurrent.atomic.AtomicReference<List<Map<String,Object>>>();
        doAnswer(invocation->{
            if(changed.compareAndSet(false,true)) {
                assertThat(rulePassed).as("The real rule guard passed before final measured evidence was read").isTrue();
                assertThat(f.jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("read committed");
                if("expired".equals(change)) {
                    wallTime.set(f.now+121_000);
                } else {
                    // A separate connection commits a complete observation after qualification.
                    // It does not alter the locked risk, event, rule run or verification ledger.
                    writer.submit(()->f.tx(()->{
                        f.measuredPoint(f.now+500,f.longitude+("outside".equals(change)?.02:.001),100);
                        if("source-changed".equals(change))
                            f.jdbc.update("UPDATE integration_source SET source_mode=? WHERE source_id=?","live".equals(mode)?"replay":"live",f.source);
                        return null;
                    })).get(5,TimeUnit.SECONDS);
                    wallTime.set(f.now+700);
                }
            }
            @SuppressWarnings("unchecked")
            var entries=(List<Map<String,Object>>)invocation.callRealMethod();
            frozenEntries.set(entries);
            return entries;
        }).when(identities).currentMeasuredEntries(any(UavRiskCaseIdentity.CaseRef.class));
        try {
            automatic.confirm(cycle,measured,run);
            assertThat(changed).isTrue();
            if("inside".equals(change)) {
                assertThat(f.jdbc.queryForObject("SELECT observed_at FROM target_latest_state WHERE target_id=?",java.sql.Timestamp.class,f.target).getTime()).isEqualTo(f.now+500);
                assertThat(frozenEntries.get()).hasSize(1);
                var snapshot=new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.jdbc.queryForObject("SELECT CAST(basis_json AS VARCHAR) FROM uav_risk_case_verification WHERE risk_case_id=?",String.class,cycle.episode()));
                var entry=snapshot.path("measured_entries");assertThat(entry.size()).isEqualTo(1);
                var accepted=frozenEntries.get().get(0);
                for(String key:List.of("event_id","evaluation_id","airspace_version_id"))
                    assertThat(entry.get(0).path(key).asText()).isEqualTo(accepted.get(key).toString());
                assertThat(entry.get(0).path("observed_at").asLong()).isEqualTo(((Number)accepted.get("observed_at")).longValue());
                assertThat(snapshot.path("checked_at").asLong()).isEqualTo(f.now+700);
                assertThat(snapshot.path("disposition_only").asBoolean()).isTrue();
            } else assertThat(frozenEntries.get()).isEmpty();

            automatic.confirm(cycle,measured,run);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id=?",Long.class,cycle.episode()))
                    .isEqualTo("inside".equals(change)?1L:0L);
            for(String event:List.of(cycle.event(),measured)) {
                assertThat(f.jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,event)).isEqualTo("PENDING_VERIFICATION");
                assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_verification WHERE event_id=?",Long.class,event)).isZero();
            }
        } finally {
            writer.shutdownNow();assertThat(writer.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void lockedCaseMembershipRejectsAnEventFromAnotherRisk() {
        Fixtures f=new Fixtures(DATABASE,"live");Cycle own=f.predict(id(),f.now-2000),other=f.predict(id(),f.now-1000);
        assertThatThrownBy(()->f.tx(()->selected(other.episode(),()->{f.identities().lockCases(own.event());return null;})))
                .isInstanceOfSatisfying(com.uav.lowaltitude.platform.api.ApiException.class,error->
                        assertThat(error.getCode()).isEqualTo("RISK_CASE_EVENT_MISMATCH"));
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id IN (?,?)",Long.class,own.episode(),other.episode())).isZero();
    }

    @Test void lockedCaseVersionRejectsAMismatchedSelectedVersion() {
        Fixtures f=new Fixtures(DATABASE,"replay");Cycle cycle=f.predict(id(),f.now-1000);
        long current=f.jdbc.queryForObject("SELECT version FROM uav_risk_case WHERE risk_case_id=?",Long.class,cycle.episode());
        assertThatThrownBy(()->f.tx(()->selected(cycle.episode(),()->{
            var request=((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest();
            request.setAttribute(UavRiskCaseIdentity.REQUEST_VERSION,current+1);
            f.identities().lockCases(cycle.event());return null;
        }))).isInstanceOfSatisfying(com.uav.lowaltitude.platform.api.ApiException.class,error->
                assertThat(error.getCode()).isEqualTo("RISK_CASE_VERSION_CONFLICT"));
        assertThat(f.jdbc.queryForObject("SELECT version FROM uav_risk_case WHERE risk_case_id=?",Long.class,cycle.episode())).isEqualTo(current);
    }

    @ParameterizedTest @ValueSource(strings={"DISABLED_RULES","CONDITION_FAILED","RULE_VERSION_CHANGED","UNKNOWN_ENTRY","UNRELATED_RUN"})
    void measuredEntryCannotReusePassingDecisionAfterRulesOrRegionalEvidenceChange(String condition) {
        Fixtures f=new Fixtures(DATABASE,"replay");Cycle cycle=f.predict(id(),f.now-20_000);String measured=f.enter(List.of(cycle));
        AutoVerification automatic=new AutoVerification(f);assertThat(automatic.evaluate(cycle,measured)).isEqualTo("PASS");
        String run=selected(cycle.episode(),()->automatic.runs.state("verify",measured).runId());
        switch(condition){
            case "DISABLED_RULES" -> f.jdbc.update("UPDATE automation_rule_condition SET enabled=FALSE WHERE category='verify'");
            case "CONDITION_FAILED" -> automatic.confidence="40";
            case "RULE_VERSION_CHANGED" -> f.jdbc.update("UPDATE automation_rule_group SET version=version+1 WHERE category='verify'");
            case "UNKNOWN_ENTRY" -> f.jdbc.update("UPDATE target_latest_state SET altitude_amsl_m=NULL WHERE target_id=?",f.target);
            case "UNRELATED_RUN" -> run=id();
            default -> throw new AssertionError(condition);
        }
        automatic.confirm(cycle,measured,run);
        assertThat(f.identities().find(cycle.episode()).confirmed()).isFalse();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id=?",Long.class,cycle.episode())).isZero();
    }

    @Test void automaticVerificationIsIsolatedByRegionAndNewRiskCycle() {
        Fixtures f=new Fixtures(DATABASE,"live");Cycle a=f.predict(id(),f.now-20_000),b=f.predict(id(),f.now-19_000);
        String measured=f.enter(List.of(a,b));AutoVerification automatic=new AutoVerification(f);
        assertThat(automatic.evaluate(a,measured)).isEqualTo("PASS");
        String runA=selected(a.episode(),()->automatic.runs.state("verify",measured).runId());
        assertThat(automatic.evaluate(b,measured)).isEqualTo("PASS");
        automatic.confirm(b,measured,runA);assertThat(f.identities().find(b.episode()).confirmed()).isFalse();
        f.jdbc.update("UPDATE airspace_version SET valid_to=? WHERE airspace_id=?",at(f.now),a.airspace());
        automatic.confirm(a,measured);assertThat(f.identities().find(a.episode()).confirmed()).isFalse();
        automatic.confirm(b,measured);assertThat(f.identities().find(b.episode()).confirmed()).isTrue();
        assertThat(f.reader().count(query(f,true,"CONFIRMED",null),ALL)).isEqualTo(1);
        f.jdbc.update("UPDATE prediction_episode SET status='CLEARED',active_key=NULL,current_evidence_valid=FALSE,closed_at=? WHERE episode_id=?",f.now,b.episode());
        Cycle next=f.predict(b.airspace(),f.now+1);
        assertThat(f.identities().find(next.episode()).confirmed()).isFalse();
        assertThat(automatic.evaluate(next,next.event())).isEqualTo("PAUSED");
        assertThat(f.identities().channelOwner(next.episode(),"SMS")).isNull();
    }

    @Test void concurrentAutomaticAndHumanVerificationSerializeOnTheSameRisk() throws Exception {
        Fixtures f=new Fixtures(DATABASE,"replay");Cycle cycle=f.predict(id(),f.now-20_000);String measured=f.enter(List.of(cycle));
        AutoVerification automatic=new AutoVerification(f);assertThat(automatic.evaluate(cycle,measured)).isEqualTo("PASS");
        var executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            var actions=List.<Runnable>of(()->automatic.confirm(cycle,measured),()->f.tx(()->selected(cycle.episode(),()->{
                f.identities().lockCases(measured);
                if("PENDING_VERIFICATION".equals(f.identities().details(cycle.episode()).get("verification_status")))f.verdict(cycle,"CONFIRMED");
                return null;
            })));
            var futures=actions.stream().map(action->executor.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("start barrier timed out");action.run();return null;})).toList();
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var future:futures)future.get(15,TimeUnit.SECONDS);
        } finally {start.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id=?",Long.class,cycle.episode())).isEqualTo(1);
        assertThat(f.identities().find(cycle.episode()).confirmed()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void predictionBecomesObservedInTheSameCaseAndPaginationExportAndCountsAgree(String mode) {
        Fixtures f = new Fixtures(DATABASE, mode);
        Cycle first = f.predict(id(), f.now - 86_400_000L);
        f.confirm(first);
        assertThat(f.reader().count(query(f, true, null, null), ALL)).isEqualTo(1);
        String observed = f.enter(List.of(first));

        var risk = f.identities().find(first.episode());
        assertThat(risk.riskCaseId()).isEqualTo(first.episode());
        assertThat(risk.actionEventId()).isEqualTo(observed);
        assertThat(risk.confirmed()).isTrue();
        assertThat(risk.manualCounterOnly()).isTrue();
        assertThat(risk.eventIds()).containsExactlyInAnyOrder(first.event(), observed);
        assertThat(f.jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?", String.class, observed))
                .as("disposal confirmation does not rewrite the actual violation verdict").isEqualTo("PENDING_VERIFICATION");
        assertThat(f.reader().count(query(f, false, null, null), ALL)).isEqualTo(2);
        assertThat(f.reader().count(query(f, true, "CONFIRMED", "ENTERED"), ALL)).isEqualTo(1);
        assertThat(f.reader().count(query(f, true, "PENDING_VERIFICATION", null), ALL)).isZero();

        Cycle otherArea = f.predict(id(), f.now + 2);
        AlarmQuery allCases = query(f, true, null, null);
        assertThat(f.reader().count(allCases, ALL)).isEqualTo(2);
        var firstPage = f.reader().list(allCases, ALL, 0, 1, "received_at", "asc");
        var secondPage = f.reader().list(allCases, ALL, 1, 1, "received_at", "asc");
        var exported = f.reader().listForExport(allCases, ALL, 10, "received_at", "asc");
        assertThat(firstPage).hasSize(1);
        assertThat(secondPage).hasSize(1);
        assertThat(List.of(firstPage.get(0).riskCaseId(), secondPage.get(0).riskCaseId()))
                .containsExactlyElementsOf(exported.stream().map(AlarmReadRepository.AlarmRow::riskCaseId).toList())
                .containsExactlyInAnyOrder(first.episode(), otherArea.episode());
        assertThat(f.reader().list(allCases, ALL, 2, 1, "received_at", "asc")).isEmpty();
        assertThat(f.reader().count(allCases, new AccessDecision(id(), ScopeMode.ASSIGNED))).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void oneMeasuredEventInTwoAreasKeepsVerificationAndRiskSelectionIndependent(String mode) {
        Fixtures f = new Fixtures(DATABASE, mode);
        Cycle areaA = f.predict(id(), f.now - 20_000);
        Cycle areaB = f.predict(id(), f.now - 19_000);
        f.confirm(areaA);
        String observed = f.enter(List.of(areaA, areaB));
        assertThat(f.identities().cases(observed)).hasSize(2);
        assertThat(f.identities().find(areaA.episode()).confirmed()).isTrue();
        assertThat(f.identities().find(areaB.episode()).confirmed()).isFalse();
        assertThat(f.reader().count(query(f, true, "PENDING_VERIFICATION", null), ALL)).isEqualTo(1);
        assertThat(f.reader().count(query(f, true, "CONFIRMED", null), ALL)).isEqualTo(1);
        assertThat(f.reader().count(query(f, true, null, null), ALL)).isEqualTo(2);
        assertThat(selected(areaA.episode(), () -> f.identities().confirmedForDisposal(observed))).isTrue();
        assertThat(selected(areaB.episode(), () -> f.identities().confirmedForDisposal(observed))).isFalse();
        f.confirm(areaB);
        assertThat(f.identities().find(areaB.episode()).confirmed()).isTrue();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id IN (?,?)",
                Long.class, areaA.episode(), areaB.episode())).isEqualTo(2);
        assertThat(f.jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,observed))
                .isEqualTo("PENDING_VERIFICATION");
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void measuredEntrySurvivesExpiredDirectionForecastButNewOutsidePositionCannotBorrowOldEntry(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-20_000);f.confirm(cycle);f.enter(List.of(cycle));
        f.jdbc.update("UPDATE prediction_episode SET valid_until=?,current_evidence_valid=FALSE,entered_evidence_valid=FALSE,reason='DIRECTION_UNRESOLVED' WHERE episode_id=?",f.now-1,cycle.episode());
        var later=f.identitiesAt(f.now+8_000);
        assertThat(later.find(cycle.episode()).stage()).isEqualTo("ENTERED");
        assertThat(later.currentMeasuredArea(later.find(cycle.episode()))).isTrue();
        assertThat(later.find(cycle.episode()).manualCounterOnly()).isTrue();
        assertThat(f.reader().count(query(f,true,"CONFIRMED","ENTERED"),ALL)).isEqualTo(1);
        f.measuredPoint(f.now+1_000,f.longitude+.001,100);
        assertThat(later.find(cycle.episode()).stage()).as("a newer real point inside the same version need not wait for the next rule cycle").isEqualTo("ENTERED");
        f.jdbc.update("UPDATE target_latest_state SET location=ST_SetSRID(ST_MakePoint(?,?),4326),observed_at=?,received_at=? WHERE target_id=?",f.longitude+0.1,f.latitude,at(f.now+1_000),at(f.now+1_000),f.target);
        assertThat(later.find(cycle.episode()).stage()).isEqualTo("UNKNOWN");
        assertThat(later.currentMeasuredArea(later.find(cycle.episode()))).isFalse();
    }

    @Test void measuredHandoffRejectsChangedAltitudeAndAirspaceVersionWithoutChangingOtherRegions() {
        Fixtures f=new Fixtures(DATABASE,"live");Cycle a=f.predict(id(),f.now-20_000),b=f.predict(id(),f.now-19_000);f.enter(List.of(a,b));
        f.jdbc.update("UPDATE prediction_episode SET valid_until=?,current_evidence_valid=FALSE,entered_evidence_valid=FALSE WHERE target_id=?",f.now-1,f.target);
        assertThat(f.identities().find(a.episode()).stage()).isEqualTo("ENTERED");
        assertThat(f.identities().find(b.episode()).stage()).isEqualTo("ENTERED");
        f.jdbc.update("UPDATE airspace_version SET valid_to=? WHERE airspace_id=?",at(f.now+1),a.airspace());
        var later=f.identitiesAt(f.now+2);
        assertThat(later.find(a.episode()).stage()).isEqualTo("UNKNOWN");
        assertThat(later.find(b.episode()).stage()).isEqualTo("ENTERED");
        f.jdbc.update("UPDATE target_latest_state SET altitude_amsl_m=NULL WHERE target_id=?",f.target);
        assertThat(later.find(b.episode()).stage()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest @ValueSource(strings={"TERMINATED","SHORT_LOST","PREDICTED_POINT","LOCATION_UNKNOWN","UNDETERMINED","UNTRUSTED_SOURCE"})
    void oldEntryCannotMaintainRiskWhenCurrentMeasurementIsUnusable(String condition) {
        Fixtures f=new Fixtures(DATABASE,"replay");Cycle cycle=f.predict(id(),f.now-20_000);f.enter(List.of(cycle));
        f.jdbc.update("UPDATE prediction_episode SET valid_until=?,current_evidence_valid=FALSE,entered_evidence_valid=FALSE WHERE episode_id=?",f.now-1,cycle.episode());
        // Controlled simulation permits mock and replay area sources in the exact same scope.
        f.jdbc.update("UPDATE airspace SET source_mode='mock' WHERE airspace_id=?",cycle.airspace());
        assertThat(f.identities().find(cycle.episode()).stage()).isEqualTo("ENTERED");
        switch(condition) {
            case "TERMINATED","SHORT_LOST" -> f.jdbc.update("INSERT INTO target_track_status(target_id,status,since,updated_at) VALUES(?,?,?,?)",f.target,condition,at(f.now),at(f.now));
            case "PREDICTED_POINT" -> f.jdbc.update("UPDATE track_point SET point_kind='PRED' WHERE track_id IN (SELECT track_id FROM track WHERE target_id=?)",f.target);
            case "LOCATION_UNKNOWN" -> f.jdbc.update("UPDATE target_latest_state SET unknown_fields=CAST('[{\"field\":\"location\"}]' AS JSON) WHERE target_id=?",f.target);
            case "UNDETERMINED" -> f.jdbc.update("UPDATE target_degradation SET determined=FALSE WHERE target_id=?",f.target);
            case "UNTRUSTED_SOURCE" -> f.measuredPoint(f.now+1,f.longitude,100,"{\"time_untrusted\":true}");
            default -> throw new AssertionError(condition);
        }
        assertThat(f.identitiesAt(f.now+1_000).find(cycle.episode()).stage()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void measuredHandoffRechecksCurrentSimulationAndBridgePolicy(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);Cycle cycle=f.predict(id(),f.now-20_000);f.enter(List.of(cycle));
        f.jdbc.update("UPDATE prediction_episode SET valid_until=?,current_evidence_valid=FALSE,entered_evidence_valid=FALSE WHERE episode_id=?",f.now-1,cycle.episode());
        var identity=f.identities();assertThat(identity.find(cycle.episode()).stage()).isEqualTo("ENTERED");
        for(var environment:List.of(qaEnvironment(false,"local","qa"),qaEnvironment(true,"local"),qaEnvironment(true,"prod","local","qa"))){
            identity.simulation(new com.uav.lowaltitude.platform.config.SimulationPolicy(environment),environment);
            assertThat(identity.find(cycle.episode()).stage()).isEqualTo("live".equals(mode)?"ENTERED":"UNKNOWN");
        }
        qa(identity);assertThat(identity.find(cycle.episode()).stage()).isEqualTo("ENTERED");
        if("live".equals(mode)){
            f.jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,source_mode,enabled,simulated,created_at,updated_at) VALUES(?,?,'Explicit simulated source','live',TRUE,TRUE,0,0)",id(),f.source);
            var formal=qaEnvironment(true,"prod","local","qa");
            identity.simulation(new com.uav.lowaltitude.platform.config.SimulationPolicy(formal),formal);
            assertThat(identity.find(cycle.episode()).stage()).isEqualTo("UNKNOWN");
            qa(identity);assertThat(identity.find(cycle.episode()).stage()).isEqualTo("ENTERED");
        }
    }

    @Test void withdrawalThenAnotherApproachCreatesANewUnverifiedCycleAndRetainsTheOldReceiptOwner() {
        Fixtures f = new Fixtures(DATABASE, "replay");
        String region = id();
        Cycle old = f.predict(region, f.now - 20_000);
        f.confirm(old);
        assertThat(f.tx(() -> f.identities().claimChannel(old.event(), "SMS"))).isTrue();
        f.jdbc.update("UPDATE prediction_episode SET status='CLEARED',active_key=NULL,current_evidence_valid=FALSE,closed_at=? WHERE episode_id=?",
                f.now, old.episode());
        assertThat(f.identities().find(old.episode()).stage()).isEqualTo("CLEARED");
        Cycle next = f.predict(region, f.now + 1);
        assertThat(next.episode()).isNotEqualTo(old.episode());
        assertThat(f.identities().find(next.episode()).confirmed()).isFalse();
        assertThat(f.identities().channelOwner(next.episode(), "SMS")).isNull();
        assertThat(f.identities().channelOwner(old.episode(), "SMS")).isEqualTo(old.event());
        assertThat(f.reader().count(query(f, true, null, "CLEARED"), ALL)).isEqualTo(1);
        assertThat(f.reader().count(query(f, true, "PENDING_VERIFICATION", "PREDICTED"), ALL)).isEqualTo(1);
        assertThat(f.tx(() -> f.identities().claimChannel(old.event(), "VOICE"))).isFalse();
        assertThat(f.tx(() -> f.identities().claimChannel(next.event(), "SMS"))).isFalse();
    }

    @Test void concurrentPredictionAndObservedSendersCanOwnOneLogicalChannelAndRestartDoesNotResetIt() throws Exception {
        Fixtures f = new Fixtures(DATABASE, "replay");
        Cycle cycle = f.predict(id(), f.now - 1000);
        f.confirm(cycle);
        String observed = f.enter(List.of(cycle));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        List<Boolean> outcomes = new ArrayList<>();
        try {
            var futures = List.of(cycle.event(), observed).stream().map(event -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timed out");
                return f.tx(() -> f.identities().claimChannel(event, "SMS"));
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) outcomes.add(future.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_channel WHERE risk_case_id=? AND channel='SMS'",
                Long.class, cycle.episode())).isEqualTo(1);
        String owner = f.identities().channelOwner(cycle.episode(), "SMS");
        String other = owner.equals(observed) ? cycle.event() : observed;
        UavRiskCaseIdentity restarted = qa(new UavRiskCaseIdentity(new JdbcTemplate(DATABASE.dataSource()), f.clock()));
        assertThat(restarted.channelOwner(cycle.episode(), "SMS")).isEqualTo(owner);
        assertThat(f.tx(() -> restarted.claimChannel(other, "SMS"))).isFalse();
        assertThat(f.tx(() -> restarted.claimChannel(owner, "SMS"))).isTrue();
        assertThat(f.tx(() -> restarted.claimChannel(observed, "VOICE"))).isTrue();
        f.jdbc.update("UPDATE prediction_episode SET status='UNKNOWN',current_evidence_valid=FALSE,entered_evidence_valid=FALSE WHERE episode_id=?",cycle.episode());
        f.jdbc.update("UPDATE target_degradation SET determined=FALSE WHERE target_id=?",f.target);
        assertThat(f.tx(() -> restarted.claimChannel(owner, "SMS"))).isFalse();
        assertThat(restarted.channelOwner(cycle.episode(), "SMS")).isEqualTo(owner);
    }

    @Test void oneObservedEventCanKeepTwoIndependentChannelTasksAndReceiptsAfterRestart() {
        Fixtures f = new Fixtures(DATABASE, "replay");
        Cycle areaA = f.predict(id(), f.now - 2000);
        Cycle areaB = f.predict(id(), f.now - 1000);
        f.confirm(areaA);
        f.confirm(areaB);
        String observed = f.enter(List.of(areaA, areaB));
        var providerKeys = new ArrayList<String>();
        for (Cycle cycle : List.of(areaA, areaB)) {
            String providerKey = "isolated-sms:" + id();
            providerKeys.add(providerKey);
            selected(cycle.episode(), () -> f.tx(() -> {
                UavRiskCaseIdentity identities = f.identities();
                assertThat(identities.claimChannel(observed, "SMS")).isTrue();
                String scope = identities.taskScope(observed, "SMS");
                assertThat(scope).isEqualTo(cycle.episode());
                f.jdbc.update("INSERT INTO uav_auto_sms_task(event_id,risk_scope,status,policy_code,reason,updated_at,attempt_count,provider_key) VALUES(?,?,'UNKNOWN','isolated-scope-policy','accepted result unknown',?,1,?)",
                        observed,scope,f.now,providerKey);
                return null;
            }));
        }
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_auto_sms_task WHERE event_id=?",Long.class,observed)).isEqualTo(2);
        UavRiskCaseIdentity restarted = qa(new UavRiskCaseIdentity(new JdbcTemplate(DATABASE.dataSource()), f.clock()));
        List<Cycle> cycles = List.of(areaA, areaB);
        for (int i=0; i<cycles.size(); i++) {
            Cycle cycle = cycles.get(i);
            assertThat(selected(cycle.episode(), () -> restarted.taskScope(observed, "SMS"))).isEqualTo(cycle.episode());
            assertThat(restarted.withReceipt(observed, "SMS", providerKeys.get(i), restarted::selectedCase)).isEqualTo(cycle.episode());
            assertThat(selected(cycle.episode(), () -> f.tx(() -> restarted.claimChannel(cycle.event(), "SMS")))).isFalse();
        }
        assertThat(restarted.selectedCase()).isNull();
        assertThat(f.jdbc.queryForList("SELECT status FROM uav_auto_sms_task WHERE event_id=?",String.class,observed))
                .containsOnly("UNKNOWN");
    }

    @Test void requestSelectionCannotDisambiguateLegacyAuthorizationAcrossRegions() {
        Fixtures f=new Fixtures(DATABASE,"replay");
        Cycle areaA=f.predict(id(),f.now-2000),areaB=f.predict(id(),f.now-1000);
        String observed=f.enter(List.of(areaA,areaB));
        String authorization=f.authorization(observed);
        var identities=f.identities();
        for(Cycle selectedArea:List.of(areaA,areaB)) {
            assertThatThrownBy(() -> selected(selectedArea.episode(),() -> identities.withAuthorization(authorization,identities::selectedCase)))
                    .isInstanceOfSatisfying(com.uav.lowaltitude.platform.api.ApiException.class,ex -> assertThat(ex.getCode()).isEqualTo("RISK_CASE_REQUIRED"));
        }
        f.jdbc.update("UPDATE disposal_authorization SET risk_case_id=? WHERE authorization_id=?",areaA.episode(),authorization);
        assertThat(selected(areaA.episode(),() -> identities.withAuthorization(authorization,identities::selectedCase))).isEqualTo(areaA.episode());
        assertThatThrownBy(() -> selected(areaB.episode(),() -> identities.withAuthorization(authorization,identities::selectedCase)))
                .isInstanceOfSatisfying(com.uav.lowaltitude.platform.api.ApiException.class,ex -> assertThat(ex.getCode()).isEqualTo("RISK_CASE_AUTHORIZATION_MISMATCH"));
        assertThat(identities.selectedCase()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void waitingSmsUsesTheOriginalTaskAndProviderKeyAcrossMeasuredEntryAndRestart(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-1000);
        var identities=f.identities();
        var tasks=new com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository(f.jdbc);
        tasks.riskCases(identities);
        selected(cycle.episode(),() -> f.tx(() -> {
            tasks.initialize(cycle.event(),f.now,AutoSmsPolicy.CODE);return null;
        }));
        String key=selected(cycle.episode(),() -> tasks.find(cycle.event()).providerKey());
        assertThat(selected(cycle.episode(),() -> tasks.find(cycle.event()).status())).isEqualTo("WAITING");
        String observed=f.enter(List.of(cycle));
        var directory=mock(com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService.class);
        var recipient=new com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot(
                id(),"isolated pilot",null,null,null,null,null,"MOCK",null,null,1L,true,null,f.now);
        when(directory.forPilotEvent(eq("ADVISORY_SMS"),anyString(),nullable(String.class))).thenReturn(recipient);
        var policy=mock(AutoSmsPolicy.class);when(policy.enabled()).thenReturn(true);
        var sms=mock(AdvisorySmsPort.class);
        var service=new AutoSmsService(tasks,new com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository(f.jdbc),
                mock(com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository.class),policy,sms,f.clock(),
                new com.fasterxml.jackson.databind.ObjectMapper(),mock(com.uav.lowaltitude.platform.audit.AuditService.class),
                new DataSourceTransactionManager(DATABASE.dataSource()),directory);
        service.riskCases(identities,f.jdbc);
        // The still-unverified case remains WAITING. Real dispatch is deliberately not configured.
        service.process(observed);service.process(cycle.event());
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_auto_sms_task WHERE event_id IN (?,?)",Long.class,cycle.event(),observed)).isEqualTo(1);
        assertThat(selected(cycle.episode(),() -> tasks.find(cycle.event()).providerKey())).isEqualTo(key);
        assertThat(selected(cycle.episode(),() -> tasks.find(cycle.event()).attempts())).isZero();
        UavRiskCaseIdentity restarted=qa(new UavRiskCaseIdentity(new JdbcTemplate(DATABASE.dataSource()),f.clock()));
        assertThat(selected(cycle.episode(),() -> restarted.notificationTaskOwner(observed,"SMS"))).isEqualTo(cycle.event());
        verifyNoInteractions(sms);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void mismatchedSourceOrScopeLinksCannotMergeIndependentRiskIdentities(boolean changeOwner) {
        Fixtures f = new Fixtures(DATABASE, "live");
        Cycle cycle = f.predict(id(), f.now - 1000);
        String anotherOrg = id();
        f.jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",
                anotherOrg, anotherOrg, "Different isolated owner");
        String observed = f.event("RULE_LEGALITY", f.now, changeOwner ? anotherOrg : f.org, changeOwner ? "live" : "replay");
        f.actualEntry(observed, cycle.airspace());
        f.jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES(?,?,'OBSERVED',?)",observed,cycle.episode(),f.now);
        assertThat(f.identities().find(cycle.episode()).eventIds()).containsExactly(cycle.event());
        assertThat(f.identities().find(cycle.episode()).actionEventId()).isEqualTo(cycle.event());
    }

    @Test void upgradeFreezesAlreadyAcceptedAndUnknownChannelsWithoutRewritingTheirTasksOrReceipts() {
        try (Database beforeUpgrade = new Database()) {
            beforeUpgrade.initialize("202610220001");
            Fixtures f = new Fixtures(beforeUpgrade, "replay");
            var cycles = new ArrayList<Cycle>();
            var smsStates = List.of("SIMULATED_DELIVERED", "SENDING", "UNKNOWN");
            var originalTasks = new ArrayList<Map<String,Object>>();
            for (int i=0; i<smsStates.size(); i++) {
                Cycle cycle = f.predict(id(), f.now - 10_000 + i);
                f.jdbc.update("UPDATE uav_event SET state_code='CONFIRMED' WHERE event_id=?",cycle.event());
                f.enter(List.of(cycle));
                String record = null;
                if (smsStates.get(i).equals("SIMULATED_DELIVERED")) {
                    record = id();
                    f.jdbc.update("INSERT INTO uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,urgent,simulated,delivery_status,trigger_mode,actor_label,policy_code) VALUES(?,?,1,'SMS_SIMULATED',?,NULL,'isolated pilot','isolated simulated receipt','预计进入禁飞区',FALSE,TRUE,'SIMULATED_DELIVERED','AUTO','isolated old channel','isolated-old-policy')",
                            record,cycle.event(),f.now);
                }
                f.jdbc.update("INSERT INTO uav_auto_sms_task(event_id,status,policy_code,reason,updated_at,attempt_count,lease_until,claim_token,delivery_record_id,provider_key) VALUES(?,?,'isolated-old-policy','actual persisted old result',?,1,?,?,?,?)",
                        cycle.event(),smsStates.get(i),f.now,f.now+60_000,id(),record,"sms:"+id());
                f.jdbc.update("INSERT INTO uav_auto_voice_task(event_id,status,policy_code,reason,updated_at,attempt_count,provider_key) VALUES(?,'UNKNOWN','isolated-old-policy','old accepted call result unknown',?,1,?)",
                        cycle.event(),f.now,"voice:"+id());
                originalTasks.add(f.jdbc.queryForMap("SELECT event_id,status,delivery_record_id,provider_key,updated_at,attempt_count FROM uav_auto_sms_task WHERE event_id=?",cycle.event()));
                cycles.add(cycle);
            }
            beforeUpgrade.migrate(null);
            for (int i=0; i<cycles.size(); i++) {
                Cycle cycle = cycles.get(i);
                assertThat(f.jdbc.queryForMap("SELECT event_id,status,delivery_record_id,provider_key,updated_at,attempt_count FROM uav_auto_sms_task WHERE event_id=?",cycle.event()))
                        .containsAllEntriesOf(originalTasks.get(i));
                assertThat(f.identities().channelOwner(cycle.episode(), "SMS")).isEqualTo(cycle.event());
                assertThat(f.identities().channelOwner(cycle.episode(), "VOICE")).isEqualTo(cycle.event());
                assertThat(f.tx(() -> f.identities().claimChannel(f.identities().find(cycle.episode()).actionEventId(), "SMS"))).isFalse();
                assertThat(f.jdbc.queryForObject("SELECT status FROM uav_auto_voice_task WHERE event_id=?",String.class,cycle.event())).isEqualTo("UNKNOWN");
            }
            assertThat(f.jdbc.queryForObject("SELECT content FROM uav_event_advisory WHERE event_id=?",String.class,cycles.get(0).event()))
                    .isEqualTo("预计进入禁飞区");
        }
    }

    private static AlarmQuery query(Fixtures f, boolean riskView, String state, String stage) {
        return new AlarmQuery(state,null,f.target,null,null,null,null,f.mode,null,null,null,null,null,null,false,riskView,stage);
    }

    private static <T> T selected(String risk, Supplier<T> action) {
        var previous = RequestContextHolder.getRequestAttributes();
        var request = new MockHttpServletRequest();
        request.setAttribute(UavRiskCaseIdentity.REQUEST_CASE,risk);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { return action.get(); }
        finally { if(previous==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(previous); }
    }

    private record Cycle(String episode,String event,String airspace,String snapshot) { }

    /** Real rule versions, runs, transactions and entry geometry; controlled source facts for rule branches. */
    private static final class AutoVerification {
        final Fixtures f;
        final AppClock clock;
        final UavRiskCaseIdentity identities;
        final com.uav.lowaltitude.modules.prediction.application.PredictionEventGate gate;
        final com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository runs;
        final com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility eligibility;
        final com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeService evaluator;
        AlarmRuleVerification verification;
        String confidence="95";
        AutoVerification(Fixtures f){
            this(f,f.clock(),f.identities());
        }
        AutoVerification(Fixtures f,AppClock clock,UavRiskCaseIdentity identities){
            this.f=f;this.clock=clock;this.identities=identities;
            gate=new com.uav.lowaltitude.modules.prediction.application.PredictionEventGate(f.jdbc,clock);gate.riskCases(identities);
            runs=new com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository(f.jdbc);runs.cases(identities);
            var config=new com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository(f.jdbc);
            var policy=new com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy(true,30_000,2000,runs,clock);
            var facts=mock(com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeFactsRepository.class);
            when(facts.read(anyString(),anyLong())).thenAnswer(call->new com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeFacts(
                    call.getArgument(0),f.target,"isolated-measured-alarm",f.org,f.district,f.mode,"PENDING_VERIFICATION","UAV",f.now,
                    java.util.Set.of(),true,Map.of("confidence",new com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeFacts.Fact(confidence,f.now,"isolated-observation:"+f.target,null),
                    "freshness",new com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeFacts.Fact("0",f.now,"isolated-observation:"+f.target,null))));
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            eligibility=new com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility(config,runs,facts,policy,json,clock);eligibility.setPredictionEventGate(gate);
            evaluator=new com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeService(config,runs,facts,policy,eligibility,json,clock);evaluator.predictions(gate);
            f.jdbc.update("UPDATE automation_rule_condition SET enabled=FALSE WHERE category='verify'");
            f.jdbc.update("UPDATE automation_rule_group SET version=version+1,scope_mode='ALL',schedule_mode='ALL_DAY',wait_seconds=0 WHERE category='verify'");
            for(String code:List.of("confidence","freshness"))f.jdbc.update("INSERT INTO automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) VALUES(?,'verify',?,?,?,0,TRUE,?,?,'isolated-test') ON CONFLICT(category,item_code) DO UPDATE SET value_text=EXCLUDED.value_text,hold_seconds=0,enabled=TRUE,updated_at=EXCLUDED.updated_at",id(),"Isolated "+code,code,"confidence".equals(code)?"90":"60",f.now,f.now);
            restart();
        }
        void restart(){
            verification=new AlarmRuleVerification(f.jdbc,new com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository(f.jdbc),mock(com.uav.lowaltitude.platform.audit.AuditService.class),clock,eligibility);
            verification.cases(identities);verification.setPredictionEventGate(gate);
        }
        String evaluate(Cycle c,String event){return f.tx(()->selected(c.episode(),()->{evaluator.evaluate("verify",event);return runs.state("verify",event).status();}));}
        void confirm(Cycle c,String event){confirm(c,event,selected(c.episode(),()->runs.state("verify",event).runId()));}
        void confirm(Cycle c,String event,String run){f.tx(()->selected(c.episode(),()->{verification.confirmIfPassed(event,run);return null;}));}
    }

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
            jdbc.update("INSERT INTO target_latest_state(target_id,location,altitude_amsl_m,observed_at,received_at,unknown_fields,created_at,updated_at) VALUES(?,ST_SetSRID(ST_MakePoint(?,?),4326),100,?,?,CAST('[]' AS JSON),?,?)",target,longitude,latitude,at(now),at(now),at(now),at(now));
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
        UavRiskCaseIdentity identities() { return identitiesAt(now); }
        UavRiskCaseIdentity identitiesAt(long at) {return qa(new UavRiskCaseIdentity(jdbc,new AppClock(Clock.fixed(Instant.ofEpochMilli(at),ZoneOffset.UTC))));}
        AlarmReadRepository reader() {
            FusionConfigService fusion=mock(FusionConfigService.class);
            FusionParams params=mock(FusionParams.class);
            when(fusion.params(null)).thenReturn(params);
            when(params.integer("identity","terminate_after_ms")).thenReturn(60_000);
            var reader=new AlarmReadRepository(jdbc,clock(),fusion);
            var env=qaEnvironment(true,"local","qa");reader.simulation(new com.uav.lowaltitude.platform.config.SimulationPolicy(env),env);return reader;
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
            String alarm=id(),event=id(),number=id().replace("-", "");
            String eventSource=source;
            if(!eventMode.equals(mode)) {
                eventSource=id();
                jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES(?,?,?,TRUE,?,?,?,0)",eventSource,eventSource,"Other isolated source",eventMode,at(now),at(now));
            }
            jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_no,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,?,?,?,?,'HIGH',?,?,?,?,?,?)",
                    alarm,target,eventSource,number,number,type,at(received),at(received),eventMode,eventOrg,district,at(received));
            jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'PENDING_VERIFICATION',?,?,?,?,0)",event,alarm,eventOrg,district,at(received),at(received));
            return event;
        }
        Cycle predict(String airspace,long started) {
            String event=event("PREDICTED_AIRSPACE_ENTRY",started),episode=id(),snapshot=id();
            jdbc.update("INSERT INTO prediction_snapshot(prediction_id,target_id,generated_at,based_on_observed_at,valid_until,status,reason,model_version,rule_version,input_json,config_json,result_json,owner_org_id,district_id,source_mode,simulated) VALUES(?,?,?,?,?,'READY','READY','K03_ISOLATED',?,CAST('{}' AS JSON),CAST('{}' AS JSON),CAST('{}' AS JSON),?,?,?,?)",
                    snapshot,target,now,now,now+60_000,parameter,org,district,mode,"replay".equals(mode));
            jdbc.update("INSERT INTO prediction_episode(episode_id,target_id,airspace_id,active_key,source_mode,owner_org_id,district_id,status,latest_prediction_id,first_prediction_id,last_observed_at,valid_until,current_evidence_valid,entered_evidence_valid,reason,started_at,stability_json,warning_json) VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,TRUE,FALSE,'ISOLATED_EVIDENCE',?,CAST('{}' AS JSON),CAST('{}' AS JSON))",
                    episode,target,airspace,target+":"+airspace,mode,org,district,snapshot,snapshot,now,now+60_000,started);
            jdbc.update("INSERT INTO prediction_event_link(event_id,episode_id,role,created_at) VALUES(?,?,'PREDICTED',?)",event,episode,started);
            return new Cycle(episode,event,airspace,snapshot);
        }
        void confirm(Cycle cycle) {
            verdict(cycle,"CONFIRMED");
        }
        void verdict(Cycle cycle,String conclusion) {
            tx(() -> {
                identities().lockCases(cycle.event());
                long version=identities().find(cycle.episode()).version()+1;
                jdbc.update("INSERT INTO uav_risk_case_verification(verification_id,risk_case_id,case_version,event_id,event_version,conclusion,basis_kind,prediction_id,actor_id,note,created_at) VALUES(?,?,?,?,0,?,'PREDICTION',?,?,'isolated human confirmation fixture',?)",
                        id(),cycle.episode(),version,cycle.event(),conclusion,cycle.snapshot(),id(),now);
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
        String authorization(String event) {
            String role="RISK-"+id().substring(0,8),actor=id(),authorization=id();
            jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)",role,role);
            jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,'NONE',0,0,0,0)",actor,actor,"Isolated authorization actor",role);
            jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,device_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at) VALUES(?,?,'COUNTERMEASURE','UAV_EVENT',?,?,'COUNTERMEASURE_4CH','isolated legacy authorization',?,?,'REQUESTED','demo-v1',?,?,?,0,?,?)",
                    authorization,authorization.replace("-",""),event,id(),actor,at(now),org,district,mode,at(now),at(now));
            return authorization;
        }
    }

    /** Same isolation guard as DeviceMonitoringPostgresFixture, with an old-version migration stage for upgrade tests. */
    private static final class Database implements AutoCloseable {
        private final String schema="risk_cases_"+id().replace("-", "");
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
        void verifySchema() { if(!schema.matches("risk_cases_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe disposable schema"); }
        @Override public void close() {
            if(!created)return;verifySchema();
            new JdbcTemplate(rootDataSource()).execute("DROP SCHEMA "+schema+" CASCADE");created=false;
        }
        static String env(String name) { String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value; }
    }
    private static org.springframework.mock.env.MockEnvironment qaEnvironment(boolean bridge,String... profiles){
        var env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles(profiles);
        env.setProperty("app.flight-device-check.simulator-device-bridge-enabled",Boolean.toString(bridge));return env;
    }
    private static UavRiskCaseIdentity qa(UavRiskCaseIdentity identity){
        var env=qaEnvironment(true,"local","qa");identity.simulation(new com.uav.lowaltitude.platform.config.SimulationPolicy(env),env);return identity;
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static OffsetDateTime at(long millis) { return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC); }
}
