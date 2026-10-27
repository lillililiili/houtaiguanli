package com.uav.lowaltitude.modules.prediction.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine;
import com.uav.lowaltitude.modules.prediction.domain.PredictionSpatialEvaluator.*;
import com.uav.lowaltitude.modules.prediction.domain.PredictionStability;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository.Inputs;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository.Target;
import com.uav.lowaltitude.platform.database.MigrationLineage;
import com.uav.lowaltitude.platform.number.BusinessNumberService;
import com.uav.lowaltitude.platform.time.AppClock;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real per-target transactions, outbox SQL and migrated FK/trigger paths, without application jobs or seeders. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class PredictionWorkerConcurrencyPostgresTest {
    private static final Database DATABASE=new Database();

    @BeforeAll static void initialize(){DATABASE.initialize();}
    @AfterAll static void close(){DATABASE.close();}

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void fusionTargetLockSkipsBeforeOwningOutboxOrTakingSnapshotForeignKey(String mode) throws Exception {
        for(String strength:List.of("FOR UPDATE","FOR NO KEY UPDATE")) {
            Fixtures f=new Fixtures(mode);f.enteredEpisodes(1);
            var before=f.job();
            try(var fusion=DATABASE.connection()) {
                lock(fusion,"SELECT target_id FROM target WHERE target_id=? "+strength,f.target);
                runBounded(()->f.process(f.now));
                assertThat(f.job()).isEqualTo(before);
                assertThat(f.snapshots()).isEqualTo(1);
                verifyNoInteractions(f.inputs,f.response);
                // Fusion can still register its next measurement while retaining the target lock.
                // An old worker blocked by the snapshot FK would instead own this job and form a cycle.
                try(var statement=fusion.prepareStatement("SELECT target_id FROM prediction_job WHERE target_id=? FOR UPDATE NOWAIT")) {
                    statement.setString(1,f.target);statement.executeQuery().close();
                }
                fusion.rollback();
            }
            f.process(f.now);
            f.assertRecovered(1);
        }
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void contendedEpisodeReleasesAlreadyAcquiredEpisodeTargetAndOutboxLocks(String mode) throws Exception {
        Fixtures f=new Fixtures(mode);var episodes=f.enteredEpisodes(2);var before=f.job();
        try(var merging=DATABASE.connection()) {
            lock(merging,"SELECT episode_id FROM prediction_episode WHERE episode_id=? FOR UPDATE",episodes.get(1));
            runBounded(()->f.process(f.now));
            assertThat(f.job()).isEqualTo(before);
            assertThat(f.snapshots()).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM prediction_episode_history WHERE episode_id IN (?,?)",Long.class,episodes.get(0),episodes.get(1))).isZero();
            verifyNoInteractions(f.inputs,f.response);
            try(var next=DATABASE.connection()) {
                lock(next,"SELECT target_id FROM target WHERE target_id=? FOR UPDATE NOWAIT",f.target);
                lock(next,"SELECT target_id FROM prediction_job WHERE target_id=? FOR UPDATE NOWAIT",f.target);
                lock(next,"SELECT episode_id FROM prediction_episode WHERE episode_id=? FOR UPDATE NOWAIT",episodes.get(0));
                next.rollback();
            }
            // The measured merger owns episode first and may now acquire target, completing normally.
            lock(merging,"SELECT target_id FROM target WHERE target_id=? FOR UPDATE NOWAIT",f.target);
            merging.rollback();
        }
        f.process(f.now);f.assertRecovered(2);
    }

    @Test
    void anotherWorkerOwningOutboxDoesNotLeaveTargetLocked() throws Exception {
        Fixtures f=new Fixtures("live");var before=f.job();
        try(var other=DATABASE.connection()) {
            lock(other,"SELECT target_id FROM prediction_job WHERE target_id=? FOR UPDATE",f.target);
            runBounded(()->f.process(f.now));
            assertThat(f.job()).isEqualTo(before);
            assertThat(f.snapshots()).isZero();
            verifyNoInteractions(f.inputs,f.response);
            try(var next=DATABASE.connection()) {
                lock(next,"SELECT target_id FROM target WHERE target_id=? FOR UPDATE NOWAIT",f.target);next.rollback();
            }
            other.rollback();
        }
    }

    @Test
    void productionClockIsReadAfterLocksWithoutExtendingMeasurementValidity() {
        Fixtures f=new Fixtures("live");
        long measuredAt=f.now+300,computedAt=f.now+600;
        var clock=mock(AppClock.class);
        when(clock.nowMillis()).thenReturn(f.now,computedAt);
        var points=new ArrayList<PredictionEngine.Measurement>();
        for(int i=4;i>=0;i--) {
            long observed=measuredAt-i*1_000;
            points.add(new PredictionEngine.Measurement(id(),observed,observed,
                    f.lon-i*30/(111_195*Math.cos(Math.toRadians(f.lat))),f.lat,1,100d,null));
        }
        var input=new Inputs(f.target(),points,f.target,null,List.of(),Map.of(),Set.of(),Set.of(),List.of());
        when(f.inputs.load(eq(f.target),anyLong(),any())).thenReturn(input);
        var worker=f.worker(clock);
        f.tx.executeWithoutResult(status->worker.processCurrent(f.target));
        assertThat(f.jdbc.queryForMap("SELECT status,generated_at,based_on_observed_at,valid_until FROM prediction_snapshot WHERE target_id=?",f.target))
                .containsEntry("status","READY").containsEntry("generated_at",computedAt)
                .containsEntry("based_on_observed_at",measuredAt).containsEntry("valid_until",measuredAt+3_000);
        verify(f.inputs).load(eq(f.target),eq(computedAt),any());
        verify(clock,times(2)).nowMillis();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void fusionEnqueueAndActualSnapshotInsertionCompleteWithoutLockUpgradeCycle(boolean existingCycle) throws Exception {
        Fixtures f=new Fixtures(existingCycle?"replay":"live");
        if(existingCycle)f.enteredEpisodes(1);
        var reading=new CountDownLatch(1);var release=new CountDownLatch(1);var fusionStarted=new CountDownLatch(1);
        when(f.inputs.load(eq(f.target),anyLong(),any())).thenAnswer(call->{
            reading.countDown();await(release);return f.input(call.getArgument(1));
        });
        var executor=Executors.newFixedThreadPool(2);
        try {
            var prediction=executor.submit(()->f.process(f.now));
            assertThat(reading.await(3,TimeUnit.SECONDS)).isTrue();
            var fusion=executor.submit(()->f.tx.executeWithoutResult(status->{
                f.jdbc.execute("SET LOCAL lock_timeout='5s'");
                fusionStarted.countDown();
                f.jdbc.update("UPDATE target SET updated_at=? WHERE target_id=?",at(f.now+1_000),f.target);
                f.queue.register(List.of(f.track),f.now+1_000);
            }));
            assertThat(fusionStarted.await(3,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            prediction.get(5,TimeUnit.SECONDS);fusion.get(5,TimeUnit.SECONDS);
            assertThat(f.snapshots()).isEqualTo(existingCycle?2:1);
            assertThat(f.job()).containsEntry("attempts",0).containsEntry("last_error",null)
                    .containsEntry("requested_at",f.now+1_000);
            verifyNoInteractions(f.response);
        } finally { release.countDown();executor.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void newCycleStillCreatesOneRealAlarmAndEventAfterThreeDistinctApproachingObservations(String mode) throws Exception {
        Fixtures f=new Fixtures(mode);String region=id(),version=id();
        // The stable route is eastbound and enters a rectangle in about ten seconds.
        var shape=new Polygon(List.of(new GeoPoint(f.lon+.003,f.lat-.01),new GeoPoint(f.lon+.02,f.lat-.01),
                new GeoPoint(f.lon+.02,f.lat+.01),new GeoPoint(f.lon+.003,f.lat+.01)),List.of());
        var area=new AirspaceVersion(region,version,f.now-60_000,null,"PROHIBITED",0d,200d,"AMSL",List.of(shape));
        when(f.inputs.load(eq(f.target),anyLong(),any())).thenAnswer(call->{
            long now=call.getArgument(1);var points=new ArrayList<PredictionEngine.Measurement>();
            for(int i=4;i>=0;i--) {
                long observed=now-i*1_000;double east=(observed-f.now)/1_000d*30;
                points.add(new PredictionEngine.Measurement(id(),observed,observed,
                        f.lon+east/(111_195*Math.cos(Math.toRadians(f.lat))),f.lat,1,100d,null));
            }
            return new Inputs(f.target(),points,f.target,null,List.of(area),Map.of(region,"Isolated approach rectangle"),Set.of(),Set.of(region),List.of());
        });
        for(int i=0;i<4;i++)f.process(f.now+i*1_000);
        assertThat(f.jdbc.queryForList("SELECT status FROM prediction_episode WHERE target_id=?",String.class,f.target)).containsExactly("ACTIVE");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM alarm WHERE target_id=? AND alarm_type=?",Long.class,f.target,PredictionWorker.ORIGIN)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE a.target_id=?",Long.class,f.target)).isEqualTo(1);
        String event=f.jdbc.queryForObject("SELECT e.event_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE a.target_id=?",String.class,f.target);
        String episode=f.jdbc.queryForObject("SELECT episode_id FROM prediction_episode WHERE target_id=?",String.class,f.target);
        var current=f.jdbc.queryForList("SELECT risk_case_id,root_event_id,episode_id,airspace_id FROM uav_risk_case WHERE target_id=? AND superseded_by IS NULL",f.target);
        assertThat(current).hasSize(1);
        assertThat(current.get(0)).containsEntry("risk_case_id",episode).containsEntry("root_event_id",event)
                .containsEntry("episode_id",episode).containsEntry("airspace_id",region);
        // Event insertion first retains an unscoped legacy identity. The explicit prediction link
        // supersedes that shell in the same transaction; it must not become a second current case.
        var historical=f.jdbc.queryForList("SELECT risk_case_id,root_event_id,episode_id,superseded_by FROM uav_risk_case WHERE target_id=? AND superseded_by IS NOT NULL",f.target);
        assertThat(historical).hasSize(1);
        assertThat(historical.get(0)).containsEntry("risk_case_id",event).containsEntry("root_event_id",event)
                .containsEntry("episode_id",null).containsEntry("superseded_by",episode);
        assertThat(f.jdbc.queryForList("SELECT event_id FROM uav_risk_case_member WHERE risk_case_id=? AND role='PREDICTED'",String.class,episode)).containsExactly(event);
        assertThat(f.jdbc.queryForList("SELECT event_id FROM uav_risk_case_member WHERE risk_case_id=? AND role='OBSERVED'",String.class,event)).containsExactly(event);
        verifyNoInteractions(f.response);
    }

    private static void runBounded(Runnable work) throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try{executor.submit(work).get(3,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Timed out coordinating isolated concurrency test");
    }
    private static void lock(Connection connection,String sql,String key) throws Exception {
        try(var statement=connection.prepareStatement(sql)){statement.setString(1,key);statement.executeQuery().close();}
    }

    private static final class Fixtures {
        final JdbcTemplate jdbc=DATABASE.jdbc();
        final ObjectMapper json=new ObjectMapper();
        final long now=System.currentTimeMillis();
        final double lon=113+Math.random(),lat=30+Math.random();
        final String org=id(),district=id(),target=id(),track=id(),mode;
        final PredictionInputRepository inputs=mock(PredictionInputRepository.class);
        final PredictionResponseService response=mock(PredictionResponseService.class);
        final PredictionConfiguration config=new PredictionConfiguration(json,"");
        final PredictionJobQueue queue=new PredictionJobQueue(jdbc,DATABASE.dataSource(),true);
        final TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(DATABASE.dataSource()));
        final PredictionWorker worker;
        final List<String> regions=new ArrayList<>();

        Fixtures(String mode) {
            this.mode=mode;tx.setTimeout(6);
            jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",org,org,"Isolated prediction lock owner");
            jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",district,district,"Isolated prediction lock district");
            jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,'UAV',?,?,?,?,?)",target,"LOCK-"+target,mode,org,district,at(now),at(now));
            jdbc.update("INSERT INTO track(track_id,target_id,external_track_id,layer,started_at,created_at) VALUES(?,?,?,'FUSED',?,?)",track,target,track,at(now),at(now));
            tx.executeWithoutResult(status->queue.register(List.of(track),now));
            when(inputs.load(eq(target),anyLong(),any())).thenAnswer(call->input(call.getArgument(1)));
            worker=worker(new AppClock());
        }
        PredictionWorker worker(AppClock clock){return new PredictionWorker(jdbc,json,queue,inputs,config,response,new AlarmMergeRepository(jdbc),
                new UavEventRepository(jdbc),new BusinessNumberService(new NamedParameterJdbcTemplate(jdbc)),clock,new DataSourceTransactionManager(DATABASE.dataSource()));}
        Target target(){return new Target(target,"LOCK-"+target,org,district,mode,"replay".equals(mode));}
        Inputs input(long now) {
            var points=new ArrayList<PredictionEngine.Measurement>();
            for(int i=4;i>=0;i--)points.add(new PredictionEngine.Measurement(id(),now-i*1_000,now-i*1_000,lon,lat,1,100d,null));
            return new Inputs(target(),points,target,null,List.of(),Map.of(),Set.copyOf(regions),Set.of(),List.of());
        }
        List<String> enteredEpisodes(int count) throws Exception {
            jdbc.update("INSERT INTO prediction_parameter_version(version_id,parameters_json,created_at) VALUES(?,CAST(? AS JSON),?) ON CONFLICT DO NOTHING",config.version(),config.serialized(),now);
            String snapshot=id();
            jdbc.update("INSERT INTO prediction_snapshot(prediction_id,target_id,generated_at,based_on_observed_at,valid_until,status,reason,model_version,rule_version,input_json,config_json,result_json,owner_org_id,district_id,source_mode,simulated) VALUES(?,?,?,?,?,'READY','ISOLATED','K03',?,CAST('{}' AS JSON),CAST('{}' AS JSON),CAST('{}' AS JSON),?,?,?,?)",
                    snapshot,target,now-1_000,now-1_000,now+2_000,config.version(),org,district,mode,"replay".equals(mode));
            var episodes=new ArrayList<String>();
            for(int i=0;i<count;i++) {
                String episode=id(),region=id();episodes.add(episode);regions.add(region);
                jdbc.update("INSERT INTO prediction_episode(episode_id,target_id,airspace_id,active_key,source_mode,owner_org_id,district_id,status,latest_prediction_id,first_prediction_id,last_observed_at,valid_until,entered_evidence_valid,reason,started_at,stability_json,warning_json) VALUES(?,?,?,?,?,?,?,'ENTERED',?,?,?,?,TRUE,'ACTUAL_ENTRY_OBSERVED',?,CAST(? AS JSON),CAST('{}' AS JSON))",
                        episode,target,region,target+"|"+region,mode,org,district,snapshot,snapshot,now-1_000,now+2_000,now-1_000,json.writeValueAsString(PredictionStability.State.initial()));
            }
            episodes.sort(String::compareTo);return episodes;
        }
        void process(long now){tx.executeWithoutResult(status->{jdbc.execute("SET LOCAL lock_timeout='2s'");worker.process(target,now);});}
        Map<String,Object> job(){return jdbc.queryForMap("SELECT requested_at,processed_at,next_attempt_at,attempts,last_error FROM prediction_job WHERE target_id=?",target);}
        long snapshots(){return jdbc.queryForObject("SELECT COUNT(*) FROM prediction_snapshot WHERE target_id=?",Long.class,target);}
        void assertRecovered(int regions){
            assertThat(snapshots()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_episode WHERE target_id=? AND status='ENTERED' AND entered_evidence_valid=TRUE AND valid_until=?",Long.class,target,now+3_000)).isEqualTo(regions);
            assertThat(job()).containsEntry("processed_at",now).containsEntry("attempts",0).containsEntry("last_error",null);
            verifyNoInteractions(response);
        }
    }

    private static final class Database implements AutoCloseable {
        private final String schema="prediction_locks_"+id().replace("-","");
        private boolean created;
        private DataSource scoped;
        void initialize(){
            JdbcTemplate root=new JdbcTemplate(rootDataSource());
            String database=root.queryForObject("SELECT current_database()",String.class);
            if(database==null||!database.matches("stage456_verify_[a-z0-9_]+"))throw new IllegalStateException("Isolated database required");
            verifySchema();root.execute("CREATE SCHEMA "+schema);created=true;
            try{
                var configuration=Flyway.configure().dataSource(rootDataSource()).schemas(schema).defaultSchema(schema)
                        .createSchemas(false).cleanDisabled(true).locations("classpath:db/migration","classpath:db/postgresql");
                MigrationLineage.configure(configuration);configuration.load().migrate();
            }catch(RuntimeException failure){close();throw failure;}
        }
        synchronized DataSource dataSource(){
            if(scoped==null)scoped=new DriverManagerDataSource(url()+"?currentSchema="+schema+",public",env("POSTGRES_TEST_USER"),env("POSTGRES_TEST_PASSWORD"));
            return scoped;
        }
        Connection connection() throws Exception {
            var connection=dataSource().getConnection();connection.setAutoCommit(false);
            try(var statement=connection.createStatement()){statement.execute("SET LOCAL lock_timeout='5s'");}
            return connection;
        }
        DataSource rootDataSource(){return new DriverManagerDataSource(url(),env("POSTGRES_TEST_USER"),env("POSTGRES_TEST_PASSWORD"));}
        JdbcTemplate jdbc(){return new JdbcTemplate(dataSource());}
        String url(){
            String url=env("POSTGRES_TEST_URL");
            if(!url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))throw new IllegalStateException("Unsafe PostgreSQL test URL");
            return url;
        }
        void verifySchema(){if(!schema.matches("prediction_locks_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe disposable schema");}
        @Override public void close(){if(created){verifySchema();new JdbcTemplate(rootDataSource()).execute("DROP SCHEMA "+schema+" CASCADE");created=false;}}
        static String env(String name){String value=System.getenv(name);if(value==null)throw new IllegalStateException(name+" required");return value;}
    }
    private static String id(){return UUID.randomUUID().toString();}
    private static OffsetDateTime at(long millis){return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC);}
}
