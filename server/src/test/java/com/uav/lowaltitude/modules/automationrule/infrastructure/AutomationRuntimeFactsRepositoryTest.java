package com.uav.lowaltitude.modules.automationrule.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.StateRow;
import com.uav.lowaltitude.platform.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AutomationRuntimeFactsRepositoryTest {
    private static final long NOW = 1_800_000_000_000L;
    private JdbcTemplate jdbc;
    private RuleEngineRepository states;
    private SpatialFactPort spatial;
    private AutomationRuntimeFactsRepository repository;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa",""));
        for (String ddl : List.of(
                "CREATE TABLE app_org(org_id varchar,enabled boolean)",
                "CREATE TABLE app_district(district_id varchar,enabled boolean)",
                "CREATE TABLE uav_event(event_id varchar,alarm_id varchar,owner_org_id varchar,district_id varchar,state_code varchar,updated_at timestamp with time zone)",
                "CREATE TABLE alarm(alarm_id varchar,target_id varchar,owner_org_id varchar,district_id varchar,source_mode varchar)",
                "CREATE TABLE target(target_id varchar,owner_org_id varchar,district_id varchar,source_mode varchar,object_type_code varchar,uav_sn varchar)",
                "CREATE TABLE target_latest_state(target_id varchar,observed_at timestamp with time zone)",
                "CREATE TABLE automation_runtime_state(event_id varchar,status varchar)",
                "CREATE TABLE target_source_link(link_id varchar,target_id varchar,source_id varchar,source_session_key varchar,external_target_id varchar)",
                "CREATE TABLE source_observation(observation_id varchar,source_id varchar,observed_at timestamp with time zone,identity_clue varchar,position_accuracy_m numeric,source_session_key varchar,external_target_id varchar,owner_org_id varchar,district_id varchar,source_mode varchar)",
                "CREATE TABLE integration_source(source_id varchar,enabled boolean,source_mode varchar)",
                "CREATE TABLE target_attribute_selection(target_id varchar,position_source_id varchar)",
                "CREATE TABLE track(track_id varchar,link_id varchar,target_id varchar,config_version varchar,layer varchar,ended_at timestamp with time zone,started_at timestamp with time zone)",
                "CREATE TABLE fusion_config(config_version varchar,params varchar)",
                "CREATE TABLE track_point(point_id varchar,point_seq bigint,point_kind varchar,observed_at timestamp with time zone,observation_id varchar,track_id varchar)",
                "CREATE TABLE rule_evaluation(evaluation_id varchar,target_id varchar,owner_org_id varchar,district_id varchar,source_mode varchar,mode varchar,subject_kind varchar,observed_at timestamp with time zone,evaluated_at timestamp with time zone,freshness_code varchar,grade varchar,legal_status varchar,alarm_id varchar)",
                "CREATE TABLE airspace(airspace_id varchar,owner_org_id varchar,district_id varchar)",
                "CREATE TABLE ops_device(device_id varchar,source_id varchar,enabled boolean,source_mode varchar)",
                "CREATE TABLE ops_device_state(device_id varchar,observed_at bigint,connectivity varchar,health_code varchar,has_alarm boolean)",
                "CREATE TABLE ops_integration_source(source_id varchar,enabled boolean,protocol_code varchar,source_mode varchar)",
                "CREATE TABLE device_business_scope(ops_device_id varchar,owner_org_id varchar,district_id varchar)",
                "CREATE TABLE disposal_authorization(authorization_id varchar,device_id varchar,channel varchar,subject_kind varchar,subject_id varchar,target_id varchar,owner_org_id varchar,district_id varchar,source_mode varchar,action_type varchar,status varchar,valid_from timestamp with time zone,valid_until timestamp with time zone)")) jdbc.execute(ddl);
        jdbc.update("INSERT INTO app_org VALUES('org',TRUE)");
        jdbc.update("INSERT INTO app_district VALUES('district',TRUE)");
        jdbc.update("INSERT INTO target VALUES('target','org','district','mock','UAV','SN')");
        jdbc.update("INSERT INTO alarm VALUES('alarm','target','org','district','mock')");
        jdbc.update("INSERT INTO uav_event VALUES('event','alarm','org','district','PENDING_VERIFICATION',?)",at(NOW));
        jdbc.update("INSERT INTO target_latest_state VALUES('target',?)",at(NOW));
        states=mock(RuleEngineRepository.class); spatial=mock(SpatialFactPort.class);
        when(states.latestState("target")).thenReturn(state(NOW));
        when(spatial.airspaceHits(any(),any())).thenReturn(List.of());
        repository=new AutomationRuntimeFactsRepository(jdbc,states,spatial,new ObjectMapper(),30_000);
    }

    @Test void tracesRealConfidenceAndLeavesAbsentAccuracyUnknown() {
        source("s1",NOW,"SN","org","mock");
        var f=repository.read("event",NOW);
        assertThat(f.facts().get("confidence").value()).isEqualTo("95.00");
        assertThat(f.facts().get("confidence").evidenceId()).contains("target_latest_state:target");
        assertThat(f.facts().get("sourceCount").value()).isEqualTo("1");
        assertThat(f.facts().get("consistency").value()).isNull();
        assertThat(f.facts().get("accuracy").value()).isNull();
        assertThat(f.facts().get("device").value()).isNull();
    }
    @Test void deduplicatesAndExcludesStaleForeignScopeAndOtherModeSources() {
        source("s1",NOW,"SN","org","mock");
        jdbc.update("INSERT INTO source_observation VALUES('old-s1','s1',?,'SN',NULL,'session','external','org','district','mock')",at(NOW-1_000));
        source("s2",NOW-31_000,"SN","org","mock");
        source("s3",NOW,"SN","other","mock");
        source("s4",NOW,"SN","org","live");
        var f=repository.read("event",NOW);
        assertThat(f.facts().get("sourceCount").value()).isEqualTo("1");
        assertThat(f.facts().get("consistency").value()).isNull();
    }
    @Test void independentIdentitySourcesCanAgreeOrConflict() {
        source("s1",NOW,"SN","org","mock"); source("s2",NOW,"SN","org","mock");
        assertThat(repository.read("event",NOW).facts().get("consistency").value()).isEqualTo("true");
        jdbc.update("UPDATE source_observation SET identity_clue='OTHER' WHERE source_id='s2'");
        assertThat(repository.read("event",NOW).facts().get("consistency").value()).isEqualTo("false");
    }
    @Test void nonUavAndScopeMismatchCannotProducePassingFacts() {
        jdbc.update("UPDATE target SET object_type_code='BIRD'");
        assertThat(repository.read("event",NOW).facts().values()).allMatch(f -> f.value()==null);
        jdbc.update("UPDATE target SET owner_org_id='other'");
        assertThat(repository.read("event",NOW)).isNull();
    }
    @Test void currentRiskMustMatchLatestObservationAndNeverUsesOldAlarmGrade() {
        risk("old",NOW-1_000,NOW-1_000,"HIGH","ILLEGAL");
        assertThat(repository.read("event",NOW).facts().get("riskLevel").value()).isNull();
        risk("current",NOW,NOW,"MEDIUM","ABNORMAL");
        assertThat(repository.read("event",NOW).facts().get("riskLevel").value()).isEqualTo("MEDIUM");
        jdbc.update("UPDATE rule_evaluation SET grade=NULL,legal_status='LEGAL' WHERE evaluation_id='current'");
        assertThat(repository.read("event",NOW).facts().get("riskActive").value()).isEqualTo("false");
        assertThat(repository.read("event",NOW).facts().get("riskLevel").value()).isNull();
    }
    @Test void h2WithoutSpatialSupportIsUnknown() {
        when(spatial.ambiguousEffectiveAirspaceVersion(any())).thenThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"SPATIAL_BACKEND_UNAVAILABLE","unavailable"));
        assertThat(repository.read("event",NOW).airspaceKnown()).isFalse();
        assertThat(repository.read("event",NOW).facts().get("position").value()).isNull();
    }
    @Test void latestEvaluationForAnotherAlarmCannotFallBackToOlderMatchingRisk() {
        risk("matching",NOW,NOW-1,"HIGH","ILLEGAL");
        risk("newest",NOW,NOW,"HIGH","ILLEGAL");
        jdbc.update("UPDATE rule_evaluation SET alarm_id='other-alarm' WHERE evaluation_id='newest'");
        var facts=repository.read("event",NOW).facts();
        assertThat(facts.get("riskLevel").value()).isNull();
        assertThat(facts.get("riskActive").value()).isNull();
        assertThat(facts.get("disposeFreshness").value()).isNull();
    }
    @Test void candidatesRevisitActiveRuntimeEvenWhenTargetIsNoLongerEligible() {
        jdbc.update("INSERT INTO automation_runtime_state VALUES('event','PASS')");
        jdbc.update("INSERT INTO automation_runtime_state VALUES('event','WAITING')");
        jdbc.update("UPDATE target SET object_type_code='BIRD'");
        jdbc.update("UPDATE uav_event SET state_code='FALSE_POSITIVE'");
        jdbc.update("UPDATE app_org SET enabled=FALSE");
        jdbc.update("UPDATE app_district SET enabled=FALSE");
        jdbc.update("UPDATE target_latest_state SET observed_at=?",at(NOW-86_400_000));
        assertThat(repository.candidates(NOW,10)).containsExactly("event");
        assertThat(repository.read("event",NOW)).isNull();
        jdbc.update("UPDATE automation_runtime_state SET status='REVIEW'");
        assertThat(repository.candidates(NOW,10)).isEmpty();
    }
    @Test void candidatesExcludeStaleUntrackedEventsAndPageAllActiveStateIdsFairly() {
        jdbc.update("UPDATE target_latest_state SET observed_at=?",at(NOW-31_000));
        assertThat(repository.candidates(NOW,10)).isEmpty();
        jdbc.update("INSERT INTO automation_runtime_state VALUES('a','WAITING'),('event','PASS'),('z','WAITING')");
        assertThat(repository.candidates(NOW,1)).containsExactly("a");
        assertThat(repository.candidates(NOW,1,"a")).containsExactly("event");
        assertThat(repository.candidates(NOW,1,"event")).containsExactly("z");
        assertThat(repository.candidates(NOW,1,"z")).isEmpty();
    }
    @Test void staleOrFutureStateNeverMakesCurrentRisk() {
        when(states.latestState("target")).thenReturn(state(NOW-31_000));
        assertThat(repository.read("event",NOW).facts().get("riskActive").value()).isNull();
        when(states.latestState("target")).thenReturn(state(NOW+1));
        assertThat(repository.read("event",NOW).facts().get("freshness").value()).isNull();
    }
    @Test void trackDoesNotBridgePredictionAndCandidateSupportsCursor() {
        source("s1",NOW,"SN","org","mock");
        jdbc.update("INSERT INTO fusion_config VALUES('c','{\"filter\":{\"max_dt_ms\":3000}}')");
        jdbc.update("INSERT INTO track VALUES('track','link-s1','target','c','RAW',NULL,?)",at(NOW-10_000));
        for(int i=0;i<4;i++) {
            long time=NOW-i*1_000;
            if(i>0) jdbc.update("INSERT INTO source_observation VALUES(?, 's1',?,'SN',NULL,'session','external','org','district','mock')","o"+i,at(time));
            jdbc.update("INSERT INTO track_point VALUES(?,?,?,?,?,'track')","p"+i,4-i,i==2?"PRED":"MEAS",at(time),i==0?"obs-s1":"o"+i);
        }
        assertThat(repository.read("event",NOW).facts().get("trackDuration").value()).isEqualTo("1.000");
        assertThat(repository.candidates(NOW,10)).containsExactly("event");
        assertThat(repository.candidates(NOW,10,"event")).isEmpty();
    }
    @Test void authorizedDeviceHealthAndConflictsAreCurrentButReceiptStillUnknown() {
        jdbc.update("INSERT INTO disposal_authorization VALUES('a','dev','COUNTERMEASURE_4CH','UAV_EVENT','event','target','org','district','mock','COUNTERMEASURE','APPROVED',?,?)",at(NOW-1_000),at(NOW+10_000));
        jdbc.update("INSERT INTO ops_device VALUES('dev','src',TRUE,'mock')");
        jdbc.update("INSERT INTO ops_device_state VALUES('dev',?,'ONLINE','GOOD',FALSE)",NOW);
        jdbc.update("INSERT INTO ops_integration_source VALUES('src',TRUE,'COUNTERMEASURE_TCP_4CH_V2_0','mock')");
        jdbc.update("INSERT INTO device_business_scope VALUES('dev','org','district')");
        var f=repository.read("event",NOW);
        assertThat(f.facts().get("device").value()).isEqualTo("true");
        assertThat(f.facts().get("conflictTask").value()).isEqualTo("true");
        assertThat(f.facts().get("receipt").value()).isNull();
        jdbc.update("INSERT INTO disposal_authorization VALUES('other','dev','COUNTERMEASURE_4CH','UAV_EVENT','other-event','other-target','org','district','mock','COUNTERMEASURE','EXECUTING',?,?)",at(NOW-1_000),at(NOW+10_000));
        assertThat(repository.read("event",NOW).facts().get("conflictTask").value()).isEqualTo("false");
        jdbc.update("UPDATE ops_device_state SET observed_at=?",NOW-31_000);
        assertThat(repository.read("event",NOW).facts().get("device").value()).isNull();
    }
    private void source(String id,long observed,String identity,String org,String mode) {
        jdbc.update("INSERT INTO integration_source VALUES(?,TRUE,?)",id,mode);
        jdbc.update("INSERT INTO target_source_link VALUES(?,'target',?,'session','external')","link-"+id,id);
        jdbc.update("INSERT INTO source_observation VALUES(?,?,?, ?,NULL,'session','external',?,'district',?)","obs-"+id,id,at(observed),identity,org,mode);
    }
    private void risk(String id,long observed,long evaluated,String grade,String legal) {
        jdbc.update("INSERT INTO rule_evaluation VALUES(?,'target','org','district','mock','ACTIVE','TARGET',?,?,'FRESH',?,?,'alarm')",id,at(observed),at(evaluated),grade,legal);
    }
    private static StateRow state(long observed) {
        return new StateRow(BigDecimal.valueOf(118),BigDecimal.valueOf(37),BigDecimal.valueOf(100),BigDecimal.valueOf(50),null,null,
                new BigDecimal("0.95"),null,at(observed),at(observed),at(observed),null,null,null);
    }
    private static OffsetDateTime at(long time) { return Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC); }
}
