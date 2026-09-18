package com.uav.lowaltitude.modules.automationrule.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;

/**
 * Run after configuration-count tests, only in the explicitly disposable verification database.
 * All fixture IDs are unique and records are mock. Append-only observations/evaluations intentionally
 * remain in that disposable database; no trigger bypass or DELETE of frozen facts is permitted.
 * This class never calls an action service, scheduler or device command API.
 */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/automation_rule_verify_[a-z0-9_]+")
@SpringBootTest(properties={"app.flight.status-advance.enabled=false","app.automation-rules.enabled=false",
        "app.rule-engine.enabled=false","app.mqtt.enabled=false","app.live-device.enabled=false",
        "app.eo-edge.auto-track.enabled=false","app.automation-rules.fact-max-age-ms=30000",
        "app.disposal.expiry.enabled=false","app.disposal.receipt-sync.enabled=false",
        "app.advisory.auto-sms.enabled=false","app.advisory.auto-voice.enabled=false"})
@ActiveProfiles("test")
class AutomationRuntimeFactsPostgresTest {
    // Explicit fixture timeline, not the host clock; historical mock data cannot become live inputs.
    private static final long NOW=1_600_000_000_000L;
    @Autowired JdbcTemplate jdbc;
    @Autowired AutomationRuntimeFactsRepository facts;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean RuleEngineRepository states;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/automation_rule_verify_[a-z0-9_]+"))
            throw new IllegalArgumentException("仅允许隔离规则测试库");
        r.add("spring.datasource.url",()->url);
        r.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));
        r.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
    }

    @Test void committedFactsUseRealPostgisCurrentEvaluationAndAuthorizedDeviceSqlInReadOnlySnapshot() {
        assertThat(AopUtils.isAopProxy(facts)).isTrue();
        assertThat(jdbc.queryForObject("SELECT PostGIS_Version()",String.class)).isNotBlank();
        Fixture f=new TransactionTemplate(transactions).execute(status->fixture());
        assertThat(f).isNotNull();
        var snapshotChecked=new AtomicBoolean();
        doAnswer(call->{
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(jdbc.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            snapshotChecked.set(true);
            return call.callRealMethod();
        }).when(states).latestState(anyString());
        try {
            var current=facts.read(f.event,NOW);
            assertThat(snapshotChecked).isTrue();
            assertThat(current).isNotNull();
            assertThat(current.sourceMode()).isEqualTo("mock");
            assertThat(current.airspaceKnown()).isTrue();
            assertThat(current.airspaceIds()).contains(f.airspace);
            assertThat(current.facts().get("position").value()).isEqualTo("true");
            assertThat(current.facts().get("sourceCount").value()).isEqualTo("2");
            assertThat(current.facts().get("consistency").value()).isEqualTo("true");
            assertThat(current.facts().get("confidence").value()).isEqualTo("95.00000");
            assertThat(current.facts().get("riskLevel").value()).isEqualTo("HIGH");
            assertThat(current.facts().get("riskActive").value()).isEqualTo("true");
            assertThat(current.facts().get("device").value()).isEqualTo("true");
            assertThat(current.facts().get("conflictTask").value()).isEqualTo("true");
            assertThat(current.facts().get("receipt").value()).isNull();
            assertThat(current.facts().get("riskLevel").evidenceId()).isEqualTo("rule_evaluation:"+f.evaluation);
            assertThat(facts.candidates(NOW,1000)).contains(f.event);

            // Append a newer current assessment attached to another alarm. Do not mutate history.
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                String otherAlarm=uuid();
                alarm(otherAlarm,f.target,f.source,f.org,f.district);
                evaluation(uuid(),f.run,f.ruleVersion,f.target,otherAlarm,f.org,f.district,NOW+1);
            });
            var changed=facts.read(f.event,NOW+1);
            assertThat(changed.facts().get("riskLevel").value()).isNull();
            assertThat(changed.facts().get("riskActive").value()).isNull();

            // Only this test's mutable rows are changed; all changes below are restored in finally.
            jdbc.update("INSERT INTO automation_runtime_state(category,event_id,group_version,status,holds_json,signature) VALUES('verify',?,0,'PASS','{}','facts-pg')",f.event);
            jdbc.update("UPDATE target SET object_type_code='BIRD' WHERE target_id=?",f.target);
            jdbc.update("UPDATE uav_event SET state_code='FALSE_POSITIVE' WHERE event_id=?",f.event);
            jdbc.update("UPDATE app_org SET enabled=FALSE WHERE org_id=?",f.org);
            assertThat(facts.candidates(NOW+86_400_000L,1000)).contains(f.event);
            assertThat(facts.read(f.event,NOW+86_400_000L)).isNull();
            jdbc.update("UPDATE automation_runtime_state SET status='REVIEW' WHERE category='verify' AND event_id=?",f.event);
            assertThat(facts.candidates(NOW+86_400_000L,1000)).doesNotContain(f.event);
        } finally {
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.update("DELETE FROM automation_runtime_state WHERE category='verify' AND event_id=?",f.event);
                jdbc.update("UPDATE target SET object_type_code='UAV' WHERE target_id=?",f.target);
                jdbc.update("UPDATE uav_event SET state_code='PENDING_VERIFICATION' WHERE event_id=?",f.event);
                jdbc.update("UPDATE app_org SET enabled=TRUE WHERE org_id=?",f.org);
            });
        }
    }

    private Fixture fixture() {
        String org=uuid(),district=uuid(),target=uuid(),alarm=uuid(),event=uuid(),airspace=uuid();
        String source=uuid(),otherSource=uuid(),run=uuid(),set=uuid(),version=uuid(),evaluation=uuid();
        OffsetDateTime at=at(NOW);
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at) VALUES(?,?, 'FACTS-PG mock org',TRUE,?,?)",org,"FACTS-PG-"+org,NOW,NOW);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at) VALUES(?,?,'FACTS-PG mock district',TRUE,?,?)",district,"FACTS-PG-"+district,NOW,NOW);
        source(source);source(otherSource);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,uav_sn,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,'UAV','FACTS-PG-SN','mock',?,?,?,?)",target,"FACTS-PG-"+target,org,district,at,at);
        jdbc.update("INSERT INTO target_latest_state(target_id,location,altitude_amsl_m,height_agl_m,classification_confidence,observed_at,received_at,created_at,updated_at) VALUES(?,ST_SetSRID(ST_MakePoint(118,37),4326),100,50,0.95,?,?,?,?)",target,at,at,at,at);
        observation(source,target,org,district);observation(otherSource,target,org,district);
        alarm(alarm,target,source,org,district);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,'PENDING_VERIFICATION',?,?,?,?)",event,alarm,org,district,at,at);
        jdbc.update("INSERT INTO airspace(airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,'FACTS-PG spatial mock','mock',?,?,?,?)",airspace,"FACTS-PG-"+airspace,org,district,at,at);
        jdbc.update("INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,created_at) VALUES(?,?,1,'RESTRICTED',ST_Multi(ST_GeomFromText('POLYGON((117.9 36.9,118.1 36.9,118.1 37.1,117.9 37.1,117.9 36.9))',4326)),0,200,'AGL',?,?,?)",uuid(),airspace,at(NOW-1000),at(NOW+60000),at);
        jdbc.update("INSERT INTO rule_set(rule_set_id,rule_set_code,name,created_at,updated_at) VALUES(?,?,'FACTS-PG fixture',?,?)",set,"FACTS-PG-"+set,at,at);
        jdbc.update("INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,source_mode,created_at) VALUES(?,?,1,'DRAFT','DEMO',?,'mock',?)",version,set,at,at);
        jdbc.update("INSERT INTO rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,finished_at,status,source_mode,created_at) VALUES(?,?,?,'ACTIVE','MANUAL',?,?,?,'DONE','mock',?)",run,set,version,at,at,at,at);
        evaluation(evaluation,run,version,target,alarm,org,district,NOW);
        deviceAuthorization(event,target,org,district);
        return new Fixture(event,target,alarm,source,org,district,airspace,run,version,evaluation);
    }
    private void source(String id) {
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at) VALUES(?,?,'FACTS-PG mock source',TRUE,'mock',?,?)",id,"FACTS-PG-"+id,at(NOW),at(NOW));
    }
    private void observation(String source,String target,String org,String district) {
        jdbc.update("INSERT INTO target_source_link(link_id,target_id,source_id,source_session_key,external_target_id,created_at) VALUES(?,?,?,'FACTS-PG','external',?)",uuid(),target,source,at(NOW));
        jdbc.update("INSERT INTO source_observation(observation_id,source_id,source_session_key,external_target_id,observed_at,received_at,location,position_accuracy_m,class_code,identity_clue,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,'FACTS-PG','external',?,?,ST_SetSRID(ST_MakePoint(118,37),4326),5,'UAV','FACTS-PG-SN','mock',?,?,?)",uuid(),source,at(NOW),at(NOW),org,district,at(NOW));
    }
    private void alarm(String id,String target,String source,String org,String district) {
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",id,target,source,"FACTS-PG-"+id,at(NOW),at(NOW),org,district,at(NOW));
    }
    private void evaluation(String id,String run,String version,String target,String alarm,String org,String district,long evaluated) {
        jdbc.update("""
                INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,
                  freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,
                  alarm_id,owner_org_id,district_id,source_mode,created_at)
                VALUES(?,?,?,'ACTIVE','TARGET',?,?,?,?,'FRESH','NONE','ILLEGAL',90,'HIGH',
                  CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,'mock',?)
                """,id,run,version,target,at(NOW),at(evaluated),at(evaluated),alarm,org,district,at(evaluated));
    }
    private void deviceAuthorization(String event,String target,String org,String district) {
        String source=uuid(),device=uuid(),authorization=uuid();
        String actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1' AND status='ACTIVE'",String.class);
        assertThat(actor).as("active isolated-test admin seed user id").isNotBlank();
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,created_at,updated_at) VALUES(?,?,'FACTS-PG mock execution source','COUNTERMEASURE_TCP_4CH_V2_0','mock',TRUE,?,?)",source,"FACTS-PG-"+source,NOW,NOW);
        jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_name,channel,enabled,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,?, 'FACTS-PG mock execution device','TEST','TEST',TRUE,'mock',TRUE,?,?)",device,source,device,"FACTS-PG-"+device,NOW,NOW);
        jdbc.update("INSERT INTO ops_device_state(device_id,connectivity,health_code,has_alarm,observed_at,received_at,simulated) VALUES(?,'ONLINE','GOOD',FALSE,?,?,TRUE)",device,NOW,NOW);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?)",device,org,district,at(NOW),at(NOW));
        jdbc.update("""
                INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,target_id,device_id,
                  channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,policy_version,
                  owner_org_id,district_id,source_mode,created_at,updated_at)
                VALUES(?,?,'COUNTERMEASURE','UAV_EVENT',?,?,?,'COUNTERMEASURE_4CH','FACTS-PG read-only fixture',
                  ?,?,?,?, ?,?,'APPROVED','demo-v1',?,?,'mock',?,?)
                """,authorization,"FACTS-PG-"+authorization.substring(0,20),event,target,device,actor,at(NOW-1000),actor,at(NOW-1000),at(NOW-1000),at(NOW+10000),org,district,at(NOW),at(NOW));
    }
    private static String uuid(){return UUID.randomUUID().toString();}
    private static OffsetDateTime at(long time){return Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC);}
    private record Fixture(String event,String target,String alarm,String source,String org,String district,String airspace,String run,String ruleVersion,String evaluation) { }
}
