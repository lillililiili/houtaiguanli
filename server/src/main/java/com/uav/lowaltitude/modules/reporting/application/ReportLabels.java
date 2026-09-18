package com.uav.lowaltitude.modules.reporting.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import static java.util.Map.entry;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Row;
import com.uav.lowaltitude.platform.export.CsvLabels;

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
        entry("PENDING_VERIFICATION","待核验"), entry("PENDING_NOTIFICATION","待通知"),
        entry("NOTIFIED","已通知"), entry("EXCLUDED","已排除"), entry("CONFIRMED","已确认"),
        entry("PENDING_DISPOSAL","待处置"), entry("DISPOSING","处置中"), entry("DISPOSED","已处置"),
        entry("PENDING_HANDOFF","待交接"), entry("HANDED_OFF","已交接"),
        entry("REQUESTED","待审批"), entry("APPROVED","已批准"), entry("REJECTED","已拒绝"),
        entry("EXECUTING","执行中"), entry("COMPLETED","已完成"), entry("FAILED","失败"),
        entry("SUCCESS","成功"), entry("CANCELLED","已取消"), entry("EXPIRED","已过期"),
        entry("STOPPED","已停止"), entry("TIMED_OUT","超时"),
        entry("PENDING_DELIVERY","待发送"), entry("SUBMITTED","已提交"), entry("DELIVERED","已送达"),
        entry("ACKNOWLEDGED","已回执"), entry("TIMEOUT","回执超时"), entry("NOT_EXPECTED","无需回执"),
        entry("NOT_TAKEN_OFF","未起飞"), entry("DEVICE_ABNORMAL","设备异常"), entry("NORMAL","正常"),
        entry("PLANNED","计划中"), entry("PENDING_EXECUTION","待执行"), entry("IN_PROGRESS","执行中"),
        entry("ACTIVE","进行中"), entry("FINISHED","已结束"), entry("mock","模拟数据"),
        entry("replay","回放数据"), entry("live","真实数据"), entry("mixed","混合数据"), entry("unknown","暂无数据"));
    public static String text(String value) { return value == null || value.isBlank() ? "未知" : LABELS.getOrDefault(value,value); }
    public static String text(String section, String field, String value) {
        if (value==null || value.isBlank()) return "未知";
        if (field.equals("severity")) return CsvLabels.severity(value);
        if (section.equals("alarms") || section.equals("events")) {
            if (field.equals("state")) return CsvLabels.uavEventState(value);
            if (field.equals("kind")) return CsvLabels.alarmType(value);
        }
        if (section.equals("risks")) {
            if (field.equals("state")) return value.equals("ACKNOWLEDGED")?"已回执":CsvLabels.riskState(value);
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
            case "plans" -> List.of(new Column("label","计划编号"),new Column("occurred_at","计划开始时间"),new Column("state","计划状态"),
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
            case "region" -> text(row.region()); case "related" -> row.related() == null ? "—" : row.related();
            case "note" -> row.note() == null ? "—" : row.note(); default -> "—";
        };
    }
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
