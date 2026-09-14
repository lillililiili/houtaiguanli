package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.config.AppProperties;

/**
 * 演示用的四个业务角色与十二个演示账号（阶段 18）。
 *
 * <p>原版演示里有五个角色：超级管理员、处置授权人、值班员、设备运维、审计员。**超级管理员这一个不在这里造**——
 * 系统强制"有且仅有一个超级管理员"（{@code SuperAdminIntegrityInitializer} 数到第二个就抛异常、应用起不来），
 * 那个位置已经由 admin1 占着。原版的 admin 账号对应的就是它。
 *
 * <p>角色都建成**非内置**：内置角色不可编辑不可删除，而演示要能在角色页上点开看权限矩阵。
 *
 * <p>与 {@code LocalStage15DemoReviewerSeeder} 同样的双门禁与幂等口径：只升不降，已有的高权限不会被这里降下去。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(130)
public class LocalDemoRolesSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoRolesSeeder.class);

    /**
     * 一个菜单进去之后，那一页要读什么（决策 18-11）。
     *
     * <p>阶段 15 踩过一次同样的坑（15-34）：菜单给了、页面要的读动作没给——**菜单点得开、一进去满屏 403，
     * 比根本没有那个菜单更让人以为系统坏了**。四个新角色又重演了一遍（助手实测 118 条里 59 红）。
     * 所以这里不再逐个角色手抄动作清单，而是把"菜单 → 这一页要读什么"列成一张表，
     * 角色只声明自己有哪些菜单，读动作由这张表推出来——以后谁加菜单而忘了配读动作，是推不出漏的。
     */
    private static final Map<String, List<PermissionCode>> MENU_READS = Map.of(
            "situation", List.of(PermissionCode.DEVICE_READ, PermissionCode.TARGET_READ, PermissionCode.FUSION_READ,
                    PermissionCode.AIRSPACE_READ, PermissionCode.ASSESSMENT_READ),
            "flights", List.of(PermissionCode.FLIGHT_READ, PermissionCode.ROUTE_READ,
                    PermissionCode.AIRSPACE_READ, PermissionCode.RISK_READ),
            // 告警页与工作台要显示这条告警的反制/干扰处置状态，所以这一页也要读处置授权（决策 18-12）。
            "alarms", List.of(PermissionCode.ALARM_READ, PermissionCode.TARGET_READ, PermissionCode.EVIDENCE_READ,
                    PermissionCode.DISPOSAL_READ),
            "punish", List.of(PermissionCode.HANDOFF_READ, PermissionCode.PUNISHMENT_READ,
                    PermissionCode.DISPOSAL_READ, PermissionCode.EVIDENCE_READ),
            "evidence", List.of(PermissionCode.EVIDENCE_READ),
            "devices", List.of(PermissionCode.DEVICE_READ),
            "monitor", List.of(PermissionCode.DEVICE_READ),
            "commission", List.of(PermissionCode.DEVICE_READ),
            "archive", List.of());

    /**
     * 工作台菜单固定可见（{@code AccessService.menuKeys} 无条件加上它），外壳在**每个路由**上都会拉工作台事项。
     * 少了它，人在任何一页上都会看到工作台那一栏报错——所以每个演示角色都给。
     */
    private static final PermissionCode WORKBENCH = PermissionCode.WORKBENCH_READ;

    /**
     * 只给读、不进菜单的模块（15-34 起）：`GET /devices` 要的是**模块码** `devices.read`，不是动作码
     * `device:read`，态势页要拉设备清单就绕不开它；但"设备管理"是运维的页面，不该出现在别人的菜单里。
     */
    private static final Map<String, List<String>> MENU_DATA_ONLY_MODULES = Map.of("situation", List.of("devices"));

    /** 演示角色：角色码、名称、说明（说明逐字取自原版演示）、菜单、以及这个角色额外的操作动作。 */
    public record DemoRole(String code, String name, String description,
            List<String> menus, List<String[]> operations) { }

    public static final List<DemoRole> ROLES = List.of(
            new DemoRole("ROLE-DEMO-AUTH", "处置授权人", "反制/干扰授权、案件审批",
                    List.of("situation", "alarms", "punish"),
                    List.of(new String[]{PermissionCode.DISPOSAL_APPROVE.value(), "OP"},
                            new String[]{PermissionCode.DISPOSAL_STOP.value(), "OP"},
                            new String[]{PermissionCode.PUNISHMENT_REVIEW.value(), "OP"})),
            new DemoRole("ROLE-DEMO-DUTY", "值班员", "态势监视、告警核实与派发",
                    List.of("situation", "alarms", "flights"),
                    List.of(new String[]{PermissionCode.ALARM_VERIFY.value(), "OP"},
                            new String[]{PermissionCode.HANDOFF_CREATE.value(), "OP"})),
            new DemoRole("ROLE-DEMO-OPS", "设备运维", "设备接入、调测与监测",
                    List.of("devices", "commission", "monitor"),
                    List.<String[]>of()),
            new DemoRole("ROLE-DEMO-AUDIT", "审计员", "只读 + 审计日志导出",
                    // 审计日志这一页就是这个角色的本职：没有它，"只读 + 审计日志导出"这条描述在界面上落不了地。
                    List.of("situation", "alarms", "flights", "punish", "evidence", "archive"),
                    List.<String[]>of()));

    /** 演示账号：账号、姓名、角色码、单位。逐条取自原版演示的用户表。 */
    public record DemoUser(String account, String name, String roleCode, String org, boolean disabled) { }

    public static final List<DemoUser> USERS = List.of(
            new DemoUser("zhangjg", "张建国", "ROLE-DEMO-AUTH", "东营市公安局", false),
            new DemoUser("liguoq", "李国强", "ROLE-DEMO-AUTH", "东营市公安局特警支队", false),
            new DemoUser("wangzh", "王振华", "ROLE-DEMO-AUTH", "东营市低空安全管理中心", false),
            new DemoUser("zhangwei", "张伟", "ROLE-DEMO-DUTY", "东营市低空安全管理中心", false),
            new DemoUser("liqiang", "李强", "ROLE-DEMO-DUTY", "东营市低空安全管理中心", false),
            new DemoUser("wanglei", "王磊", "ROLE-DEMO-DUTY", "东营区公安分局", false),
            new DemoUser("zhaopeng", "赵鹏", "ROLE-DEMO-OPS", "东营市低空安全管理中心", false),
            new DemoUser("suntao", "孙涛", "ROLE-DEMO-OPS", "设备厂商A（驻场）", false),
            // 原版把唯一的停用样本落在周敏（设备运维另有两个在用账号），而不是唯一的审计员吴刚——
            // 否则"审计员只能看不能发"这条规则在界面上永远演示不到。这里照搬这个安排。
            new DemoUser("zhoumin", "周敏", "ROLE-DEMO-OPS", "设备厂商F（驻场）", true),
            new DemoUser("wugang", "吴刚", "ROLE-DEMO-AUDIT", "东营市审计局", false),
            new DemoUser("zhengkai", "郑凯", "ROLE-DEMO-DUTY", "广饶县公安局", false));

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public LocalDemoRolesSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AppProperties appProperties) {
        this.jdbc = jdbc; this.passwordEncoder = passwordEncoder; this.appProperties = appProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = appProperties.getDevSeed().getPassword();
        if (password == null || password.isBlank()) return;   // 与其它演示种子同一来源；没有口令就不造账号。
        long now = System.currentTimeMillis();
        String hash = passwordEncoder.encode(password);
        boolean changed = false;
        for (DemoRole role : ROLES) {
            changed |= role(role, now);
            changed |= modules(role);
            changed |= actions(role);
        }
        for (DemoUser user : USERS) changed |= user(user, hash, now);
        if (changed) {
            // 与其它种子一致：补权之后递增权限版本，让已有会话失效，否则补上的菜单要等重新登录才看得见。
            jdbc.update("UPDATE app_user SET permission_version=permission_version+1 WHERE role_code LIKE 'ROLE-DEMO-%'");
            log.info("demo roles and users seeded: roles={}, users={}", ROLES.size(), USERS.size());
        }
    }

    private boolean role(DemoRole role, long now) {
        return jdbc.update("INSERT INTO app_role (role_code,name,description,builtin,enabled,created_at,updated_at,"
                + "version,system_role) SELECT ?,?,?,FALSE,TRUE,?,?,0,FALSE"
                + " WHERE NOT EXISTS (SELECT 1 FROM app_role WHERE role_code=?)",
                role.code(), role.name(), role.description(), now, now, role.code()) > 0;
    }

    /**
     * 模块矩阵按目录整组落：只插几行会让角色页打开是一片空白，而不是"哪些能看、哪些不能"。
     * 随后把该给的抬到 READ 并打开菜单——**只升不降**，已经是 OP/AUTH 的行原样保留。
     */
    /**
     * 模块矩阵按目录整组落：只插几行会让角色页打开是一片空白，而不是"哪些能看、哪些不能"。
     * 随后把该给的抬到 READ 并打开菜单——**只升不降**，已经是 OP/AUTH 的行原样保留。
     *
     * <p>审计日志（菜单 `archive`）要的是**模块级** `audit.op`（列表 `audit.read`、导出 `audit.op`），
     * 没有对应的动作码，所以这里给到 OP 而不是 READ。
     */
    private boolean modules(DemoRole role) {
        Map<String, String> moduleByRoute = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT permission_code, route_key FROM app_permission WHERE permission_kind='MODULE'")) {
            if (row.get("route_key") != null) moduleByRoute.put(String.valueOf(row.get("route_key")),
                    String.valueOf(row.get("permission_code")));
        }
        java.util.Set<String> menuModules = new java.util.LinkedHashSet<>();
        for (String menu : role.menus()) {
            String module = moduleByRoute.get(menu);
            if (module != null) menuModules.add(module);
        }
        java.util.Set<String> readable = new java.util.LinkedHashSet<>(menuModules);
        for (String menu : role.menus()) readable.addAll(MENU_DATA_ONLY_MODULES.getOrDefault(menu, List.of()));

        boolean changed = false;
        for (String code : jdbc.queryForList(
                "SELECT permission_code FROM app_permission WHERE permission_kind='MODULE'", String.class)) {
            changed |= jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,"
                    + "menu_enabled,created_at) SELECT ?,?,?,?,CURRENT_TIMESTAMP WHERE NOT EXISTS"
                    + " (SELECT 1 FROM app_role_permission WHERE role_code=? AND permission_code=?)",
                    role.code(), code, readable.contains(code) ? level(code) : "NONE", menuModules.contains(code),
                    role.code(), code) > 0;
        }
        for (String code : readable) {
            changed |= raiseTo(role.code(), code, level(code));
            if (menuModules.contains(code)) {
                changed |= jdbc.update("UPDATE app_role_permission SET menu_enabled=TRUE"
                        + " WHERE role_code=? AND permission_code=? AND COALESCE(menu_enabled,FALSE)<>TRUE",
                        role.code(), code) > 0;
            }
        }
        return changed;
    }

    /** 审计日志没有动作码，读与导出都挂在模块等级上，所以它要 OP；其余模块只读。 */
    private static String level(String moduleCode) { return "audit".equals(moduleCode) ? "OP" : "READ"; }

    /**
     * 动作权限 = 该角色每个菜单所需的读动作（由 {@link #MENU_READS} 推出）+ 工作台读 + 这个角色自己的操作动作。
     * 不再逐个角色手抄清单：手抄就会漏，15-34 与 18-11 漏的是同一类。
     */
    private boolean actions(DemoRole role) {
        Map<String, String> wanted = new java.util.LinkedHashMap<>();
        wanted.put(WORKBENCH.value(), "READ");
        for (String menu : role.menus()) {
            for (PermissionCode code : MENU_READS.getOrDefault(menu, List.of())) {
                wanted.putIfAbsent(code.value(), "READ");
            }
        }
        // 操作动作后放：同一个码既是读又是操作时，取更高的那个。
        for (String[] operation : role.operations()) wanted.put(operation[0], operation[1]);

        boolean changed = false;
        for (Map.Entry<String, String> action : wanted.entrySet()) {
            changed |= jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,"
                    + "menu_enabled,created_at) SELECT ?,?,?,FALSE,CURRENT_TIMESTAMP"
                    + " WHERE EXISTS (SELECT 1 FROM app_permission WHERE permission_code=? AND permission_kind='ACTION')"
                    + " AND NOT EXISTS (SELECT 1 FROM app_role_permission WHERE role_code=? AND permission_code=?)",
                    role.code(), action.getKey(), action.getValue(), action.getKey(), role.code(), action.getKey()) > 0;
            changed |= raiseTo(role.code(), action.getKey(), action.getValue());
        }
        return changed;
    }

    /** 已有行等级低于声明时抬上去；等于或高于不动，免得收走演示时手工调高的权限。 */
    private boolean raiseTo(String roleCode, String permissionCode, String level) {
        List<String> current = jdbc.queryForList(
                "SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code=?",
                String.class, roleCode, permissionCode);
        if (current.isEmpty() || rank(current.get(0)) >= rank(level)) return false;
        return jdbc.update("UPDATE app_role_permission SET permission_level=? WHERE role_code=? AND permission_code=?",
                level, roleCode, permissionCode) > 0;
    }

    private static int rank(String level) {
        return switch (level == null ? "NONE" : level) {
            case "READ" -> 1; case "OP" -> 2; case "AUTH" -> 3; default -> 0;
        };
    }

    private boolean user(DemoUser user, String hash, long now) {
        return jdbc.update("INSERT INTO app_user (user_id,account,name,role_code,status,password_hash,fail_count,"
                + "org_id,scope_mode,must_change_password,permission_version,created_at,updated_at,version)"
                + " SELECT ?,?,?,?,?,?,0,(SELECT org_id FROM app_user WHERE account='admin1'),'ALL',FALSE,0,?,?,0"
                + " WHERE EXISTS (SELECT 1 FROM app_role WHERE role_code=?)"
                + " AND NOT EXISTS (SELECT 1 FROM app_user WHERE account=?)",
                UUID.randomUUID().toString(), user.account(), user.name(), user.roleCode(),
                user.disabled() ? "DISABLED" : "ACTIVE", hash, now, now, user.roleCode(), user.account()) > 0;
    }
}
