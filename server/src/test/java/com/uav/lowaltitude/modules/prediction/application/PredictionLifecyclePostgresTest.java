package com.uav.lowaltitude.modules.prediction.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository.AlarmQuery;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmMergeRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;
import com.uav.lowaltitude.modules.identity.domain.*;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine;
import com.uav.lowaltitude.modules.prediction.domain.PredictionSpatialEvaluator.*;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository.*;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.platform.number.BusinessNumberService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Transactional
class PredictionLifecyclePostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){DATABASE.springProperties(r);}
    @AfterAll static void close(){DATABASE.close();}
    @Autowired JdbcTemplate jdbc;
    @Autowired PredictionWorker worker;
    @Autowired ObjectMapper json;
    @Autowired FusionConfigService fusionConfig;
    @Autowired PredictionJobQueue jobs;
    @Autowired PredictionConfiguration configuration;
    @Autowired AlarmMergeRepository alarms;
    @Autowired UavEventRepository events;
    @Autowired BusinessNumberService numbers;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean PredictionInputRepository input;
    @MockitoBean PredictionResponseService response;
    private Target target;
    private String region,track;
    private long base;
    private double longitude,latitude;

    @BeforeEach void fixtures() {
        String org=id(),district=id(); region=id();track=id();
        base=Instant.now().toEpochMilli();longitude=117+new Random().nextDouble();latitude=36+new Random().nextDouble();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",org,org,"K03 isolated org");
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",district,district,"K03 isolated district");
        target=new Target(id(),"K03-"+id().substring(0,8),org,district,"live",false);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,owner_org_id,district_id,source_mode,created_at,updated_at) VALUES (?,?,'UAV',?,?,'live',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                target.id(),target.no(),org,district);
        jdbc.update("INSERT INTO prediction_job(target_id,requested_at) VALUES (?,?)",target.id(),base);
    }
    @Test void threeNewSamplesOpenOnceAndSixSafeSamplesWithdrawWithoutFalsePositiveThenNewCycle() {
        for(int second=0;second<3;second++) step(second,0,10,false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link WHERE role='PREDICTED'",Long.class)).isEqualTo(1);
        var first=jdbc.queryForMap("SELECT * FROM prediction_episode WHERE target_id=?",target.id());
        assertThat(first.get("status")).isEqualTo("ACTIVE");
        String episode=(String)first.get("episode_id");
        // Duplicate generation from the same measurement does not count as another observation.
        jdbc.update("UPDATE prediction_job SET next_attempt_at=0 WHERE target_id=?",target.id());
        worker.process(target.id(),base+2001);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link WHERE role='PREDICTED'",Long.class)).isEqualTo(1);
        for(int second=3;second<=7;second++) step(second,-500,-10,false);
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE episode_id=?",String.class,episode)).isEqualTo("UNKNOWN");
        step(8,-500,-10,false);
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE episode_id=?",String.class,episode)).isEqualTo("CLEARED");
        assertThat(jdbc.queryForObject("SELECT active_key FROM prediction_episode WHERE episode_id=?",String.class,episode)).isNull();
        assertThat(jdbc.queryForObject("SELECT e.state_code FROM uav_event e JOIN prediction_event_link l ON l.event_id=e.event_id WHERE l.episode_id=?",String.class,episode)).isEqualTo("PENDING_VERIFICATION");
        verify(response,atLeastOnce()).pause(anyString(),eq("NEW_MEASUREMENTS_CONFIRM_AVOIDANCE"),anyLong());
        for(int second=9;second<=11;second++) step(second,0,10,false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link WHERE role='PREDICTED'",Long.class)).isEqualTo(2);
    }
    @Test void staleInputPausesAndNeverClearsOrContributesSafeSamples() {
        for(int second=0;second<3;second++) step(second,0,10,false);
        String basis=jdbc.queryForObject("SELECT prediction_id FROM prediction_current WHERE target_id=?",String.class,target.id());
        var stale=measurements(base+2000,0,10);
        when(input.load(eq(target.id()),anyLong(),any())).thenReturn(data(stale,Set.of(),Set.of(region),null));
        worker.process(target.id(),base+6000);
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE target_id=?",String.class,target.id())).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT current_evidence_valid FROM prediction_episode WHERE target_id=?",Boolean.class,target.id())).isFalse();
        verify(response,atLeastOnce()).pause(anyString(),anyString(),anyLong());
        when(input.load(eq(target.id()),anyLong(),any())).thenReturn(new Inputs(target,List.of(),track,
                "STALE_OR_UNTRUSTED_MEASUREMENT",List.of(),Map.of(),Set.of(),Set.of(),List.of()));
        worker.process(target.id(),base+8000);
        assertThat(jdbc.queryForObject("SELECT last_observed_at FROM prediction_episode WHERE target_id=?",Long.class,target.id())).isEqualTo(base+2000);
        var reader=new PredictionReadService(new NamedParameterJdbcTemplate(jdbc),p->new AccessDecision("reader",ScopeMode.ALL),json,
                new AppClock(Clock.fixed(Instant.ofEpochMilli(base+9000),ZoneOffset.UTC)));
        for(long now:List.of(base+9000,base+10_000)) {
            jdbc.update("UPDATE prediction_job SET next_attempt_at=0 WHERE target_id=?",target.id());
            worker.process(target.id(),now);
            var result=reader.target(target.id());
            assertThat(result).containsEntry("status","UNKNOWN").containsEntry("evidence_status","LAST_KNOWN")
                    .containsEntry("based_on_observed_at",base+2000).containsEntry("last_evidence_prediction_id",basis)
                    .containsEntry("reason_codes",List.of("STALE_OR_UNTRUSTED_MEASUREMENT"));
            assertThat((List<?>)result.get("points")).isEmpty();
            assertThat(result).doesNotContainKey("trajectory");
            var warning=(Map<?,?>)result.get("warning");
            assertThat(warning.get("airspace_name")).isEqualTo("Dynamic prohibited area");
            assertThat(warning.get("airspace_version_ids")).isEqualTo(List.of(region));
            assertThat(warning.get("reason")).isEqualTo("STALE_OR_UNTRUSTED_MEASUREMENT");
            assertThat(warning.get("airspace_basis")).isEqualTo("LAST_KNOWN");
            assertThat(warning.containsKey("entry_at")).isFalse();
        }
    }
    @Test void workerSamplingGapKeepsReliableWarningWhenFrozenMeasurementsProveContinuousInput() {
        step(0,0,10,false);
        assertThat(jdbc.queryForObject("SELECT reason FROM prediction_episode WHERE target_id=?",String.class,target.id()))
                .isEqualTo("ENTRY_AWAITING_CONFIRMATION");
        step(1,0,10,false);
        step(2,0,10,false);
        var points=new ArrayList<>(measurements(base+2000,0,10));
        for(long delta:new long[]{3100,4100}) {
            var p=point((delta-2000)/100.0,0);
            points.add(new PredictionEngine.Measurement(id(),base+delta,base+delta,p.longitude(),p.latitude(),1.0,100.0,null));
        }
        when(input.load(eq(target.id()),anyLong(),any())).thenReturn(data(points,Set.of(),Set.of(region),null));
        worker.process(target.id(),base+4100);
        var row=jdbc.queryForMap("SELECT status,stability_json FROM prediction_episode WHERE target_id=?",target.id());
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        var state=json.convertValue(readJson(row.get("stability_json")),com.uav.lowaltitude.modules.prediction.domain.PredictionStability.State.class);
        assertThat(state.streakCount()).isEqualTo(4);
        assertThat(state.streakStartedAt()).isEqualTo(base);
        verify(response,never()).pause(anyString(),anyString(),anyLong());
        jdbc.update("UPDATE prediction_job SET next_attempt_at=0 WHERE target_id=?",target.id());
        worker.process(target.id(),base+4101);
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE target_id=?",String.class,target.id())).isEqualTo("ACTIVE");
        var gapPoint=point(42.01,0);
        points.add(new PredictionEngine.Measurement(id(),base+6201,base+6201,gapPoint.longitude(),gapPoint.latitude(),1.0,100.0,null));
        when(input.load(eq(target.id()),anyLong(),any())).thenReturn(data(points,Set.of(),Set.of(region),null));
        worker.process(target.id(),base+6201);
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE target_id=?",String.class,target.id())).isEqualTo("UNKNOWN");
        verify(response,atLeastOnce()).pause(anyString(),eq("DISCONTINUOUS_MEASUREMENTS"),anyLong());
    }
    @Test void clockExpiryChangesAlarmStatusFilterWithoutWaitingForWorker() {
        for(int second=0;second<3;second++) step(second,0,10,false);
        var access=new AccessDecision("reader",ScopeMode.ALL);
        var active=new AlarmQuery(null,null,target.id(),null,null,null,null,"live",null,null,null,null,
                PredictionWorker.ORIGIN,"ACTIVE");
        var unknown=new AlarmQuery(null,null,target.id(),null,null,null,null,"live",null,null,null,null,
                PredictionWorker.ORIGIN,"UNKNOWN");
        var before=new AlarmReadRepository(jdbc,new AppClock(Clock.fixed(Instant.ofEpochMilli(base+4999),ZoneOffset.UTC)),fusionConfig);
        assertThat(before.count(active,access)).isEqualTo(1);
        assertThat(before.count(unknown,access)).isZero();
        var expired=new AlarmReadRepository(jdbc,new AppClock(Clock.fixed(Instant.ofEpochMilli(base+5000),ZoneOffset.UTC)),fusionConfig);
        assertThat(expired.count(unknown,access)).isEqualTo(1);
        assertThat(expired.count(active,access)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE target_id=?",String.class,target.id())).isEqualTo("ACTIVE");
    }
    @Test void actualEntrySurvivesUnstableForecastButRequiresCurrentMeasurementAndCanEndCycle() {
        for(int second=0;second<3;second++) step(second,0,10,false);
        step(3,300,10,true);
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE target_id=?",String.class,target.id())).isEqualTo("ENTERED");
        when(input.load(eq(target.id()),anyLong(),any())).thenReturn(data(measurements(base+4000,300,0),Set.of(region),Set.of(),null));
        worker.process(target.id(),base+4000);
        var row=jdbc.queryForMap("SELECT * FROM prediction_episode WHERE target_id=?",target.id());
        assertThat(row.get("entered_evidence_valid")).isEqualTo(true);
        assertThat(((Number)row.get("valid_until")).longValue()).isEqualTo(base+7000);
        jdbc.update("UPDATE prediction_job SET next_attempt_at=0 WHERE target_id=?",target.id());
        worker.process(target.id(),base+4001);
        var repeated=jdbc.queryForMap("SELECT * FROM prediction_episode WHERE target_id=?",target.id());
        assertThat(repeated.get("entered_evidence_valid")).isEqualTo(true);
        assertThat(((Number)repeated.get("valid_until")).longValue()).isEqualTo(base+7000);
        for(int second=5;second<=10;second++) step(second,-500,-10,false);
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE target_id=?",String.class,target.id())).isEqualTo("ENTERED");
        assertThat(jdbc.queryForObject("SELECT active_key FROM prediction_episode WHERE target_id=?",String.class,target.id())).isNull();
    }
    @Test void readScopeAndFrozenThreeHorizonsRemainDistinctFromActualState() {
        for(int second=0;second<3;second++) step(second,0,10,false);
        var reader=new PredictionReadService(new NamedParameterJdbcTemplate(jdbc),p->new AccessDecision("reader",ScopeMode.ALL),json,
                new AppClock(Clock.fixed(Instant.ofEpochMilli(base+2000),ZoneOffset.UTC)));
        var page=reader.current(new LinkedMultiValueMap<>());
        assertThat(page.total()).isEqualTo(1);
        assertThat((List<?>)page.items().get(0).get("points")).hasSize(3);
        var linked=jdbc.queryForMap("SELECT e.event_id,e.alarm_id FROM prediction_event_link l JOIN uav_event e ON e.event_id=l.event_id WHERE l.role='PREDICTED' AND l.episode_id IN (SELECT episode_id FROM prediction_episode WHERE target_id=?)",target.id());
        String event=(String)linked.get("event_id"),alarm=(String)linked.get("alarm_id");
        var batch=reader.enrichList(List.of(alarmDto(alarm,event,target.id()))).get(0);
        assertThat(batch.getOriginKind()).isEqualTo(PredictionWorker.ORIGIN);
        assertThat((List<?>)batch.getPrediction().get("points")).hasSize(3);
        assertThat((List<?>)batch.getPrediction().get("warnings")).hasSize(1);
        assertThat(batch.getPredictionSnapshot()).isEmpty();
        assertThat(batch.getPredictionHistory()).isEmpty();
        var detail=reader.enrich(alarmDto(alarm,event,target.id()),event,true);
        assertThat(detail.getPredictionSnapshot()).containsKey("prediction_id");
        assertThat((List<?>)detail.getPredictionSnapshot().get("points")).hasSize(3);
        assertThat(detail.getPredictionHistory()).isNotEmpty();
        assertThat(reader.exportSummaries(List.of(event)).get(event))
                .containsExactly("预测进入禁飞区","预测风险持续","连续实测支持稳定的入区预测");
        var hidden=reader.enrichList(List.of(alarmDto(alarm,event,null))).get(0);
        assertThat(hidden.getPrediction()).doesNotContainKeys("target_id","target_no","anchor","points","trajectory","heading_deg","speed_mps");
        for(Object warning:(List<?>)hidden.getPrediction().get("warnings"))
            assertThat(((Map<?,?>)warning).containsKey("entry_location")).isFalse();
        var hiddenDetail=reader.enrich(alarmDto(alarm,event,null),event,false);
        assertThat(hiddenDetail.getPredictionSnapshot()).doesNotContainKeys("target_id","target_no","anchor","points","trajectory");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM target_latest_state WHERE target_id=?",Long.class,target.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_evaluation WHERE target_id=?",Long.class,target.id())).isZero();
        var denied=new PredictionReadService(new NamedParameterJdbcTemplate(jdbc),p->new AccessDecision("different",ScopeMode.ASSIGNED),json,new AppClock());
        assertThat(denied.current(new LinkedMultiValueMap<>()).total()).isZero();
        assertThatThrownBy(()->denied.target(target.id())).isInstanceOf(com.uav.lowaltitude.platform.api.ApiException.class);
    }
    @Test void measuredEntryBeforeThreeConfirmationsDoesNotCreateLatePredictiveAlarm() {
        step(0,0,10,false);
        step(1,300,10,true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link WHERE role='PREDICTED'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT reason FROM prediction_episode WHERE target_id=?",String.class,target.id())).isEqualTo("ACTUAL_ENTRY_BEFORE_WARNING");
        assertThat(jdbc.queryForObject("SELECT active_key FROM prediction_episode WHERE target_id=?",String.class,target.id())).isNull();
    }
    @Test void unknownReasonIsPreservedAndTransferredTargetDoesNotExposePriorScopeSnapshot() {
        when(input.load(eq(target.id()),anyLong(),any())).thenReturn(data(measurements(base,0,0),Set.of(),Set.of(region),null));
        worker.process(target.id(),base);
        var reader=new PredictionReadService(new NamedParameterJdbcTemplate(jdbc),p->new AccessDecision("reader",ScopeMode.ALL),json,
                new AppClock(Clock.fixed(Instant.ofEpochMilli(base+100),ZoneOffset.UTC)));
        assertThat(reader.target(target.id()).get("reason_codes")).isEqualTo(List.of("DIRECTION_UNRESOLVED"));
        String nextOrg=id();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",nextOrg,nextOrg,"transferred");
        jdbc.update("UPDATE target SET owner_org_id=? WHERE target_id=?",nextOrg,target.id());
        assertThat(reader.current(new LinkedMultiValueMap<>()).items()).isEmpty();
        assertThat(reader.target(target.id()).get("prediction_id")).isNull();
    }
    @Test void missingMeasurementHeightCannotCreateAnEntryWarningFromHorizontalIntersectionAlone() {
        for(int second=0;second<3;second++) {
            long now=base+second*1000L;
            var noHeight=measurements(now,0,10).stream().map(p->new PredictionEngine.Measurement(
                    p.pointId(),p.observedAt(),p.receivedAt(),p.longitude(),p.latitude(),p.accuracyM(),null,null)).toList();
            when(input.load(eq(target.id()),anyLong(),any())).thenReturn(data(noHeight,Set.of(),Set.of(),null));
            jdbc.update("UPDATE prediction_job SET next_attempt_at=0 WHERE target_id=?",target.id());
            worker.process(target.id(),now);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_episode WHERE target_id=?",Long.class,target.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link WHERE role='PREDICTED'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT CAST(result_json AS VARCHAR) FROM prediction_snapshot s JOIN prediction_current c ON c.prediction_id=s.prediction_id WHERE c.target_id=?",
                String.class,target.id())).contains("ALTITUDE_RELATION_UNKNOWN");
    }
    @Test void durableQueueRetriesAfterFailureAndNewWorkerRestoresProcessingWithoutLosingWarning() {
        for(int second=0;second<3;second++) step(second,0,10,false);
        String fusedTrack=id();
        String fusionVersion=jdbc.queryForObject("SELECT config_version FROM fusion_config ORDER BY created_at,config_version FETCH FIRST 1 ROW ONLY",String.class);
        jdbc.update("INSERT INTO track(track_id,target_id,external_track_id,layer,config_version,started_at,created_at) VALUES (?,?,?,'FUSED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                fusedTrack,target.id(),fusedTrack,fusionVersion);
        jobs.register(List.of(fusedTrack),base+3000);
        assertThat(jdbc.queryForObject("SELECT requested_at FROM prediction_job WHERE target_id=?",Long.class,target.id())).isEqualTo(base+3000);
        when(input.load(eq(target.id()),anyLong(),any())).thenThrow(new IllegalStateException("test input temporarily unavailable"));
        workerAt(base+3000).poll();
        var failed=jdbc.queryForMap("SELECT * FROM prediction_job WHERE target_id=?",target.id());
        assertThat(((Number)failed.get("attempts")).intValue()).isEqualTo(1);
        assertThat(((Number)failed.get("next_attempt_at")).longValue()).isEqualTo(base+4000);
        assertThat(failed.get("last_error")).isEqualTo("PROCESSING_FAILED:IllegalStateException");
        assertThat(jdbc.queryForObject("SELECT status FROM prediction_episode WHERE target_id=?",String.class,target.id())).isEqualTo("UNKNOWN");
        verify(response,atLeastOnce()).pause(anyString(),eq("PREDICTION_PROCESSING_FAILED"),anyLong());
        doReturn(data(measurements(base+4000,0,10),Set.of(),Set.of(region),null))
                .when(input).load(eq(target.id()),anyLong(),any());
        // A new worker instance has no in-memory forecast/counter cache; durable rows drive recovery.
        workerAt(base+4000).poll();
        var recovered=jdbc.queryForMap("SELECT * FROM prediction_job WHERE target_id=?",target.id());
        assertThat(((Number)recovered.get("attempts")).intValue()).isZero();
        assertThat(((Number)recovered.get("processed_at")).longValue()).isEqualTo(base+4000);
        assertThat(((Number)recovered.get("next_attempt_at")).longValue()).isEqualTo(base+5000);
        assertThat(recovered.get("last_error")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link WHERE role='PREDICTED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_key FROM prediction_episode WHERE target_id=?",String.class,target.id())).isNotNull();
    }
    private PredictionWorker workerAt(long now) {
        return new PredictionWorker(jdbc,json,jobs,input,configuration,response,alarms,events,numbers,
                new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),transactions);
    }
    private AlarmDto alarmDto(String alarm,String event,String visibleTarget) {
        return new AlarmDto(alarm,event,"PENDING_VERIFICATION",PredictionWorker.ORIGIN,"HIGH",base+2000,base+2000,
                "k03-prediction-live","live",target.orgId(),target.districtId(),visibleTarget,null,null,null,null,
                visibleTarget==null?null:target.no(),"HIGH",List.of(),0,null,"CURRENT","CURRENT",null);
    }
    private com.fasterxml.jackson.databind.JsonNode readJson(Object value) {
        try { return json.readTree(value.toString()); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    private void step(int second,double x,double speed,boolean inside) {
        long now=base+second*1000L;
        when(input.load(eq(target.id()),anyLong(),any())).thenReturn(data(measurements(now,x,speed),
                inside?Set.of(region):Set.of(),inside?Set.of():Set.of(region),null));
        jdbc.update("UPDATE prediction_job SET next_attempt_at=0,requested_at=? WHERE target_id=?",now,target.id());
        worker.process(target.id(),now);
    }
    private Inputs data(List<PredictionEngine.Measurement> points,Set<String> inside,Set<String> outside,String invalid) {
        var ring=List.of(point(180,-200),point(1000,-200),point(1000,200),point(180,200),point(180,-200));
        var version=new AirspaceVersion(region,region,base-60_000,base+120_000,"PROHIBITED",0.0,200.0,"AMSL",List.of(new Polygon(ring,List.of())));
        return new Inputs(target,points,track,invalid,List.of(version),Map.of(region,"Dynamic prohibited area"),inside,outside,List.of());
    }
    private List<PredictionEngine.Measurement> measurements(long now,double x,double speed) {
        List<PredictionEngine.Measurement> result=new ArrayList<>();
        for(int i=-4;i<=0;i++) {
            var p=point(x+speed*i,0);long observed=now+i*1000L;
            result.add(new PredictionEngine.Measurement(UUID.nameUUIDFromBytes((track+observed).getBytes()).toString(),observed,observed,
                    p.longitude(),p.latitude(),1.0,100.0,null));
        }
        return result;
    }
    private GeoPoint point(double east,double north){double[] p=AlphaBetaFilter.fromEnu(longitude,latitude,east,north);return new GeoPoint(p[0],p[1]);}
    private static String id(){return UUID.randomUUID().toString();}
}
