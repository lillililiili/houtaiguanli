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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_profile WHERE org_id=?", Integer.class, org)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_org_delegation_preview WHERE org_id=?", Integer.class, org)).isZero();
        String detail = jdbc.queryForObject("SELECT detail FROM audit_log WHERE object_id=? AND action='organization_deleted'", String.class, org);
        assertThat(json.readTree(detail).path("profile").path("remarks").asText()).isEqualTo("原始基本资料");
        assertThat(json.readTree(detail).path("invalidated_preview_count").asInt()).isEqualTo(1);
        remove(platform, org, 0, key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
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
    void activeDisabledAndHistoricalUsersAllPreserveTheirOrganization(String userStatus) throws Exception {
        String user = addUser(org, "ROLE-BACKEND");
        if (userStatus.equals("DELETED")) {
            jdbc.update("UPDATE app_user SET status='DELETED',role_code=NULL,deleted_role_code='ROLE-BACKEND',deleted_at=1 WHERE user_id=?", user);
        } else jdbc.update("UPDATE app_user SET status=? WHERE user_id=?", userStatus, user);
        remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(containsString("人员")));
        assertThat(countOrg(org)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id=?", String.class, user)).isEqualTo(userStatus);
    }

    @Test void unassignedUnitRolesAndRevokedDelegationStillBlockDeletion() throws Exception {
        String role = "ROLE-" + UUID.randomUUID().toString().substring(0, 18);
        jdbc.update("""
                INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,owner_org_id,name_scope)
                VALUES(?,?,'',false,true,0,0,0,?,?)
                """, role, role, org, org);
        remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(containsString("单位角色")));
        String delegated = UUID.randomUUID().toString();
        insertOrg(delegated, null);
        jdbc.update("INSERT INTO app_org_delegation(org_id,administrator_user_id,version,created_at,updated_at) VALUES(?,NULL,1,0,0)", delegated);
        remove(platform, delegated, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"))
                .andExpect(jsonPath("$.error.message").value(containsString("单位授权")));
        assertThat(countOrg(org)).isEqualTo(1);
        assertThat(countOrg(delegated)).isEqualTo(1);
    }

    @Test void conventionalOwnershipWithoutForeignKeyIsProtectedAndHistoryRemainsUnchanged() throws Exception {
        String target = jdbc.queryForObject("SELECT target_id FROM target ORDER BY target_id LIMIT 1", String.class);
        String episode = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO prediction_episode(episode_id,target_id,airspace_id,source_mode,owner_org_id,district_id,status,
                    valid_until,reason,started_at,closed_at,stability_json,warning_json)
                VALUES(?,?,?,'replay',?,?,'CLEARED',1,'历史已撤销',0,1,'{}','{}')
                """, episode, target, UUID.randomUUID().toString(), org, UUID.randomUUID().toString());
        remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM prediction_episode WHERE episode_id=?", String.class, episode)).isEqualTo(org);
        assertThat(countOrg(org)).isEqualTo(1);
    }

    @Test void personnelDataScopeAndContactsAreNotCascaded() throws Exception {
        String actor = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        String district = jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id LIMIT 1", String.class);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)", actor, org, district);
        remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"));
        String contactOrg = UUID.randomUUID().toString();
        insertOrg(contactOrg, null);
        jdbc.update("INSERT INTO business_contact(contact_id,org_id,name,roles,enabled,created_at,updated_at,version) VALUES(?,?,'联系人','[]',false,0,0,0)",
                UUID.randomUUID().toString(), contactOrg);
        remove(platform, contactOrg, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_contact WHERE org_id=?", Integer.class, contactOrg)).isEqualTo(1);
    }

    @Test void deviceOwnershipPreventsDeletionRegardlessOfCurrentDeviceState() throws Exception {
        String device = jdbc.queryForObject("SELECT ops_device_id FROM device_business_scope ORDER BY ops_device_id LIMIT 1", String.class);
        jdbc.update("UPDATE device_business_scope SET owner_org_id=? WHERE ops_device_id=?", org, device);
        remove(platform, org, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM device_business_scope WHERE ops_device_id=?", String.class, device)).isEqualTo(org);
        assertThat(countOrg(org)).isEqualTo(1);
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
        return jdbc.queryForObject("SELECT count(*) FROM app_org WHERE org_id=?", Integer.class, id);
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
