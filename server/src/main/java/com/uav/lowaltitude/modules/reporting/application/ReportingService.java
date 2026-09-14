package com.uav.lowaltitude.modules.reporting.application;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.reporting.application.ReportPeriodResolver.ReportPeriod;
import com.uav.lowaltitude.modules.reporting.infrastructure.OperationsWorkbookWriter;
import com.uav.lowaltitude.modules.reporting.infrastructure.ReportingRepository;
import com.uav.lowaltitude.modules.reporting.infrastructure.ReportingRepository.Scope;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class ReportingService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final List<String> OBJECT_TYPES = List.of("无人机", "鸟", "未知", "识别中", "船", "车");
    static final List<String> RISK_LEVELS = List.of("超高风险", "高风险", "中风险", "低风险", "未识别");
    static final List<String> DURATION_BANDS = List.of("0-10", "10-30", "30-60", "60-120", "120以上");
    static final List<String> TRACK_BANDS = List.of("0-1", "1-5", "5-10", "10-20", "20以上");
    static final List<String> ALT_BANDS = List.of("0-50", "50-120", "120-300", "300-600", "600 以上");
    static final List<String> DISTRICTS = List.of("东营区", "广饶县", "河口区", "垦利区", "利津县", "东营港经济区");
    static final List<String> PENALTIES = List.of("警告", "罚款", "驱离");

    private static final String DURATION_EXPR = """
            CASE WHEN duration_min < 10 THEN '0-10'
                 WHEN duration_min < 30 THEN '10-30'
                 WHEN duration_min < 60 THEN '30-60'
                 WHEN duration_min < 120 THEN '60-120'
                 ELSE '120以上' END
            """;
    private static final String TRACK_EXPR = """
            CASE WHEN track_km < 1 THEN '0-1'
                 WHEN track_km < 5 THEN '1-5'
                 WHEN track_km < 10 THEN '5-10'
                 WHEN track_km < 20 THEN '10-20'
                 ELSE '20以上' END
            """;
    private static final String ALT_EXPR = """
            CASE WHEN altitude_amsl_m < 50 THEN '0-50'
                 WHEN altitude_amsl_m < 120 THEN '50-120'
                 WHEN altitude_amsl_m < 300 THEN '120-300'
                 WHEN altitude_amsl_m < 600 THEN '300-600'
                 ELSE '600 以上' END
            """;
    private static final String RISK_EXPR = """
            CASE WHEN risk_level IS NULL OR risk_level='' THEN '未识别' ELSE risk_level END
            """;

    private final AccessService access;
    private final ReportingRepository repository;
    private final AppClock clock;
    private final AppProperties properties;
    private final AuditService auditService;
    private final ReportPeriodResolver periodResolver;
    private final OperationsWorkbookWriter workbookWriter;

    public ReportingService(AccessService access, ReportingRepository repository, AppClock clock,
            AppProperties properties, AuditService auditService, ReportPeriodResolver periodResolver,
            OperationsWorkbookWriter workbookWriter) {
        this.access = access;
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
        this.auditService = auditService;
        this.periodResolver = periodResolver;
        this.workbookWriter = workbookWriter;
    }

    public OperationsReport operations(String fromText, String toText) {
        access.requireBusinessData("statistics.read");
        AuthUser user = AuthContext.require();
        DateRange range = range(fromText, toText);
        Scope scope = new Scope("ALL".equals(user.scopeMode()), user.userId());

        Map<String, Object> summaryRow = repository.targetSummary(range.from(), range.to(), scope);
        int total = number(summaryRow, "total");
        int punish = repository.caseCount(range.from(), range.to(), scope);
        Summary summary = new Summary(total, number(summaryRow, "illegal"), punish,
                number(summaryRow, "high_risk"), number(summaryRow, "uav"), number(summaryRow, "abnormal"));

        Map<String, Integer> dayPunish = toIntMap(repository.casesByDay(range.from(), range.to(), scope), "occurred_on", "punish");
        List<DayPoint> days = new ArrayList<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            days.add(new DayPoint(day.toString(), day.toString().substring(5), 0, 0, 0, 0));
        }
        Map<String, Integer> dayIndex = new LinkedHashMap<>();
        for (int i = 0; i < days.size(); i++) dayIndex.put(days.get(i).date(), i);
        for (Map<String, Object> row : repository.targetsByDay(range.from(), range.to(), scope)) {
            Integer index = dayIndex.get(dateKey(row.get("occurred_on")));
            if (index == null) continue;
            days.set(index, new DayPoint(days.get(index).date(), days.get(index).md(),
                    number(row, "total"), number(row, "illegal"),
                    dayPunish.getOrDefault(dateKey(row.get("occurred_on")), 0),
                    number(row, "high_risk")));
        }
        for (Map.Entry<String, Integer> entry : dayPunish.entrySet()) {
            Integer index = dayIndex.get(entry.getKey());
            if (index == null) continue;
            DayPoint current = days.get(index);
            if (current.punish() == 0 && entry.getValue() != 0) {
                days.set(index, new DayPoint(current.date(), current.md(), current.total(), current.illegal(),
                        entry.getValue(), current.highRisk()));
            }
        }

        Map<String, Integer> regionPunish = toIntMap(repository.casesByRegion(range.from(), range.to(), scope), "name", "punish");
        Map<String, Map<String, Object>> regionRows = new LinkedHashMap<>();
        for (Map<String, Object> row : repository.targetsByRegion(range.from(), range.to(), scope)) {
            regionRows.put(text(row, "name"), row);
        }
        List<RegionPoint> regions = new ArrayList<>();
        for (String name : DISTRICTS) {
            Map<String, Object> row = regionRows.getOrDefault(name, Map.of());
            regions.add(new RegionPoint(name, number(row, "total"), number(row, "illegal"),
                    regionPunish.getOrDefault(name, 0), number(row, "high_risk")));
        }
        regions.sort((a, b) -> Integer.compare(b.total(), a.total()));

        List<NamedCount> byType = fill(OBJECT_TYPES, repository.namedCounts("object_type", range.from(), range.to(), scope));
        List<NamedCount> byRisk = fill(RISK_LEVELS, repository.namedCounts(RISK_EXPR, range.from(), range.to(), scope));
        List<NamedCount> byDuration = fill(DURATION_BANDS, repository.namedCounts(DURATION_EXPR, range.from(), range.to(), scope));
        List<NamedCount> byTrack = fill(TRACK_BANDS, repository.namedCounts(TRACK_EXPR, range.from(), range.to(), scope));
        List<NamedCount> altBands = fill(ALT_BANDS, repository.namedCounts(ALT_EXPR, range.from(), range.to(), scope));
        List<NamedCount> byPenalty = fill(PENALTIES, repository.penaltyCounts(range.from(), range.to(), scope));

        List<PartnerRank> partners = new ArrayList<>();
        for (Map<String, Object> row : repository.partnerRanks(range.from(), range.to(), scope)) {
            partners.add(new PartnerRank(text(row, "name"), number(row, "case_count"), number(row, "fine")));
        }

        Map<String, Object> source = repository.sourceSummary(range.from(), range.to(), scope);
        int rows = number(source, "n");
        String sourceMode;
        boolean simulated;
        if (rows == 0) {
            sourceMode = properties.getSourceMode();
            simulated = false;
        } else {
            int mock = number(source, "mock_n");
            int live = number(source, "live_n");
            sourceMode = live == 0 ? "mock" : mock == 0 && live == rows ? "live" : "mixed";
            simulated = number(source, "simulated_n") == rows;
        }

        DeviceCounts devices = null;
        if (scope.allScope()) {
            Map<String, Object> deviceRow = repository.deviceCounts();
            int deviceTotal = number(deviceRow, "total");
            int online = number(deviceRow, "online");
            Double rate = deviceTotal == 0 ? null : Math.round(online * 1000.0 / deviceTotal) / 10.0;
            devices = new DeviceCounts(deviceTotal, online, rate);
        }

        return new OperationsReport(range.from().toString(), range.to().toString(), sourceMode, simulated,
                summary, devices, days, byRisk, byType, byDuration, byTrack, altBands, total, regions, byPenalty,
                partners);
    }

    public CsvExport exportCsv(String fromText, String toText, String ip, String userAgent) {
        OperationsReport report = operations(fromText, toText);
        AuthUser actor = AuthContext.require();
        auditService.recordStandalone(actor.userId(), actor.account(), actor.roleCode(), "statistics",
                "stats_export_requested", "stats", "CSV",
                "from=" + report.from() + "; to=" + report.to() + "; simulated=" + report.simulated(),
                "SUCCESS", ip, safeAgent(userAgent));
        return new CsvExport("operations-stats.csv", toCsv(report));
    }

    public ReportPreview preview(String reportType, String anchorDate) {
        ResolvedReport resolved = resolvedReport(reportType, anchorDate);
        return new ReportPreview(resolved.period().type().name(), resolved.period().anchor().toString(),
                resolved.period().label(), resolved.generatedAt().toEpochMilli(), resolved.report());
    }

    public ExcelExport exportExcel(String reportType, String anchorDate, String ip, String userAgent) {
        ResolvedReport resolved = resolvedReport(reportType, anchorDate);
        byte[] body = workbookWriter.write(resolved.period(), resolved.report(), resolved.generatedAt());
        AuthUser actor = AuthContext.require();
        auditService.recordStandalone(actor.userId(), actor.account(), actor.roleCode(), "statistics",
                "stats_export_requested", "stats_report", resolved.period().type().name(),
                "format=XLSX; report_type=" + resolved.period().type().name()
                        + "; from=" + resolved.report().from() + "; to=" + resolved.report().to()
                        + "; simulated=" + resolved.report().simulated(),
                "SUCCESS", ip, safeAgent(userAgent));
        return new ExcelExport(resolved.period().filename(), body);
    }

    private ResolvedReport resolvedReport(String reportType, String anchorDate) {
        ReportPeriod period = periodResolver.resolve(reportType, anchorDate);
        OperationsReport report = operations(period.from().toString(), period.to().toString());
        return new ResolvedReport(period, clock.now(), report);
    }

    private static String safeAgent(String userAgent) {
        String agent = userAgent == null ? "" : userAgent;
        return agent.length() > 512 ? agent.substring(0, 512) : agent;
    }

    private static String toCsv(OperationsReport report) {
        StringBuilder out = new StringBuilder();
        out.append(csv("分类")).append(',').append(csv("项目")).append(',').append(csv("指标")).append(',').append(csv("数值")).append('\n');
        line(out, "元数据", "统计区间", "开始日期", report.from());
        line(out, "元数据", "统计区间", "结束日期", report.to());
        line(out, "元数据", "数据来源", "source_mode", report.sourceMode());
        line(out, "元数据", "数据来源", "simulated", String.valueOf(report.simulated()));
        Summary summary = report.summary();
        line(out, "总览", "合计", "飞行/目标总次数", summary.total());
        line(out, "总览", "合计", "非法飞行次数", summary.illegal());
        line(out, "总览", "合计", "处罚案件数", summary.punish());
        line(out, "总览", "合计", "高风险目标数", summary.highRisk());
        line(out, "总览", "合计", "无人机次数", summary.uav());
        line(out, "总览", "合计", "异常目标数", summary.abnormal());
        DeviceCounts devices = report.devices();
        if (devices != null) {
            line(out, "总览", "设备", "接入设备总数", devices.total());
            line(out, "总览", "设备", "在线设备数", devices.online());
            if (devices.onlineRate() != null) line(out, "总览", "设备", "在线率%", devices.onlineRate());
        }
        for (DayPoint day : report.days()) {
            line(out, "分日", day.date(), "目标总次数", day.total());
            line(out, "分日", day.date(), "非法飞行", day.illegal());
            line(out, "分日", day.date(), "处罚案件", day.punish());
            line(out, "分日", day.date(), "高风险", day.highRisk());
        }
        for (RegionPoint region : report.regions()) {
            line(out, "区域", region.name(), "目标总次数", region.total());
            line(out, "区域", region.name(), "非法飞行", region.illegal());
            line(out, "区域", region.name(), "处罚案件", region.punish());
            line(out, "区域", region.name(), "高风险", region.highRisk());
        }
        for (NamedCount item : report.byRisk()) line(out, "风险等级", item.name(), "数量", item.value());
        for (NamedCount item : report.byType()) line(out, "目标类型", item.name(), "数量", item.value());
        for (NamedCount item : report.byDuration()) line(out, "飞行时长(分钟)", item.name(), "次数", item.value());
        for (NamedCount item : report.byTrack()) line(out, "轨迹长度(公里)", item.name(), "次数", item.value());
        line(out, "飞行高度", "海拔高 AMSL", "参与统计目标数", report.altTotal());
        for (NamedCount item : report.altBands()) line(out, "飞行高度(海拔米)", item.name(), "目标数", item.value());
        for (NamedCount item : report.byPenalty()) line(out, "处罚类型", item.name(), "案件数", item.value());
        for (PartnerRank partner : report.partners()) {
            line(out, "违规主体", partner.name(), "案件数", partner.caseCount());
            line(out, "违规主体", partner.name(), "罚款(元)", partner.fine());
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String category, String item, String metric, Object value) {
        out.append(csv(category)).append(',').append(csv(item)).append(',').append(csv(metric)).append(',')
                .append(csv(value == null ? "" : String.valueOf(value))).append('\n');
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private DateRange range(String fromText, String toText) {
        LocalDate today = clock.now().atZone(ZONE).toLocalDate();
        LocalDate from = parseDate(fromText, today.minusDays(29), "from");
        LocalDate to = parseDate(toText, today, "to");
        if (from.isAfter(to)) throw bad("VALIDATION_ERROR", "from 不能晚于 to");
        if (ChronoUnit.DAYS.between(from, to) > 365) throw bad("VALIDATION_ERROR", "统计区间不能超过 366 天");
        return new DateRange(from, to);
    }

    private static LocalDate parseDate(String text, LocalDate fallback, String name) {
        if (text == null || text.isBlank()) return fallback;
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException ex) {
            throw bad("VALIDATION_ERROR", name + " 必须是 YYYY-MM-DD");
        }
    }

    private static List<NamedCount> fill(List<String> names, List<Map<String, Object>> rows) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) values.put(text(row, "name"), number(row, "item_count"));
        List<NamedCount> result = new ArrayList<>();
        for (String name : names) result.add(new NamedCount(name, values.getOrDefault(name, 0)));
        return result;
    }

    private static Map<String, Integer> toIntMap(List<Map<String, Object>> rows, String key, String value) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = key.equals("occurred_on") ? dateKey(row.get(key)) : text(row, key);
            if (name != null) map.put(name, number(row, value));
        }
        return map;
    }

    private static String dateKey(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof LocalDate date) return date.toString();
        String text = String.valueOf(value);
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record DateRange(LocalDate from, LocalDate to) { }

    private record ResolvedReport(ReportPeriod period, Instant generatedAt, OperationsReport report) { }

    public record ReportPreview(String reportType, String anchorDate, String periodLabel, long generatedAt,
            OperationsReport report) { }

    public record ExcelExport(String filename, byte[] body) { }

    public record OperationsReport(String from, String to, String sourceMode, boolean simulated,
            Summary summary, DeviceCounts devices, List<DayPoint> days, List<NamedCount> byRisk,
            List<NamedCount> byType, List<NamedCount> byDuration, List<NamedCount> byTrack,
            List<NamedCount> altBands, int altTotal, List<RegionPoint> regions, List<NamedCount> byPenalty,
            List<PartnerRank> partners) { }

    public record Summary(int total, int illegal, int punish, int highRisk, int uav, int abnormal) { }

    public record DeviceCounts(int total, int online, Double onlineRate) { }

    public record DayPoint(String date, String md, int total, int illegal, int punish, int highRisk) { }

    public record NamedCount(String name, int value) { }

    public record RegionPoint(String name, int total, int illegal, int punish, int highRisk) { }

    public record PartnerRank(String name, int caseCount, int fine) { }

    public record CsvExport(String filename, String body) { }
}
