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

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"live,true","live,false","replay,true","replay,false"})
    void measuredPunishmentUsesIndependentFactsAndSameCaseRealCompletion(String mode,boolean predictionAuthorization) {
        Fixtures f=new Fixtures(DATABASE,mode);Cycle cycle=f.predict(id(),f.now-2000);f.confirm(cycle);
        String observed=f.enter(List.of(cycle));String actor=actor(f);
        String auth=authorization(f,cycle.episode(),predictionAuthorization?cycle.event():observed,actor,"COMPLETED");
        completeDevice(f,auth,actor,f.now+1000);
        var basis=punishment(f).resolve(observed,auth,f.now+1000);
        assertThat(basis).isNotNull();
        assertThat(basis.riskCaseId()).isEqualTo(cycle.episode());
        assertThat(basis.observedAt()).isEqualTo(f.now);
        assertThat(basis.facts()).containsEntry("basis_kind","INDEPENDENT_MEASURED_ENTRY")
                .containsEntry("risk_verification_is_legal_verdict",false).containsEntry("simulated","replay".equals(mode));
        assertThat((Map<?,?>)basis.facts().get("risk_verification")).containsEntry("basis_kind","PREDICTION");
        assertThat(f.jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,observed)).isEqualTo("PENDING_VERIFICATION");
        assertThat(punishment(f).resolve(cycle.event(),auth,f.now+1000)).as("Prediction material is never a legal fact").isNull();
        var repository=disposals(f);
        assertThat(repository.punishmentCompletion(observed).authorizationId()).isEqualTo(auth);
        assertThat(repository.completedWithoutPunishment()).contains(observed).doesNotContain(cycle.event());
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void measuredPunishmentHonorsRetainedExternalMockAirspaceWithoutAdmittingLiveMixing(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);Cycle cycle=f.predict(id(),f.now-2000);f.confirm(cycle);
        String observed=f.enter(List.of(cycle)),actor=actor(f),auth=authorization(f,cycle.episode(),observed,actor,"COMPLETED");
        completeDevice(f,auth,actor,f.now+1000);
        f.jdbc.update("UPDATE airspace SET source_mode='mock' WHERE airspace_id=?",cycle.airspace());
        assertThat(punishment(f).resolve(observed,auth,f.now+1000)!=null).isEqualTo("replay".equals(mode));
    }

    @ParameterizedTest @ValueSource(strings={"no-verification","missing-height","wrong-source","after-completion","old-cycle","unknown-evidence","stop-unknown","stop-wrong-mask","missing-case"})
    void measuredPunishmentDoesNotBorrowMissingOrInvalidFacts(String invalid) {
        Fixtures f=new Fixtures(DATABASE,"replay");Cycle cycle=f.predict(id(),f.now-2000);
        if(!invalid.equals("no-verification"))f.confirm(cycle);
        String observed=f.enter(List.of(cycle)),actor=actor(f),auth=authorization(f,cycle.episode(),observed,actor,"COMPLETED");
        long completed=invalid.equals("after-completion")?f.now-1:f.now+1000;
        String command=completeDevice(f,auth,actor,completed);
        switch(invalid) {
            case "missing-height" -> f.jdbc.update("UPDATE track_point SET altitude_amsl_m=NULL WHERE track_id=?",f.measuredTrack);
            case "wrong-source" -> f.jdbc.update("UPDATE source_observation SET source_mode='live' WHERE source_id=?",f.source);
            case "old-cycle" -> f.jdbc.update("UPDATE prediction_episode SET started_at=? WHERE episode_id=?",f.now+1,cycle.episode());
            case "unknown-evidence" -> f.jdbc.update("UPDATE rule_evaluation SET decision_assurance_code='INSUFFICIENT' WHERE target_id=?",f.target);
            case "stop-unknown" -> f.jdbc.update("UPDATE device_command SET status='UNKNOWN' WHERE command_id=?",command);
            case "stop-wrong-mask" -> f.jdbc.update("UPDATE countermeasure_4ch_command SET mask=1 WHERE command_id=?",command);
            case "missing-case" -> f.jdbc.update("UPDATE disposal_authorization SET risk_case_id=NULL WHERE authorization_id=?",auth);
            default -> { }
        }
        assertThat(punishment(f).resolve(observed,auth,completed)).isNull();
        assertThat(disposals(f).punishmentCompletion(observed)).isNull();
    }

    @Test void measuredPunishmentSeparatesSharedEventsAndFrozenAuthorization() {
        Fixtures f=new Fixtures(DATABASE,"live");Cycle first=f.predict(id(),f.now-2000),second=f.predict(id(),f.now-1000);
        f.confirm(first);String observed=f.enter(List.of(first,second)),actor=actor(f);
        String auth=authorization(f,first.episode(),first.event(),actor,"COMPLETED");completeDevice(f,auth,actor,f.now+1000);
        var identity=f.identities();var basis=punishment(f);var repository=disposals(f);
        assertThat(basis.resolve(observed,auth,f.now+1000)).isNull();
        var frozen=identity.withCase(first.episode(),()->basis.resolve(observed,auth,f.now+1000));
        assertThat(frozen).isNotNull();
        f.confirm(second);
        assertThat(identity.withCase(second.episode(),()->basis.resolve(observed,auth,f.now+1000))).isNull();
        assertThat(identity.withCase(second.episode(),()->repository.punishmentCompletion(observed))).isNull();
        assertThat(identity.withCase(first.episode(),()->basis.resolveFrozen(observed,auth,f.now+1000,frozen.evaluationId(),frozen.pointId()))).isNotNull();
        assertThat(identity.withCase(first.episode(),()->basis.resolveFrozen(observed,auth,f.now+1000,id(),frozen.pointId()))).isNull();
        assertThat(identity.withCase(first.episode(),()->basis.resolveFrozen(observed,auth,f.now+999,frozen.evaluationId(),frozen.pointId()))).isNull();
    }

    @Test void completedPunishmentScanDoesNotStarveBehindInvalidCandidates() {
        // Explicit lexical prefixes prove that all 101 invalid candidates precede the valid one,
        // including a second SQL page. The remainder of every identity/time/location is generated.
        for(int n=0;n<101;n++) {
            Fixtures f=new Fixtures(DATABASE,"live");Cycle cycle=f.predict(id(),f.now-2000);f.confirm(cycle);
            f.nextEventId="0"+id().substring(1);String observed=f.enter(List.of(cycle));
            String actor=actor(f),auth=authorization(f,cycle.episode(),observed,actor,"COMPLETED");completeDevice(f,auth,actor,f.now+1000);
            f.jdbc.update("UPDATE track_point SET altitude_amsl_m=NULL WHERE track_id=?",f.measuredTrack);
        }
        Fixtures f=new Fixtures(DATABASE,"replay");Cycle cycle=f.predict(id(),f.now-2000);f.confirm(cycle);
        f.nextEventId="f"+id().substring(1);String observed=f.enter(List.of(cycle));
        String actor=actor(f),auth=authorization(f,cycle.episode(),observed,actor,"COMPLETED");completeDevice(f,auth,actor,f.now+1000);
        assertThat(disposals(f).completedWithoutPunishment()).contains(observed);
    }

    private static com.uav.lowaltitude.modules.handoff.application.MeasuredPunishmentBasis punishment(Fixtures f) {
        var clock=new AppClock(Clock.fixed(Instant.ofEpochMilli(f.now+2000),ZoneOffset.UTC));
        var proof=new com.uav.lowaltitude.modules.handoff.application.MeasuredPunishmentBasis(f.jdbc,f.identities(),clock);
        var env=qaEnvironment();proof.simulation(new com.uav.lowaltitude.platform.config.SimulationPolicy(env),env);return proof;
    }
    private static com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository disposals(Fixtures f) {
        var repository=new com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository(f.jdbc,new DataSourceTransactionManager(f.database.dataSource()));
        repository.riskCases(f.identities());repository.measuredPunishment(punishment(f));return repository;
    }
    private static String completeDevice(Fixtures f,String auth,String actor,long completed) {
        String device=f.jdbc.queryForObject("SELECT device_id FROM disposal_authorization WHERE authorization_id=?",String.class,auth),command=id();
        f.jdbc.update("INSERT INTO ops_device(device_id,device_no,name,device_type,channel,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,'反制','测试',?,?,?,?)",device,device,"Isolated counter",f.mode,!f.mode.equals("live"),f.now,f.now);
        f.jdbc.update("INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,source_mode,simulated,completed_at,created_at,updated_at) VALUES(?,?,?,?,'COUNTERMEASURE_4CH_SET','Isolated real-off fixture','SUCCEEDED',?,?,?,?,?)",command,command,device,actor,f.mode,!f.mode.equals("live"),completed,f.now,f.now);
        f.jdbc.update("INSERT INTO countermeasure_4ch_command(command_id,action,mask,authorization_id) VALUES(?,'SET_MASK',0,?)",command,auth);
        f.jdbc.update("UPDATE disposal_authorization SET execution_command_id=? WHERE authorization_id=?",command,auth);
        f.jdbc.update("INSERT INTO disposal_device_run(authorization_id,device_id,on_command_id,on_at,off_due_at,off_command_id,off_attempts,created_at) VALUES(?,?,?,?,?,?,1,?)",auth,device,id(),at(completed-100000),at(completed-1000),command,at(completed-100000));
        return command;
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void currentReadKeepsMeasuredEntryWhenFrameCommitsDuringConfigurationLookup(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);
        f.enter(List.of(cycle));
        assertThat(f.identities().find(cycle.episode()).stage()).isEqualTo("ENTERED");

        var wallTime=new java.util.concurrent.atomic.AtomicLong(f.now);
        AppClock clock=mock(AppClock.class);
        when(clock.nowMillis()).thenAnswer(ignored->wallTime.get());
        when(clock.now()).thenAnswer(ignored->Instant.ofEpochMilli(wallTime.get()));
        long nextObservation=f.now+500;
        var writer=java.util.concurrent.Executors.newSingleThreadExecutor();
        var reads=new AfterFreshnessReadJdbcTemplate(DATABASE.dataSource(),()->{
            try {
                // A second connection commits an entire new fused frame while this read-only
                // transaction is between the configuration SELECT and current-risk SELECT.
                writer.submit(()->f.tx(()->{
                    f.measuredPoint(nextObservation,f.longitude+0.001,100);
                    return null;
                })).get(5,java.util.concurrent.TimeUnit.SECONDS);
            } catch(Exception failure) {
                throw new IllegalStateException("The concurrent fused frame did not commit",failure);
            }
            wallTime.set(nextObservation+100);
        });
        var identities=qa(new UavRiskCaseIdentity(reads,clock));
        try {
            var result=f.tx(()->{
                assertThat(f.jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("read committed");
                return identities.find(cycle.episode());
            });
            assertThat(reads.triggered).isTrue();
            assertThat(f.jdbc.queryForObject("SELECT observed_at FROM target_latest_state WHERE target_id=?",
                    java.sql.Timestamp.class,f.target).getTime()).isEqualTo(nextObservation);
            assertThat(result.stage()).as("A committed current MEAS frame must not become future evidence because the request captured its clock before reading configuration")
                    .isEqualTo("ENTERED");
            assertThat(result.riskCaseId()).isEqualTo(cycle.episode());
        } finally {
            writer.shutdownNow();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"live,guard","replay,guard","live,area","replay,area","live,entries","replay,entries"})
    void currentActionEvidenceDoesNotMistakeAFrameCommittedAfterClockCaptureForFutureData(String mode,String read) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);f.confirm(cycle);
        String event=f.enter(List.of(cycle));
        var seeded=f.identities().find(cycle.episode());
        var wallTime=new java.util.concurrent.atomic.AtomicLong(f.now);
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        var writer=java.util.concurrent.Executors.newSingleThreadExecutor();
        AppClock clock=mock(AppClock.class);
        when(clock.now()).thenAnswer(ignored->Instant.ofEpochMilli(wallTime.get()));
        when(clock.nowMillis()).thenAnswer(ignored->{
            long captured=wallTime.get();
            if(committed.compareAndSet(false,true)) {
                // The application has sampled its comparison time. A complete, valid frame
                // commits before the spatial SELECT takes its snapshot (e.g. JDBC/plan delay).
                writer.submit(()->f.tx(()->{f.measuredPoint(f.now+500,f.longitude+0.001,100);return null;}))
                        .get(5,java.util.concurrent.TimeUnit.SECONDS);
                wallTime.set(f.now+600);
            }
            return captured;
        });
        var identities=qa(new UavRiskCaseIdentity(f.jdbc,clock));
        var gate=new com.uav.lowaltitude.modules.prediction.application.PredictionEventGate(f.jdbc,clock);
        gate.riskCases(identities);
        try {
            if("guard".equals(read)) {
                String block=f.tx(()->identities.withCase(cycle.episode(),()->gate.counterBlock(event,true)));
                assertThat(block).as("The measured event must keep its ordinary guard after a newer valid frame; it must not be cancelled as UNKNOWN").isNull();
            } else if("area".equals(read)) {
                assertThat(f.tx(()->identities.currentMeasuredArea(seeded))).isTrue();
            } else {
                assertThat(f.tx(()->identities.currentMeasuredEntries(seeded))).hasSize(1);
            }
            assertThat(committed).isTrue();
            assertThat(identities.find(cycle.episode()).stage()).isEqualTo("ENTERED");
            var oldSample=new HashMap<String,Object>();oldSample.put("case_id",cycle.episode());
            UavMeasuredRiskSql.addTimes(oldSample,f.jdbc,f.clock(),true,true);
            assertThat(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(f.jdbc).queryForObject(
                    UavMeasuredRiskSql.expand("SELECT "+UavMeasuredRiskSql.TOKEN+" FROM uav_risk_case c WHERE c.risk_case_id=:case_id",true),oldSample,Boolean.class))
                    .as("Without a new full read, the same committed frame is rejected solely by the earlier clock sample").isFalse();
        } finally {writer.shutdownNow();}
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"live,future","replay,future","live,outside","replay,outside",
            "live,missing-altitude","replay,missing-altitude","live,wrong-source","replay,wrong-source"})
    void concurrentFrameRetryDoesNotRestoreInvalidEvidence(String mode,String invalid) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);f.confirm(cycle);
        String event=f.enter(List.of(cycle));
        var wallTime=new java.util.concurrent.atomic.AtomicLong(f.now);
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        var writer=java.util.concurrent.Executors.newSingleThreadExecutor();
        AppClock clock=mock(AppClock.class);
        when(clock.now()).thenAnswer(ignored->Instant.ofEpochMilli(wallTime.get()));
        when(clock.nowMillis()).thenAnswer(ignored->{
            long captured=wallTime.get();
            if(committed.compareAndSet(false,true)) {
                writer.submit(()->f.tx(()->{
                    f.measuredPoint(f.now+("future".equals(invalid)?1000:500),
                            f.longitude+("outside".equals(invalid)?0.02:0.001),100);
                    if("missing-altitude".equals(invalid)) {
                        f.jdbc.update("UPDATE target_latest_state SET altitude_amsl_m=NULL WHERE target_id=?",f.target);
                        f.jdbc.update("UPDATE track_point SET altitude_amsl_m=NULL WHERE track_id=?",f.measuredTrack);
                    }
                    if("wrong-source".equals(invalid))f.jdbc.update("UPDATE integration_source SET source_mode=? WHERE source_id=?",
                            "live".equals(mode)?"replay":"live",f.source);
                    return null;
                })).get(5,java.util.concurrent.TimeUnit.SECONDS);
                wallTime.set(f.now+600);
            }
            return captured;
        });
        var identities=qa(new UavRiskCaseIdentity(f.jdbc,clock));
        var gate=new com.uav.lowaltitude.modules.prediction.application.PredictionEventGate(f.jdbc,clock);gate.riskCases(identities);
        try {
            assertThat(f.tx(()->identities.withCase(cycle.episode(),()->gate.counterBlock(event,true)))).isNotBlank();
            assertThat(committed).isTrue();
            var current=identities.find(cycle.episode());
            assertThat(current.stage()).isEqualTo("UNKNOWN");
            assertThat(identities.currentMeasuredArea(current)).isFalse();
            assertThat(identities.currentMeasuredEntries(current)).isEmpty();
        } finally {writer.shutdownNow();}
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"live,future","replay,future","live,expired","replay,expired",
            "live,outside","replay,outside","live,wrong-source","replay,wrong-source"})
    void currentReadStillRejectsInvalidMeasuredFrames(String mode,String invalid) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);
        f.enter(List.of(cycle));
        assertThat(f.identities().find(cycle.episode()).stage()).isEqualTo("ENTERED");
        switch(invalid) {
            case "future" -> f.tx(()->{f.measuredPoint(f.now+1000,f.longitude,100);return null;});
            case "expired" -> f.tx(()->{f.measuredPoint(f.now-121000,f.longitude,100);return null;});
            case "outside" -> f.tx(()->{f.measuredPoint(f.now+1,f.longitude+0.02,100);return null;});
            case "wrong-source" -> f.jdbc.update("UPDATE integration_source SET source_mode=? WHERE source_id=?",
                    "live".equals(mode)?"replay":"live",f.source);
            default -> throw new IllegalArgumentException(invalid);
        }
        var afterFrame=qa(new UavRiskCaseIdentity(f.jdbc,
                new AppClock(Clock.fixed(Instant.ofEpochMilli(f.now+100),ZoneOffset.UTC))));
        assertThat(afterFrame.find(cycle.episode()).stage()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void lateSourceSwitchHistoryDoesNotReplaceTheCurrentMeasuredEntry(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);
        f.enter(List.of(cycle));
        assertThat(f.identities().find(cycle.episode()).stage()).isEqualTo("ENTERED");
        f.tx(()->{f.lateSourceSwitchPoint(f.now+100);return null;});

        // The fusion writer appends late frames to history but deliberately leaves the
        // current state unchanged. Arrival sequence is therefore not a current-state pointer.
        var last=f.jdbc.queryForMap("SELECT observed_at,received_at,source_switched,position_source_id FROM track_point WHERE track_id=? ORDER BY point_seq DESC LIMIT 1",f.measuredTrack);
        assertThat(((java.sql.Timestamp)last.get("observed_at")).getTime()).isEqualTo(f.now-1000);
        assertThat(((java.sql.Timestamp)last.get("received_at")).getTime()).isEqualTo(f.now+100);
        assertThat(last.get("source_switched")).isEqualTo(true);
        assertThat(last.get("position_source_id")).isNotEqualTo(f.source);
        assertThat(f.jdbc.queryForObject("SELECT observed_at FROM target_latest_state WHERE target_id=?",java.sql.Timestamp.class,f.target).getTime()).isEqualTo(f.now);
        var clock=new AppClock(Clock.fixed(Instant.ofEpochMilli(f.now+200),ZoneOffset.UTC));
        var identities=qa(new UavRiskCaseIdentity(f.jdbc,clock));
        var current=identities.find(cycle.episode());
        assertThat(current.stage()).isEqualTo("ENTERED");
        assertThat(identities.currentMeasuredArea(current)).isTrue();
        assertThat(identities.currentMeasuredEntries(current)).hasSize(1);
        assertThat(f.reader(clock).count(query(f,"ENTERED",null,false),ALL)).isEqualTo(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"live,current-pred","replay,current-pred",
            "live,same-time-pred-mismatch","replay,same-time-pred-mismatch",
            "live,short-lost","replay,short-lost","live,wrong-source","replay,wrong-source",
            "live,wrong-identity","replay,wrong-identity"})
    void historicalMeasurementsCannotRescueInvalidCurrentEvidence(String mode,String invalid) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);f.enter(List.of(cycle));
        f.tx(()->{f.lateSourceSwitchPoint(f.now+100);return null;});
        switch(invalid) {
            case "current-pred","same-time-pred-mismatch" -> f.tx(()->{
                // The new PRED retains the same observation time and position. Its new
                // receive time identifies the actual current point; old MEAS cannot qualify it.
                f.jdbc.update("INSERT INTO track_point(point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,point_kind,position_accuracy_m,created_at)"
                        +" SELECT ?,?,(SELECT MAX(point_seq)+1 FROM track_point WHERE track_id=?),observed_at,?,location,altitude_amsl_m,height_agl_m,'PRED',2,? FROM target_latest_state WHERE target_id=?",
                        id(),f.measuredTrack,f.measuredTrack,at(f.now+150),at(f.now+150),f.target);
                if("current-pred".equals(invalid))
                    f.jdbc.update("UPDATE target_latest_state SET received_at=? WHERE target_id=?",at(f.now+150),f.target);
                // An inconsistent current pointer also cannot revive an older MEAS when
                // a same-observation-time PRED has superseded it in the fused track.
                return null;
            });
            case "short-lost" -> f.jdbc.update("INSERT INTO target_track_status(target_id,status,since,confirm_hits,miss_frames,last_observed_at,last_received_at,updated_at,version)"
                    +" SELECT target_id,'SHORT_LOST',observed_at,0,1,observed_at,received_at,received_at,0 FROM target_latest_state WHERE target_id=?",f.target);
            case "wrong-source" -> f.jdbc.update("UPDATE integration_source SET source_mode=? WHERE source_id=?","live".equals(mode)?"replay":"live",f.source);
            case "wrong-identity" -> f.jdbc.update("UPDATE target_source_link SET source_session_key=? WHERE target_id=? AND source_id=?",id(),f.target,f.source);
            default -> throw new IllegalArgumentException(invalid);
        }
        var clock=new AppClock(Clock.fixed(Instant.ofEpochMilli(f.now+200),ZoneOffset.UTC));
        var identities=qa(new UavRiskCaseIdentity(f.jdbc,clock));
        var current=identities.find(cycle.episode());
        assertThat(current.stage()).isEqualTo("UNKNOWN");
        assertThat(identities.currentMeasuredArea(current)).isFalse();
        assertThat(identities.currentMeasuredEntries(current)).isEmpty();
        assertThat(f.reader(clock).count(query(f,"ENTERED",null,false),ALL)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void proxiedRiskListKeepsCountRowsAndSummaryOnOneSnapshotThenSeesTheNextFrame(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);
        f.enter(List.of(cycle));
        var wallTime=new java.util.concurrent.atomic.AtomicLong(f.now);
        AppClock clock=mock(AppClock.class);
        when(clock.nowMillis()).thenAnswer(ignored->wallTime.get());
        when(clock.now()).thenAnswer(ignored->Instant.ofEpochMilli(wallTime.get()));
        var reader=spy(f.reader(clock));
        var writer=java.util.concurrent.Executors.newSingleThreadExecutor();
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{
            long count=(long)invocation.callRealMethod();
            if(committed.compareAndSet(false,true)) {
                assertThat(f.jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
                assertThat(f.jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
                assertThat(count).isEqualTo(1);
                // The actual repository count has finished. Commit a measured position outside
                // this region before the actual list and summary SQL execute on the reader.
                writer.submit(()->f.tx(()->{
                    f.measuredPoint(f.now+500,f.longitude+0.02,100);
                    return null;
                })).get(5,java.util.concurrent.TimeUnit.SECONDS);
                wallTime.set(f.now+600);
            }
            return count;
        }).when(reader).count(any(AlarmQuery.class),any(AccessDecision.class));
        AccessControlService access=mock(AccessControlService.class);
        when(access.require(any(com.uav.lowaltitude.modules.identity.domain.PermissionCode.class))).thenReturn(ALL);
        var target=new AlarmReadService(access,reader,mock(AuditService.class),clock,new ObjectMapper(),mock(UavAdvisoryService.class));
        target.riskCases(qa(new UavRiskCaseIdentity(f.jdbc,clock)));
        // Use Spring's actual annotation-driven interceptor, rather than manually choosing an
        // isolation level in the test: removing the production transaction changes the result.
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(DATABASE.dataSource()));
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory=new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        var service=(AlarmReadService)factory.getProxy();
        var values=new org.springframework.util.LinkedMultiValueMap<String,String>();
        values.set("view","risk_case");values.set("target_id",f.target);values.set("source_mode",mode);
        values.set("risk_stage","ENTERED");
        try {
            var first=service.list(values);
            assertThat(committed).isTrue();
            assertThat(first.total()).isEqualTo(1);
            assertThat(first.items()).singleElement().satisfies(row->assertThat(row.getRiskCase())
                    .containsEntry("risk_case_id",cycle.episode()).containsEntry("stage","ENTERED"));

            var next=service.list(values);
            assertThat(next.total()).isZero();assertThat(next.items()).isEmpty();
            values.set("risk_stage","UNKNOWN");
            var changed=service.list(values);
            assertThat(changed.total()).isEqualTo(1);
            assertThat(changed.items()).singleElement().satisfies(row->assertThat(row.getRiskCase())
                    .containsEntry("risk_case_id",cycle.episode()).containsEntry("stage","UNKNOWN"));
        } finally {
            writer.shutdownNow();
        }
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void proxiedAdvisoryKeepsEntryAndEligibilityOnOneSnapshotThenSeesDeparture(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);f.confirm(cycle);
        String measured=f.enter(List.of(cycle));
        var wallTime=new java.util.concurrent.atomic.AtomicLong(f.now);
        AppClock clock=mock(AppClock.class);
        when(clock.nowMillis()).thenAnswer(ignored->wallTime.get());
        when(clock.now()).thenAnswer(ignored->Instant.ofEpochMilli(wallTime.get()));
        var identities=qa(new UavRiskCaseIdentity(f.jdbc,clock));
        var events=new UavEventRepository(f.jdbc);
        AccessControlService access=mock(AccessControlService.class);
        when(access.require(any(com.uav.lowaltitude.modules.identity.domain.PermissionCode.class))).thenReturn(ALL);
        var target=spy(new UavAdvisoryService(events,null,access,null,null,clock,null,null,null,null,null,null,null,null));
        target.responseOrder(null,identities);
        var writer=java.util.concurrent.Executors.newSingleThreadExecutor();
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{
            var event=(UavEventRepository.EventRow)invocation.getArgument(0);
            var before=identities.find(cycle.episode());
            if(committed.compareAndSet(false,true)) {
                assertThat(before.stage()).isEqualTo("ENTERED");
                // The overview has read its entry stage. A full new MEAS frame commits before
                // its actual eligibility guard and final summary, on a separate connection.
                writer.submit(()->f.tx(()->{f.measuredPoint(f.now+500,f.longitude+0.02,100);return null;}))
                        .get(5,java.util.concurrent.TimeUnit.SECONDS);
                wallTime.set(f.now+600);
            }
            boolean eligible=before.confirmed()&&identities.currentMeasuredArea(before);
            var summary=identities.summary(cycle.episode());
            return new com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Overview(event.eventId(),event.version(),
                    "SIMULATED",false,eligible,eligible,false,eligible?"":"CURRENT_ENTRY_UNAVAILABLE",List.of(),
                    null,null,null,null,true,null,null,null,false,null,null,summary);
        }).when(target).view(any(UavEventRepository.EventRow.class));
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(DATABASE.dataSource()));
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory=new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);factory.addAdvice(interceptor);
        var service=(UavAdvisoryService)factory.getProxy();
        try {
            var first=service.overview(measured);
            assertThat(committed).isTrue();
            assertThat(first.riskCase()).containsEntry("stage","ENTERED").containsEntry("verification_status","CONFIRMED");
            assertThat(first.canDirectCounter()).isTrue();
            assertThat(first.counterBlockReason()).isEmpty();
            var next=service.overview(measured);
            assertThat(next.riskCase()).containsEntry("stage","UNKNOWN").containsEntry("verification_status","CONFIRMED");
            assertThat(next.canDirectCounter()).isFalse();
            assertThat(next.counterBlockReason()).isEqualTo("CURRENT_ENTRY_UNAVAILABLE");
            // A subsequent action guard reads the newly committed evidence, not this page's
            // earlier snapshot. Neither a previous display nor human verification authorizes it.
            assertThat(f.tx(()->identities.currentMeasuredArea(identities.find(cycle.episode())))).isFalse();
        } finally { writer.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void verificationCommitsAndReleasesEpisodeBeforeBuildingItsResponse(String mode) {
        Fixtures f=new Fixtures(DATABASE,mode);
        Cycle cycle=f.predict(id(),f.now-2000);
        f.jdbc.update("INSERT INTO prediction_episode_snapshot(episode_id,prediction_id,created_at) VALUES(?,?,?)",cycle.episode(),cycle.snapshot(),f.now);
        var identities=f.identities();
        var events=new UavEventRepository(f.jdbc);
        var access=mock(AccessControlService.class);
        when(access.require(any(com.uav.lowaltitude.modules.identity.domain.PermissionCode.class))).thenReturn(ALL);
        var gate=new com.uav.lowaltitude.modules.prediction.application.PredictionEventGate(f.jdbc,f.clock());
        gate.riskCases(identities);
        var target=spy(new UavRiskCaseService(identities,mock(UavEventVerificationService.class),events,null,null,
                mock(com.uav.lowaltitude.modules.alarm.infrastructure.UavVerificationBasisRepository.class),gate,access,
                mock(com.uav.lowaltitude.modules.identity.application.IdempotencyGuard.class),f.jdbc,new ObjectMapper(),f.clock(),mock(AuditService.class)));
        var writer=java.util.concurrent.Executors.newSingleThreadExecutor();
        var rendered=new java.util.concurrent.atomic.AtomicBoolean();
        String actor=actor(f);
        com.uav.lowaltitude.platform.security.AuthContext.set(new com.uav.lowaltitude.platform.security.AuthUser(actor,"isolated-operator","Operator",id(),0,false,"ALL"));
        doAnswer(invocation->{
            // The detail method itself is a read-only transaction, not the preceding command.
            assertThat(f.jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            writer.submit(()->f.tx(()->{
                assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id=?",Long.class,cycle.episode())).isEqualTo(1);
                f.jdbc.queryForList("SELECT episode_id FROM prediction_episode WHERE episode_id=? FOR UPDATE NOWAIT",cycle.episode());
                f.jdbc.queryForList("SELECT risk_case_id FROM uav_risk_case WHERE risk_case_id=? FOR UPDATE NOWAIT",cycle.episode());
                f.jdbc.queryForList("SELECT event_id FROM uav_event WHERE event_id=? FOR UPDATE NOWAIT",cycle.event());
                f.measuredPoint(f.now+500,f.longitude,100);
                return null;
            })).get(5,java.util.concurrent.TimeUnit.SECONDS);
            rendered.set(true);
            return Map.of("risk_case",Map.of("risk_case_id",cycle.episode(),"verification_status","CONFIRMED"));
        }).when(target).detail(cycle.episode());
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(DATABASE.dataSource()));
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory=new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);factory.addAdvice(interceptor);
        var service=(UavRiskCaseService)factory.getProxy();
        var controller=new com.uav.lowaltitude.modules.alarm.api.UavRiskCaseController(service);
        var current=identities.find(cycle.episode());
        String request=json(Map.of("conclusion","CONFIRMED","expected_version",0,"expected_case_version",current.version(),"prediction_id",cycle.snapshot()));
        try {
            var response=controller.verify(cycle.episode(),request,id());
            assertThat(response.isOk()).isTrue();
            assertThat(rendered).isTrue();
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id=?",Long.class,cycle.episode())).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT observed_at FROM target_latest_state WHERE target_id=?",java.sql.Timestamp.class,f.target).getTime()).isEqualTo(f.now+500);
            clearInvocations(target);
            assertThatThrownBy(()->controller.verify(cycle.episode(),request,id())).isInstanceOf(com.uav.lowaltitude.platform.api.ApiException.class);
            verify(target,never()).detail(anyString());
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification WHERE risk_case_id=?",Long.class,cycle.episode())).isEqualTo(1);
        } finally {
            com.uav.lowaltitude.platform.security.AuthContext.clear();writer.shutdownNow();
        }
    }

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

    /** Deterministic query boundary; no sleeps, business IDs, or production data are involved. */
    private static final class AfterFreshnessReadJdbcTemplate extends JdbcTemplate {
        private final Runnable afterRead;
        private boolean triggered;
        AfterFreshnessReadJdbcTemplate(DataSource source,Runnable afterRead){super(source);this.afterRead=afterRead;}
        @Override public <T> List<T> queryForList(String sql,Class<T> elementType) {
            List<T> result=super.queryForList(sql,elementType);
            if(!triggered&&sql.contains("p.param_key='fresh_seconds'")) {
                triggered=true;
                afterRead.run();
            }
            return result;
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

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void verificationReadsOnlyThisCasesLatestSnapshotAndRejectsExpiredOrChangedBasis(String mode){
        Fixtures f=new Fixtures(DATABASE,mode);Cycle cycle=f.predict(id(),f.now-1000);
        var reader=new com.uav.lowaltitude.modules.prediction.application.PredictionReadService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(f.jdbc),mock(AccessControlService.class),new ObjectMapper(),f.clock());
        String latest=id();
        // The current read must not need a historical episode-snapshot link or the first frozen alarm.
        f.jdbc.update("INSERT INTO prediction_snapshot(prediction_id,target_id,generated_at,based_on_observed_at,valid_until,status,reason,model_version,rule_version,input_json,config_json,result_json,owner_org_id,district_id,source_mode,simulated)"
                +" SELECT ?,target_id,generated_at,based_on_observed_at,valid_until,status,reason,model_version,rule_version,input_json,config_json,CAST(? AS JSON),owner_org_id,district_id,source_mode,simulated FROM prediction_snapshot WHERE prediction_id=?",
                latest,json(Map.of("prediction_id",latest,"target_id",f.target,"status","READY","valid_until",f.now+60_000)),cycle.snapshot());
        f.jdbc.update("UPDATE prediction_episode SET latest_prediction_id=? WHERE episode_id=?",latest,cycle.episode());
        var dto=verificationAlarm(f,cycle);
        var basis=reader.verificationEvidence(dto);
        assertThat(basis.confirmable()).isTrue();assertThat(basis.checkedAt()).isEqualTo(f.now);
        assertThat(dto.getPrediction()).containsEntry("prediction_id",latest).containsEntry("status","READY");
        assertThat(dto.getPredictionSnapshot()).isEmpty();assertThat(dto.getPredictionHistory()).isEmpty();
        // Neither an expired current row nor a case version change borrows an older valid snapshot.
        f.jdbc.update("UPDATE prediction_episode SET valid_until=? WHERE episode_id=?",f.now,cycle.episode());
        var expired=verificationAlarm(f,cycle);
        assertThat(reader.verificationEvidence(expired).confirmable()).isFalse();
        assertThat(expired.getPrediction()).containsEntry("status","UNKNOWN");
        f.jdbc.update("UPDATE prediction_episode SET valid_until=? WHERE episode_id=?",f.now+60_000,cycle.episode());
        var beforeChange=verificationAlarm(f,cycle);f.confirm(cycle);
        assertThat(reader.verificationEvidence(beforeChange).confirmable()).isFalse();
        assertThat(beforeChange.getPrediction()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void verificationCannotBorrowAnotherTargetOrSourceSnapshotOrAnotherRegionalRisk(String mode){
        Fixtures f=new Fixtures(DATABASE,mode);Cycle own=f.predict(id(),f.now-1000),otherArea=f.predict(id(),f.now-500);
        var reader=new com.uav.lowaltitude.modules.prediction.application.PredictionReadService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(f.jdbc),mock(AccessControlService.class),new ObjectMapper(),f.clock());
        var wrongCase=verificationAlarm(f,own).withRiskCase(f.identities().summary(otherArea.episode()));
        assertThat(reader.verificationEvidence(wrongCase).confirmable()).isFalse();assertThat(wrongCase.getPrediction()).isNull();
        Fixtures other=new Fixtures(DATABASE,mode);Cycle foreign=other.predict(id(),other.now-500);
        String otherSource=id();
        f.jdbc.update("INSERT INTO prediction_snapshot(prediction_id,target_id,generated_at,based_on_observed_at,valid_until,status,reason,model_version,rule_version,input_json,config_json,result_json,owner_org_id,district_id,source_mode,simulated)"
                +" SELECT ?,target_id,generated_at,based_on_observed_at,valid_until,status,reason,model_version,rule_version,input_json,config_json,result_json,owner_org_id,district_id,?,simulated FROM prediction_snapshot WHERE prediction_id=?",
                otherSource,"live".equals(mode)?"replay":"live",own.snapshot());
        // First varies only the target within the same source mode; second only the snapshot's source mode.
        for(String mismatched:List.of(foreign.snapshot(),otherSource)){
            f.jdbc.update("UPDATE prediction_episode SET latest_prediction_id=? WHERE episode_id=?",mismatched,own.episode());
            var wrongSnapshot=verificationAlarm(f,own);
            assertThat(reader.verificationEvidence(wrongSnapshot).confirmable()).isFalse();assertThat(wrongSnapshot.getPrediction()).isNull();
        }
        f.jdbc.update("UPDATE prediction_episode SET latest_prediction_id=? WHERE episode_id=?",own.snapshot(),own.episode());
        assertThat(reader.verificationEvidence(verificationAlarm(f,own)).confirmable()).isTrue();
    }

    private static com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto verificationAlarm(Fixtures f,Cycle cycle){
        String alarmId=f.jdbc.queryForObject("SELECT alarm_id FROM uav_event WHERE event_id=?",String.class,cycle.event());
        return new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto(alarmId,cycle.event(),"PENDING_VERIFICATION","PREDICTED_AIRSPACE_ENTRY","HIGH",f.now,
                f.now,"source",f.mode,f.org,f.district,f.target,"ALARM","source","org","district","target","HIGH",List.of(),0,null,"CURRENT","CURRENT",null)
                .withRiskCase(f.identities().summary(cycle.episode()));
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
        String measuredDevice,measuredTrack,measuredSession,measuredExternal,nextEventId;
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
        AlarmReadRepository reader() {return reader(clock());}
        AlarmReadRepository reader(AppClock readClock) {
            FusionConfigService fusion=mock(FusionConfigService.class);
            FusionParams params=mock(FusionParams.class);
            when(fusion.params(null)).thenReturn(params);
            when(params.integer("identity","terminate_after_ms")).thenReturn(60_000);
            var reader=new AlarmReadRepository(jdbc,readClock,fusion);
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
            String alarm=id(),event=nextEventId==null?id():nextEventId,number="RISK-"+id().replace("-", "").substring(0, 24);
            nextEventId=null;
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
        void lateSourceSwitchPoint(long received) {
            String lateSource=id(),lateDevice=id(),inbox=id(),observation=id(),session=id();
            long observed=now-1000;
            jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,protocol_code,created_at,updated_at,version) VALUES(?,?,?,TRUE,?,'SIM_NORMALIZED',?,?,0)",lateSource,lateSource,"Isolated late second sensor",mode,at(observed),at(received));
            jdbc.update("INSERT INTO device(device_id,source_id,external_device_id,device_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,?,?,?,?,?)",lateDevice,lateSource,lateDevice,lateDevice,"Isolated late input",mode,org,district,at(observed),at(received));
            if("replay".equals(mode))jdbc.update("INSERT INTO simulator_observation_source(source_id,device_id,message_id,owner_org_id,district_id,request_hash,created_at) VALUES(?,?,?,?,?,REPEAT('b',64),?)",lateSource,lateDevice,id(),org,district,at(observed));
            jdbc.update("INSERT INTO target_source_link(link_id,target_id,source_id,device_id,source_session_key,external_target_id,created_at) VALUES(?,?,?,?,?,?,?)",id(),target,lateSource,lateDevice,session,measuredExternal,at(observed));
            jdbc.update("INSERT INTO inbox_message(inbox_id,source,source_msg_id,received_at,processed_at,source_id,payload_hash,payload,status) VALUES(?,?,?,?,?,?,REPEAT('b',64),CAST('{}' AS JSON),'DONE')",inbox,"sim-normalized:"+lateSource,id(),received,received,lateSource);
            jdbc.update("INSERT INTO source_observation(observation_id,inbox_id,source_id,device_id,source_session_key,external_target_id,observed_at,received_at,location,position_accuracy_m,altitude_amsl_m,quality,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,?,?,?,?,?,?,ST_SetSRID(ST_MakePoint(?,?),4326),2,100,CAST('{}' AS JSON),?,?,?,?)",observation,inbox,lateSource,lateDevice,session,measuredExternal,at(observed),at(received),longitude-.001,latitude,mode,org,district,at(received));
            jdbc.update("INSERT INTO track_point(point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,point_kind,observation_id,position_accuracy_m,position_source_id,source_switched,created_at)"
                    +" VALUES(?,?,(SELECT MAX(point_seq)+1 FROM track_point WHERE track_id=?),?,?,ST_SetSRID(ST_MakePoint(?,?),4326),100,'MEAS',?,2,?,TRUE,?)",
                    id(),measuredTrack,measuredTrack,at(observed),at(received),longitude-.001,latitude,observation,lateSource,at(received));
        }
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
