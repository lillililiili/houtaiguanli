package com.uav.lowaltitude.modules.reporting.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Row;
import com.uav.lowaltitude.platform.export.CsvLabels;
import com.uav.lowaltitude.platform.report.DeviceDisplayNo;

public final class ReportLabels {
    private ReportLabels() { }
    private static final Map<String,String> LABELS = Map.ofEntries(
        entry("UNKNOWN","未知"), entry("NOT_VERIFIED","未核验"), entry("PENDING","待处理"), entry("HANDLED","已处理"),
        entry("EVIDENCE_REQUIRED","待补充证据"), entry("FALSE_POSITIVE","误报"),
        entry("radar","雷达"), entry("oe","光电"), entry("5ga","5G-A"), entry("tdoa","TDOA 定位"),
        entry("aoa","AOA 定位"), entry("dcd","协议破解"), entry("rid","Remote ID"), entry("ifr","射频侦测"), entry("other","其他"),
        entry("COUNTERMEASURE","反制"), entry("JAMMING","干扰"), entry("DISPERSAL","驱离"), entry("DECOY","诱骗"),
        entry("ONLINE","在线"), entry("OFFLINE","离线"), entry("GOOD","良好"), entry("BAD","异常"),
        entry("ABNORMAL","异常"), entry("DEGRADED","一般"), entry("UAV","无人机"), entry("BIRD","鸟类"),
        entry("HIGH","高"), entry("MEDIUM","中"), entry("LOW","低"), entry("CRITICAL","严重"),
        entry("READY_TO_COMPLETE","待确认办结"), entry("LEGACY_HANDLED","历史已反馈"), entry("PENDING_VERIFICATION","待核验"), entry("PENDING_NOTIFICATION","待通知"),
        // NOTIFIED 一律说"通知已提交"：提交成功不等于对方收到，写"已通知"会让人以为对方已经知道了（ZT-48）。
        entry("NOTIFIED","通知已提交"), entry("EXCLUDED","已排除"), entry("CONFIRMED","已确认"),
        entry("PENDING_DISPOSAL","待处置"), entry("DISPOSING","处置中"), entry("DISPOSED","已处置"),
        entry("PENDING_HANDOFF","待交接"), entry("HANDED_OFF","已交接"),
        entry("REQUESTED","待审批"), entry("APPROVED","已批准"), entry("REJECTED","已拒绝"),
        entry("EXECUTING","执行中"), entry("COMPLETED","已完成"), entry("FAILED","失败"),
        entry("SUCCESS","成功"), entry("CANCELLED","已取消"), entry("EXPIRED","已过期"),
        entry("STOPPED","已停止"), entry("TIMED_OUT","超时"),
        entry("PENDING_DELIVERY","待发送"), entry("SUBMITTED","已提交"), entry("DELIVERED","已送达"),
        entry("PENDING_DECISION","待决定通知"), entry("DO_NOT_NOTIFY","已选择不通知"),
        entry("ACKNOWLEDGED","已回执"), entry("TIMEOUT","回执超时"), entry("NOT_EXPECTED","无需回执"),
        entry("NOT_TAKEN_OFF","未起飞"), entry("DEVICE_ABNORMAL","设备异常"), entry("NORMAL","正常"),
        entry("PLANNED","已安排"), entry("PENDING_EXECUTION","待执行"), entry("IN_PROGRESS","执行中"),
        entry("ACTIVE","进行中"), entry("FINISHED","已结束"), entry("mock","模拟数据"),
        entry("replay","回放数据"), entry("live","真实数据"), entry("mixed","混合数据"),
        entry("simulated","模拟验收"), entry("unknown","暂无数据"),
        // 设备类型代码兜底（报表优先用台账 device_type_name，与设备管理页同一字段）。
        entry("EO","光电"), entry("eo","光电"), entry("countermeasure","反制设备"), entry("counter","四通道反制设备"),
        entry("weather","气象设备"), entry("weather_sensor","天气传感器"), entry("RADAR","雷达"),
        // 交接类型与设备执行结果（问题清单 #20：报表不再出现 UAV_PUNISHMENT、COUNTERMEASURE_SET_OK 等英文码）。
        entry("UAV_PUNISHMENT","处罚移送"), entry("RISK_NOTICE","风险通报"), entry("DEVICE_INCIDENT","设备异常"),
        entry("COUNTERMEASURE_SET_OK","设备已确认开启"), entry("COUNTERMEASURE_TCP_OK","设备连接正常"),
        entry("COUNTERMEASURE_QUERY_PASSED","设备状态查询通过"), entry("COUNTERMEASURE_STATE_MISMATCH","设备状态与指令不一致"),
        entry("COUNTERMEASURE_OUTCOME_UNKNOWN","设备执行结果待确认"), entry("COUNTERMEASURE_COMPLETED","反制已完成"),
        entry("JAMMING_COMPLETED","干扰已完成"), entry("DEVICE_SUCCEEDED","设备执行成功"), entry("DEVICE_FAILED","设备执行失败"),
        entry("DEVICE_TIMED_OUT","设备回执超时"), entry("DEVICE_TIMEOUT","设备回执超时"), entry("DEVICE_ALL_OFF_ISSUED","已下发全部关闭"),
        entry("DEVICE_OFFLINE","设备离线"), entry("DEVICE_BUSY","设备忙"), entry("DEVICE_NOT_BOUND","设备未登记控制连接"),
        entry("ADAPTER_TIMEOUT","设备回执超时"), entry("ADAPTER_UNAVAILABLE","设备通道不可用"), entry("ADAPTER_RETRY_EXHAUSTED","重试后仍未成功"),
        entry("COMMAND_FAILED","指令执行失败"), entry("COMMAND_EXPIRED","指令已过期"), entry("MANUAL_RESULT","人工登记结果"),
        entry("LINGYUN_CONTROL_SUCCEEDED","设备执行成功"), entry("LINGYUN_CONTROL_FAILED","设备执行失败"),
        entry("LINGYUN_CONTROL_TIMED_OUT","设备回执超时"), entry("LINGYUN_CONTROL_QUEUED","指令排队中"),
        entry("LINGYUN_CONTROL_LATE_RESPONSE","设备回执迟到"), entry("PENDING_NOTIFICATION_DECISION","待决定通知"),
        entry("PASS","通过"), entry("FAIL","未通过"), entry("PROCESSING","处理中"),
        entry("PLANNED_OFFLINE","计划下线（模拟器已停止）"));
    /** 形如 FOO_BAR / foo_bar 的内部代码；未配中文时不直接显示（显示“其他”），区域、名称等中文值不受影响。 */
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^(?:[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+|[a-z][a-z0-9]*(?:_[a-z0-9]+)+)$");
    public static String text(String value) {
        if (value == null || value.isBlank()) return "未知";
        String label = LABELS.get(value);
        if (label != null) return label;
        return CODE.matcher(value).matches() ? "其他" : value;
    }
    public static String text(String section, String field, String value) {
        if (value==null || value.isBlank()) return "未知";
        if (field.equals("severity")) return CsvLabels.severity(value);
        if (section.equals("alarms") || section.equals("events")) {
            if (field.equals("state")) return CsvLabels.uavEventState(value);
            if (field.equals("kind")) return CsvLabels.alarmType(value);
        }
        // 设备异常处置单的办理阶段与“设备异常处理”工作台同一写法（docs/device-maintenance-workflow.md）。
        if (section.equals("maintenance") && field.equals("state")) {
            if (value.equals("COMPLETED")) return "已办结";
            if (value.equals("PENDING_VERIFICATION")) return "历史流程自动核验中";
        }
        if (section.equals("risks")) {
            if (field.equals("state")) return CsvLabels.riskState(value);
            if (field.equals("kind")) return CsvLabels.riskType(value);
        }
        return text(value);
    }
    public static String time(Long millis) {
        return millis == null ? "—" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(millis));
    }
    public record Column(String field, String label) { }
    public static List<Column> columns(String key) {
        return switch (key) {
            case "devices" -> List.of(new Column("related","设备编号"),new Column("label","设备名称"),new Column("state","连接状态"),
                    new Column("kind","设备类型"),new Column("result","健康状态"),new Column("region","区域"),new Column("source_mode","来源"));
            case "maintenance" -> List.of(new Column("label","异常设备"),new Column("related","设备编号"),new Column("occurred_at","通知时间"),
                    new Column("state","处理状态"),new Column("note","异常原因"),new Column("result","处理结果"),new Column("region","区域"),new Column("source_mode","来源"));
            case "plans" -> List.of(new Column("label","任务编号"),new Column("occurred_at","任务开始时间"),new Column("state","任务状态"),
                    new Column("result","最新核验"),new Column("note","核验说明"),new Column("region","区域"),new Column("source_mode","来源"));
            case "authorizations","handoffs" -> List.of(new Column("label",key.equals("handoffs")?"接收方":"授权编号"),
                    new Column("related","关联事件 ID"),new Column("occurred_at","关联事件创建时间"),new Column("kind","类型"),
                    new Column("state",key.equals("handoffs")?"发送状态":"授权状态"),new Column("result",key.equals("handoffs")?"回执状态":"执行结果"),
                    new Column("note","结果说明"),new Column("source_mode","来源"));
            default -> List.of(new Column("label","业务编号"),new Column("occurred_at","统计时间"),new Column("kind","类型"),
                    new Column("severity","等级"),new Column("state","当前状态"),new Column("region","区域"),new Column("note","说明"),new Column("source_mode","来源"));
        };
    }
    public static String cell(String section, Row row, String field) {
        return switch(field) {
            case "label" -> row.label() == null ? row.id() : row.label();
            case "occurred_at" -> time(row.occurredAt());
            case "state" -> text(section,field,row.state()); case "kind" -> text(section,field,row.kind()); case "severity" -> text(section,field,row.severity());
            case "result" -> text(row.result()); case "source_mode" -> text(row.sourceMode());
            case "region" -> text(row.region());
            case "related" -> row.related() == null ? "—" : deviceSection(section) ? DeviceDisplayNo.format(row.related()) : row.related();
            case "note" -> row.note() == null ? "—" : row.note(); default -> "—";
        };
    }
    /** 设备快照与运维待办的“设备编号”按设备管理页的显示编号写，原始编号只用于关联。 */
    static boolean deviceSection(String section) { return section.equals("devices") || section.equals("maintenance"); }
    public static Map<String,String> dictionary() {
        Map<String,String> labels=new java.util.LinkedHashMap<>(LABELS);
        var codes=new java.util.LinkedHashSet<>(LABELS.keySet());
        codes.addAll(List.of("UAV_INTRUSION","RULE_LEGALITY","FLIGHT_OPERATION","AIRSPACE","WEATHER","SPACE_OBJECT","FOREIGN_OBJECT"));
        for(String section:List.of("targets","devices","maintenance","alarms","risks","plans","events","authorizations","handoffs"))
            for(String field:List.of("state","kind","severity"))
                for(String code:codes) {
                    String translated=text(section,field,code);
                    if(!translated.equals(text(code))) labels.put(section+"."+field+"."+code,translated);
                }
        return labels;
    }
}
