package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganizationDeletionApiTest {
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    protected String org, platform;

    @BeforeEach void fixture() throws Exception {
        org = UUID.randomUUID().toString();
        insertOrg(org, null);
        platform = login("admin1", "changeme", "BACKEND");
    }

    @Test void deletesUnusedOrganizationWithoutBodyAndPreservesAuditedProfile() throws Exception {
        jdbc.update("INSERT INTO organization_profile(org_id,organization_type,address,remarks) VALUES(?,'OTHER','测试地址','原始基本资料')", org);
        String actor = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        jdbc.update("""
                INSERT INTO app_org_delegation_preview(token,org_id,actor_id,request_hash,snapshot_hash,expires_at,consumed)
                VALUES(?,?,?,'request-hash','snapshot-hash',0,true)
                """, UUID.randomUUID().toString(), org, actor);
        String key = UUID.randomUUID().toString();
        remove(platform, org, 0, key).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(countOrg(org)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_profile WHERE org_id=?", Integer.class, org)).isEqualTo(1);
        var retired = jdbc.queryForMap("SELECT * FROM app_org WHERE org_id=?", org);
        assertThat(retired.get("deleted_by")).isEqualTo(actor);
        assertThat(retired.get("deleted_at")).isNotNull();
        assertThat(retired.get("enabled")).isEqualTo(false);
        assertThat(((Number) retired.get("version")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_org_delegation_preview WHERE org_id=?", Integer.class, org)).isZero();
        String detail = jdbc.queryForObject("SELECT detail FROM audit_log WHERE object_id=? AND action='organization_deleted'", String.class, org);
        assertThat(json.readTree(detail).path("profile").path("remarks").asText()).isEqualTo("原始基本资料");
        assertThat(json.readTree(detail).path("invalidated_preview_count").asInt()).isEqualTo(1);
        assertThat(json.readTree(detail).path("deletion_type").asText()).isEqualTo("LOGICAL_RETIREMENT");
        remove(platform, org, 0, key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_DELETED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='organization_deleted'", Integer.class, org)).isEqualTo(1);
    }

    @Test void staleVersionAndMissingOrganizationAreRejectedWithoutDeletion() throws Exception {
        jdbc.update("UPDATE app_org SET version=2 WHERE org_id=?", org);
        remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(countOrg(org)).isEqualTo(1);
        remove(platform, UUID.randomUUID().toString(), 0).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORG_NOT_FOUND"));
        remove(platform, org, -1).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='organization_deleted'", Integer.class, org)).isZero();
    }

    @Test void childOrganizationBlocksItsParentButAnUnrelatedEmptyLeafCanBeDeleted() throws Exception {
        String child = UUID.randomUUID().toString();
        insertOrg(child, org);
        remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(containsString("下级单位")));
        remove(platform, child, 0).andExpect(status().isOk());
        assertThat(countOrg(org)).isEqualTo(1);
        remove(platform, org, 0).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings={"ACTIVE", "DISABLED", "DELETED"})
    void activeAndDisabledUsersBlockButDeletedUsersOnlyRetainHistory(String userStatus) throws Exception {
        String user = addUser(org, "ROLE-BACKEND");
        if (userStatus.equals("DELETED")) {
            jdbc.update("UPDATE app_user SET status='DELETED',role_code=NULL,deleted_role_code='ROLE-BACKEND',deleted_at=1 WHERE user_id=?", user);
        } else jdbc.update("UPDATE app_user SET status=? WHERE user_id=?", userStatus, user);
        if (userStatus.equals("DELETED")) {
            remove(platform, org, 0).andExpect(status().isOk());
            assertThat(countOrg(org)).isZero();
            assertThat(jdbc.queryForObject("SELECT org_id FROM app_user WHERE user_id=?", String.class, user)).isEqualTo(org);
        } else {
            remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(containsString("人员")));
            assertThat(countOrg(org)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id=?", String.class, user)).isEqualTo(userStatus);
    }

    @Test void unassignedRolesAndRevokedDelegationAreRetainedButDisabled() throws Exception {
        String role = "ROLE-" + UUID.randomUUID().toString().substring(0, 18);
        jdbc.update("""
                INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,owner_org_id,name_scope)
                VALUES(?,?,'',false,true,0,0,0,?,?)
                """, role, role, org, org);
        jdbc.update("INSERT INTO app_org_delegation(org_id,administrator_user_id,version,created_at,updated_at) VALUES(?,NULL,1,0,0)", org);
        remove(platform, org, 0).andExpect(status().isOk());
        assertThat(countOrg(org)).isZero();
        assertThat(jdbc.queryForObject("SELECT enabled FROM app_role WHERE role_code=?", Boolean.class, role)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_org_delegation WHERE org_id=?", Integer.class, org)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"CLEARED","ACTIVE","UNKNOWN","CANDIDATE","ENTERED"})
    void predictionHistoryDoesNotBlockButUnresolvedRiskDoes(String state) throws Exception {
        String target = jdbc.queryForObject("SELECT target_id FROM target ORDER BY target_id LIMIT 1", String.class);
        String episode = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO prediction_episode(episode_id,target_id,airspace_id,source_mode,owner_org_id,district_id,status,
                    valid_until,reason,started_at,closed_at,stability_json,warning_json)
                VALUES(?,?,?,'replay',?,?,?,1,'隔离测试',0,1,'{}','{}')
                """, episode, target, UUID.randomUUID().toString(), org, UUID.randomUUID().toString(), state);
        if (state.equals("CLEARED")) remove(platform, org, 0).andExpect(status().isOk());
        else remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM prediction_episode WHERE episode_id=?", String.class, episode)).isEqualTo(org);
        assertThat(countOrg(org)).isEqualTo(state.equals("CLEARED") ? 0 : 1);
    }

    @Test void personnelDataScopeAndContactsAreNotCascaded() throws Exception {
        String actor = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        String district = jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id LIMIT 1", String.class);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)", actor, org, district);
        remove(platform, org, 0).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_data_scope WHERE org_id=?", Integer.class, org)).isEqualTo(1);
        String contactOrg = UUID.randomUUID().toString();
        insertOrg(contactOrg, null);
        jdbc.update("INSERT INTO business_contact(contact_id,org_id,name,roles,enabled,created_at,updated_at,version) VALUES(?,?,'联系人','[]',false,0,0,0)",
                UUID.randomUUID().toString(), contactOrg);
        remove(platform, contactOrg, 0).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_contact WHERE org_id=?", Integer.class, contactOrg)).isEqualTo(1);
    }

    @Test void idleDeviceAndItsOwnershipAreRetainedAfterDeletion() throws Exception {
        String device = addDevice(org);
        remove(platform, org, 0).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM device_business_scope WHERE ops_device_id=?", String.class, device)).isEqualTo(org);
        assertThat(countOrg(org)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"QUEUED","SENT","ACCEPTED","SUCCEEDED","CANCELLED"})
    void onlyInFlightDeviceCommandsBlockRetirement(String state) throws Exception {
        String device = addDevice(org);
        String command = addCommand(device, state, "REBOOT");
        if (java.util.Set.of("QUEUED","SENT","ACCEPTED").contains(state)) {
            remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(containsString("在途设备指令")));
        } else remove(platform, org, 0).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, command)).isEqualTo(state);
    }

    @ParameterizedTest @ValueSource(strings={"PENDING","APPROVED","EXECUTING","COMPLETED","CANCELLED"})
    void anElapsedWindowDoesNotCloseAFlightTask(String state) throws Exception {
        String plan = addPlan(org, state);
        if (java.util.Set.of("COMPLETED","CANCELLED").contains(state)) remove(platform,org,0).andExpect(status().isOk());
        else remove(platform,org,0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.message").value(containsString("飞行任务")));
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM flight_plan WHERE plan_id=?",String.class,plan)).isEqualTo(org);
    }

    @ParameterizedTest @ValueSource(strings={"PENDING_VERIFICATION","PENDING_NOTIFICATION","NOTIFIED","ACKNOWLEDGED","EXCLUDED"})
    void riskSubmittedNotificationStillWaitsForItsReceipt(String state) throws Exception {
        String plan=addPlan(org,"COMPLETED"), risk=UUID.randomUUID().toString();
        String source=jdbc.queryForObject("SELECT source_id FROM integration_source ORDER BY source_id LIMIT 1",String.class);
        jdbc.update("""
            INSERT INTO flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,
                received_at,source_mode,owner_org_id,district_id,created_at,updated_at)
            SELECT ?,?,?,p.plan_id,p.route_version_id,'BIRD_FLOCK','LOW',?,'TEST','隔离风险',CURRENT_TIMESTAMP,'replay',p.owner_org_id,p.district_id,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            FROM flight_plan p WHERE p.plan_id=?
            """,risk,source,risk,state,plan);
        if (java.util.Set.of("ACKNOWLEDGED","EXCLUDED").contains(state)) remove(platform,org,0).andExpect(status().isOk());
        else remove(platform,org,0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.message").value(containsString("飞行风险")));
        assertThat(jdbc.queryForObject("SELECT state_code FROM flight_risk WHERE risk_id=?",String.class,risk)).isEqualTo(state);
    }

    @Test void operatorOrganizationAlsoProtectsAnUnfinishedPlan() throws Exception {
        String owner=UUID.randomUUID().toString(); insertOrg(owner,null);
        String plan=addPlan(owner,"EXECUTING");
        jdbc.update("UPDATE flight_plan SET operator_org_id=? WHERE plan_id=?",org,plan);
        remove(platform,org,0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.message").value(containsString("飞行任务")));
        jdbc.update("UPDATE flight_plan SET status_code='COMPLETED' WHERE plan_id=?",plan);
        remove(platform,org,0).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT operator_org_id FROM flight_plan WHERE plan_id=?",String.class,plan)).isEqualTo(org);
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void oneAirspaceDepartureCannotCloseAnotherRiskOrChangeOrganizationScope(String mode) throws Exception {
        DeletionRisk event = addDeletionRiskEvent(org, mode);
        String first = addDeletionCase(event), second = addDeletionCase(event);
        addDeparture(event, first);
        remove(platform,org,0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(containsString("尚未结束的告警处置")));

        // Another unit's unfinished event is neither closed nor counted against this unit.
        String otherOrg=UUID.randomUUID().toString(); insertOrg(otherOrg,null);
        DeletionRisk other=addDeletionRiskEvent(otherOrg,mode); addDeletionCase(other);
        addDeparture(event, second);
        remove(platform,org,0).andExpect(status().isOk());
        assertThat(countOrg(otherOrg)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case WHERE risk_case_id IN (?,?)",Integer.class,first,second)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_auto_voice_task WHERE event_id=?",Integer.class,event.eventId())).isEqualTo(2);
    }

    @Test void sharedLegacyChannelClaimDoesNotGiveADepartureSnapshotAnAirspaceIdentity() throws Exception {
        DeletionRisk event=addDeletionRiskEvent(org,"replay");
        String first=addDeletionCase(event), second=addDeletionCase(event);
        addDeparture(event, "");
        for(String risk:java.util.List.of(first,second))
            jdbc.update("INSERT INTO uav_risk_channel(risk_case_id,channel,event_id,task_scope,claimed_at) VALUES(?,'VOICE',?,'',?)",risk,event.eventId(),event.createdAt());
        remove(platform,org,0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(containsString("尚未结束的告警处置")));
        addDeparture(event,first);
        remove(platform,org,0).andExpect(status().isConflict());
        addDeparture(event,second);
        remove(platform,org,0).andExpect(status().isOk());
    }

    @Test void eventsWithoutRiskCasesRetainTheirHistoricalDepartureBehavior() throws Exception {
        DeletionRisk event=addDeletionRiskEvent(org,"replay");
        remove(platform,org,0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(containsString("尚未结束的告警处置")));
        addDeparture(event,"");
        remove(platform,org,0).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,event.eventId())).isEqualTo("CONFIRMED");
    }

    private record DeletionRisk(String eventId,String targetId,String orgId,String districtId,String mode,long createdAt) { }

    private DeletionRisk addDeletionRiskEvent(String owner,String mode) {
        String event=UUID.randomUUID().toString(), alarm=UUID.randomUUID().toString(), source=UUID.randomUUID().toString(), target=UUID.randomUUID().toString();
        String district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id LIMIT 1",String.class);
        long now=System.currentTimeMillis()-30_000;
        var at=new java.sql.Timestamp(now);
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES(?,?,?,TRUE,?,?,?,0)",source,source,"单位删除隔离来源",mode,at,at);
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'UAV',?,?,?,?,?,0)",target,target,mode,owner,district,at,at);
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,?,?,'RULE_LEGALITY','HIGH',?,?,?,?,?,?)",alarm,target,source,alarm,at,at,mode,owner,district,at);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'CONFIRMED',?,?,?,?,0)",event,alarm,owner,district,at,at);
        // PG creates its initial generic case by trigger; this fixture deliberately models either
        // a legacy event with none or two explicitly separate area cases on both database engines.
        jdbc.update("DELETE FROM uav_risk_case_member WHERE risk_case_id IN(SELECT risk_case_id FROM uav_risk_case WHERE root_event_id=?)",event);
        jdbc.update("DELETE FROM uav_risk_case WHERE root_event_id=?",event);
        return new DeletionRisk(event,target,owner,district,mode,now);
    }

    private String addDeletionCase(DeletionRisk event) {
        String risk=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO uav_risk_case(risk_case_id,root_event_id,airspace_id,target_id,owner_org_id,district_id,source_mode,created_at) VALUES(?,?,?,?,?,?,?,?)",risk,event.eventId(),UUID.randomUUID().toString(),event.targetId(),event.orgId(),event.districtId(),event.mode(),event.createdAt());
        jdbc.update("INSERT INTO uav_risk_case_member(risk_case_id,event_id,role,created_at) VALUES(?,?,'OBSERVED',?)",risk,event.eventId(),event.createdAt());
        return risk;
    }

    private void addDeparture(DeletionRisk event,String scope) {
        jdbc.update("INSERT INTO uav_auto_voice_task(event_id,risk_scope,status,policy_code,reason,updated_at,provider_key,departure_evaluated_at,departure_observed_at) VALUES(?,?,'BLOCKED','ISOLATED_DEPARTURE','离开依据夹具',?,?,?,?)",event.eventId(),scope,event.createdAt()+10_000,UUID.randomUUID().toString(),event.createdAt()+10_000,event.createdAt()+9_000);
    }

    @Test void ordinaryAndAppointedFrontendUsersCannotUsePlatformDeletionEndpoint() throws Exception {
        String role = "ROLE-" + UUID.randomUUID().toString().substring(0, 18);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version) VALUES(?,?,'',false,true,0,0,0)", role, role);
        String user = addUser(org, role);
        String token = login(user, "DeleteCheck#2026", "FRONTEND");
        remove(token, org, 0).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_role SET owner_org_id=?,name_scope=? WHERE role_code=?", org, org, role);
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED',scope_org_rule='OWN_ORG' WHERE user_id=?", user);
        jdbc.update("INSERT INTO app_org_delegation(org_id,administrator_user_id,version,created_at,updated_at) VALUES(?,?,1,0,0)", org, user);
        token = login(user, "DeleteCheck#2026", "BACKEND");
        remove(token, org, 0).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/organizations/" + org).param("expected_version", "0")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        assertThat(countOrg(org)).isEqualTo(1);
    }

    protected void insertOrg(String id, String parent) {
        jdbc.update("INSERT INTO app_org(org_id,parent_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?, ?,true,0,0,0)",
                id, parent, "O-" + id, "待删除测试单位 " + id);
    }

    protected int countOrg(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM app_org WHERE org_id=? AND deleted_at IS NULL", Integer.class, id);
    }

    private String addUser(String orgId, String role) {
        String user = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO app_user(user_id,account,name,org_id,role_code,status,password_hash,scope_mode,must_change_password,
                    permission_version,created_at,updated_at,version)
                VALUES(?,?,?,?,?,'ACTIVE',?,'ALL',false,0,0,0,0)
                """, user, user, user, orgId, role, passwords.encode("DeleteCheck#2026"));
        return user;
    }

    protected String addDevice(String orgId) {
        String device = UUID.randomUUID().toString();
        String district = jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id LIMIT 1", String.class);
        jdbc.update("""
            INSERT INTO ops_device(device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,created_at,updated_at)
            VALUES(?,?,?,'COUNTERMEASURE','反制','TCP',true,'replay',true,0,0)
            """, device, device, "隔离测试设备");
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", device, orgId, district);
        return device;
    }

    protected String addCommand(String device, String state, String type) {
        String command = UUID.randomUUID().toString();
        String actor = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        jdbc.update("""
            INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,source_mode,simulated,created_at,updated_at)
            VALUES(?,?,?,?,?,'隔离测试',?,'replay',true,0,0)
            """, command, command, device, actor, type, state);
        return command;
    }

    protected String addPlan(String orgId,String state) {
        String plan=UUID.randomUUID().toString();
        String route=jdbc.queryForObject("SELECT route_version_id FROM route_version ORDER BY route_version_id LIMIT 1",String.class);
        String district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id LIMIT 1",String.class);
        jdbc.update("""
            INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at)
            VALUES(?,?,?,'replay',TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00',TIMESTAMP WITH TIME ZONE '2000-01-01 01:00:00+00',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,plan,plan,state,route,orgId,district);
        return plan;
    }

    protected ResultActions remove(String token, String id, int version) throws Exception {
        return remove(token, id, version, UUID.randomUUID().toString());
    }

    protected ResultActions remove(String token, String id, int version, String key) throws Exception {
        return mvc.perform(delete("/api/v1/organizations/" + id).param("expected_version", String.valueOf(version))
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key));
    }

    private String login(String account, String password, String type) throws Exception {
        var body = json.createObjectNode().put("account", account).put("password", password).put("client_type", type);
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("session_id").asText();
    }
}
