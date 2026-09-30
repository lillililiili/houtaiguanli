package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeWorker;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** R03 technical identity comparison only. R01 business authorization is deliberately not asserted. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
class AutomationIdentityComparisonPostgresTest extends AutomationMqttFixture {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.automation-rules.enabled",()->true);
        registry.add("app.automation-rules.fact-max-age-ms",()->300000);
        registry.add("app.mqtt.enabled",()->true);
        registry.add("app.outbox.enabled",()->true);
        registry.add("app.lingyun-control.command-timeout-millis",()->120000);
    }
    @AfterAll static void closeDatabase(){DATABASE.close();}
    @Autowired AutomationRuntimeWorker automatic;
    @Autowired AutomationRuntimeRepository runtime;

    @Test void r03SystemAndHumanKeepDistinctRequestersNoApproversAndExplicitHumanDirectPermission() throws Exception {
        prepareAutomaticRules();
        replayEvidence();
        String automaticEvent=eventId;
        automatic.poll();
        assertThat(runtime.state("counter",eventId).status()).isEqualTo("PASS");
        String automaticId=jdbc.queryForObject("select authorization_id from disposal_authorization where subject_id=? and requested_by=?",String.class,eventId,AutomationPrincipal.USER_ID);
        awaitWire(1);
        assertThat(frames.get(0).path("data").path("operationCmd").asInt()).isEqualTo(60003);
        var systemIdentity=identity(automaticId,AutomationPrincipal.USER_ID);
        assertThat(jdbc.queryForObject("select status from app_user where user_id=?",String.class,AutomationPrincipal.USER_ID)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("select count(*) from app_role_permission where role_code=? and permission_code='disposal:direct' and permission_level='OP'",Integer.class,AutomationPrincipal.ROLE)).isZero();
        // A local protocol refusal terminates this command, freeing the isolated device for the second batch.
        reply(commandOf(automaticId),1);
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(statusOf(automaticId)).isEqualTo("FAILED"));
        assertThat(children(automaticId)).isEmpty();

        eventId=event();replayEvidence();
        String humanEvent=eventId;
        assertThat(humanEvent).isNotEqualTo(automaticEvent);
        String human=user("disposal:read","devices","target:read");
        String humanId=jdbc.queryForObject("select user_id from app_session where session_id=?",String.class,human);
        String role=jdbc.queryForObject("select role_code from app_user where user_id=?",String.class,humanId);
        request("/api/v1/disposal-authorizations/direct-execute",human,key(),body("R03 no direct must reject")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?",Integer.class,humanEvent)).isZero();
        assertThat(frames).hasSize(1);
        var denialAudit=jdbc.queryForList("select user_id,account,role_code,action,object_id,result,detail from audit_log where user_id=? and result<>'SUCCESS' order by occurred_at,audit_id",humanId);
        assertThat(denialAudit).isNotEmpty();
        // Explicit OP grant in this disposable QA role; the same person's API call now has the required permission.
        jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,'disposal:direct','OP',false,current_timestamp)",role);
        String humanAuthorization=data(request("/api/v1/disposal-authorizations/direct-execute",human,key(),body("R03 explicit human direct"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        awaitWire(2);
        assertThat(frames.get(1).path("data").path("operationCmd").asInt()).isEqualTo(60003);
        var humanIdentity=identity(humanAuthorization,humanId);
        assertThat(humanId).isNotEqualTo(AutomationPrincipal.USER_ID);
        var out=new LinkedHashMap<String,Object>();
        out.put("scope","R03 技术身份对照；合成业务事实、实际自动规则、人工认证API及本机MQTT；无真实设备动作");
        out.put("authorization_source_boundary","系统审计主体非已确认业务授权；R01待确认");
        out.put("automatic_event_id",automaticEvent);out.put("human_event_id",humanEvent);
        out.put("automatic",systemIdentity);out.put("human",humanIdentity);out.put("human_without_direct_denial_audit",denialAudit);
        out.put("automatic_runtime",runtime.run(runtime.state("counter",automaticEvent).runId()));
        out.put("wire_frames",frames);
        Path output=Path.of("target","disposal-mqtt-evidence","postgresql","r03-identity-comparison.json");
        Files.createDirectories(output.getParent());Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }

    private Map<String,Object> identity(String authorization,String requester) {
        var row=jdbc.queryForMap("select authorization_id,subject_id,authorization_mode,requested_by,approved_by from disposal_authorization where authorization_id=?",authorization);
        assertThat(row.get("requested_by")).isEqualTo(requester);assertThat(row.get("approved_by")).isNull();
        assertThat(row.get("authorization_mode")).isEqualTo("DIRECT");
        var events=jdbc.queryForList("select event_kind,actor_id,occurred_at from disposal_authorization_event where authorization_id=? order by occurred_at,event_id",authorization);
        assertThat(events.stream().map(e->e.get("event_kind")).toList()).contains("DIRECT_AUTHORIZE","EXECUTE").doesNotContain("APPROVE");
        assertThat(events.stream().filter(e->List.of("DIRECT_AUTHORIZE","EXECUTE").contains(e.get("event_kind"))).toList()).allSatisfy(e->assertThat(e.get("actor_id")).isEqualTo(requester));
        var audit=jdbc.queryForList("select user_id,account,role_code,action,object_id,result,detail from audit_log where object_id=? order by occurred_at,audit_id",authorization);
        assertThat(audit.stream().map(e->e.get("action")).toList()).contains("disposal_direct_authorized","disposal_executed");
        assertThat(audit).allSatisfy(e->{assertThat(e.get("user_id")).isEqualTo(requester);assertThat(e.get("result")).isEqualTo("SUCCESS");});
        return Map.of("authorization",row,"approved_by_is_null",true,"authorization_events",events,"audit_log",audit);
    }

    private void prepareAutomaticRules() {
        if(jdbc.queryForObject("select count(*) from app_role where role_code=?",Integer.class,AutomationPrincipal.ROLE)==0)
            jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,'自动规则隔离测试','',false,true,0,0,0,false)",AutomationPrincipal.ROLE);
        if(jdbc.queryForObject("select count(*) from app_user where user_id=?",Integer.class,AutomationPrincipal.USER_ID)==0)
            jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,?,?,?,'DISABLED','unused',0,'ALL',0,0,0,0)",AutomationPrincipal.USER_ID,AutomationPrincipal.ACCOUNT,"自动规则隔离测试",AutomationPrincipal.ROLE);
        jdbc.update("update automation_rule_condition set enabled=false");
        jdbc.update("update automation_rule_group set version=831,scope_mode='ALL',schedule_mode='ALL_DAY',wait_seconds=0 where category='counter'");
        jdbc.update("insert into automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) values(?,'counter','身份对照时效','counterFreshness','300',0,true,?,?,'qa-auto') on conflict(category,item_code) do update set enabled=true,value_text='300',hold_seconds=0",key(),clock.nowMillis(),clock.nowMillis());
        jdbc.update("update ops_device set enabled=false where device_type_code='ifr' and device_id<>?",binding.opsDeviceId());
    }

    private void replayEvidence() {
        String alarm=jdbc.queryForObject("select alarm_id from uav_event where event_id=?",String.class,eventId);
        String target=jdbc.queryForObject("select target_id from alarm where alarm_id=?",String.class,alarm);
        jdbc.update("update alarm set source_mode='replay' where alarm_id=?",alarm);
        jdbc.update("update target set source_mode='replay' where target_id=?",target);
        String previous=jdbc.queryForObject("select evaluation_id from rule_evaluation where target_id=? order by evaluated_at desc,evaluation_id desc fetch first 1 rows only",String.class,target);
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons,supersedes_evaluation_id) "
                +"select ?,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,?,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,'replay',created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons,evaluation_id from rule_evaluation where evaluation_id=?",key(),Timestamp.from(clock.now()),previous);
        assertThat(advisory.counterBlockReason(eventId)).isEmpty();
    }
}
