package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;

/** Shared local QA rule definitions. Creates only missing configuration; never activates a version or inserts business fixtures. */
final class LocalRuleCatalogDefinitions {
    public static final String RULE_SET_CODE = "LEGALITY-DEMO";
    public static final String RULE_SET_ID = "seed-stage7-ruleset";
    public static final String VERSION_1 = "seed-stage7-rsv-1", VERSION_2 = "seed-stage7-rsv-2";
    private static final String[] RULE_CODES = {RuleCodes.C01, RuleCodes.C02_1, RuleCodes.C02_2, RuleCodes.C02_3, RuleCodes.C02_4, RuleCodes.C02_5,
            RuleCodes.C02_6, RuleCodes.C02_7, RuleCodes.C02_8, RuleCodes.C03, RuleCodes.C06};
    /** C06 在决策之后执行，优先级排在 C03 之后（RuleCodes 未定义，这里只表示顺序，不是阈值）。 */
    private static final int PRIORITY_C06 = RuleCodes.PRIORITY_C03 + 100;
    private static final int[] PRIORITIES = {RuleCodes.PRIORITY_C01, RuleCodes.PRIORITY_C02_1, RuleCodes.PRIORITY_C02_2, RuleCodes.PRIORITY_C02_3,
            RuleCodes.PRIORITY_C02_4, RuleCodes.PRIORITY_C02_5, RuleCodes.PRIORITY_C02_6, RuleCodes.PRIORITY_C02_7, RuleCodes.PRIORITY_C02_8,
            RuleCodes.PRIORITY_C03, PRIORITY_C06};
    /**
     * 契约 DEMO 参数目录（LEGALITY-DEMO v1）；v2 只改 C02-3.tolerance_m。
     * 阶段 10（决策 10-2）：C02-2.kinds 只写规范值 ALTITUDE_LIMIT、C02-8.kinds 只写 TEMPORARY_CONTROL——迁移 074 起 airspace_version.kind_code 的 CHECK 只留
     * AirspaceKind 的五值，HEIGHT_LIMIT / TEMPORARY 已不可能出现在库里，参数里再列它们只是让读者以为它们仍是合法种类。
     * 注意 param() 只补缺行：已发布版本的参数在 PostgreSQL 上不可改，已有库里的 C02-2.kinds / C02-8.kinds 仍是旧串（含规范值，判定不变）。
     */
    private static final String[][] DEMO_PARAMS = {
            {"C01", "time_window_min", "10", "INTEGER", "min"}, {"C01", "corridor_tolerance_m", "100", "NUMBER", "m"},
            {"C02-1", "kinds", "PROHIBITED,RESTRICTED", "LIST", null}, {"C02-2", "kinds", "ALTITUDE_LIMIT", "LIST", null},
            {"C02-3", "tolerance_m", "20", "NUMBER", "m"}, {"C02-4", "grace_min", "10", "INTEGER", "min"},
            {"C02-5", "timezone", "Asia/Shanghai", "STRING", null}, {"C02-5", "night_from", "20", "INTEGER", "h"}, {"C02-5", "night_to", "6", "INTEGER", "h"},
            {"C02-6", "vlos_m", "500", "NUMBER", "m"}, {"C02-8", "kinds", "TEMPORARY_CONTROL", "LIST", null},
            {"C03", "fresh_seconds", "120", "INTEGER", "s"}, {"C03", "track_points", "10", "INTEGER", null}, {"C03", "conf_min", "0.75", "NUMBER", null},
            {"C03", "min_points", "3", "INTEGER", null}, {"C03", "gap_seconds", "30", "INTEGER", "s"}, {"C03", "no_plan_status", "ILLEGAL", "STRING", null},
            {"C03", "ignore_undetermined_rules", "C02-6", "LIST", null},
            {"C03", "w.violation", "0.40", "NUMBER", null}, {"C03", "w.plan_match", "0.25", "NUMBER", null}, {"C03", "w.airspace", "0.15", "NUMBER", null},
            {"C03", "w.track", "0.10", "NUMBER", null}, {"C03", "w.confidence", "0.10", "NUMBER", null},
            {"C03", "severity.INSIDE_RESTRICTED_AIRSPACE", "1.0", "NUMBER", null}, {"C03", "severity.AIRSPACE_ALTITUDE_EXCEEDED", "0.9", "NUMBER", null},
            {"C03", "severity.TEMPORARY_RESTRICTION_ACTIVE", "0.9", "NUMBER", null}, {"C03", "severity.NO_AUTHORIZATION", "0.8", "NUMBER", null},
            {"C03", "severity.ROUTE_DEVIATION", "0.6", "NUMBER", null}, {"C03", "severity.PLAN_ALTITUDE_EXCEEDED", "0.5", "NUMBER", null},
            {"C03", "severity.TIME_WINDOW_OVERRUN", "0.4", "NUMBER", null}, {"C03", "severity.NIGHT_FLIGHT", "0.3", "NUMBER", null},
            {"C03", "grade.high", "67", "NUMBER", null}, {"C03", "grade.medium", "34", "NUMBER", null},
            {"C06", "dedup_window_min", "5", "INTEGER", "min"}, {"C06", "upgrade_window_min", "10", "INTEGER", "min"}, {"C06", "auto_close_min", "15", "INTEGER", "min"},
            {"C06", "severity_by_grade", "HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW", "LIST", null}};
    private static final String V2_C02_3_TOLERANCE = "50";

