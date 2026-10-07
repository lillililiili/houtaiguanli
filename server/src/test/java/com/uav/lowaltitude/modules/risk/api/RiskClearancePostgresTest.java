package com.uav.lowaltitude.modules.risk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.risk.application.RiskPresenceService;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskEvaluationService;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.identity.domain.*;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.fusion.FusionContracts.*;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort;

@SpringBootTest(properties="app.rule-engine.allow-demo-active=true")
@AutoConfigureMockMvc
@ActiveProfiles(value={"test","postgres-test"},inheritProfiles=false)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RiskClearancePostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){DATABASE.springProperties(p);}
    @AfterAll static void close(){DATABASE.close();}
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RiskPresenceService presence;
    @Autowired RiskRepository risks;
    @Autowired SpaceRiskEvaluationService evaluation;
    @Autowired SpaceRiskSpatialPort spatial;
    @Autowired FusedLayerWriter writer;
    @SpyBean AppClock clock;
    @SpyBean com.uav.lowaltitude.modules.assessment.engine.RuleParamLoader parameters;
    static final String ORG="seed-stage3-org",DISTRICT="seed-stage3-district";
    String risk,target,track,point,plan,session,routeVersion,factRule,sourceObservation,sourceLink;
    long now;
    @BeforeEach void fixture() { prepare(100,"space-risk-c04-v1"); }
    void prepare(Integer width,String rule) {
        reset(parameters);
        reset(clock);now=System.currentTimeMillis();
        doReturn(now).when(clock).nowMillis();doReturn(Instant.ofEpochMilli(now)).when(clock).now();
        risk=id();target=id();track=id();point=id();plan=id();session=id();routeVersion=id();factRule=rule;
        jdbc.update("update rule_set set active_version_id='space-risk-demo-v1' where rule_set_code='SPACE-RISK-DEMO'");
        String routeId=id();
        jdbc.update("insert into route(route_id,route_no,name,source_id,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,'QA C04 route','seed-stage3-source','mock',?,?,?,?)",routeId,"QA-"+routeId,ORG,DISTRICT,ts(now-3600000),ts(now));
        jdbc.update("insert into route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) values(?,?,1,ST_GeomFromText('LINESTRING(118 37,118.1 37)',4326),?,0,100,'AGL',?,?)",routeVersion,routeId,width,ts(now-3600000),ts(now-3600000));
        jdbc.update("update integration_source set enabled=true,source_mode='mock' where source_id='seed-stage3-source'");
        jdbc.update("insert into flight_plan(plan_id,plan_no,status_code,source_id,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version) select ?,?,'PENDING',source_id,'mock',?,?,?,owner_org_id,district_id,created_at,updated_at,0 from flight_plan where plan_id='seed-stage3-plan-legal'",plan,"QA-CLEAR-"+plan.substring(0,6),ts(now-3600000),ts(now+3600000),routeVersion);
        jdbc.update("insert into target(target_id,target_no,object_type_code,subtype,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'BIRD','BIRD','mock',?,?,?, ?,0)",target,"QA-CLEAR-"+target.substring(0,6),ORG,DISTRICT,ts(now-600000),ts(now));
        sourceObservation=insertSourceObservation(now-1000,now-500,37.01);
        jdbc.update("insert into track(track_id,target_id,link_id,external_track_id,layer,started_at,created_at) values(?,?,null,?,'FUSED',?,?)",track,target,track,ts(now-600000),ts(now-600000));
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,created_at,point_kind,position_accuracy_m,position_source_id,observation_id) values(?,?,1,?,?,ST_SetSRID(ST_MakePoint(118.05,37.01),4326),?,'MEAS',5,'seed-stage3-source',?)",point,track,ts(now-1000),ts(now-500),ts(now-500),sourceObservation);
        jdbc.update("insert into target_latest_state(target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at) select ?,location,observed_at,received_at,CAST('[]' AS JSON),created_at,created_at from track_point where point_id=?",target,point);
        originalRisk(risk,now-600000);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) select ?,user_id,?,'127.0.0.1',permission_version from app_user where account='admin1'",session,now+3600000);
    }
    @AfterEach void restoreClock(){reset(clock);reset(parameters);}

    @ParameterizedTest
    @CsvSource({"mock,PRED", "replay,PRED", "live,PRED", "mock,BRIDGE", "replay,BRIDGE", "live,BRIDGE"})
    void retainedFusionPositionCannotGenerateAnotherRisk(String mode,String kind) {
        position(37);
        jdbc.update("update target set source_mode=? where target_id=?",mode,target);
        String airport=id();
        jdbc.update("insert into airport(airport_id,icao_code,name,reference_point,owner_org_id,district_id,created_at) values(?,?,'QA prediction guard',ST_SetSRID(ST_MakePoint(118.05,37),4326),?,?,?)",airport,airport.substring(0,8),ORG,DISTRICT,ts(now));
        var from=Instant.ofEpochMilli(now-2000).atOffset(ZoneOffset.UTC);
        var to=Instant.ofEpochMilli(now+1).atOffset(ZoneOffset.UTC);
        assertThat(spatial.observations(from,to,15)).anyMatch(o -> target.equals(o.targetId()) && plan.equals(o.planId()));
        assertThat(spatial.airportProximity(from,to,15)).anyMatch(o -> target.equals(o.targetId()) && airport.equals(o.airportId()));
        // Exercise the production terminal-frame writer: its retained coordinate gets a new frame time.
        writer.write(new TargetFrameResult(target,new FusionDomainKey(mode,ORG,DISTRICT),
                Instant.ofEpochMilli(now),List.of(),TrackStatus.TERMINATED,1,"demo-v1"));
        jdbc.update("update track_point set point_kind=? where track_id=? and observed_at=?",kind,track,ts(now));
        assertThat(jdbc.queryForObject("select location is not null from target_latest_state where target_id=?",Boolean.class,target)).isTrue();
        assertThat(spatial.observations(from,to,15)).noneMatch(o -> target.equals(o.targetId()));
        assertThat(spatial.airportProximity(from,to,15)).noneMatch(o -> target.equals(o.targetId()));
        long before=jdbc.queryForObject("select count(*) from flight_risk where target_id=?",Long.class,target);
        assertThat(evaluation.evaluate("C04",from,to,"SCHEDULED",null).status()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where target_id=?",Long.class,target)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"mock,PRED", "replay,PRED", "live,PRED", "mock,BRIDGE", "replay,BRIDGE", "live,BRIDGE"})
    void predictionOnlyHistoricalEvaluationDoesNotReplaceGenuineRisk(String mode,String kind) throws Exception {
        position(37);
        jdbc.update("update target set source_mode=? where target_id=?",mode,target);
        writer.write(new TargetFrameResult(target,new FusionDomainKey(mode,ORG,DISTRICT),
                Instant.ofEpochMilli(now),List.of(),TrackStatus.TERMINATED,1,"demo-v1"));
        jdbc.update("update track_point set point_kind=? where track_id=? and observed_at=?",kind,track,ts(now));
        String predictedRisk=id();originalRisk(predictedRisk,now);
        jdbc.update("update flight_risk set source_mode=?,source_id=? where target_id=?",mode,"rule-engine-space-risk-"+mode,target);
        var before=jdbc.queryForList("select * from flight_risk where target_id=? order by risk_id",target);
        mvc.perform(get("/api/v1/risks/current?plan_id="+plan).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].risk.risk_id").value(risk));
        mvc.perform(get("/api/v1/risks/"+predictedRisk).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.current_status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.current_reason").value(org.hamcrest.Matchers.containsString("预测")));
        mvc.perform(get("/api/v1/risks?plan_id="+plan).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        // A later real measurement must not rehabilitate an assessment originally based on prediction.
        now+=5000;doReturn(now).when(clock).nowMillis();doReturn(Instant.ofEpochMilli(now)).when(clock).now();
        point=jdbc.queryForObject("select point_id from track_point where track_id=? order by point_seq desc limit 1",String.class,track);
        appendPosition(37);
        mvc.perform(get("/api/v1/risks/current?plan_id="+plan).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].risk.risk_id").value(risk));
        presence.recordC04Clearances();
        assertThat(jdbc.queryForList("select * from flight_risk where target_id=? order by risk_id",target)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from risk_clearance_evidence where risk_id in (?,?)",Integer.class,risk,predictedRisk)).isZero();
    }

    @Test @Order(1) void pipelinePersistsClearanceWithoutChangingNotificationAndReadsNeverWrite() throws Exception {
        var before=jdbc.queryForMap("select * from flight_risk where risk_id=?",risk);
        assertThat(readStatus()).isEqualTo("UNKNOWN");
        assertThat(count()).isZero();
        var run=evaluation.evaluate("C04",Instant.ofEpochMilli(now-2000).atOffset(ZoneOffset.UTC),Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC),"SCHEDULED",null);
        assertThat(run.status()).as(run.message()).isEqualTo("SUCCESS");
        assertThat(count()).isEqualTo(1);
        assertThat(readStatus()).isEqualTo("CLEARED");
        assertThat(jdbc.queryForMap("select * from flight_risk where risk_id=?",risk)).isEqualTo(before);
        presence.recordC04Clearances();assertThat(count()).isEqualTo(1);
        mvc.perform(get("/api/v1/risks/current?plan_id="+plan).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/risks?plan_id="+plan).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].current_status").value("CLEARED"))
                .andExpect(jsonPath("$.data.items[0].state").value("NOTIFIED"));
    }

    @Test void clearanceSurvivesExpiryAndReentryBelongsToANewRisk() throws Exception {
        presence.recordC04Clearances();assertThat(readStatus()).isEqualTo("CLEARED");
        doReturn(now+600000).when(clock).nowMillis();
        assertThat(readStatus()).isEqualTo("CLEARED");
        now+=5000;
        doReturn(now).when(clock).nowMillis();doReturn(Instant.ofEpochMilli(now)).when(clock).now();
        appendPosition(37.0001);
        String next=id();originalRisk(next,now-2000);
        assertThat(presence.read(risks.find(next,new AccessDecision("qa",ScopeMode.ALL)),now).status()).isEqualTo("CURRENT");
        presence.recordC04Clearances();
        assertThat(readStatus()).isEqualTo("CLEARED");
        assertThat(jdbc.queryForObject("select count(*) from risk_clearance_evidence where risk_id=?",Integer.class,next)).isZero();
        mvc.perform(get("/api/v1/risks/current?plan_id="+plan).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].risk.risk_id").value(next))
                .andExpect(jsonPath("$.data.items[0].risk.current_status").value("CURRENT"))
                .andExpect(jsonPath("$.data.items[0].current_status").value("CURRENT"));
    }

    @ParameterizedTest
    @ValueSource(strings={"old","future","late","stale","predicted","bridge","missing_latest","unknown_position","precision_unknown","overlap","wrong_scope","wrong_source","replay","unconfirmed_live","missing_rule","c05","same_timestamp_conflict","observation_mismatch","unknown_geometry"})
    void invalidEvidenceCannotClearOrPretendCurrent(String condition) throws Exception {
        switch(condition) {
            case "old" -> observationTime(now-700000,now-500);
            case "future" -> observationTime(now+1000,now+2000);
            case "late" -> observationTime(now-200000,now-500);
            case "stale" -> observationTime(now-200000,now-199000);
            case "predicted" -> jdbc.update("update track_point set point_kind='PRED' where point_id=?",point);
            case "bridge" -> jdbc.update("update track_point set point_kind='BRIDGE' where point_id=?",point);
            case "missing_latest" -> jdbc.update("update target_latest_state set location=null where target_id=?",target);
            case "unknown_position" -> jdbc.update("update target_latest_state set unknown_fields=CAST('[{\"field\":\"location\"}]' AS JSON) where target_id=?",target);
            case "precision_unknown" -> jdbc.update("update track_point set position_accuracy_m=null where point_id=?",point);
            case "overlap" -> jdbc.update("update track_point set position_accuracy_m=2000 where point_id=?",point);
            case "wrong_scope" -> jdbc.update("update target set owner_org_id='seed-stage3-other-org' where target_id=?",target);
            case "wrong_source" -> jdbc.update("update integration_source set source_mode='live' where source_id='seed-stage3-source'");
            case "replay" -> jdbc.update("update flight_risk set source_mode='replay',source_id='rule-engine-space-risk-replay' where risk_id=?",risk);
            case "unconfirmed_live" -> jdbc.update("update flight_risk set source_mode='live',source_id='rule-engine-space-risk-live' where risk_id=?",risk);
            case "missing_rule" -> doThrow(new com.uav.lowaltitude.platform.api.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"RULE_CONFIGURATION_INVALID","QA missing parameters")).when(parameters).load("space-risk-demo-v1");
            case "c05" -> prepare(100,"space-risk-c05-v1");
            case "same_timestamp_conflict" -> jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,created_at,point_kind,position_accuracy_m,position_source_id) select ?,track_id,2,observed_at,received_at,ST_SetSRID(ST_MakePoint(118.05,37),4326),created_at,'MEAS',5,position_source_id from track_point where point_id=?",id(),point);
            case "observation_mismatch" -> jdbc.update("update target_latest_state set observed_at=? where target_id=?",ts(now-2000),target);
            case "unknown_geometry" -> prepare(null,"space-risk-c04-v1");
        }
        presence.recordC04Clearances();
        assertThat(count()).as(condition).isZero();
        assertThat(readStatus()).as(condition).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?",String.class,risk)).isEqualTo("NOTIFIED");
    }

    @Test void notificationAcknowledgementAndMissingHeightCannotSubstituteForHorizontalEvidence() throws Exception {
        position(37.0001);
        jdbc.update("update flight_risk set state_code='ACKNOWLEDGED',observed_altitude_m=30,observed_altitude_datum='AMSL' where risk_id=?",risk);
        presence.recordC04Clearances();assertThat(count()).isZero();assertThat(readStatus()).isEqualTo("CURRENT");
        position(37.01);presence.recordC04Clearances();assertThat(readStatus()).isEqualTo("CLEARED");
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?",String.class,risk)).isEqualTo("ACKNOWLEDGED");
    }

    @ParameterizedTest
    @ValueSource(strings={"missing_observation","missing_link","other_target","other_scope","source_mismatch","stale_source","pre_risk_source","raw_masks_conflict","raw_masks_accuracy","raw_only"})
    void provenanceAndFusedAuthorityCannotBeBypassed(String condition) throws Exception {
        switch(condition) {
            case "missing_observation" -> jdbc.update("update track_point set observation_id=null where point_id=?",point);
            case "missing_link" -> jdbc.update("delete from target_source_link where link_id=?",sourceLink);
            case "other_target" -> {
                String other=id();
                jdbc.update("insert into target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,'BIRD','mock',?,?,?,?)",other,"QA-"+other,ORG,DISTRICT,ts(now),ts(now));
                jdbc.update("update target_source_link set target_id=? where link_id=?",other,sourceLink);
            }
            case "other_scope" -> replaceSourceObservation(now-1000,now-500,37.01,"seed-stage3-other-org");
            case "source_mismatch" -> jdbc.update("update track_point set position_source_id='rule-engine-space-risk-mock' where point_id=?",point);
            case "stale_source" -> replaceSourceObservation(now-200000,now-199000,37.01,ORG);
            case "pre_risk_source" -> replaceSourceObservation(now-700000,now-699000,37.01,ORG);
            case "raw_masks_conflict" -> {
                jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,created_at,point_kind,position_accuracy_m,position_source_id,observation_id) select ?,track_id,2,observed_at,received_at,ST_SetSRID(ST_MakePoint(118.05,37),4326),created_at,'MEAS',5,position_source_id,observation_id from track_point where point_id=?",id(),point);
                laterRawPoint();
            }
            case "raw_masks_accuracy" -> {
                jdbc.update("update track_point set position_accuracy_m=2000 where point_id=?",point);
                laterRawPoint();
            }
            case "raw_only" -> {
                laterRawPoint();
                jdbc.update("delete from track_point where point_id=?",point);
            }
        }
        presence.recordC04Clearances();
        assertThat(count()).as(condition).isZero();
        assertThat(readStatus()).as(condition).isEqualTo("UNKNOWN");
    }

    @Test void asynchronousFusedSourceCanBeOlderThanFrameAndRawDisagreementDoesNotReplaceFusion() throws Exception {
        // Production fusion retains the selected source observation, which can predate the composite frame.
        replaceSourceObservation(now-2000,now-1500,37.01,ORG);
        String rawPoint=laterRawPoint();
        jdbc.update("update track_point set location=ST_SetSRID(ST_MakePoint(118.05,37),4326) where point_id=?",rawPoint);
        presence.recordC04Clearances();
        assertThat(count()).isEqualTo(1);
        assertThat(readStatus()).isEqualTo("CLEARED");
        assertThat(jdbc.queryForObject("select point_id from risk_clearance_evidence where risk_id=?",String.class,risk)).isEqualTo(point);
    }

    String laterRawPoint() {
        String rawTrack=id(),rawPoint=id();
        jdbc.update("insert into track(track_id,target_id,link_id,external_track_id,layer,started_at,created_at) values(?,?,?,?,'RAW',?,?)",rawTrack,target,sourceLink,rawTrack,ts(now-600000),ts(now-600000));
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,created_at,point_kind,position_accuracy_m,observation_id) select ?,?,1,observed_at,?,location,?,'MEAS',5,observation_id from track_point where point_id=?",rawPoint,rawTrack,ts(now-100),ts(now-100),point);
        return rawPoint;
    }

    String insertSourceObservation(long observed,long received,double latitude) {
        return insertSourceObservation(observed,received,latitude,ORG);
    }
    String insertSourceObservation(long observed,long received,double latitude,String owner) {
        String observation=id(),sourceSession=id();sourceLink=id();
        // Observations are append-only. A separate source session also preserves the identity/time unique key.
        jdbc.update("insert into target_source_link(link_id,target_id,source_id,source_session_key,external_target_id,created_at) values(?,?,'seed-stage3-source',?,?,?)",sourceLink,target,sourceSession,target,ts(received));
        jdbc.update("insert into source_observation(observation_id,source_id,source_session_key,external_target_id,observed_at,received_at,location,position_accuracy_m,source_mode,owner_org_id,district_id,created_at) values(?,'seed-stage3-source',?,?,?,?,ST_SetSRID(ST_MakePoint(118.05,?),4326),5,'mock',?,?,?)",observation,sourceSession,target,ts(observed),ts(received),latitude,owner,DISTRICT,ts(received));
        return observation;
    }
    void replaceSourceObservation(long observed,long received,double latitude,String owner) {
        sourceObservation=insertSourceObservation(observed,received,latitude,owner);
        jdbc.update("update track_point set observation_id=? where point_id=?",sourceObservation,point);
    }
    void originalRisk(String id,long at) {
        jdbc.update("insert into flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,'rule-engine-space-risk-mock',?,?,?,?, 'SPACE_OBJECT','HIGH','NOTIFIED','SPACE_OBJECT_IN_CORRIDOR','QA C04 original risk',?,?,'UNKNOWN','mock',?,?,?, ?,0)",id,"QA-"+id,plan,routeVersion,target,ts(at),ts(at+1000),ORG,DISTRICT,ts(at),ts(at));
        jdbc.update("insert into space_risk_fact(risk_id,subtype_code,rule_version_id,rule_set_version_id,distance_to_route_m,corridor_relation,altitude_band,trend,unknown_reasons,target_location,window_from,window_to,created_at) values(?,'BIRD_FLOCK',?,'space-risk-demo-v1',0,'INSIDE','UNKNOWN','UNKNOWN',CAST('[]' AS JSON),ST_SetSRID(ST_MakePoint(118.05,37),4326),?,?,?)",id,factRule,ts(at-1000),ts(at),ts(at));
    }
    void position(double latitude) {
        long[] times=jdbc.queryForObject("select observed_at,received_at from track_point where point_id=?",(rs,n)->new long[]{rs.getTimestamp(1).getTime(),rs.getTimestamp(2).getTime()},point);
        replaceSourceObservation(times[0],times[1],latitude,ORG);
        jdbc.update("update track_point set location=ST_SetSRID(ST_MakePoint(118.05,?),4326) where point_id=?",latitude,point);
        jdbc.update("update target_latest_state set location=ST_SetSRID(ST_MakePoint(118.05,?),4326) where target_id=?",latitude,target);
    }
    void appendPosition(double latitude) {
        String previous=point;point=id();
        sourceObservation=insertSourceObservation(now-100,now-50,latitude);
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,created_at,point_kind,position_accuracy_m,position_source_id,observation_id) select ?,track_id,point_seq+1,?,?,ST_SetSRID(ST_MakePoint(118.05,?),4326),?,'MEAS',5,position_source_id,? from track_point where point_id=?",point,ts(now-100),ts(now-50),latitude,ts(now-50),sourceObservation,previous);
        jdbc.update("update target_latest_state set location=ST_SetSRID(ST_MakePoint(118.05,?),4326),observed_at=?,received_at=? where target_id=?",latitude,ts(now-100),ts(now-50),target);
    }
    void observationTime(long observed,long received) {
        Double latitude=jdbc.queryForObject("select ST_Y(location) from track_point where point_id=?",Double.class,point);
        replaceSourceObservation(observed,received,latitude,ORG);
        jdbc.update("update track_point set observed_at=?,received_at=? where point_id=?",ts(observed),ts(received),point);
        jdbc.update("update target_latest_state set observed_at=?,received_at=? where target_id=?",ts(observed),ts(received),target);
    }
    int count(){return jdbc.queryForObject("select count(*) from risk_clearance_evidence where risk_id=?",Integer.class,risk);}
    String readStatus() throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/risks/"+risk).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("current_status").asText();
    }
    static String id(){return UUID.randomUUID().toString();}
    static Timestamp ts(long value){return new Timestamp(value);}
}
