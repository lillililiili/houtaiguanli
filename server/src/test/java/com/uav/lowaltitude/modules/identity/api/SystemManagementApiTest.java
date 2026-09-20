package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemManagementApiTest {

    private static final String TEMP_PASSWORD = "TempUser#2026A";
    private static final String NEW_PASSWORD = "Changed#2026UserA";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanCreatedFixtures() {
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'itest-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'itest-%')");
        jdbc.update("delete from app_user where account like 'itest-%'");
        jdbc.update("delete from app_role_permission where role_code in (select role_code from app_role where name like '集成测试角色%')");
        jdbc.update("delete from app_role where name like '集成测试角色%'");
    }

    @Test
    void freshSeedContainsOnlyOneBuiltinSuperAdmin() {
        assertThat(jdbc.queryForObject("select count(*) from app_role where builtin=true", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from app_role where role_code='ROLE-ADMIN'", String.class))
                .isEqualTo("超级管理员");
        assertThat(jdbc.queryForObject("select count(*) from app_user where role_code='ROLE-ADMIN'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user where account='admin1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_user where account in ('admin2','duty1','judge1','auth1','auth2','ops1','audit1')", Integer.class))
                .isZero();
    }

    @Test
    void permissionCatalogMatchesCurrentMenus() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode catalog = data(mvc.perform(get("/api/v1/permissions/catalog").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> visible = new ArrayList<>();
        for (JsonNode item : catalog) {
            String routeKey = item.path("route_key").asText(null);
            if (routeKey == null || routeKey.isBlank()) continue;
            visible.add(item.path("permission_code").asText() + ":" + item.path("module_name").asText() + ":" + routeKey);
        }
        assertThat(visible).containsExactly(
                "dashboard:数据大屏:bigscreen",
                "sensing:感知监测:situation",
                "flights:飞行监管:flights",
                "legality:飞行监管:legality",
                // 阶段 9（迁移 060）曾给既有 MODULE 行 airspace / risk 补 route_key 成为真实菜单，
                // 迁移 V202609070010 撤回为别名（route_key 为空，用户 2026-09-07 裁定），因此不再出现在可见菜单里。
                "alarms:事件处置:alarms",
                "punishment:事件处置:punish",
                "statistics:分析报告:stats",
                "evidence:分析报告:evidence",
                "devices:运维管理:devices",
                "monitoring:运维管理:monitor",
                "commissioning:运维管理:commission",
                "maps:运维管理:maps",
                "organizations:系统管理:organizations",
                "notificationSettings:系统管理:notificationSettings",
                "responsePlans:系统管理:responsePlans",
                "users:系统管理:users",
                "roles:系统管理:roles",
                "audit:系统管理:archive");
    }

    @Test
    void customRoleAndUserTakeEffectImmediatelyAndPermissionChangeRevokesSession() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode createdRole = createRole(admin, "READ");
        String roleCode = createdRole.path("role_code").asText();
        String orgId = jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'", String.class);
        String account = "itest-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode createUser = json.createObjectNode();
        createUser.put("account", account).put("name", "集成测试用户").put("phone", "13800000000")
                .put("org_id", orgId).put("role_code", roleCode)
                .put("temporary_password", "Ab1#" + "x".repeat(29));
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "create-user-too-long-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(createUser)))
                .andExpect(status().isBadRequest());
        createUser.put("temporary_password", TEMP_PASSWORD);
        String userBody = mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "create-user-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(createUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account").value(account))
                .andExpect(jsonPath("$.data.must_change_password").value(true))
                .andExpect(jsonPath("$.data.scope_mode").doesNotExist())
                .andExpect(jsonPath("$.data.scope_grants").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        JsonNode user = data(userBody);

        String firstSession = login(account, TEMP_PASSWORD);
        mvc.perform(get("/api/v1/devices").header("Authorization", bearer(firstSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(firstSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"" + TEMP_PASSWORD + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(firstSession)))
                .andExpect(status().isUnauthorized());

        String userSession = login(account, NEW_PASSWORD);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(userSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menu_keys[?(@ == 'devices')]").exists())
                .andExpect(jsonPath("$.data.menu_keys[?(@ == 'users')]").doesNotExist())
                .andExpect(jsonPath("$.data.permission_codes[?(@ == 'devices.read')]").exists())
                .andExpect(jsonPath("$.data.scope_mode").value("ALL"));
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden());

        JsonNode role = data(mvc.perform(get("/api/v1/roles/{code}", roleCode)
                        .header("Authorization", bearer(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        ObjectNode update = permissionPayload(role.path("permissions"), "OP");
        update.put("expected_version", role.path("version").asInt());
        mvc.perform(put("/api/v1/roles/{code}/permissions", roleCode)
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "role-update-" + roleCode)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[?(@.permission_code == 'devices')].level").value("OP"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(userSession)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action='role_permissions_updated' and object_id=? and detail like ?",
                Integer.class, roleCode, "%超级管理员直接调整角色权限%")).isEqualTo(1);

        assertThat(jdbc.queryForObject("select count(*) from audit_log where detail like ?", Integer.class,
                "%" + TEMP_PASSWORD + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='user_created' and object_id=? and detail like ?", Integer.class,
                user.path("user_id").asText(), "%超级管理员直接创建用户%")).isEqualTo(1);
        assertThat(user.path("role_code").asText()).isEqualTo(roleCode);
        assertThat(jdbc.queryForObject("select scope_mode from app_user where account=?", String.class, account))
                .isEqualTo("ALL");
    }

    /**
     * 决策 18-13：审计日志的读与导出可以授给非超管角色——审计员按"只读 + 审计日志导出"定义，
     * 看不到审计日志这个角色就不成立。18-10 之前只改了初始化器那一半，授权接口这一半还挡着，
     * 于是管理员在角色矩阵里改不动，演示审计员角色整组提交还会被整次拒绝。
     */
    @Test
    void customRoleCanBeGrantedAuditReadAndItsMenu() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode role = createRole(admin, "READ");
        String roleCode = role.path("role_code").asText();

        ObjectNode payload = permissionPayload(role.path("permissions"), "READ");
        for (JsonNode item : payload.withArray("permissions")) {
            if ("audit".equals(item.path("permission_code").asText())) {
                ((ObjectNode) item).put("level", "OP").put("menu_enabled", true);
            }
        }
        payload.put("expected_version", role.path("version").asInt()).put("reason", "审计员需要看审计日志");
        mvc.perform(put("/api/v1/roles/{code}/permissions", roleCode)
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "audit-" + roleCode)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select permission_level from app_role_permission"
                + " where role_code=? and permission_code='audit'", String.class, roleCode)).isEqualTo("OP");
        assertThat(jdbc.queryForObject("select menu_enabled from app_role_permission"
                + " where role_code=? and permission_code='audit'", Boolean.class, roleCode)).isTrue();
    }

    @Test
    void customRoleCannotReceiveProtectedPermissionsAndSuperAdminCannotBeChanged() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode role = createRole(admin, "READ");
        String roleCode = role.path("role_code").asText();

        ObjectNode forbidden = permissionPayload(role.path("permissions"), "READ");
        for (JsonNode item : forbidden.withArray("permissions")) {
            // 原来这里试的是 audit——决策 18-13 之后审计已经可以授给自定义角色了，
            // 继续拿它当反例就等于把放开的那条又钉死。改用仍然只归超管的反制/干扰。
            if ("countermeasure".equals(item.path("permission_code").asText())) {
                ((ObjectNode) item).put("level", "READ").put("menu_enabled", true);
            }
        }
        forbidden.put("expected_version", role.path("version").asInt()).put("reason", "尝试越权");
        mvc.perform(put("/api/v1/roles/{code}/permissions", roleCode)
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "protected-" + roleCode)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(forbidden)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SYSTEM_PERMISSION_PROTECTED"));

        String adminId = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        int adminVersion = jdbc.queryForObject("select version from app_user where account='admin1'", Integer.class);
        mvc.perform(put("/api/v1/users/{id}/status", adminId).header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "disable-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"expected_version\":" + adminVersion + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SUPER_ADMIN_PROTECTED"));

        ObjectNode access = json.createObjectNode().put("role_code", roleCode)
                .put("expected_version", adminVersion).put("reason", "尝试转移管理员");
        mvc.perform(put("/api/v1/users/{id}/access", adminId).header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "move-admin")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(access)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SUPER_ADMIN_PROTECTED"));

        ObjectNode adminPermissions = permissionPayload(role.path("permissions"), "READ");
        adminPermissions.put("expected_version", 0).put("reason", "尝试修改管理员权限");
        mvc.perform(put("/api/v1/roles/ROLE-ADMIN/permissions").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "change-admin-role")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(adminPermissions)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUILTIN_ROLE_PROTECTED"));
    }

    @Test
    void userAccessIsImmediateRoleDeletionChecksOccupancyAndVersions() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode roleA = createRole(admin, "READ");
        JsonNode roleB = createRole(admin, "NONE");
        String account = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String orgId = jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'", String.class);
        ObjectNode create = json.createObjectNode().put("account", account).put("name", "权限调整用户")
                .put("org_id", orgId).put("role_code", roleA.path("role_code").asText())
                .put("temporary_password", TEMP_PASSWORD);
        JsonNode user = data(mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "user-access-create-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        mvc.perform(delete("/api/v1/roles/{code}", roleA.path("role_code").asText())
                        .param("expected_version", roleA.path("version").asText())
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "delete-in-use")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"验证占用保护\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_IN_USE"));

        ObjectNode access = json.createObjectNode().put("role_code", roleB.path("role_code").asText())
                .put("expected_version", user.path("version").asInt())
                .put("reason", "即时调整角色");
        mvc.perform(put("/api/v1/users/{id}/access", user.path("user_id").asText())
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "access-now-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(access)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role_code").value(roleB.path("role_code").asText()));

        mvc.perform(delete("/api/v1/roles/{code}", roleA.path("role_code").asText())
                        .param("expected_version", "999")
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "delete-stale")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"验证版本冲突\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(delete("/api/v1/roles/{code}", roleA.path("role_code").asText())
                        .param("expected_version", roleA.path("version").asText())
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "delete-free")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"验证直接删除\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logicalUserDeletionRevokesSessionHidesAccountAndReleasesRole() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode role = createRole(admin, "READ");
        String roleCode = role.path("role_code").asText();
        String account = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String orgId = jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'", String.class);
        ObjectNode create = json.createObjectNode().put("account", account).put("name", "待删除用户")
                .put("org_id", orgId).put("role_code", roleCode).put("scope_mode", "NONE")
                .put("temporary_password", TEMP_PASSWORD);
        create.putArray("scope_grants");
        JsonNode user = data(mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "soft-delete-create-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String userSession = login(account, TEMP_PASSWORD);

        mvc.perform(delete("/api/v1/users/{id}", user.path("user_id").asText())
                        .param("expected_version", "999")
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "soft-delete-stale-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"验证删除版本冲突\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        String adminId = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        int adminVersion = jdbc.queryForObject("select version from app_user where account='admin1'", Integer.class);
        mvc.perform(delete("/api/v1/users/{id}", adminId).param("expected_version", String.valueOf(adminVersion))
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "soft-delete-admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"尝试删除超级管理员\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("SUPER_ADMIN_PROTECTED"));

        mvc.perform(delete("/api/v1/users/{id}", user.path("user_id").asText())
                        .param("expected_version", user.path("version").asText())
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "soft-delete-ok-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"人员账号不再使用\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(userSession)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(admin)).param("keyword", account))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        assertThat(jdbc.queryForObject("select status from app_user where deleted_account=?", String.class, account))
                .isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select role_code from app_user where deleted_account=?", String.class, account))
                .isNull();
        assertThat(jdbc.queryForObject("select deleted_role_code from app_user where deleted_account=?", String.class, account))
                .isEqualTo(roleCode);
        assertThat(jdbc.queryForObject("select account from app_user where deleted_account=?", String.class, account))
                .contains("~deleted~");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where account='admin1' and action='user_deleted' and object_id=?", Integer.class,
                user.path("user_id").asText())).isEqualTo(1);

        mvc.perform(delete("/api/v1/roles/{code}", roleCode)
                        .param("expected_version", role.path("version").asText())
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "delete-released-role-" + roleCode)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"逻辑删除用户后清理空角色\"}"))
                .andExpect(status().isOk());

        JsonNode replacementRole = createRole(admin, "NONE");
        create.put("role_code", replacementRole.path("role_code").asText());
        JsonNode replacement = data(mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "re-register-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.account").value(account))
                .andReturn().getResponse().getContentAsString());
        assertThat(replacement.path("user_id").asText()).isNotEqualTo(user.path("user_id").asText());
    }

    @Test
    void numericTemporaryPasswordIsRejectedWithPolicyError() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode role = createRole(admin, "READ");
        String account = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String orgId = jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'", String.class);
        ObjectNode create = json.createObjectNode().put("account", account).put("name", "弱密码用户")
                .put("org_id", orgId).put("role_code", role.path("role_code").asText())
                .put("temporary_password", "12345678");
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "weak-password-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_POLICY_VIOLATION"))
                .andExpect(jsonPath("$.ok").value(false));
        assertThat(jdbc.queryForObject("select count(*) from app_user where account=?", Integer.class, account)).isZero();
    }

    @Test
    void profileUpdateCanChangeRoleWithoutReason() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode roleA = createRole(admin, "READ");
        JsonNode roleB = createRole(admin, "NONE");
        String account = "itest-" + UUID.randomUUID().toString().substring(0, 8);
        String orgId = jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'", String.class);
        ObjectNode create = json.createObjectNode().put("account", account).put("name", "资料角色用户")
                .put("org_id", orgId).put("role_code", roleA.path("role_code").asText())
                .put("temporary_password", TEMP_PASSWORD);
        JsonNode user = data(mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "profile-role-create-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(create)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String userSession = login(account, TEMP_PASSWORD);

        ObjectNode updated = json.createObjectNode().put("name", "已改名").put("org_id", orgId)
                .put("role_code", roleB.path("role_code").asText())
                .put("expected_version", user.path("version").asInt());
        mvc.perform(patch("/api/v1/users/{id}", user.path("user_id").asText())
                        .header("Authorization", bearer(admin)).header("Idempotency-Key", "profile-role-ok-" + account)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("已改名"))
                .andExpect(jsonPath("$.data.role_code").value(roleB.path("role_code").asText()));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(userSession)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='user_access_updated' and object_id=? and detail like ?",
                Integer.class, user.path("user_id").asText(), "%超级管理员直接调整用户角色%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='user_profile_updated' and object_id=?",
                Integer.class, user.path("user_id").asText())).isEqualTo(1);
    }

    @Test
    void retiredApprovalEndpointsAlwaysReturn410() throws Exception {
        String admin = login("admin1", "changeme");
        mvc.perform(post("/api/v1/users/creation-requests").header("Authorization", bearer(admin)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("APPROVAL_FLOW_REMOVED"));
        mvc.perform(post("/api/v1/users/any/access-change-requests").header("Authorization", bearer(admin)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("APPROVAL_FLOW_REMOVED"));
        mvc.perform(post("/api/v1/roles/any/access-change-requests").header("Authorization", bearer(admin)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("APPROVAL_FLOW_REMOVED"));
        mvc.perform(post("/api/v1/roles/any/deletion-requests").header("Authorization", bearer(admin)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("APPROVAL_FLOW_REMOVED"));
        mvc.perform(get("/api/v1/access-change-requests").header("Authorization", bearer(admin)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("APPROVAL_FLOW_REMOVED"));
        mvc.perform(post("/api/v1/access-change-requests/any/approve").header("Authorization", bearer(admin)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("APPROVAL_FLOW_REMOVED"));
        mvc.perform(post("/api/v1/access-change-requests/any/reject").header("Authorization", bearer(admin)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error.code").value("APPROVAL_FLOW_REMOVED"));
    }

    private JsonNode createRole(String token, String deviceLevel) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode catalog = data(mvc.perform(get("/api/v1/permissions/catalog").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ObjectNode request = permissionPayload(catalog, deviceLevel);
        request.put("name", "集成测试角色-" + suffix).put("description", "自动回归测试")
                .put("reason", "验证角色直接创建");
        String body = mvc.perform(post("/api/v1/roles").header("Authorization", bearer(token))
                        .header("Idempotency-Key", "create-role-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.builtin").value(false))
                .andReturn().getResponse().getContentAsString();
        return data(body);
    }

    private ObjectNode permissionPayload(JsonNode source, String deviceLevel) {
        ObjectNode body = json.createObjectNode();
        ArrayNode permissions = body.putArray("permissions");
        for (JsonNode item : source) {
            String code = item.path("permission_code").asText();
            String level = "devices".equals(code) ? deviceLevel : "NONE";
            permissions.addObject().put("permission_code", code).put("level", level)
                    .put("menu_enabled", "devices".equals(code) && !"NONE".equals(level));
        }
        return body;
    }

    private String login(String account, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("account", account).put("password", password).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return data(body).path("session_id").asText();
    }

    private JsonNode data(String body) throws Exception { return json.readTree(body).path("data"); }
    private static String bearer(String token) { return "Bearer " + token; }
}