    private final JdbcTemplate jdbc;
    LocalRuleCatalogDefinitions(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Configuration only; local QA activates through the audited management API. */
    void prepareRuleCatalog(Instant at) {
        for (int i = 0; i < RULE_CODES.length; i++) {
            String id = "seed-stage7-rule-" + RULE_CODES[i];
            jdbc.update("insert into rule_version (rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,source_snapshot,created_at)"
                    + " select ?,?,1,'ACTIVE',?,'mock',CAST('{}' AS JSON),? where not exists (select 1 from rule_version where rule_version_id=?)",
                    id, RULE_CODES[i], ts(at), ts(at), id);
        }
        jdbc.update("insert into rule_set (rule_set_id,rule_set_code,name,active_version_id,shadow_version_id,previous_active_version_id,version,created_at,updated_at)"
                + " select ?,?,'合法性研判演示规则集',null,null,null,0,?,? where not exists (select 1 from rule_set where rule_set_id=?)",
                RULE_SET_ID, RULE_SET_CODE, ts(at), ts(at), RULE_SET_ID);
        version(VERSION_1, 1, "演示参数，尚未经业务方确认", at);
        version(VERSION_2, 2, "v2：C02-3 航线偏离容差放宽到 50 m（未激活，供影子/激活演示）", at);
        for (int i = 0; i < RULE_CODES.length; i++) {
            member(VERSION_1, "seed-stage7-rule-" + RULE_CODES[i], PRIORITIES[i]);
            member(VERSION_2, "seed-stage7-rule-" + RULE_CODES[i], PRIORITIES[i]);
        }
        for (String[] p : DEMO_PARAMS) {
            param(VERSION_1, p[0], p[1], p[2], p[3], p[4]);
            boolean tolerance = RuleCodes.C02_3.equals(p[0]) && "tolerance_m".equals(p[1]);
            param(VERSION_2, p[0], p[1], tolerance ? V2_C02_3_TOLERANCE : p[2], p[3], p[4]);
        }
    }

    private void version(String id, int no, String description, Instant at) {
        jdbc.update("insert into rule_set_version (rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,valid_to,description,source_mode,created_at,published_at)"
                + " select ?,?,?,'PUBLISHED','DEMO',?,null,?,'mock',?,? where not exists (select 1 from rule_set_version where rule_set_version_id=?)",
                id, RULE_SET_ID, no, ts(at), description, ts(at), ts(at), id);
    }

    private void member(String versionId, String ruleVersionId, int priority) {
        jdbc.update("insert into rule_set_member (rule_set_version_id,rule_version_id,priority,enabled) select ?,?,?,true"
                + " where not exists (select 1 from rule_set_member where rule_set_version_id=? and rule_version_id=?)", versionId, ruleVersionId, priority, versionId, ruleVersionId);
    }

    private void param(String versionId, String ruleCode, String key, String value, String type, String unit) {
        // 已发布版本的参数在 PostgreSQL 上不可改：这里只补缺行，绝不 UPDATE 已存在的参数。
        jdbc.update("insert into rule_param (rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)"
                + " select ?,?,?,?,?,?,?,'DEMO','演示参数，尚未经业务方确认' where not exists (select 1 from rule_param where rule_set_version_id=? and rule_code=? and param_key=?)",
                stableId(versionId + ":" + ruleCode + ":" + key), versionId, ruleCode, key, value, type, unit, versionId, ruleCode, key);
    }

    /** 参数行主键限 36 字符：用稳定的名称 UUID 保证幂等，而不是拼接超长字符串。 */
    private static String stableId(String seed) { return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString(); }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
