package com.uav.lowaltitude.platform.export;

import java.util.Map;

/**
 * 导出正文里枚举列的中文（决策 15-32）。列头早就是中文了，正文却还是 HIGH / PENDING_VERIFICATION——
 * 一线人员拿到的是一份半中半英的表。
 *
 * 中文**逐字取自前端** dongying-vue：`src/ui/labels.js` 的 SEVERITY_LABEL / ALARM_TYPE_LABEL /
 * RISK_TYPE_LABEL / RISK_STATE_LABEL，以及 `src/services/workbenchEvents.js` 的 UAV_EVENT 状态表。
 * 导出与页面必须是同一套说法，否则同一条记录在屏幕上和表格里叫两个名字。
 *
 * **未知码原样输出**：编不出中文就把原码给出去，至少还能对照系统查；换成"未知"“—”会把信息抹掉。
 */
public final class CsvLabels {

    private CsvLabels() { }

    private static final Map<String, String> SEVERITY =
            Map.of("CRITICAL", "紧急", "HIGH", "高", "MEDIUM", "中", "LOW", "低");

    private static final Map<String, String> ALARM_TYPE =
            Map.of("UAV_INTRUSION", "无人机入侵", "UAV", "无人机告警", "RULE_LEGALITY", "飞行违规");

    /**
     * 告警状态取自 uav_event。注意与风险状态**不是**同一套：同样是 PENDING_VERIFICATION，
     * 告警说"待核实"、风险说"待核验"——这是页面上一直以来的区分（告警核实、风险核验），不是笔误，别去"统一"。
     */
    private static final Map<String, String> UAV_EVENT_STATE = Map.of(
            "PENDING_VERIFICATION", "待核实",
            "CONFIRMED", "已核实，待处置", "FALSE_POSITIVE", "误报");

    private static final Map<String, String> RISK_TYPE = Map.of(
            "FLIGHT_OPERATION", "飞行作业风险", "AIRSPACE", "空域风险",
            "SPACE_OBJECT", "空中异物风险", "FOREIGN_OBJECT", "空中异物风险");

    private static final Map<String, String> RISK_STATE = Map.of(
            "PENDING_VERIFICATION", "待核验", "PENDING_NOTIFICATION", "待通知",
            "NOTIFIED", "已通知", "EXCLUDED", "已排除");

    public static String severity(String code) { return label(SEVERITY, code); }

    public static String alarmType(String code) { return label(ALARM_TYPE, code); }

    public static String uavEventState(String code) { return label(UAV_EVENT_STATE, code); }

    public static String riskType(String code) { return label(RISK_TYPE, code); }

    public static String riskState(String code) { return label(RISK_STATE, code); }

    /** null 仍旧是 null——由 CsvExport 写成空单元格，不要在这里变成 "null" 或 "—"。 */
    private static String label(Map<String, String> dictionary, String code) {
        return code == null ? null : dictionary.getOrDefault(code, code);
    }
}
