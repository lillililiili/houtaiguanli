package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** End-to-end authorization tests use new identifiers and two sibling units plus a child unit on every run. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UnitDelegationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired SqlSessionTemplate sql;
    @Autowired com.uav.lowaltitude.modules.identity.application.UnitManagementAccessService managementAccess;
    private static final String PASSWORD="UnitMembers#2026";
    protected String org,other,child,administrator,member,outsider,childUser,sharedRole,platform;

    @BeforeEach void fixture() throws Exception {
        String tag=UUID.randomUUID().toString().substring(0,8);
        org="unit-a-"+tag; other="unit-b-"+tag; child="unit-c-"+tag;
        administrator="unit-admin-"+tag; member="unit-member-"+tag;
        outsider="unit-other-"+tag; childUser="unit-child-"+tag; sharedRole="ROLE-SHARED-"+tag;
        for(String id:new String[]{org,other,child}) jdbc.update(
                "INSERT INTO app_org(org_id,org_code,name,parent_id,enabled,created_at,updated_at,version) VALUES(?,?,?,?,true,0,0,0)",
                id,id,"单位 "+id,id.equals(child)?org:null);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version) VALUES(?,?,'',false,true,0,0,0)",
                sharedRole,"共享值班员 "+tag);
        for(String[] row:new String[][]{{administrator,org},{member,org},{outsider,other},{childUser,child}}) {
            jdbc.update("""
                INSERT INTO app_user(user_id,account,name,org_id,role_code,status,password_hash,fail_count,
                    scope_mode,must_change_password,permission_version,created_at,updated_at,version)
                VALUES(?,?,?,?,?,'ACTIVE',?,0,'ALL',false,0,0,0,0)
                """,row[0],row[0],row[0],row[1],sharedRole,passwords.encode(PASSWORD));
        }
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'alarms','AUTH',true)",sharedRole);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'flights','READ',true)",sharedRole);
        for(String action:new String[]{"alarm:read","alarm:verify","flight:read","disposal:request","disposal:approve","disposal:direct"})
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,?,'OP',false)",sharedRole,action);
        sql.clearCache();
        platform=login("admin1","changeme",true);
    }

    @Test void enablingUsesExplicitRoleDefinitionsAndAssignmentsWithoutRewritingSharedRoles() throws Exception {
        String oldToken=login(member,PASSWORD,false);
        ObjectNode request=delegation(0,"OP",true);
        JsonNode preview=preview(org,request);
        assertThat(preview.path("users")).hasSize(2);
        assertThat(preview.path("users").toString()).contains("OWN_ORG",sharedRole).doesNotContain(outsider,childUser);
        apply(org,request,preview);
        String localRole=userRole(member);
        assertThat(localRole).isNotEqualTo(sharedRole).isEqualTo(userRole(administrator));
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM app_role WHERE role_code=?",String.class,localRole)).isEqualTo(org);
        assertThat(jdbc.queryForObject("SELECT copied_from_role_code FROM app_role WHERE role_code=?",String.class,localRole)).isNull();
        assertThat(preview.path("users").findValues("will_clone_role")).allSatisfy(value->assertThat(value.asBoolean()).isFalse());
        assertThat(userRole(outsider)).isEqualTo(sharedRole);
        assertThat(userRole(childUser)).isEqualTo(sharedRole);
        assertThat(jdbc.queryForObject("SELECT scope_mode FROM app_user WHERE user_id=?",String.class,outsider)).isEqualTo("ALL");
        assertThat(jdbc.queryForObject("SELECT scope_org_rule FROM app_user WHERE user_id=?",String.class,member)).isEqualTo("OWN_ORG");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_data_scope WHERE user_id=? AND org_id<>?",Integer.class,member,org)).isZero();
        assertThat(jdbc.queryForObject("SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code='alarms'",String.class,sharedRole)).isEqualTo("AUTH");
        mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(oldToken))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='unit_delegation_updated' AND object_id=? AND detail LIKE '%members_before%' AND detail LIKE '%members_after%'",Integer.class,org)).isEqualTo(1);
        String adminToken=login(administrator,PASSWORD,true);
        mvc.perform(get("/api/v1/unit-admin/catalog").header("Authorization",bearer(adminToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.org_id").value(org));
    }

    @Test void explicitNewRoleDoesNotInheritGlobalLegacyOrDirectActionRows() throws Exception {
        jdbc.update("UPDATE app_role_permission SET permission_level='READ' WHERE role_code=? AND permission_code IN ('alarm:read','disposal:direct')",sharedRole);
        jdbc.update("UPDATE app_role_permission SET permission_level='AUTH' WHERE role_code=? AND permission_code='flight:read'",sharedRole);
        sql.clearCache();
        ObjectNode request=delegation(0,"OP",true);
        request.withArray("actions").add(json.createObjectNode().put("permission_code","disposal:direct").put("level","OP"));
        explicitRoles(org,request);
        var explicitlyGranted=((ObjectNode)request.path("new_roles").get(0)).putArray("actions");
        explicitlyGranted.add(node().put("permission_code","alarm:read").put("level","OP"));
        explicitlyGranted.add(node().put("permission_code","flight:read").put("level","OP"));
        apply(org,request,preview(org,request));
        String code=userRole(member);
        for(String action:new String[]{"alarm:read","flight:read"}) {
            assertThat(jdbc.queryForObject("SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code=?",String.class,code,action)).isEqualTo("OP");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role_permission WHERE role_code=? AND permission_code='disposal:direct'",Integer.class,code)).isZero();
        assertThat(jdbc.queryForObject("SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code='alarm:read'",String.class,sharedRole)).isEqualTo("READ");
        mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(login(member,PASSWORD,false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'alarm:read')]").exists())
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'flight:read')]").exists())
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'disposal:direct')]").doesNotExist());
    }

    @Test void copiedRoleShowsFrozenSourceInPlatformAndUnitViewsWithoutChangingBindingsOrPermissions() throws Exception {
        String sourceName=jdbc.queryForObject("SELECT name FROM app_role WHERE role_code=?",String.class,sharedRole);
        enable();
        String code=userRole(administrator), token=login(administrator,PASSWORD,true);
        var bindings=jdbc.queryForList("SELECT user_id,role_code,permission_version,version FROM app_user WHERE org_id=? ORDER BY user_id",org);
        var grants=jdbc.queryForList("SELECT * FROM app_role_permission WHERE role_code=? ORDER BY permission_code",code);
        int version=jdbc.queryForObject("SELECT version FROM app_role WHERE role_code=?",Integer.class,code);
        long copiedAt=jdbc.queryForObject("SELECT created_at FROM app_role WHERE role_code=?",Long.class,code);
        // Fixture represents a pre-existing historical automatic copy; current APIs never create copy provenance.
        jdbc.update("UPDATE app_role SET name=?,copied_from_role_code=?,copied_from_role_name=?,copied_at=? WHERE role_code=?",
                sourceName,sharedRole,sourceName,copiedAt,code);
        jdbc.update("UPDATE app_role SET name=? WHERE role_code=?","源角色后续改名 "+UUID.randomUUID().toString().substring(0,8),sharedRole);
        sql.clearCache();
        mvc.perform(get("/api/v1/roles/"+code).header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value(sourceName))
                .andExpect(jsonPath("$.data.copy_origin.source_role_code").value(sharedRole))
                .andExpect(jsonPath("$.data.copy_origin.source_role_name").value(sourceName))
                .andExpect(jsonPath("$.data.copy_origin.copied_at").value(copiedAt));
        JsonNode list=data(mvc.perform(get("/api/v1/roles").header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.findValues("copy_origin")).anySatisfy(origin -> {
            assertThat(origin.path("source_role_code").asText()).isEqualTo(sharedRole);
            assertThat(origin.path("source_role_name").asText()).isEqualTo(sourceName);
        });
        mvc.perform(get("/api/v1/unit-admin/roles").header("Authorization",bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].copy_origin.source_role_name").value(sourceName));
        assertThat(jdbc.queryForList("SELECT user_id,role_code,permission_version,version FROM app_user WHERE org_id=? ORDER BY user_id",org)).isEqualTo(bindings);
        assertThat(jdbc.queryForList("SELECT * FROM app_role_permission WHERE role_code=? ORDER BY permission_code",code)).isEqualTo(grants);
        assertThat(jdbc.queryForObject("SELECT version FROM app_role WHERE role_code=?",Integer.class,code)).isEqualTo(version);
        assertThat(userRole(member)).isEqualTo(code);
    }

    @Test void manuallyCreatedRoleDoesNotClaimAnAutomaticCopySource() throws Exception {
        enable();
        String token=login(administrator,PASSWORD,true);
        JsonNode role=createdRole(token,"手工人员角色","READ");
        assertThat(role.has("copy_origin")).isFalse();
        mvc.perform(get("/api/v1/roles/"+role.path("role_code").asText()).header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.copy_origin").doesNotExist());
        mvc.perform(get("/api/v1/roles/"+sharedRole).header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.copy_origin").doesNotExist());
    }

    @Test void previewIdentifiesEachFunctionItsReducedLevelAndMenuClosureIndependentlyOfGroupName() throws Exception {
        jdbc.update("UPDATE app_permission SET module_name='共同导航分组' WHERE permission_code IN ('flights','legality','airspace','risk')");
        jdbc.update("UPDATE app_role_permission SET permission_level='OP' WHERE role_code=? AND permission_code='flights'",sharedRole);
        for(String[] grant:new String[][]{{"legality","AUTH"},{"airspace","OP"},{"risk","READ"}})
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,?,?,?)",
                    sharedRole,grant[0],grant[1],"legality".equals(grant[0]));
        sql.clearCache();
        ObjectNode request=delegation(0,"AUTH",false);
        for(JsonNode grant:request.withArray("permissions"))
            if("alarms".equals(grant.path("permission_code").asText())) ((ObjectNode)grant).put("menu_enabled",false);
        request.withArray("permissions").add(node().put("permission_code","airspace").put("level","READ").put("menu_enabled",false));
        JsonNode impact=preview(org,request).path("roles").get(0).path("removed_permissions");
        var changes=new java.util.ArrayList<String>();
        impact.forEach(value->changes.add(value.asText()));
        assertThat(changes).contains("飞行任务：操作→只读","合法性判定：授权→无权限；菜单关闭",
                "空域与航线：操作→只读","空间风险：只读→无权限","异常告警：菜单关闭");
        assertThat(changes).noneMatch(value->value.contains("共同导航分组"));
        String verifyName=jdbc.queryForObject("SELECT name FROM app_permission WHERE permission_code='alarm:verify'",String.class);
        assertThat(changes).contains(verifyName+"：允许→无权限");
        assertThat(jdbc.queryForObject("SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code='flights'",String.class,sharedRole))
                .isEqualTo("OP");
    }

    @Test void memberPagesAndEveryMutationExcludeOtherAndChildUnits() throws Exception {
        enable();
        String token=login(administrator,PASSWORD,true);
        mvc.perform(get("/api/v1/unit-admin/users").header("Authorization",bearer(token)).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v1/unit-admin/users").header("Authorization",bearer(token)).param("keyword",outsider))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        for(String foreign:new String[]{outsider,childUser}) {
            mvc.perform(get("/api/v1/unit-admin/users/"+foreign).header("Authorization",bearer(token)))
                    .andExpect(status().isNotFound());
            perform(put("/api/v1/unit-admin/users/"+foreign+"/status"),token,node().put("status","DISABLED").put("expected_version",0))
                    .andExpect(status().isNotFound());
            perform(post("/api/v1/unit-admin/users/"+foreign+"/reset-password"),token,node().put("temporary_password",PASSWORD).put("expected_version",0))
                    .andExpect(status().isNotFound());
            perform(delete("/api/v1/unit-admin/users/"+foreign).param("expected_version","0"),token,node().put("reason","测试越权"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/unit-admin/users").header("Authorization",bearer(login(member,PASSWORD,false))))
                .andExpect(status().isForbidden());
    }

    @Test void selfAndSelfRoleCannotBeChangedAndForgedScopeCannotEscapeUnit() throws Exception {
        enable(); String token=login(administrator,PASSWORD,true); String localRole=userRole(administrator);
        int version=userVersion(administrator);
        perform(put("/api/v1/unit-admin/users/"+administrator+"/status"),token,node().put("status","DISABLED").put("expected_version",version))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("UNIT_ADMIN_SELF_PROTECTED"));
        perform(post("/api/v1/unit-admin/users/"+administrator+"/reset-password"),token,node().put("temporary_password",PASSWORD).put("expected_version",version))
                .andExpect(status().isForbidden());
        perform(delete("/api/v1/unit-admin/users/"+administrator).param("expected_version",String.valueOf(version)),token,node().put("reason","测试本人保护"))
                .andExpect(status().isForbidden());
        for(String endpoint:new String[]{"/status","/permissions"}) {
            ObjectNode body=endpoint.equals("/status")?node().put("enabled",false).put("expected_version",0):rolePayload("变更", "OP").put("expected_version",0);
            perform(put("/api/v1/unit-admin/roles/"+localRole+endpoint),token,body).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/unit-admin/roles/"+localRole).header("Authorization",bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.platform_maintained").value(true));
        for(ObjectNode body:new ObjectNode[]{
                userPayload(localRole).put("org_id",other),
                userPayload(localRole).put("data_scope","ALL"),
                userPayload(localRole).put("user_type","BACKEND"),
                userPayload(sharedRole)}) {
            perform(post("/api/v1/unit-admin/users"),token,body).andExpect(status().is4xxClientError());
        }
    }

    @Test void rolePrivilegesStayBelowCeilingAndCeilingExpansionDoesNotRestoreRemovedGrants() throws Exception {
        enable(); String token=login(administrator,PASSWORD,true);
        ObjectNode excessive=rolePayload("越权角色","AUTH");
        perform(post("/api/v1/unit-admin/roles"),token,excessive)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("UNIT_PERMISSION_CEILING"));
        ObjectNode direct=rolePayload("越权动作","READ");
        direct.withArray("actions").add(node().put("permission_code","disposal:direct").put("level","OP"));
        perform(post("/api/v1/unit-admin/roles"),token,direct).andExpect(status().isForbidden());
        JsonNode role=createdRole(token,"值班员","OP");
        String code=role.path("role_code").asText();
        perform(put("/api/v1/unit-admin/users/"+member+"/access"),token,node().put("role_code",code)
                .put("expected_version",userVersion(member)).put("reason","分配本单位角色")).andExpect(status().isOk());
        String oldMemberToken=login(member,PASSWORD,false);
        ObjectNode restricted=delegation(1,"READ",false);
        apply(org,restricted,preview(org,restricted));
        mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(oldMemberToken))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code='alarms'",String.class,code)).isEqualTo("READ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role_permission WHERE role_code=? AND permission_code='alarm:verify'",Integer.class,code)).isZero();
        ObjectNode expanded=delegation(2,"OP",true);
        apply(org,expanded,preview(org,expanded));
        assertThat(jdbc.queryForObject("SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code='alarms'",String.class,code)).isEqualTo("READ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role_permission WHERE role_code=? AND permission_code='alarm:verify'",Integer.class,code)).isZero();
    }

    @Test void platformCannotBypassScopedRoleOrMemberRangeAndAdministratorRequiresExplicitRemoval() throws Exception {
        enable(); String localRole=userRole(member);
        ObjectNode profile=node().put("name","保持身份").put("org_id",org).put("role_code",localRole)
                .put("expected_version",userVersion(member)).put("data_scope","ALL").put("user_type","FRONTEND");
        perform(patch("/api/v1/users/"+member),platform,profile).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNIT_MEMBER_SCOPE_FIXED"));
        ObjectNode cross=userPayload(localRole).put("org_id",other);
        perform(post("/api/v1/users"),platform,cross).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ROLE_ORGANIZATION_MISMATCH"));
        perform(put("/api/v1/users/"+administrator+"/status"),platform,node().put("status","DISABLED").put("expected_version",userVersion(administrator)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNIT_ADMIN_APPOINTMENT_PROTECTED"));
        JsonNode detail=data(mvc.perform(get("/api/v1/roles/"+localRole).header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ObjectNode grant=node().put("expected_version",detail.path("version").asInt());
        grant.set("permissions",detail.path("permissions").deepCopy());
        for(JsonNode item:grant.withArray("permissions")) if(item.path("permission_code").asText().equals("alarms")) ((ObjectNode)item).put("level","AUTH");
        perform(put("/api/v1/roles/"+localRole+"/permissions"),platform,grant).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNIT_PERMISSION_CEILING"));
    }

    @Test void stalePreviewForeignAdministratorAndReplayedApplyCannotActivateUnexpectedConfiguration() throws Exception {
        ObjectNode request=delegation(0,"OP",true);
        JsonNode stale=preview(org,request);
        jdbc.update("UPDATE app_role SET version=version+1 WHERE role_code=?",sharedRole); sql.clearCache();
        request.put("preview_token",stale.path("preview_token").asText());
        perform(put("/api/v1/organizations/"+org+"/delegation"),platform,request)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DELEGATION_PREVIEW_STALE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_org_delegation WHERE org_id=?",Integer.class,org)).isZero();
        ObjectNode foreign=delegation(0,"OP",true).put("administrator_user_id",outsider);
        explicitRoles(org,foreign);
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,foreign).andExpect(status().isNotFound());
        ObjectNode current=delegation(0,"OP",true);
        JsonNode p=preview(org,current);
        current.put("preview_token",p.path("preview_token").asText());
        String key=UUID.randomUUID().toString();
        mvc.perform(put("/api/v1/organizations/"+org+"/delegation").header("Authorization",bearer(platform))
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(current.toString())).andExpect(status().isOk());
        mvc.perform(put("/api/v1/organizations/"+org+"/delegation").header("Authorization",bearer(platform))
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(current.toString())).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT version FROM app_org_delegation WHERE org_id=?",Integer.class,org)).isEqualTo(1);
    }

    @Test void removalKeepsUnitManagedAndReplacementUsesNewAdministratorAtomically() throws Exception {
        enable(); String oldAdmin=login(administrator,PASSWORD,true);
        ObjectNode replace=delegation(1,"OP",true).put("administrator_user_id",member);
        apply(org,replace,preview(org,replace));
        mvc.perform(get("/api/v1/unit-admin/users").header("Authorization",bearer(oldAdmin))).andExpect(status().isUnauthorized());
        String currentAdmin=login(member,PASSWORD,true);
        mvc.perform(get("/api/v1/unit-admin/users").header("Authorization",bearer(currentAdmin))).andExpect(status().isOk());
        ObjectNode revoke=delegation(2,"READ",false).putNull("administrator_user_id");
        apply(org,revoke,preview(org,revoke));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_org_delegation WHERE org_id=? AND administrator_user_id IS NULL",Integer.class,org)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT scope_org_rule FROM app_user WHERE user_id=?",String.class,member)).isEqualTo("OWN_ORG");
        mvc.perform(get("/api/v1/unit-admin/users").header("Authorization",bearer(login(member,PASSWORD,false)))).andExpect(status().isForbidden());
    }

    @Test void ownUnitUserLifecycleEnforcesVersionAndLeavesAuditWithoutPasswords() throws Exception {
        enable(); String token=login(administrator,PASSWORD,true);
        JsonNode role=createdRole(token,"新人角色","READ"); String code=role.path("role_code").asText();
        JsonNode created=data(perform(post("/api/v1/unit-admin/users"),token,userPayload(code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data_scope").value("OWN_ORG")).andReturn().getResponse().getContentAsString());
        String id=created.path("user_id").asText();
        ObjectNode profile=node().put("name","已编辑").put("phone","13800000000").put("org_id",org).put("role_code",code)
                .put("data_scope","OWN_ORG").put("user_type","FRONTEND").put("expected_version",0);
        perform(patch("/api/v1/unit-admin/users/"+id),token,profile).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("已编辑"));
        perform(patch("/api/v1/unit-admin/users/"+id),token,profile).andExpect(status().isConflict());
        perform(post("/api/v1/unit-admin/users/"+id+"/reset-password"),token,node().put("temporary_password","FreshMember#2026").put("expected_version",1)).andExpect(status().isOk());
        perform(put("/api/v1/unit-admin/users/"+id+"/status"),token,node().put("status","DISABLED").put("expected_version",2)).andExpect(status().isOk());
        perform(delete("/api/v1/unit-admin/users/"+id).param("expected_version","3"),token,node().put("reason","生命周期回归")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/unit-admin/users/"+id).header("Authorization",bearer(token))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id=?",String.class,id)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action LIKE 'unit_user_%'",Integer.class,id)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT detail FROM audit_log WHERE object_id=? AND action='unit_user_updated'",String.class,id))
                .contains("before","after","新人员","已编辑","13800000000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE detail LIKE '%FreshMember#2026%'",Integer.class)).isZero();
    }

    @Test void separateUnitsCanUseSameRoleNameAndDoNotShareRoleAssignments() throws Exception {
        enable();
        ObjectNode otherConfig=delegation(0,"READ",false).put("administrator_user_id",outsider);
        apply(other,otherConfig,preview(other,otherConfig));
        String a=login(administrator,PASSWORD,true), b=login(outsider,PASSWORD,true);
        JsonNode roleA=createdRole(a,"值班员","READ"), roleB=createdRole(b,"值班员","READ");
        assertThat(roleA.path("role_code").asText()).isNotEqualTo(roleB.path("role_code").asText());
        perform(put("/api/v1/unit-admin/users/"+member+"/access"),a,node().put("role_code",roleB.path("role_code").asText())
                .put("expected_version",userVersion(member)).put("reason","跨单位拒绝")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE name='值班员' AND owner_org_id IN (?,?)",Integer.class,org,other)).isEqualTo(2);
    }

    @Test void previewIncludesRealCrossUnitTaskCountsAndOperatorAssociation() throws Exception {
        String route="r-"+UUID.randomUUID().toString().substring(0,8), version="rv-"+route, plan="p-"+route;
        String district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id LIMIT 1",String.class);
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,?,true,'live',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                route,route,route,other,district);
        String geometry;
        try(var connection=jdbc.getDataSource().getConnection()) {
            geometry=connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")
                    ?"ST_GeomFromText('LINESTRING(118 37,118.01 37.01)',4326)":"CAST('SRID=4326;LINESTRING(118 37,118.01 37.01)' AS GEOMETRY)";
        }
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,valid_from,created_at) VALUES(?,?,1,"+geometry+",CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",version,route);
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,operator_org_id,created_at,updated_at,version) VALUES(?,?,'APPROVED','live',?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                plan,plan,version,other,district,org);
        sql.clearCache();
        JsonNode p=preview(org,delegation(0,"OP",true));
        assertThat(p.path("cross_org_impacts").toString()).contains(other,"FLIGHT_PLAN");
        assertThat(p.path("warnings").toString()).contains("1 条任务归属其他单位");
    }

    @Test void managementGrantsAreIndependentAndAnOlderClientDoesNotClearThem() throws Exception {
        enable();
        JsonNode initial=data(mvc.perform(get("/api/v1/organizations/"+org+"/delegation").header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(initial.path("management_permissions")).hasSize(5);
        initial.path("management_permissions").forEach(g->assertThat(g.path("level").asText()).isEqualTo("NONE"));
        assertThat(managementAccess.permissionCodes(administrator)).isEmpty();

        ObjectNode request=delegation(1,"OP",true);
        request.putArray("management_permissions")
                .add(node().put("module_key","devices").put("level","OP"))
                .add(node().put("module_key","monitoring").put("level","READ"));
        apply(org,request,preview(org,request));
        String token=login(administrator,PASSWORD,true);
        mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.management_context.permission_codes[?(@ == 'devices.op')]").exists())
                .andExpect(jsonPath("$.data.management_context.menu_keys[?(@ == 'monitoring')]").exists())
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'devices.op')]").doesNotExist());
        assertThat(managementAccess.allows(administrator,"devices.op")).isTrue();
        assertThat(managementAccess.allows(administrator,"monitoring.op")).isFalse();
        assertThat(managementAccess.permissionCodes(member)).isEmpty();
        assertThat(managementAccess.permissionCodes(outsider)).isEmpty();
        ObjectNode oldClient=delegation(2,"OP",true);
        apply(org,oldClient,preview(org,oldClient));
        assertThat(managementAccess.allows(administrator,"devices.op")).isTrue();
        assertThat(managementAccess.allows(administrator,"monitoring.read")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='unit_delegation_updated' AND detail LIKE '%management_permissions%'",Integer.class,org)).isEqualTo(3);
    }

    @Test void managementGrantChangesBindPreviewAndRevokeExistingFrontAndBackSessions() throws Exception {
        ObjectNode request=delegation(0,"OP",true);
        request.putArray("management_permissions").add(node().put("module_key","commissioning").put("level","OP"));
        apply(org,request,preview(org,request));
        String front=login(administrator,PASSWORD,false),back=login(administrator,PASSWORD,true);
        ObjectNode next=delegation(1,"OP",true);
        next.putArray("management_permissions").add(node().put("module_key","commissioning").put("level","READ"));
        JsonNode p=preview(org,next);
        ((ObjectNode)next.path("management_permissions").get(0)).put("level","OP");
        next.put("preview_token",p.path("preview_token").asText());
        perform(put("/api/v1/organizations/"+org+"/delegation"),platform,next).andExpect(status().isConflict());
        ((ObjectNode)next.path("management_permissions").get(0)).put("level","READ");
        apply(org,next,preview(org,next));
        assertThat(managementAccess.allows(administrator,"commissioning.read")).isTrue();
        assertThat(managementAccess.allows(administrator,"commissioning.op")).isFalse();
        for(String session:new String[]{front,back}) mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(session)))
                .andExpect(status().isUnauthorized());
        ObjectNode again=delegation(2,"OP",true);
        JsonNode stale=preview(org,again);
        jdbc.update("UPDATE app_org_management_permission SET permission_level='NONE' WHERE org_id=? AND module_key='commissioning'",org);
        sql.clearCache();
        again.put("preview_token",stale.path("preview_token").asText());
        perform(put("/api/v1/organizations/"+org+"/delegation"),platform,again).andExpect(status().isConflict());
    }

    @Test void managementPermissionsRejectUnknownModulesDuplicatesAndMonitoringOperation() throws Exception {
        for(String key:new String[]{"interfaces","maps","audit","rules","monitoring"}) {
            ObjectNode request=delegation(0,"OP",true);
            request.putArray("management_permissions").add(node().put("module_key",key).put("level","OP"));
            perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,request).andExpect(status().isBadRequest());
        }
        ObjectNode duplicate=delegation(0,"OP",true);
        duplicate.putArray("management_permissions").add(node().put("module_key","devices").put("level","READ"))
                .add(node().put("module_key","devices").put("level","OP"));
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,duplicate).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_org_delegation WHERE org_id=?",Integer.class,org)).isZero();
    }

    @Test void managementAccessAlwaysRechecksCurrentAppointmentAccountRoleAndExactUnit() throws Exception {
        ObjectNode request=delegation(0,"OP",true);
        request.putArray("management_permissions").add(node().put("module_key","maintenance").put("level","OP"));
        apply(org,request,preview(org,request));
        assertThat(managementAccess.orgId(administrator)).isEqualTo(org);
        assertThat(managementAccess.allows(administrator,"maintenance.op")).isTrue();
        jdbc.update("UPDATE app_org SET enabled=false WHERE org_id=?",org);
        assertThat(managementAccess.orgId(administrator)).isNull();
        jdbc.update("UPDATE app_org SET enabled=true WHERE org_id=?",org);
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE user_id=?",administrator);
        assertThat(managementAccess.allows(administrator,"maintenance.op")).isFalse();
        jdbc.update("UPDATE app_user SET status='ACTIVE',scope_mode='ALL',scope_org_rule=NULL WHERE user_id=?",administrator);
        assertThat(managementAccess.allows(administrator,"maintenance.op")).isFalse();
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED',scope_org_rule='OWN_ORG' WHERE user_id=?",administrator);
        jdbc.update("UPDATE app_role SET enabled=false WHERE role_code=?",userRole(administrator));
        assertThat(managementAccess.permissionCodes(administrator)).isEmpty();
        jdbc.update("UPDATE app_role SET enabled=true WHERE role_code=?",userRole(administrator));
        jdbc.update("UPDATE app_user SET org_id=? WHERE user_id=?",child,administrator);
        assertThat(managementAccess.orgId(administrator)).isNull();
        jdbc.update("UPDATE app_user SET org_id=? WHERE user_id=?",org,administrator);
        jdbc.update("UPDATE app_org_delegation SET administrator_user_id=NULL WHERE org_id=?",org);
        assertThat(managementAccess.allows(administrator,"maintenance.op")).isFalse();
        assertThat(managementAccess.menuKeys(administrator)).isEmpty();
    }

    @Test void managementRequireRejectsTemporaryPasswordEvenWithAnOtherwiseValidGrant() throws Exception {
        ObjectNode request=delegation(0,"OP",true);
        request.putArray("management_permissions").add(node().put("module_key","devices").put("level","OP"));
        apply(org,request,preview(org,request));
        var previous=com.uav.lowaltitude.platform.security.AuthContext.get();
        try {
            com.uav.lowaltitude.platform.security.AuthContext.set(new com.uav.lowaltitude.platform.security.AuthUser(
                    administrator,administrator,administrator,userRole(administrator),0,true,"ASSIGNED"));
            assertThatThrownBy(()->managementAccess.require("devices.op"))
                    .isInstanceOfSatisfying(com.uav.lowaltitude.platform.api.ApiException.class,error->{
                        assertThat(error.getCode()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
                        assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    });
            com.uav.lowaltitude.platform.security.AuthContext.set(new com.uav.lowaltitude.platform.security.AuthUser(
                    administrator,administrator,administrator,userRole(administrator),0,false,"ASSIGNED"));
            assertThat(managementAccess.require("devices.op")).isEqualTo(org);
            jdbc.update("UPDATE app_user SET must_change_password=true WHERE user_id=?",administrator);
            assertThat(managementAccess.orgId(administrator)).isEqualTo(org);
            assertThat(managementAccess.allows(administrator,"devices.op")).isFalse();
            assertThat(managementAccess.permissionCodes(administrator)).isEmpty();
        } finally {
            if(previous==null) com.uav.lowaltitude.platform.security.AuthContext.clear();
            else com.uav.lowaltitude.platform.security.AuthContext.set(previous);
        }
    }

    @Test void platformCreatesNamedUnitRolesWithinCeilingWithoutTouchingGlobalRole() throws Exception {
        enable();
        ObjectNode otherConfig=delegation(0,"READ",false).put("administrator_user_id",outsider);
        apply(other,otherConfig,preview(other,otherConfig));
        JsonNode catalog=data(mvc.perform(get("/api/v1/permissions/catalog").header("Authorization",bearer(platform)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String name="平台值班员 "+UUID.randomUUID().toString().substring(0,8);
        ObjectNode body=node().put("name",name).put("owner_org_id",org);
        var permissions=body.putArray("permissions");
        for(JsonNode p:catalog) permissions.add(node().put("permission_code",p.path("permission_code").asText())
                .put("level","alarms".equals(p.path("permission_code").asText())?"READ":"NONE").put("menu_enabled",false));
        JsonNode first=data(perform(post("/api/v1/roles"),platform,body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(first.path("owner_org_id").asText()).isEqualTo(org);
        body.put("owner_org_id",other);
        JsonNode second=data(perform(post("/api/v1/roles"),platform,body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(second.path("role_code").asText()).isNotEqualTo(first.path("role_code").asText());
        body.remove("owner_org_id");
        perform(post("/api/v1/roles"),platform,body).andExpect(status().isOk());
        body.put("owner_org_id","  ").put("name",name+" global blank");
        JsonNode global=data(perform(post("/api/v1/roles"),platform,body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM app_role WHERE role_code=?",String.class,global.path("role_code").asText())).isNull();
        body.put("owner_org_id",child).put("name",name+" child");
        perform(post("/api/v1/roles"),platform,body).andExpect(status().isBadRequest());
        body.put("owner_org_id",other).put("name",name+" over");
        for(JsonNode p:permissions) if("alarms".equals(p.path("permission_code").asText())) ((ObjectNode)p).put("level","AUTH");
        perform(post("/api/v1/roles"),platform,body).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM app_role WHERE role_code=?",String.class,sharedRole)).isNull();
        body.put("owner_org_id",org).put("name",name);
        for(JsonNode p:permissions) if("alarms".equals(p.path("permission_code").asText())) ((ObjectNode)p).put("level","READ");
        perform(post("/api/v1/roles"),platform,body).andExpect(status().isConflict());
    }

    @Test void appointmentWithoutExplicitAssignmentsNeverCreatesOrCopiesRoles() throws Exception {
        var roles=jdbc.queryForList("SELECT * FROM app_role ORDER BY role_code");
        ObjectNode body=delegation(0,"OP",true);
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNIT_ROLE_ASSIGNMENT_REQUIRED"));
        perform(put("/api/v1/organizations/"+org+"/delegation"),platform,body)
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForList("SELECT * FROM app_role ORDER BY role_code")).isEqualTo(roles);
        assertThat(userRole(administrator)).isEqualTo(sharedRole);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_org_delegation WHERE org_id=?",Integer.class,org)).isZero();
    }

    @Test void explicitNewRoleDefaultsToNoPermissionsAndDisabledMembersAlsoNeedAssignments() throws Exception {
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE user_id=?",member);sql.clearCache();
        ObjectNode body=delegation(0,"OP",true);
        body.putArray("new_roles").add(node().put("client_key","empty-role").put("name","人工空权限角色"));
        body.putArray("member_assignments").add(node().put("user_id",administrator).put("new_role_key","empty-role"));
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNIT_ROLE_ASSIGNMENT_REQUIRED"));
        body.withArray("member_assignments").add(node().put("user_id",member).put("new_role_key","empty-role"));
        apply(org,body,preview(org,body));
        String code=userRole(administrator);
        assertThat(userRole(member)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role_permission WHERE role_code=? AND permission_level<>'NONE'",Integer.class,code)).isZero();
        assertThat(jdbc.queryForObject("SELECT copied_from_role_code FROM app_role WHERE role_code=?",String.class,code)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id=?",String.class,member)).isEqualTo("DISABLED");
        mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(login(administrator,PASSWORD,true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.permission_codes").isEmpty());
    }

    @Test void platformCanExplicitlyAssignExistingUnitRoleWithoutCreatingAnything() throws Exception {
        enable();String token=login(administrator,PASSWORD,true);
        String target=createdRole(token,"人工现有角色","READ").path("role_code").asText();
        int count=jdbc.queryForObject("SELECT count(*) FROM app_role WHERE owner_org_id=?",Integer.class,org);
        ObjectNode body=delegation(1,"OP",true);
        body.putArray("member_assignments").add(node().put("user_id",member).put("role_code",target));
        apply(org,body,preview(org,body));
        assertThat(userRole(member)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE owner_org_id=?",Integer.class,org)).isEqualTo(count);
    }

    @Test void equivalentManualReplacementMovesEveryMemberThenDeletesOnlySelectedHistoricalCopy() throws Exception {
        enable();String old=legacyCopy();
        String previousToken=login(administrator,PASSWORD,true);
        jdbc.update("UPDATE app_user SET status='DISABLED' WHERE user_id=?",member);sql.clearCache();
        var oldGrants=jdbc.queryForList("SELECT permission_code,permission_level,menu_enabled FROM app_role_permission WHERE role_code=? ORDER BY permission_code",old);
        ObjectNode body=replacement(old);
        JsonNode p=preview(org,body);
        apply(org,body,p);
        String next=userRole(administrator);
        assertThat(next).isNotEqualTo(old).isEqualTo(userRole(member));
        assertThat(jdbc.queryForList("SELECT permission_code,permission_level,menu_enabled FROM app_role_permission WHERE role_code=? ORDER BY permission_code",next)).isEqualTo(oldGrants);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE role_code=?",Integer.class,old)).isZero();
        assertThat(jdbc.queryForObject("SELECT copied_from_role_code FROM app_role WHERE role_code=?",String.class,next)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id=?",String.class,member)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT detail FROM audit_log WHERE object_id=? AND action='unit_role_deleted'",String.class,old)).contains("copy_origin",sharedRole);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE object_id=? AND action='unit_delegation_updated' AND detail LIKE '%EXPLICIT%'",Integer.class,org)).isEqualTo(2);
        mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(previousToken))).andExpect(status().isUnauthorized());
        perform(put("/api/v1/organizations/"+org+"/delegation"),platform,body).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE owner_org_id=?",Integer.class,org)).isEqualTo(1);
    }

    @Test void retirementToExistingEquivalentRoleDoesNotChangeItsGrantsVersionOrOtherMembers() throws Exception {
        enable();String old=legacyCopy(),token=login(administrator,PASSWORD,true);
        ObjectNode definition=node().put("name","已存在正式角色");
        definition.set("permissions",delegation(1,"OP",true).path("permissions"));
        definition.set("actions",delegation(1,"OP",true).path("actions"));
        String target=data(perform(post("/api/v1/unit-admin/roles"),token,definition).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("role_code").asText();
        JsonNode unaffected=data(perform(post("/api/v1/unit-admin/users"),token,userPayload(target)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String unaffectedId=unaffected.path("user_id").asText(),unaffectedAccount=unaffected.path("account").asText();
        // First-login reset is irrelevant to this authorization equivalence fixture.
        jdbc.update("UPDATE app_user SET must_change_password=false WHERE user_id=?",unaffectedId);sql.clearCache();
        JsonNode beforeAccess=authState(unaffectedAccount);
        var targetBefore=jdbc.queryForMap("SELECT * FROM app_role WHERE role_code=?",target);
        var targetGrants=jdbc.queryForList("SELECT * FROM app_role_permission WHERE role_code=? ORDER BY permission_code",target);
        ObjectNode body=delegation(1,"OP",true);
        body.putArray("retire_role_codes").add(old);
        body.putArray("member_assignments").add(node().put("user_id",administrator).put("role_code",target))
                .add(node().put("user_id",member).put("role_code",target));
        apply(org,body,preview(org,body));
        assertThat(jdbc.queryForMap("SELECT * FROM app_role WHERE role_code=?",target)).isEqualTo(targetBefore);
        assertThat(jdbc.queryForList("SELECT * FROM app_role_permission WHERE role_code=? ORDER BY permission_code",target)).isEqualTo(targetGrants);
        assertThat(userRole(administrator)).isEqualTo(target);
        assertThat(userRole(unaffectedId)).isEqualTo(target);
        JsonNode afterAccess=authState(unaffectedAccount);
        assertThat(afterAccess.path("permission_codes")).isEqualTo(beforeAccess.path("permission_codes"));
        assertThat(afterAccess.path("menu_keys")).isEqualTo(beforeAccess.path("menu_keys"));
    }

    @Test void equivalentRetirementComparesEffectivePrivilegesInsteadOfLegacyRawGrantRows() throws Exception {
        enable();String old=legacyCopy();
        jdbc.update("UPDATE app_role_permission SET permission_level='AUTH',menu_enabled=true WHERE role_code=? AND permission_code='statistics'",old);
        jdbc.update("UPDATE app_role_permission SET permission_level='READ' WHERE role_code=? AND permission_code='alarm:read'",old);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'disposal:request','OP',false)",old);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'disposal:direct','READ',false)",old);
        sql.clearCache();
        JsonNode before=authState(member);
        assertThat(before.path("permission_codes").toString()).doesNotContain("statistics.read","disposal:request","disposal:direct");
        ObjectNode body=replacement(old);
        apply(org,body,preview(org,body));
        JsonNode after=authState(member);
        assertThat(after.path("permission_codes")).isEqualTo(before.path("permission_codes"));
        assertThat(after.path("menu_keys")).isEqualTo(before.path("menu_keys"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE role_code=?",Integer.class,old)).isZero();
        assertThat(jdbc.queryForObject("SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code='statistics'",String.class,userRole(member))).isEqualTo("NONE");
    }

    @Test void differingMenuLevelOrActionCannotRetireCopyAndDoesNotPartiallyCreateRole() throws Exception {
        enable();String old=legacyCopy();
        for(String difference:new String[]{"level","menu","action"}) {
            ObjectNode body=replacement(old);
            ObjectNode definition=(ObjectNode)body.path("new_roles").get(0);
            if("level".equals(difference)) ((ObjectNode)definition.path("permissions").get(0)).put("level","READ");
            else if("menu".equals(difference)) ((ObjectNode)definition.path("permissions").get(0)).put("menu_enabled",false);
            else definition.putArray("actions");
            perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,body)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ROLE_REPLACEMENT_PERMISSION_MISMATCH"));
            assertThat(userRole(administrator)).isEqualTo(old);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE owner_org_id=?",Integer.class,org)).isEqualTo(1);
        }
    }

    @Test void retirementCannotHidePermissionLossThroughSimultaneousCeilingChangeOrDisabledRole() throws Exception {
        enable();String old=legacyCopy();
        ObjectNode replacementAdministrator=replacement(old).put("administrator_user_id",member);
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,replacementAdministrator)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ROLE_RETIREMENT_AUTHORIZATION_CHANGE"));
        assertThat(jdbc.queryForObject("SELECT administrator_user_id FROM app_org_delegation WHERE org_id=?",String.class,org)).isEqualTo(administrator);
        ObjectNode body=replacement(old);
        ((ObjectNode)body.path("permissions").get(0)).put("level","READ");
        ((ObjectNode)body.path("new_roles").get(0).path("permissions").get(0)).put("level","READ");
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,body)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ROLE_RETIREMENT_AUTHORIZATION_CHANGE"));
        jdbc.update("UPDATE app_role SET enabled=false WHERE role_code=?",old);sql.clearCache();
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,replacement(old))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ROLE_REPLACEMENT_PERMISSION_MISMATCH"));
    }

    @Test void staleReplacementPreviewOrChangedRequestCannotMovePeopleOrDeleteCopy() throws Exception {
        enable();String old=legacyCopy();ObjectNode body=replacement(old);
        JsonNode p=preview(org,body);body.put("preview_token",p.path("preview_token").asText());
        ((ObjectNode)body.path("new_roles").get(0)).put("description","预览后修改定义");
        perform(put("/api/v1/organizations/"+org+"/delegation"),platform,body).andExpect(status().isConflict());
        p=preview(org,body);body.put("preview_token",p.path("preview_token").asText());
        jdbc.update("UPDATE app_role SET version=version+1 WHERE role_code=?",old);sql.clearCache();
        perform(put("/api/v1/organizations/"+org+"/delegation"),platform,body).andExpect(status().isConflict());
        assertThat(userRole(administrator)).isEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE owner_org_id=?",Integer.class,org)).isEqualTo(1);
    }

    @Test void foreignOrProtectedMemberAssignmentsAndUnprovenRetirementAreRejected() throws Exception {
        ObjectNode initial=delegation(0,"OP",true);explicitRoles(org,initial);
        initial.withArray("member_assignments").add(node().put("user_id",outsider).put("new_role_key","manual-duty"));
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,initial)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_ROLE_ASSIGNMENT"));
        enable();String code=userRole(administrator);
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,replacement(code))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ROLE_COPY_RETIREMENT_INVALID"));
        ObjectNode foreign=delegation(1,"OP",true);
        foreign.putArray("member_assignments").add(node().put("user_id",member).put("role_code","ROLE-ADMIN"));
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,foreign).andExpect(status().isNotFound());
    }

    @Test void historicalRegistrationReferenceKeepsCopyAndRegistrationIntact() throws Exception {
        enable();String old=legacyCopy(),change=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO access_change_request(change_id,change_type,subject_type,subject_id,requester_id,
                  before_snapshot,after_snapshot,reason,subject_version,status,requested_at,version)
                VALUES(?,'USER_CREATE','USER',?,?,'{}','{}','历史申请',0,'REJECTED',0,0)
                """,change,"old-subject-"+change.substring(0,8),administrator);
        jdbc.update("""
                INSERT INTO pending_user_registration(change_id,account,name,org_id,role_code,scope_mode,scope_grants,password_hash)
                VALUES(?,?,?, ?,?,'ASSIGNED','[]','test-only-hash')
                """,change,"old-"+change.substring(0,8),"历史申请人",org,old);
        perform(post("/api/v1/organizations/"+org+"/delegation/preview"),platform,replacement(old))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_IN_USE"));
        assertThat(jdbc.queryForObject("SELECT role_code FROM pending_user_registration WHERE change_id=?",String.class,change)).isEqualTo(old);
        assertThat(userRole(administrator)).isEqualTo(old);
    }

    private String legacyCopy() {
        String code=userRole(administrator);
        jdbc.update("UPDATE app_role SET copied_from_role_code=?,copied_from_role_name=?,copied_at=created_at WHERE role_code=?",
                sharedRole,"历史全局值班角色",code);sql.clearCache();return code;
    }
    private JsonNode authState(String account) throws Exception {
        return data(mvc.perform(get("/api/v1/auth/me").header("Authorization",bearer(login(account,PASSWORD,false))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private ObjectNode replacement(String old) {
        ObjectNode body=delegation(1,"OP",true);
        var definition=node().put("client_key","formal").put("name","人工正式值班角色");
        definition.set("permissions",body.path("permissions").deepCopy());definition.set("actions",body.path("actions").deepCopy());
        body.putArray("new_roles").add(definition);
        body.putArray("member_assignments").add(node().put("user_id",administrator).put("new_role_key","formal"))
                .add(node().put("user_id",member).put("new_role_key","formal"));
        body.putArray("retire_role_codes").add(old);return body;
    }

    protected void enable() throws Exception { ObjectNode body=delegation(0,"OP",true); apply(org,body,preview(org,body)); }
    protected ObjectNode delegation(int version,String level,boolean verify) {
        ObjectNode body=node().put("administrator_user_id",administrator).put("expected_version",version);
        body.putArray("permissions").add(node().put("permission_code","alarms").put("level",level).put("menu_enabled",true))
                .add(node().put("permission_code","flights").put("level","READ").put("menu_enabled",true));
        var actions=body.putArray("actions");
        actions.add(node().put("permission_code","alarm:read").put("level","OP"));
        actions.add(node().put("permission_code","flight:read").put("level","OP"));
        if(verify) actions.add(node().put("permission_code","alarm:verify").put("level","OP"));
        return body;
    }
    private ObjectNode rolePayload(String name,String level) {
        ObjectNode body=node().put("name",name).put("description","独立单位角色");
        body.putArray("permissions").add(node().put("permission_code","alarms").put("level",level).put("menu_enabled",true));
        body.putArray("actions").add(node().put("permission_code","alarm:read").put("level","OP"));
        return body;
    }
    private JsonNode createdRole(String token,String name,String level) throws Exception {
        ObjectNode body=rolePayload(name,level);
        if("OP".equals(level)) body.withArray("actions").add(node().put("permission_code","alarm:verify").put("level","OP"));
        return data(perform(post("/api/v1/unit-admin/roles"),token,body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    private ObjectNode userPayload(String role) {
        return node().put("account","new-"+UUID.randomUUID().toString().substring(0,8)).put("name","新人员").put("org_id",org)
                .put("role_code",role).put("user_type","FRONTEND").put("data_scope","OWN_ORG").put("temporary_password",PASSWORD);
    }
    protected JsonNode preview(String id,ObjectNode body) throws Exception {
        if(body.path("expected_version").asInt()==0&&!body.has("member_assignments")) explicitRoles(id,body);
        return data(perform(post("/api/v1/organizations/"+id+"/delegation/preview"),platform,body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
    /** Test requests explicitly define their fixture role and every assignment; production never copies a source role. */
    private void explicitRoles(String id,ObjectNode body) {
        var role=node().put("client_key","manual-duty").put("name","本单位值班角色").put("description","测试明确配置的人员角色");
        role.set("permissions",body.path("permissions").deepCopy());role.set("actions",body.path("actions").deepCopy());
        body.putArray("new_roles").add(role);
        var assignments=body.putArray("member_assignments");
        jdbc.queryForList("SELECT user_id FROM app_user WHERE org_id=? AND status<>'DELETED' AND role_code NOT IN ('ROLE-ADMIN','ROLE-BACKEND','ROLE-AUTOMATION') ORDER BY user_id",String.class,id)
                .forEach(user->assignments.add(node().put("user_id",user).put("new_role_key","manual-duty")));
    }
    private void apply(String id,ObjectNode body,JsonNode preview) throws Exception {
        body.put("preview_token",preview.path("preview_token").asText());
        perform(put("/api/v1/organizations/"+id+"/delegation"),platform,body).andExpect(status().isOk());
        sql.clearCache();
    }
    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder builder,String token,ObjectNode body) throws Exception {
        return mvc.perform(builder.header("Authorization",bearer(token)).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }
    private String userRole(String id) { return jdbc.queryForObject("SELECT role_code FROM app_user WHERE user_id=?",String.class,id); }
    private int userVersion(String id) { return jdbc.queryForObject("SELECT version FROM app_user WHERE user_id=?",Integer.class,id); }
    private ObjectNode node() { return json.createObjectNode(); }
    private JsonNode data(String body) throws Exception { return json.readTree(body).path("data"); }
    private static String bearer(String token) { return "Bearer "+token; }
    protected String login(String account,String password,boolean backend) throws Exception {
        return data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(node().put("account",account).put("password",password).put("client_type",backend?"BACKEND":"FRONTEND").toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("session_id").asText();
    }
}
