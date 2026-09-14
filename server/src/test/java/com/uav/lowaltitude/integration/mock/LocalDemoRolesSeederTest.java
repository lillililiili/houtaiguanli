package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 演示角色与演示账号（阶段 18）：把原版演示的五个角色、十二个账号搬回来。
 *
 * <p>断言站在**用户能看到什么**这一侧：菜单进得去、该有的动作在 `/auth/me` 里、没授的不在。
 * 只断授权行的话，授权行对了而菜单进不去照样是"功能在、人用不了"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalDemoRolesSeederTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LocalDemoRolesSeeder seeder;
    @Autowired com.uav.lowaltitude.modules.identity.application.SuperAdminIntegrityInitializer superAdminInitializer;

    @Test
    void allFourDemoRolesAndElevenDemoAccountsExist() {
        // 超级管理员不在这里造：系统强制"有且仅有一个"，那个位置由 admin1 占着（原版的 admin 就是它）。
        for (LocalDemoRolesSeeder.DemoRole role : LocalDemoRolesSeeder.ROLES) {
            assertThat(jdbc.queryForObject("select name from app_role where role_code=?", String.class, role.code()))
                    .as("角色 %s", role.code()).isEqualTo(role.name());
        }
        assertThat(jdbc.queryForObject("select count(*) from app_user where role_code like 'ROLE-DEMO-%'", Long.class))
                .as("演示账号数（含阶段 15 的演示复核员）").isGreaterThanOrEqualTo(11L);
        // 角色建成非内置：内置角色不可编辑不可删除，而演示要能在角色页上点开看权限矩阵。
        assertThat(jdbc.queryForObject("select count(*) from app_role where role_code like 'ROLE-DEMO-%'"
                + " and builtin=true", Long.class)).isZero();
    }

    @Test
    void theDisabledDemoAccountCannotLogIn() throws Exception {
        // 原版把唯一的停用样本落在周敏而不是唯一的审计员——否则"审计员只能看不能发"演示不到。
        assertThat(jdbc.queryForObject("select status from app_user where account='zhoumin'", String.class))
                .isEqualTo("DISABLED");
        assertThat(login("zhoumin")).as("停用账号不该能登录").isNull();
        assertThat(login("wugang")).as("审计员必须能登录，否则这一行权限演示不到").isNotNull();
    }

    /**
     * 决策 18-11：**每个菜单进去之后要读的东西都得给全**。
     *
     * 15-34 踩过一次：菜单给了、读动作没给——菜单点得开、一进去满屏 403，比根本没有那个菜单更像系统坏了。
     * 四个新角色又重演了一遍（助手实测 118 条里 59 红）。所以这里按同一张"菜单 → 该页要读什么"的表逐个断，
     * 谁将来加菜单而忘了配读动作，这条就红。
     */
    private static final java.util.Map<String, List<String>> MENU_NEEDS = java.util.Map.of(
            "situation", List.of("device:read", "target:read", "fusion:read", "airspace:read", "assessment:read"),
            "flights", List.of("flight:read", "route:read", "airspace:read", "risk:read"),
            // 告警页与工作台要显示这条告警的反制/干扰处置状态（18-12）。
            "alarms", List.of("alarm:read", "target:read", "evidence:read", "disposal:read"),
            "punish", List.of("handoff:read", "punishment:read", "disposal:read", "evidence:read"),
            "evidence", List.of("evidence:read"),
            "devices", List.of("device:read"),
            "monitor", List.of("device:read"),
            "commission", List.of("device:read"));

    @Test
    void everyMenuComesWithTheReadActionsThatPageNeeds() throws Exception {
        for (String account : List.of("zhangjg", "zhangwei", "zhaopeng", "wugang")) {
            JsonNode me = me(account);
            List<String> menus = menus(me), codes = codes(me);
            // 工作台固定可见，而外壳在**每个路由**上都拉工作台事项——少了它，人在任何一页都会看到那一栏报错。
            assertThat(codes).as("%s 的工作台读权限", account).contains("workbench:read");
            for (String menu : menus) {
                for (String needed : MENU_NEEDS.getOrDefault(menu, List.of())) {
                    assertThat(codes).as("%s 的 %s 这一页要的 %s", account, menu, needed).contains(needed);
                }
            }
            // 态势页要拉设备清单，走的是**模块码** devices.read（不是动作码 device:read）。
            if (menus.contains("situation")) {
                assertThat(codes).as("%s 的态势页要拉设备清单", account).contains("devices.read");
                assertThat(menus).as("但不该多出设备管理菜单").doesNotContain("devices");
            }
        }
    }

    @Test
    void noRoleGetsOperationRightsItsJobDoesNotNeed() throws Exception {
        // 给全读权限不等于给操作权。这条反面断言和上面那条是一对：只断"够用"会一路放宽到人人都能操作。
        assertThat(codes(me("zhangwei"))).as("值班员不做审批")
                .doesNotContain("disposal:approve", "punishment:review");
        assertThat(codes(me("zhangjg"))).as("授权人不做核实与派发")
                .doesNotContain("alarm:verify", "handoff:create");
        assertThat(codes(me("zhaopeng"))).as("运维不碰业务处置")
                .doesNotContain("disposal:approve", "alarm:verify", "punishment:review");
        assertThat(codes(me("wugang"))).as("审计员只读，不该有任何操作权")
                .doesNotContain("alarm:verify", "disposal:approve", "punishment:review", "handoff:create");
    }

    /**
     * 决策 18-11：审计员的本职就是"只读 + 审计日志导出"。没有审计日志这一页，这个角色在演示里什么也证明不了。
     *
     * 审计日志没有动作码：列表要模块级 `audit.read`、导出要 `audit.op`。
     */
    @Test
    void theAuditorCanActuallyReachAndExportTheAuditLog() throws Exception {
        JsonNode me = me("wugang");
        assertThat(menus(me)).as("审计员要进得去审计日志").contains("archive");
        assertThat(codes(me)).as("审计日志列表").contains("audit.read");
        assertThat(codes(me)).as("审计日志导出").contains("audit.op");
    }

    /**
     * 审计员的权限必须**撑得过下一次重启**。
     *
     * 单个 Spring 上下文里两个 runner 各跑一遍、且初始化器(@Order 30) 在种子(@Order 130) 之前，
     * 所以上面那条用例是绿的——但那只证明了"第一次启动"。15-25 就是这么漏过去的：
     * 初始化器每次启动都把非超管角色的 users/roles/audit/countermeasure 抹成 NONE，
     * 第二次启动才现形。这里手动再跑一次初始化器，把"第二次启动"补出来。
     */
    @Test
    void theAuditorKeepsTheAuditLogAfterTheNextRestart() {
        superAdminInitializer.run(new DefaultApplicationArguments());
        // 决策 18-13：审计从"只有超管可持"的清单里拿掉了，但**用户、角色、反制仍然只归超管**——
        // 放宽的是审计这一项，不是整条规则。这条反面断言和上面那条是一对：只断"审计保住了"，
        // 哪天有人把整个清单删空也照样绿。
        assertThat(jdbc.queryForObject("""
                select count(*) from app_role_permission
                where role_code <> 'ROLE-ADMIN' and permission_code in ('users','roles','countermeasure')
                  and (permission_level <> 'NONE' or menu_enabled = true)
                """, Integer.class)).as("用户、角色、反制仍只归超管").isZero();
        assertThat(jdbc.queryForObject("select permission_level from app_role_permission"
                + " where role_code='ROLE-DEMO-AUDIT' and permission_code='audit'", String.class))
                .as("重启后审计员仍要能读审计日志").isEqualTo("OP");
        assertThat(jdbc.queryForObject("select menu_enabled from app_role_permission"
                + " where role_code='ROLE-DEMO-AUDIT' and permission_code='audit'", Boolean.class))
                .as("重启后审计日志菜单仍要在").isTrue();
    }

    @Test
    void rerunIsIdempotentAndDoesNotDowngradeWhatSomeoneRaised() {
        jdbc.update("update app_role_permission set permission_level='OP'"
                + " where role_code='ROLE-DEMO-AUDIT' and permission_code='alarm:read'");
        long before = jdbc.queryForObject("select count(*) from app_user where role_code like 'ROLE-DEMO-%'", Long.class);

        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());

        assertThat(jdbc.queryForObject("select count(*) from app_user where role_code like 'ROLE-DEMO-%'", Long.class))
                .as("重复跑不该造出第二批账号").isEqualTo(before);
        // 只升不降：演示时有人手工调高过的权限，种子不该在下次重启时收回去。
        assertThat(jdbc.queryForObject("select permission_level from app_role_permission"
                + " where role_code='ROLE-DEMO-AUDIT' and permission_code='alarm:read'", String.class)).isEqualTo("OP");
        jdbc.update("update app_role_permission set permission_level='READ'"
                + " where role_code='ROLE-DEMO-AUDIT' and permission_code='alarm:read'");
    }

    private String login(String account) throws Exception {
        String body = "{\"account\":\"" + account + "\",\"password\":\"changeme\"}";
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        if (result.getResponse().getStatus() != 200) return null;
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("session_id").asText();
    }

    private JsonNode me(String account) throws Exception {
        String token = login(account);
        assertThat(token).as("账号 %s 应能登录", account).isNotNull();
        return json.readTree(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private List<String> menus(JsonNode me) {
        List<String> out = new ArrayList<>();
        me.path("menu_keys").forEach(node -> out.add(node.asText()));
        return out;
    }

    private List<String> codes(JsonNode me) {
        List<String> out = new ArrayList<>();
        me.path("permission_codes").forEach(node -> out.add(node.asText()));
        return out;
    }
}
