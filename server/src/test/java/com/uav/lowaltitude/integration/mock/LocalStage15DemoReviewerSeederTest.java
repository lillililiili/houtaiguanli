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
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;

/** 本地第二账号（决策 15-3）：能登录、持有该有的行、重跑不重复。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalStage15DemoReviewerSeederTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LocalStage15DemoReviewerSeeder seeder;

    @Test
    void reviewerCanLogIn() throws Exception {
        // 这个账号存在的意义就是"复核人 ≠ 承办人"（14-27）能在本地被真的走一遍；登不上就等于没有。
        String body = "{\"account\":\"reviewer1\",\"password\":\"changeme\"}";
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    /**
     * 决策 15-31：动作权限齐了，菜单进不去照样白搭——阶段 14 的复核要在**处罚页**上点。
     *
     * 原先 MODULE_READ 里写的是 `risks`/`handoffs`，这两个模块码不存在（真码是单数 `risk`；
     * 交接没有独立模块），循环按目录取码，对不上的字符串既不报错也不落行，于是两条授权一直是空写，
     * reviewer1 的 menu_keys 只有 workbench/alarms/monitor。断菜单键而不是断授权行：
     * 授权行对了但 route_key 为空同样进不去页面，用户看到的是菜单，不是表里的行。
     */
    @Test
    void canReachThePagesTheTwoPersonFlowsHappenOn() throws Exception {
        String body = "{\"account\":\"reviewer1\",\"password\":\"changeme\"}";
        String token = json.readTree(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("session_id").asText();
        JsonNode me = json.readTree(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        List<String> menus = new ArrayList<>();
        me.path("menu_keys").forEach(node -> menus.add(node.asText()));
        // punish 是阶段 14 复核那一页；situation/flights 是简报要求的另外两个。
        assertThat(menus).as("reviewer1 的菜单").contains("punish", "situation", "flights", "alarms", "monitor");
    }

    /**
     * 决策 15-31 修订：**升级库上这些行早就以 NONE 存在**，种子里 `INSERT ... WHERE NOT EXISTS` 碰不到它们。
     * 只改 MODULE_READ 的话，全新库好了、升级库照旧进不去菜单——领导指出的这一点我上一轮漏了。
     *
     * 这条用例把升级库那个状态在 H2 里直接造出来（把 punishment 行按回 NONE + 菜单关掉），再跑种子。
     * 迁移类的升级路径 H2 造不出来，但**行状态**可以，所以这一条没有理由不测。
     */
    @Test
    void upgradesModuleRowsThatAlreadyExistAsNone() {
        jdbc.update("update app_role_permission set permission_level='NONE', menu_enabled=false"
                + " where role_code=? and permission_code='punishment'", LocalStage15DemoReviewerSeeder.ROLE);
        int versionBefore = permissionVersion();

        seeder.run(new DefaultApplicationArguments());
        assertRowIsReadableAndOnTheMenu();
        // 补权后必须递增权限版本，否则补上的菜单要等这个账号自己重新登录才看得见。
        assertThat(permissionVersion()).as("权限版本").isGreaterThan(versionBefore);

        // 再跑一次：结果不变，且**不再**递增版本——每次重启都递增会把在线会话踢下线。
        int versionAfter = permissionVersion();
        seeder.run(new DefaultApplicationArguments());
        assertRowIsReadableAndOnTheMenu();
        assertThat(permissionVersion()).as("无变化时的权限版本").isEqualTo(versionAfter);
    }

    private void assertRowIsReadableAndOnTheMenu() {
        java.util.Map<String, Object> row = jdbc.queryForMap("select permission_level, menu_enabled"
                + " from app_role_permission where role_code=? and permission_code='punishment'",
                LocalStage15DemoReviewerSeeder.ROLE);
        assertThat(row.get("permission_level")).isEqualTo("READ");
        assertThat(row.get("menu_enabled")).isEqualTo(Boolean.TRUE);
    }

    private int permissionVersion() {
        Integer v = jdbc.queryForObject("select permission_version from app_user where account=?",
                Integer.class, LocalStage15DemoReviewerSeeder.ACCOUNT);
        return v == null ? 0 : v;
    }

    /**
     * 决策 15-34：菜单与"这一页要读什么"必须配套。15-31 把 sensing/flights 两个菜单给了 reviewer1，
     * 却没给这两页要读的动作——助手 E2E 上 5 条红全是这么来的：菜单点得开、一进去满屏 403，
     * 比根本没有那个菜单更让人以为系统坏了。
     *
     * 这张表把对应关系**写死**：以后谁往 MODULE_READ 里加菜单而忘了配读动作，这条就红。
     */
    private static final java.util.Map<String, List<PermissionCode>> MENU_NEEDS = java.util.Map.of(
            "situation", List.of(PermissionCode.DEVICE_READ, PermissionCode.TARGET_READ, PermissionCode.FUSION_READ,
                    PermissionCode.AIRSPACE_READ, PermissionCode.ASSESSMENT_READ),
            "flights", List.of(PermissionCode.FLIGHT_READ, PermissionCode.ROUTE_READ,
                    PermissionCode.AIRSPACE_READ, PermissionCode.RISK_READ),
            // 处罚页要看案件证据（18-12）：复核人判不了"证据够不够"就复核不了案子。
            "punish", List.of(PermissionCode.PUNISHMENT_READ, PermissionCode.EVIDENCE_READ));

    @Test
    void everyMenuComesWithTheReadActionsThatPageNeeds() throws Exception {
        JsonNode me = me();
        List<String> menus = new ArrayList<>();
        me.path("menu_keys").forEach(node -> menus.add(node.asText()));
        List<String> codes = new ArrayList<>();
        me.path("permission_codes").forEach(node -> codes.add(node.asText()));

        MENU_NEEDS.forEach((menu, needed) -> {
            assertThat(menus).as("菜单 %s", menu).contains(menu);
            for (PermissionCode code : needed) {
                // 决策 16-4 起 /auth/me 也下发动作码原文，所以这里直接站在前端的位置断言：
                // 前端就是拿这份 permission_codes 决定动作按钮显不显示的。
                assertThat(codes).as("%s 这一页要的 %s", menu, code.value()).contains(code.value());
            }
        });

        // /devices 走的是**模块码** devices.read，不是动作码 device:read——
        // 只断动作码的话，态势页那条 403 照样在，而用例是绿的。这一条得从 permission_codes 看。
        assertThat(codes).as("态势页要拉设备清单").contains("devices.read");
        // 但不给菜单：设备管理是运维的页面，复核员不该看到。读与菜单在这里是分开的。
        assertThat(menus).as("不该多出设备管理菜单").doesNotContain("devices");
        // 没授的动作不能出现：下发的是"这个账号能做什么"，多给一个前端就会亮出一个点不动的按钮。
        assertThat(codes).as("没授的动作").doesNotContain("fusion:manage", "punishment:close");
    }

    private JsonNode me() throws Exception {
        String body = "{\"account\":\"reviewer1\",\"password\":\"changeme\"}";
        String token = json.readTree(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("session_id").asText();
        return json.readTree(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test
    void holdsEveryActionTheTwoPersonFlowsNeed() {
        // 逐码断言，且**引用枚举而不是抄字符串**：抄字符串的话，枚举改名后这里照样绿，
        // 而演示时才发现账号没有那项权限。
        assertThat(level(PermissionCode.PUNISHMENT_REVIEW.value())).isEqualTo("OP");
        assertThat(level(PermissionCode.PUNISHMENT_READ.value())).isEqualTo("READ");
        // 阶段 13 的两人审批链路：没有 disposal:approve，reviewer1 批授权就是 403——实跑撞到过。
        assertThat(level(PermissionCode.DISPOSAL_APPROVE.value())).isEqualTo("OP");
        assertThat(level(PermissionCode.DISPOSAL_EXECUTE.value())).isEqualTo("OP");
        assertThat(level(PermissionCode.DISPOSAL_STOP.value())).isEqualTo("OP");
        assertThat(level(PermissionCode.DISPOSAL_READ.value())).isEqualTo("READ");
        assertThat(level(PermissionCode.ALARM_READ.value())).isEqualTo("READ");
        assertThat(level(PermissionCode.TARGET_READ.value())).isEqualTo("READ");
        assertThat(level(PermissionCode.HANDOFF_READ.value())).isEqualTo("READ");
    }

    @Test
    void doesNotHoldSystemAdministration() {
        // 演示复核员不该顺手拿到用户/角色/审计——与 15-2 同一条红线。
        for (String forbidden : new String[]{"users", "roles", "audit"}) {
            assertThat(level(forbidden)).as(forbidden).isIn(null, "NONE");
        }
    }

    @Test
    void moduleMatrixIsComplete() {
        // 矩阵的语义是"每一项都有明确取值"。只插几行会让角色页打开是一片空白，
        // 而不是清清楚楚的"这些能看、那些不能"。
        long catalog = jdbc.queryForObject(
                "select count(*) from app_permission where permission_kind='MODULE'", Long.class);
        long granted = jdbc.queryForObject("select count(*) from app_role_permission p join app_permission a"
                + " on a.permission_code=p.permission_code where p.role_code=? and a.permission_kind='MODULE'",
                Long.class, LocalStage15DemoReviewerSeeder.ROLE);
        assertThat(granted).isEqualTo(catalog);
    }

    @Test
    void roleIsNotBuiltinSoItStaysEditable() {
        // 标成内置会让它落进 BUILTIN_ROLE_PROTECTED，反而没人能再调整这个演示角色。
        assertThat(jdbc.queryForObject("select builtin from app_role where role_code=?", Boolean.class,
                LocalStage15DemoReviewerSeeder.ROLE)).isFalse();
    }

    @Test
    void rerunIsIdempotent() {
        long usersBefore = count("select count(*) from app_user where account=?", LocalStage15DemoReviewerSeeder.ACCOUNT);
        long rowsBefore = count("select count(*) from app_role_permission where role_code=?",
                LocalStage15DemoReviewerSeeder.ROLE);
        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());
        assertThat(count("select count(*) from app_user where account=?", LocalStage15DemoReviewerSeeder.ACCOUNT))
                .isEqualTo(usersBefore);
        assertThat(count("select count(*) from app_role_permission where role_code=?",
                LocalStage15DemoReviewerSeeder.ROLE)).isEqualTo(rowsBefore);
    }

    private String level(String permissionCode) {
        return jdbc.query("select permission_level from app_role_permission where role_code=? and permission_code=?",
                rs -> rs.next() ? rs.getString(1) : null, LocalStage15DemoReviewerSeeder.ROLE, permissionCode);
    }

    private long count(String sql, Object arg) { return jdbc.queryForObject(sql, Long.class, arg); }
}
