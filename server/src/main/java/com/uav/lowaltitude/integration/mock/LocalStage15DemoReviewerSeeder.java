package com.uav.lowaltitude.integration.mock;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.config.AppProperties;

/**
 * 本地演示的第二账号 `reviewer1`（决策 15-3）。
 *
 * 为什么必须有第二个人：阶段 14 的复核要求"复核人 ≠ 承办人"（14-27/14-32），
 * 而本地只有 `admin1` 一个账号——于是复核这条路在演示环境里**根本走不到**，
 * 一点就是 409。这个种子存在的全部意义就是让那条路能被真的走一遍。
 *
 * 密码与 `LocalUserSeeder` 同源（`app.dev-seed.password`），不另开环境变量。
 * 双门禁（!production & (local|test) + app.dev-seed.enabled）；全部 `WHERE NOT EXISTS`，重跑幂等。
 */
@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@DependsOn("localUserSeeder")
@Order(120)
public class LocalStage15DemoReviewerSeeder implements ApplicationRunner {
    static final String ROLE = "ROLE-DEMO-REVIEWER";
    static final String ACCOUNT = "reviewer1";

    /**
     * 模块矩阵：够看处置与处罚两条链，但不给用户/角色/审计——那是超管的事（与 15-2 同一条红线）。
     *
     * 决策 15-31：原先写的是 `risks` 与 `handoffs`，**这两个模块码根本不存在**（真码是单数 `risk`；
     * 交接没有独立模块，靠 `handoff:read` 动作把关）。循环按目录里的码取，对不上的字符串既不报错也不落行，
     * 于是两条授权一直是空写——reviewer1 的 menu_keys 只有 workbench/alarms/monitor，进不了处罚页，
     * 阶段 14 的两人链路在页面上根本走不到。补 punishment/sensing/flights，并把 risks 改成 risk。
     */
    private static final List<String> MODULE_READ =
            List.of("monitoring", "alarms", "punishment", "sensing", "flights");

    /**
     * 只给读、**不进菜单**的模块（决策 15-34）。
     *
     * `GET /devices` 要的是**模块码** `devices.read`（A 的 `DeviceAccessPolicy:18`），
     * 不是动作码 `device:read`——态势页要拉设备清单，光加动作码那条 403 不会消失。
     * 但"设备管理"是运维的页面，不该出现在复核员的菜单里，所以读与菜单在这里分开。
     */
    private static final List<String> MODULE_DATA_ONLY = List.of("devices");

