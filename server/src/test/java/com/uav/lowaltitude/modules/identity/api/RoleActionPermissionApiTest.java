package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 角色的**动作权限**（决策 15-1 / 15-2）。
 *
 * 这一块的意义在于：阶段 13/14 把处置与处罚的动作权限登记进了目录，但没有任何界面能把它们授给人，
 * 于是除超级管理员外没人能用——功能上线了却没人够得着。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleActionPermissionApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.uav.lowaltitude.modules.identity.application.SuperAdminIntegrityInitializer superAdminInitializer;

    @AfterEach
    void cleanup() {
        // 严格按"先引用方、后被引用方"删。这里的外键都是 RESTRICT：
        // disposal_authorization_event.actor_id 与 disposal_authorization.requested_by/approved_by 指向 app_user，
        // disposal_authorization 又被事件引用，uav_event 引用 alarm。次序错了会在 @AfterEach 撞 23503——
        // 而清理失败的表现是"下一个用例莫名其妙地错"，排查会一路查到产品代码上去。
        jdbc.update("delete from disposal_authorization_event where authorization_id like 'act-auth-%'");
        jdbc.update("delete from disposal_authorization where authorization_id like 'act-auth-%'");
        jdbc.update("delete from uav_event where event_id like 'act-event-%'");
        jdbc.update("delete from alarm where alarm_id like 'act-alarm-%'");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'act-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'act-%')");
        jdbc.update("delete from app_user where account like 'act-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-ACT-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-ACT-%'");
        jdbc.update("delete from app_permission where permission_code='audit:purge'");
    }

    /* ---- 目录 ---- */

    @Test
    void actionCatalogIsGroupedByModuleAndCarriesReadableNames() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode data = data(mvc.perform(get("/api/v1/permissions/actions").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));
        assertThat(data).isNotEmpty();
        boolean sawDisposal = false;
        for (JsonNode module : data) {
            assertThat(module.path("module_code").asText()).isNotBlank();
            // 模块名要能上屏：只给 module_code 的话页面只能显示 disposal/punishment 这种码。
            assertThat(module.path("module_name").asText()).isNotBlank();
            assertThat(module.path("actions")).isNotEmpty();
            for (JsonNode action : module.path("actions")) {
                assertThat(action.path("permission_code").asText()).contains(":");
                assertThat(action.path("action_code").asText()).isNotBlank();
                assertThat(action.path("name").asText()).isNotBlank();
            }
            if ("disposal".equals(module.path("module_code").asText())) sawDisposal = true;
        }
        // 阶段 13 登记的处置动作必须出现，否则处置授权仍然只有超管能用。
        assertThat(sawDisposal).as("处置动作应出现在目录里").isTrue();
    }

    /**
     * 决策 15-24：目录行的 module_name/name 原来是 'Stage 13 disposal approve' / 'Approve or reject ...'
     * 这类英文开发描述，而这个接口就是角色权限编辑页的数据源——不翻译就是让一线人员对着英文勾权限。
     * 逐字对到前端 labels.js 那份字典：同一个码在全站只能有一个中文说法。
     */
    @Test
    void actionCatalogNamesAreChinese() throws Exception {
        String admin = login("admin1", "changeme");
        JsonNode data = data(mvc.perform(get("/api/v1/permissions/actions").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));
        int actions = 0;
        String disposalModuleName = null, approveName = null;
        for (JsonNode module : data) {
            assertThat(module.path("module_name").asText())
                    .as("模块 %s 的名字仍是英文", module.path("module_code").asText())
                    .matches(".*[\\u4e00-\\u9fa5].*");
            if ("disposal".equals(module.path("module_code").asText())) {
                disposalModuleName = module.path("module_name").asText();
            }
            for (JsonNode action : module.path("actions")) {
                actions++;
                assertThat(action.path("name").asText())
                        .as("动作 %s 的名字仍是英文", action.path("permission_code").asText())
                        .matches(".*[\\u4e00-\\u9fa5].*");
                if ("disposal:approve".equals(action.path("permission_code").asText())) {
                    approveName = action.path("name").asText();
                }
            }
        }
        // 只断言"含中文"会被一句半中半英的描述蒙混过去，所以再钉死一对具体措辞。
        assertThat(disposalModuleName).isEqualTo("处置授权");
        assertThat(approveName).isEqualTo("审批处置");
        // 目录动作数与 PermissionCode 枚举一致：撤除动作（如 F8 的 flight:authorize）时两边一起减，不再钉死数字。
        assertThat(actions).as("目录里的动作数").isEqualTo(com.uav.lowaltitude.modules.identity.domain.PermissionCode.values().length);
    }

    /**
     * 决策 15-25：动作权限只有 READ/OP 两级，AUTH 是模块矩阵才有的"可授权他人"。
     * 一条 AUTH 动作行意味着某个角色拿到了矩阵那边校验不到的等级。
     */
    @Test
    void noActionRowIsGrantedAtAuthLevel() throws Exception {
        Integer authRows = jdbc.queryForObject("""
                select count(*) from app_role_permission rp
                  join app_permission p on p.permission_code = rp.permission_code
                 where p.permission_kind = 'ACTION' and rp.permission_level = 'AUTH'
                """, Integer.class);
        assertThat(authRows).as("等级为 AUTH 的动作行").isZero();

        // 上面那条只说"现在没有"。真正拦住它的是入口校验，所以顺带证明入口会拒。
        String admin = login("admin1", "changeme");
        String roleCode = customRole();
        putPermissions(admin, roleCode, new ObjectNode[] { entry("disposal:approve", "AUTH") })
                .andExpect(status().isBadRequest());
    }

    /**
     * 决策 15-25 修订：`SuperAdminIntegrityInitializer` 每次启动都把 ROLE-ADMIN 的授权行置成 AUTH，
     * 原先没有区分模块行与动作行。它 @Order(30) 跑在 `LocalStage2AccessSeeder` @Order(40) 之前，
     * 所以**首次**启动时动作行还不存在、什么也没抹到；种子插完 42 条 READ 动作行之后，**第二次**启动
     * 才把它们一并抹成 AUTH。
     *
     * 一个 Spring 测试上下文里两个 runner 只跑一遍，这个缺陷在 H2 上永远不会现形——真实库
     * `uav_stage10_verify` 上实测就是 42 行 AUTH 动作行。所以这条用例**手动再跑一次 initializer**，
     * 把"第二次启动"补出来；不这么做，测试再多也只覆盖第一次启动那一种形态。
     */
    @Test
    void rerunningTheSuperAdminInitializerLeavesActionRowsAtReadOrOp() throws Exception {
        String probe = "disposal:read";
        boolean inserted = jdbc.update("insert into app_role_permission (role_code,permission_code,"
                + "permission_level,menu_enabled,created_at) select 'ROLE-ADMIN',?,'READ',false,current_timestamp"
                + " where not exists (select 1 from app_role_permission"
                + " where role_code='ROLE-ADMIN' and permission_code=?)", probe, probe) == 1;
        try {
            // 前置必须成立，否则下面断言的是"一张空表里没有 AUTH"——永远绿，什么也没测。
            assertThat(adminActionRows()).as("ROLE-ADMIN 的动作行数").isPositive();

            superAdminInitializer.run(null);

            assertThat(jdbc.queryForObject("select permission_level from app_role_permission"
                    + " where role_code='ROLE-ADMIN' and permission_code=?", String.class, probe))
                    .as("再跑一次 initializer 之后 %s 的等级", probe).isEqualTo("READ");
            assertThat(jdbc.queryForObject("""
                    select count(*) from app_role_permission rp
                      join app_permission p on p.permission_code = rp.permission_code
                     where p.permission_kind = 'ACTION' and rp.permission_level = 'AUTH'
                    """, Integer.class)).as("再跑一次之后的 AUTH 动作行").isZero();
            // 反面：模块行仍要被置成 AUTH，别为了修动作行把超管的矩阵一起改坏了。
            assertThat(jdbc.queryForObject("""
                    select count(*) from app_role_permission rp
                      join app_permission p on p.permission_code = rp.permission_code
                     where p.permission_kind = 'MODULE' and rp.role_code = 'ROLE-ADMIN'
                       and rp.permission_level <> 'AUTH'
                    """, Integer.class)).as("超管非 AUTH 的模块行").isZero();
        } finally {
            if (inserted) {
                jdbc.update("delete from app_role_permission where role_code='ROLE-ADMIN' and permission_code=?",
                        probe);
            }
        }
    }

    private int adminActionRows() {
        Integer n = jdbc.queryForObject("""
                select count(*) from app_role_permission rp
                  join app_permission p on p.permission_code = rp.permission_code
                 where p.permission_kind = 'ACTION' and rp.role_code = 'ROLE-ADMIN'
                """, Integer.class);
        return n == null ? 0 : n;
    }

    /* ---- 授予后真的能用 ---- */

    @Test
    void grantingDisposalApproveLetsThatRoleApprove() throws Exception {
        String admin = login("admin1", "changeme");
        String roleCode = customRole();
        String[] user = userInRole(roleCode, "APR");
        String authorizationId = requestedAuthorization();

        // 授之前：动作权限缺失，一律 403。
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", authorizationId)
                        .header("Authorization", bearer(user[0])).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0}"))
                .andExpect(status().isForbidden());

        grantActions(admin, roleCode, entry("disposal:read", "READ"), entry("disposal:approve", "OP"));

        // 授权变更会作废旧会话（permission_version 递增），重新登录再试。
        String[] refreshed = userInRole(roleCode, "AP2");
        String refreshedAuthorization = requestedAuthorization();
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", refreshedAuthorization)
                        .header("Authorization", bearer(refreshed[0])).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void changingActionsInvalidatesExistingSessionsAndIsAudited() throws Exception {
        String admin = login("admin1", "changeme");
        String roleCode = customRole();
        String[] user = userInRole(roleCode, "SES");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(user[0]))).andExpect(status().isOk());

        grantActions(admin, roleCode, entry("disposal:read", "READ"));

        // 旧会话必须失效：权限变了却让人拿着旧令牌继续用，等于变更没有生效。
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(user[0])))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='role_permissions_updated'"
                + " and object_id=? and detail like '%actions_granted%'", Integer.class, roleCode)).isEqualTo(1);
    }

    @Test
    void omittingActionsLeavesExistingActionRowsAlone() throws Exception {
        String admin = login("admin1", "changeme");
        String roleCode = customRole();
        grantActions(admin, roleCode, entry("disposal:read", "READ"));
        assertThat(actionCount(roleCode)).isEqualTo(1);
        // 不带 actions 的提交只改模块矩阵：把它当成"清空动作"会让一次无关的矩阵调整悄悄收走别人的权限。
        putPermissions(admin, roleCode, null);
        assertThat(actionCount(roleCode)).isEqualTo(1);
    }

    @Test
    void noneLevelRemovesTheActionRow() throws Exception {
        String admin = login("admin1", "changeme");
        String roleCode = customRole();
        grantActions(admin, roleCode, entry("disposal:read", "READ"), entry("disposal:approve", "OP"));
        assertThat(actionCount(roleCode)).isEqualTo(2);
        grantActions(admin, roleCode, entry("disposal:read", "READ"), entry("disposal:approve", "NONE"));
        assertThat(actionCount(roleCode)).isEqualTo(1);
    }

    @Test
    void directCountermeasureAcceptsOnlyNoneOrOpAndDoesNotGrantOtherActions() throws Exception {
        String admin = login("admin1", "changeme");
        String roleCode = customRole();

        expectGrantError(admin, roleCode, "INVALID_PERMISSION_LEVEL", entry("disposal:direct", "READ"));
        putPermissions(admin, roleCode, new ObjectNode[]{entry("disposal:direct", "AUTH")})
                .andExpect(status().isBadRequest());
        grantActions(admin, roleCode, entry("disposal:direct", "OP"));

        assertThat(jdbc.queryForList("select permission_code from app_role_permission where role_code=?"
                + " and permission_code like 'disposal:%' order by permission_code", String.class, roleCode))
                .containsExactly("disposal:direct");
    }

    /* ---- 拒绝 ---- */

    @Test
    void adminRoleActionsAreLocked() throws Exception {
        String admin = login("admin1", "changeme");
        // 超级管理员的权限是固定全量：允许改它等于允许把自己锁在系统外。
        // 带上它真实的矩阵，否则会先被请求体校验挡下，测不到这条规则。
        putPermissions(admin, "ROLE-ADMIN", new ObjectNode[]{entry("disposal:read", "READ")})
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROLE_LOCKED"));
    }

    @Test
    void unknownActionCodeIsRejected() throws Exception {
        String admin = login("admin1", "changeme");
        String roleCode = customRole();
        expectGrantError(admin, roleCode, "INVALID_PERMISSION", entry("disposal:not-a-thing", "OP"));
        // 模块码不是动作码：拿它来授会绕过矩阵那套完整性校验。
        expectGrantError(admin, roleCode, "INVALID_PERMISSION", entry("devices", "OP"));
    }

    @Test
    void protectedDomainActionsCannotBeGrantedToCustomRoles() throws Exception {
        String admin = login("admin1", "changeme");
        String roleCode = customRole();
        // 造一条审计域的动作行：目前目录里没有，但这条保护必须先于它存在，否则谁加谁就顺手把它授出去了。
        jdbc.update("insert into app_permission (permission_code,module_name,route_key,sort_order,module_code,"
                + "permission_kind,action_code,name) values ('audit:purge','Audit purge',null,990,'audit','ACTION',"
                + "'purge','Purge audit log')");
        expectGrantError(admin, roleCode, "SYSTEM_PERMISSION_PROTECTED", entry("audit:purge", "OP"));
        // 全局切图会同时影响全部业务页面，即使同属 maps 域也不能把启用权下放给自定义角色。
        expectGrantError(admin, roleCode, "SYSTEM_PERMISSION_PROTECTED", entry("map:activate", "OP"));
    }

    @Test
    void roleDetailListsGrantedActions() throws Exception {
        String admin = login("admin1", "changeme");
        String roleCode = customRole();
        grantActions(admin, roleCode, entry("disposal:read", "READ"), entry("disposal:approve", "OP"));
        JsonNode role = data(mvc.perform(get("/api/v1/roles/{code}", roleCode).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));
        JsonNode actions = role.path("actions");
        assertThat(actions).hasSize(2);
        // 只列已授的行：把全目录都列出来、未授的标 NONE，会让页面分不清"没授"和"没这项"。
        for (JsonNode action : actions) {
            assertThat(action.path("permission_code").asText()).startsWith("disposal:");
            assertThat(action.path("level").asText()).isIn("READ", "OP");
        }
    }

    /* ---- 辅助 ---- */

    private ObjectNode entry(String code, String level) {
        ObjectNode node = json.createObjectNode();
        node.put("permission_code", code);
        node.put("level", level);
        return node;
    }

    private void grantActions(String admin, String roleCode, ObjectNode... actions) throws Exception {
        putPermissions(admin, roleCode, actions).andExpect(status().isOk());
    }

    private void expectGrantError(String admin, String roleCode, String code, ObjectNode... actions) throws Exception {
        putPermissions(admin, roleCode, actions)
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private org.springframework.test.web.servlet.ResultActions putPermissions(String admin, String roleCode,
            ObjectNode[] actions) throws Exception {
        JsonNode role = data(mvc.perform(get("/api/v1/roles/{code}", roleCode).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));
        ObjectNode body = json.createObjectNode();
        ArrayNode permissions = body.putArray("permissions");
        for (JsonNode permission : role.path("permissions")) {
            ObjectNode item = permissions.addObject();
            item.put("permission_code", permission.path("permission_code").asText());
            item.put("level", permission.path("level").asText());
            item.put("menu_enabled", permission.path("menu_enabled").asBoolean());
        }
        body.put("expected_version", role.path("version").asInt());
        body.put("reason", "集成测试：调整动作权限");
        if (actions != null) {
            ArrayNode array = body.putArray("actions");
            for (ObjectNode action : actions) array.add(action);
        }
        return mvc.perform(put("/api/v1/roles/{code}/permissions", roleCode)
                .header("Authorization", bearer(admin)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }


    private String customRole() {
        String roleCode = "ROLE-ACT-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,"
                + "system_role) values (?,?,'集成测试角色-动作权限',false,true,0,0,0,false)", roleCode, "集成测试角色" + roleCode);
        for (String module : jdbc.queryForList(
                "select permission_code from app_permission where permission_kind='MODULE'", String.class)) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,"
                    + "created_at) values (?,?,'NONE',false,current_timestamp)", roleCode, module);
        }
        return roleCode;
    }

    private String[] userInRole(String roleCode, String tag) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        // permission_version 存在 app_user 上，角色权限变更时按角色整体递增；会话记下签发当时的值，
        // 对不上就失效。新用户从 0 起，与它的会话一致即可。
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',"
                + "0,0,0,0)", userId, "act-" + tag.toLowerCase() + "-" + suffix, "动作" + tag, roleCode);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,'seed-stage3-org',"
                + "'seed-stage3-district')", userId);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,"
                + "'127.0.0.1',(select permission_version from app_user where user_id=?))",
                token, userId, System.currentTimeMillis() + 3_600_000, userId);
        return new String[]{token, userId};
    }

    /** 一条 REQUESTED 的处置授权，用来验证"授了 disposal:approve 之后真的能批"。 */
    private String requestedAuthorization() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alarmId = "act-alarm-" + suffix, eventId = "act-event-" + suffix, id = "act-auth-" + suffix;
        Timestamp at = Timestamp.from(Instant.parse("2026-09-08T04:00:00Z"));
        String admin = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,"
                + "updated_at,version) select 'act-src','ACT-TEST','动作权限测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='act-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'act-src',?,'UAV_INTRUSION','HIGH',?,?,'mock','seed-stage3-org',"
                + "'seed-stage3-district',?)", alarmId, "告警-动作-" + suffix, at, at, at);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,"
                + "updated_at,version) values (?,?,'CONFIRMED','seed-stage3-org','seed-stage3-district',?,?,1)",
                eventId, alarmId, at, at);
        jdbc.update("insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,"
                + "subject_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,"
                + "source_mode,version,created_at,updated_at) values (?,?,'COUNTERMEASURE','UAV_EVENT',?,'MANUAL',"
                + "'动作权限用例',?,?,'REQUESTED','demo-v1','seed-stage3-org','seed-stage3-district','mock',0,?,?)",
                id, "AUTH-20260908-" + (7000 + (int) (Math.random() * 999)), eventId, admin, at, at, at);
        return id;
    }

    private long actionCount(String roleCode) {
        return jdbc.queryForObject("select count(*) from app_role_permission p join app_permission a"
                + " on a.permission_code=p.permission_code where p.role_code=? and a.permission_kind='ACTION'",
                Long.class, roleCode);
    }

    private String login(String account, String password) throws Exception {
        String body = "{\"account\":\"" + account + "\",\"password\":\"" + password + "\"}";
        return data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())).path("session_id").asText();
    }

    private JsonNode data(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
