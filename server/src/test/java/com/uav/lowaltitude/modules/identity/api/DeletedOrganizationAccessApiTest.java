package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.OrgEffectivePermissionService;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** Tombstones are test fixtures; no acceptance database or permissions are changed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeletedOrganizationAccessApiTest {
    private static final String PASSWORD = "OrgLifecycle#2026";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired SqlSessionTemplate sql;
    @Autowired IdentityAdminMapper identity;
    @Autowired MqttRepository mqtt;
    @Autowired AccessService access;
    @Autowired OrgEffectivePermissionService effective;
    private String removed, current, district, role, member, peer, platform;

    @BeforeEach void fixture() throws Exception {
        removed = UUID.randomUUID().toString(); current = UUID.randomUUID().toString();
        district = UUID.randomUUID().toString(); role = "R-" + UUID.randomUUID().toString().substring(0, 12);
        for (String org : new String[]{removed, current}) jdbc.update("""
                INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version)
                VALUES(?,?,?,true,0,0,0)
                """, org, org, "历史保留单位 " + org);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,true,0,0,0)",
                district, district, "测试区域");
        jdbc.update("INSERT INTO app_role(role_code,name,builtin,enabled,created_at,updated_at,version) VALUES(?,?,false,true,0,0,0)", role, role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'alarms','READ',true)", role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'alarm:read','OP',false)", role);
        member = person(removed); peer = person(current);
        for (String org : new String[]{removed,current}) jdbc.update(
                "INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)", peer, org, district);
        sql.clearCache();
        platform = login("admin1", "changeme", "BACKEND");
    }

    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test void oldAndNewBusinessSessionsFailButPlatformAndOtherUnitsRemainAvailable() throws Exception {
        String memberSession = login(member, PASSWORD, "FRONTEND");
        String peerSession = login(peer, PASSWORD, "FRONTEND");
        assertThat(effective.permissionCodes(member)).contains("alarm:read");
        tombstone();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(memberSession))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                node().put("account",member).put("password",PASSWORD).put("client_type","FRONTEND").toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(peerSession))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(platform))).andExpect(status().isOk());
        assertThat(effective.permissionCodes(member)).isEmpty();
        assertThat(effective.permissionCodes(peer)).contains("alarm:read");
    }

    @Test void currentDirectoriesHideDeletedUnitsWhileHistoricalNamesAndScopeRowsRemain() throws Exception {
        tombstone();
        assertThat(identity.listOrganizations()).extracting(row -> row.getOrgId()).doesNotContain(removed).contains(current);
        assertThat(identity.findOrganization(removed).getName()).isEqualTo("历史保留单位 " + removed);
        assertThat(identity.listUserScopes(peer)).extracting(row -> row.getOrgId()).contains(removed, current);
        assertThat(identity.countUserScopeTuple(peer, removed, district)).isZero();
        assertThat(identity.countUserScopeTuple(peer, current, district)).isEqualTo(1);
        assertThat(mqtt.scopeOptions()).noneMatch(row -> removed.equals(row.get("org_id")));
        assertThat(mqtt.scopeExists(removed,district)).isFalse();
        mvc.perform(get("/api/v1/directory-options").param("kind","organizations").param("keyword",removed)
                .header("Authorization",bearer(platform))).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/organization-profiles/"+removed).header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("历史保留单位 " + removed));
        AuthContext.set(new AuthUser(peer,peer,peer,role,0,false,"ASSIGNED"));
        assertThat(access.canAccessTuple(removed,district)).isFalse();
        assertThat(access.canAccessTuple(current,district)).isTrue();
        String adminId=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        AuthContext.set(new AuthUser(adminId,"admin1","平台","ROLE-ADMIN",0,false,"ALL"));
        assertThat(access.canAccessTuple(removed,district)).isTrue();
    }

    @Test void deletedUnitCannotReceiveNewChildrenUsersRolesOrAuthorization() throws Exception {
        tombstone();
        send(post("/api/v1/organizations"),node().put("name","新的下级单位").put("parent_id",removed))
                .andExpect(status().isConflict());
        send(patch("/api/v1/organizations/"+removed),node().put("name","修改删除单位").put("expected_version",1))
                .andExpect(status().isConflict());
        send(patch("/api/v1/organization-profiles/"+removed),node().put("name","修改已删除资料").put("expected_version",1))
                .andExpect(status().isConflict());
        send(post("/api/v1/users"),node().put("account","blocked-"+UUID.randomUUID()).put("name","不能创建")
                .put("org_id",removed).put("role_code",role).put("temporary_password",PASSWORD).put("user_type","FRONTEND"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_DELETED"));
        ObjectNode roleRequest=node().put("name","不能创建角色").put("owner_org_id",removed);
        roleRequest.putArray("permissions").add(node().put("permission_code","alarms").put("level","READ").put("menu_enabled",true));
        send(post("/api/v1/roles"),roleRequest).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/organizations/"+removed+"/delegation").header("Authorization",bearer(platform)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_DELETED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_org WHERE parent_id=?",Integer.class,removed)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"OWNER","OPERATOR","REPORTING"})
    void completedPlanAssociationsRemainReadOnlyWhenAnyExistingUnitWasDeleted(String retiredRelation) throws Exception {
        String historic=plan("OWNER".equals(retiredRelation)?removed:current,
                "OPERATOR".equals(retiredRelation)?removed:current,
                "REPORTING".equals(retiredRelation)?removed:current);
        String editable=plan(current,current,current);
        var before=jdbc.queryForMap("SELECT owner_org_id,source_binding_id,operator_org_id,pilot_contact_id,version FROM flight_plan WHERE plan_id=?",historic);
        tombstone();
        mvc.perform(get("/api/v1/flight-plans/"+historic+"/subjects").header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.plan_id").value(historic));
        ObjectNode clear=node().putNull("source_binding_id").putNull("operator_org_id").putNull("pilot_contact_id")
                .put("expected_version",0).put("reason","测试变更任务关联");
        send(patch("/api/v1/flight-plans/"+historic+"/subjects"),clear)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_UNAVAILABLE"));
        assertThat(jdbc.queryForMap("SELECT owner_org_id,source_binding_id,operator_org_id,pilot_contact_id,version FROM flight_plan WHERE plan_id=?",historic))
                .isEqualTo(before);
        // An unrelated current unit retains the pre-existing ability to maintain plan associations.
        send(patch("/api/v1/flight-plans/"+editable+"/subjects"),clear).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT source_binding_id FROM flight_plan WHERE plan_id=?",String.class,editable)).isNull();
    }

    private String plan(String owner,String operator,String reporting) {
        String plan=UUID.randomUUID().toString(),binding=UUID.randomUUID().toString();
        String source=jdbc.queryForObject("SELECT source_id FROM integration_source ORDER BY source_id LIMIT 1",String.class);
        String route=jdbc.queryForObject("SELECT route_version_id FROM route_version ORDER BY route_version_id LIMIT 1",String.class);
        jdbc.update("INSERT INTO plan_source_binding(binding_id,source_id,external_org_code,org_id,enabled,created_at,updated_at,version) VALUES(?,?,?,?,true,0,0,0)",binding,source,binding,reporting);
        jdbc.update("""
                INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,
                    owner_org_id,district_id,source_id,source_binding_id,operator_org_id,created_at,updated_at,version)
                VALUES(?,?,'COMPLETED','replay',TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00',
                    TIMESTAMP WITH TIME ZONE '2000-01-01 01:00:00+00',?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """,plan,plan,route,owner,district,source,binding,operator);
        return plan;
    }

    private String person(String org) {
        String id=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO app_user(user_id,account,name,org_id,role_code,status,password_hash,scope_mode,
                    must_change_password,permission_version,created_at,updated_at,version)
                VALUES(?,?,?,?,?,'ACTIVE',?,'ASSIGNED',false,0,0,0,0)
                """,id,id,id,org,role,passwords.encode(PASSWORD));
        return id;
    }
    private void tombstone() {
        String actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        jdbc.update("UPDATE app_org SET deleted_at=100,deleted_by=?,enabled=false,version=version+1 WHERE org_id=?",actor,removed);
        sql.clearCache();
    }
    private String login(String account,String password,String type) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                node().put("account",account).put("password",password).put("client_type",type).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("session_id").asText();
    }
    private ResultActions send(MockHttpServletRequestBuilder request,ObjectNode body) throws Exception {
        return mvc.perform(request.header("Authorization",bearer(platform)).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }
    private ObjectNode node() { return json.createObjectNode(); }
    private String bearer(String token) { return "Bearer "+token; }
}