    /**
     * 动作权限。这个账号要撑起**两条**"必须两个人"的链路，缺一条演示环境里就走不通：
     * - 阶段 14 处罚复核：复核人 ≠ 承办人（14-27）→ `punishment:review`；
     * - 阶段 13 处置审批：审批人 ≠ 申请人（两人规则）→ `disposal:approve`，以及随后的执行与停止。
     * 用 `PermissionCode` 枚举而不是字符串字面量：谁改了枚举，这里在编译期就断，
     * 而不是等到某天演示时才发现某个码悄悄改了名、种子照样跑绿但账号没有那项权限。
     */
    private static final List<String[]> ACTIONS = List.of(
            new String[]{PermissionCode.PUNISHMENT_READ.value(), "READ"},
            new String[]{PermissionCode.PUNISHMENT_REVIEW.value(), "OP"},
            new String[]{PermissionCode.DISPOSAL_READ.value(), "READ"},
            new String[]{PermissionCode.DISPOSAL_APPROVE.value(), "OP"},
            new String[]{PermissionCode.DISPOSAL_EXECUTE.value(), "OP"},
            new String[]{PermissionCode.DISPOSAL_STOP.value(), "OP"},
            new String[]{PermissionCode.ALARM_READ.value(), "READ"},
            new String[]{PermissionCode.TARGET_READ.value(), "READ"},
            new String[]{PermissionCode.HANDOFF_READ.value(), "READ"},
            // 处罚页要看案件证据（决策 18-12）：复核人判不了"证据够不够"就复核不了案子。
            new String[]{PermissionCode.EVIDENCE_READ.value(), "READ"},
            // 决策 15-34：15-31 给了 sensing/flights 两个菜单，却没给这两页要读的东西——
            // 菜单点得开、一进去满屏 403，比没有菜单更让人以为系统坏了。
            // 这几个码是助手 E2E 实测 403 的那些接口各自要的：
            // /devices 与态势聚合(DEVICE_READ) · /flight-plans(FLIGHT_READ, ROUTE_READ) ·
            // /airspaces(AIRSPACE_READ) · /legality-evaluations(ASSESSMENT_READ) ·
            // /risks 与 /risks/districts(RISK_READ) · /fusion/status(FUSION_READ)。
            // 不给 RULE_READ：/rule-sets 只有合法性页会发，那一页不在这个账号的菜单里。
            new String[]{PermissionCode.DEVICE_READ.value(), "READ"},
            new String[]{PermissionCode.FLIGHT_READ.value(), "READ"},
            new String[]{PermissionCode.ROUTE_READ.value(), "READ"},
            new String[]{PermissionCode.AIRSPACE_READ.value(), "READ"},
            new String[]{PermissionCode.ASSESSMENT_READ.value(), "READ"},
            new String[]{PermissionCode.RISK_READ.value(), "READ"},
            new String[]{PermissionCode.FUSION_READ.value(), "READ"});

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public LocalStage15DemoReviewerSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
            AppProperties appProperties) {
        this.jdbc = jdbc; this.passwordEncoder = passwordEncoder; this.appProperties = appProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = appProperties.getDevSeed().getPassword();
        if (password == null || password.isBlank()) return;   // 与 LocalUserSeeder 同一来源；没有就不造账号。
        long now = System.currentTimeMillis();
        role(now);
        boolean changed = modulePermissions();
        changed |= actionPermissions();
        user(passwordEncoder.encode(password), now);
        if (changed) {
            // 与 LocalStage2AccessSeeder 同一做法：一轮补权只递增一次权限版本，让已有会话失效——
            // 不递增的话，补上的菜单要等这个账号自己重新登录才看得见，演示时表现为"改了没用"。
            jdbc.update("UPDATE app_user SET permission_version=permission_version+1 WHERE role_code=?", ROLE);
        }
    }

    private void role(long now) {
        jdbc.update("INSERT INTO app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,"
                + "system_role) SELECT ?,'演示复核员','本地演示：处罚复核（决策 15-3）',FALSE,TRUE,?,?,0,FALSE"
                + " WHERE NOT EXISTS (SELECT 1 FROM app_role WHERE role_code=?)", ROLE, now, now, ROLE);
    }

    /**
     * 模块矩阵按目录整组落：矩阵的语义是"每一项都要有明确取值"，
     * 只插几行会让这个角色的矩阵不完整，页面上打开就是一片空白而不是"全部无权限"。
     */
    private boolean modulePermissions() {
        List<String> catalog = jdbc.queryForList(
                "SELECT permission_code FROM app_permission WHERE permission_kind='MODULE'", String.class);
        // 拼错的模块码以前是静默空写（见 MODULE_READ 上的说明），要到演示时点不开菜单才发现。
        // 这里当场断掉：种子跑不起来比"跑绿了但账号没有权限"容易查得多。
        List<String> unknown = java.util.stream.Stream.concat(MODULE_READ.stream(), MODULE_DATA_ONLY.stream())
                .filter(code -> !catalog.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("unknown module permission code(s) in demo reviewer seed: " + unknown);
        }
        boolean changed = false;
        for (String code : catalog) {
            String level = MODULE_READ.contains(code) || MODULE_DATA_ONLY.contains(code) ? "READ" : "NONE";
            boolean menu = MODULE_READ.contains(code);
            changed |= jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,"
                    + "menu_enabled,created_at) SELECT ?,?,?,?,CURRENT_TIMESTAMP WHERE NOT EXISTS"
                    + " (SELECT 1 FROM app_role_permission WHERE role_code=? AND permission_code=?)",
                    ROLE, code, level, menu, ROLE, code) > 0;
        }
        // 升级库上这个角色的 18 行 MODULE 早就以 NONE 存在了，上面那条 NOT EXISTS 的插入**碰不到它们**——
        // 只改 MODULE_READ 的话，全新库好了、升级库照旧进不去菜单（决策 15-31 修订，领导指出）。
        // 只升不降：NONE 抬成 READ，menu 一律打开，已经是 OP/AUTH 的行原样保留——
        // 演示时有人手工调高过，种子不该在下次重启时悄悄收回去。
        for (String code : MODULE_READ) {
            changed |= jdbc.update("UPDATE app_role_permission"
                    + " SET permission_level=CASE WHEN permission_level='NONE' THEN 'READ' ELSE permission_level END,"
                    + " menu_enabled=TRUE"
                    + " WHERE role_code=? AND permission_code=?"
                    + "   AND (permission_level='NONE' OR COALESCE(menu_enabled,FALSE)<>TRUE)",
                    ROLE, code) > 0;
        }
        // 只给读的那些不碰 menu_enabled：抬等级是为了页面能拉到数据，不是为了多一个菜单项。
        for (String code : MODULE_DATA_ONLY) {
            changed |= jdbc.update("UPDATE app_role_permission SET permission_level='READ'"
                    + " WHERE role_code=? AND permission_code=? AND permission_level='NONE'", ROLE, code) > 0;
        }
        return changed;
    }

    private boolean actionPermissions() {
        boolean changed = false;
        for (String[] action : ACTIONS) {
            changed |= jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,"
                    + "menu_enabled,created_at) SELECT ?,?,?,FALSE,CURRENT_TIMESTAMP"
                    + " WHERE EXISTS (SELECT 1 FROM app_permission WHERE permission_code=? AND permission_kind='ACTION')"
                    + " AND NOT EXISTS (SELECT 1 FROM app_role_permission WHERE role_code=? AND permission_code=?)",
                    ROLE, action[0], action[1], action[0], ROLE, action[0]) > 0;
            // 动作行同样是"存在就不动"：升级库上已有的行卡在旧等级，改了 ACTIONS 也不会生效。
            // 与模块行同样只升不降（决策 15-31 修订）。
            changed |= raiseTo(action[0], action[1]);
        }
        return changed;
    }

    /** 已有行的等级低于种子声明时抬上去；等于或高于就不动，免得收走演示时手工调高的权限。 */
    private boolean raiseTo(String permissionCode, String level) {
        List<String> current = jdbc.queryForList(
                "SELECT permission_level FROM app_role_permission WHERE role_code=? AND permission_code=?",
                String.class, ROLE, permissionCode);
        if (current.isEmpty() || rank(current.get(0)) >= rank(level)) return false;
        return jdbc.update("UPDATE app_role_permission SET permission_level=? WHERE role_code=? AND permission_code=?",
                level, ROLE, permissionCode) > 0;
    }

    private static int rank(String level) {
        return switch (level == null ? "NONE" : level) {
            case "READ" -> 1; case "OP" -> 2; case "AUTH" -> 3; default -> 0;
        };
    }

    /** 账号挂在 admin1 所在的组织上，`scope_mode=ALL`——演示复核不该被数据范围挡住。 */
    private void user(String hash, long now) {
        jdbc.update("INSERT INTO app_user (user_id,account,name,role_code,status,password_hash,fail_count,org_id,"
                + "scope_mode,must_change_password,permission_version,created_at,updated_at,version)"
                + " SELECT ?,?,'演示复核员',?,'ACTIVE',?,0,(SELECT org_id FROM app_user WHERE account='admin1'),"
                + "'ALL',FALSE,0,?,?,0"
                + " WHERE EXISTS (SELECT 1 FROM app_role WHERE role_code=?)"
                + " AND NOT EXISTS (SELECT 1 FROM app_user WHERE account=?)",
                UUID.randomUUID().toString(), ACCOUNT, ROLE, hash, now, now, ROLE, ACCOUNT);
    }
}
