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
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ReportingService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final List<String> RISK_LEVELS = List.of("超高风险", "高风险", "中风险", "低风险", "未识别");
    static final List<String> ALT_BANDS = List.of("0 以下", "0-50", "50-120", "120-300", "300-600", "600 以上");
    static final List<String> DISTRICTS = List.of("东营区", "广饶县", "河口区", "垦利区", "利津县", "东营港经济区");

    private final AccessService access;
    private final ReportingRepository repository;
    private final AppClock clock;
    private final com.uav.lowaltitude.modules.identity.application.AccessControlService domainAccess;
    private final com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository targetRepository;
    private final com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository;
    private final AuditService auditService;
    private final ReportPeriodResolver periodResolver;
    private final OperationsWorkbookWriter workbookWriter;

    public ReportingService(AccessService access, ReportingRepository repository, AppClock clock,
            com.uav.lowaltitude.modules.identity.application.AccessControlService domainAccess,
            com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository targetRepository,
            com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository, AuditService auditService, ReportPeriodResolver periodResolver,
            OperationsWorkbookWriter workbookWriter) {
        this.access = access;
        this.repository = repository;
        this.clock = clock;
        this.domainAccess = domainAccess;
        this.targetRepository = targetRepository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
        this.periodResolver = periodResolver;
        this.workbookWriter = workbookWriter;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public OperationsReport operations(String fromText, String toText) {
        access.requireBusinessData("statistics.read");
        AuthUser user = AuthContext.require();
        DateRange range = range(fromText, toText);
        Scope scope = new Scope("ALL".equals(user.scopeMode()), user.userId());
        boolean targetsAllowed = allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.TARGET_READ);
        boolean legalityAllowed = targetsAllowed && allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.ASSESSMENT_READ);
        boolean risksAllowed = targetsAllowed && allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.RISK_READ);
        boolean casesAllowed = allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.PUNISHMENT_READ);
        boolean devicesAllowed = access.permissionCodes(user.roleCode()).contains("devices.read");
        var targets = targetsAllowed ? repository.targets(range.from(),range.to(),scope) : List.<ReportingRepository.TargetFact>of();
        var cases = casesAllowed ? repository.cases(range.from(),range.to(),scope) : List.<ReportingRepository.CaseFact>of();
        Map<String,com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TargetSummariesRow> summaries = new java.util.HashMap<>();
        for (int start=0; start<targets.size(); start+=500) {
            summaries.putAll(targetRepository.summaries(targets.subList(start,Math.min(start+500,targets.size())).stream().map(ReportingRepository.TargetFact::id).toList()));
        }
        Map<String,int[]> days = new LinkedHashMap<>();
        for(LocalDate date=range.from();!date.isAfter(range.to());date=date.plusDays(1)) days.put(date.toString(),new int[4]);
        Map<String,int[]> regions = new LinkedHashMap<>();
        DISTRICTS.forEach(name -> regions.put(name,new int[4]));
        Map<String,Integer> types=new LinkedHashMap<>(), risks=new LinkedHashMap<>(), altitudes=new LinkedHashMap<>(), penalties=new LinkedHashMap<>();
        RISK_LEVELS.forEach(name -> risks.put(name,0)); ALT_BANDS.forEach(name -> altitudes.put(name,0));
        var modes=new java.util.TreeSet<String>();
        int illegal=0,highRisk=0,uav=0,abnormal=0,altTotal=0,unknownLegality=0,unknownRisk=0;
        for(var target:targets) {
            modes.add(target.sourceMode());
            var state=summaries.get(target.id());
            String legal=state==null||state.legality()==null?null:state.legality().legalStatus();
            String risk=state==null||state.risk()==null?null:state.risk().severity();
            boolean bad="ILLEGAL".equals(legal), high="HIGH".equals(risk)||"CRITICAL".equals(risk);
            if(bad) illegal++; if(high) highRisk++;
            if(legal==null||(!"LEGAL".equals(legal)&&!"ILLEGAL".equals(legal)&&!"ABNORMAL".equals(legal)&&!"NOT_APPLICABLE".equals(legal))) { unknownLegality++; }
            if("ABNORMAL".equals(legal)) abnormal++;
            if(risk==null || !List.of("LOW","MEDIUM","HIGH","CRITICAL").contains(risk)) unknownRisk++;
            if("UAV".equalsIgnoreCase(target.type())) uav++;
            increment(types,typeLabel(target.type())); increment(risks,riskLabel(risk));
            var day=days.get(target.firstSeenAt().atZoneSameInstant(ZONE).toLocalDate().toString());
            var region=regions.computeIfAbsent(target.region(),key->new int[4]);
            for(var counts:List.of(day,region)) { counts[0]++;if(bad)counts[1]++;if(high)counts[3]++; }
            if(target.altitude()!=null) {
                double altitude=target.altitude().doubleValue();
                increment(altitudes,altitude<0?"0 以下":altitude<50?"0-50":altitude<120?"50-120":altitude<300?"120-300":altitude<600?"300-600":"600 以上");altTotal++;
            }
        }
        Map<String,List<ReportingRepository.CaseFact>> parties=new LinkedHashMap<>();
        int undecided=0;
        for(var item:cases) {
            modes.add(item.sourceMode());days.get(item.filedAt().atZoneSameInstant(ZONE).toLocalDate().toString())[2]++;
            regions.computeIfAbsent(item.region(),key->new int[4])[2]++;
            if(item.penaltyType()==null) undecided++; else increment(penalties,penaltyLabel(item.penaltyType()));
            parties.computeIfAbsent(item.party(),key->new ArrayList<>()).add(item);
        }
        List<PartnerRank> partners=parties.entrySet().stream().map(entry -> {
            boolean complete=entry.getValue().stream().allMatch(item->item.fineCents()!=null);
            java.math.BigDecimal fine=complete?entry.getValue().stream().map(ReportingRepository.CaseFact::fineCents).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add).movePointLeft(2):null;
            return new PartnerRank(entry.getKey(),entry.getValue().size(),fine);
        }).sorted(java.util.Comparator.comparingInt(PartnerRank::caseCount).reversed().thenComparing(PartnerRank::name)).limit(5).toList();
        Map<String,MetricAvailability> availability=new LinkedHashMap<>();
        availability.put("total",metric(targetsAllowed,0,"按首次发现时间去重统计新增目标"));
        availability.put("illegal",metric(legalityAllowed,unknownLegality,"按生成时最新研判统计明确非法目标；无明确结论的目标不计入"));
        availability.put("high_risk",metric(risksAllowed,unknownRisk,"按生成时最新风险等级统计高风险及超高风险目标；无等级目标不计入"));
        availability.put("punish",metric(casesAllowed,0,"按立案时间统计案件，移送及通知不计作立案"));
        availability.put("by_type",metric(targetsAllowed,0,"生成时目标类型"));
        availability.put("by_risk",metric(risksAllowed,unknownRisk,"无风险等级的目标归入未识别"));
        availability.put("by_duration",new MetricAvailability("UNAVAILABLE","尚无可靠的飞行时长汇总，不能用观测时间跨度代替",null));
        availability.put("by_track",new MetricAvailability("UNAVAILABLE","尚无排除断点及重复轨迹的可靠里程汇总",null));
        availability.put("alt_bands",metric(targetsAllowed,targets.size()-altTotal,"仅统计最新状态中有效海拔高度，缺失海拔不以离地高度替代"));
        availability.put("by_penalty",metric(casesAllowed,undecided,"仅统计有效决定书对应的已确认处罚结果；未形成有效结果的案件不计入"));
        availability.put("partners",metric(casesAllowed,undecided,"金额单位为元；主体存在未形成有效处罚结果的案件时金额暂不可统计"));
        availability.put("devices",metric(devicesAllowed,0,"当前权限范围设备台账与状态快照，排除已删除设备"));
        DeviceCounts devices=null;
        if(devicesAllowed) { var row=deviceRepository.overview();int total=number(row,"total"),online=number(row,"online");devices=new DeviceCounts(total,online,total==0?null:Math.round(online*1000.0/total)/10.0); }
        List<DayPoint> dayPoints=days.entrySet().stream().map(e->new DayPoint(e.getKey(),e.getKey().substring(5),value(targetsAllowed,e.getValue()[0]),value(legalityAllowed,e.getValue()[1]),value(casesAllowed,e.getValue()[2]),value(risksAllowed,e.getValue()[3]))).toList();
        List<RegionPoint> regionPoints=regions.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue()[0],a.getValue()[0])).map(e->new RegionPoint(e.getKey(),value(targetsAllowed,e.getValue()[0]),value(legalityAllowed,e.getValue()[1]),value(casesAllowed,e.getValue()[2]),value(risksAllowed,e.getValue()[3]))).toList();
        return new OperationsReport(range.from().toString(),range.to().toString(),modes.isEmpty()?"unknown":modes.size()==1?modes.first():"mixed",modes.contains("mock")||modes.contains("replay"),
            new Summary(value(targetsAllowed,targets.size()),value(legalityAllowed,illegal),value(casesAllowed,cases.size()),value(risksAllowed,highRisk),value(targetsAllowed,uav),value(legalityAllowed,abnormal)),devices,dayPoints,
            risksAllowed?counts(risks):List.of(),targetsAllowed?counts(types):List.of(),List.of(),List.of(),targetsAllowed?counts(altitudes):List.of(),value(targetsAllowed,altTotal),regionPoints,counts(penalties),partners,clock.now().toEpochMilli(),availability);
    }

    private boolean allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode permission) {
        try { domainAccess.require(permission);return true; }
        catch(ApiException ex) { if(ex.getStatus()!=HttpStatus.FORBIDDEN)throw ex;return false; }
    }
    private static Integer value(boolean available,int value) { return available?value:null; }
    private static MetricAvailability metric(boolean allowed,int missing,String reason) {
        return new MetricAvailability(!allowed?"UNAVAILABLE":missing>0?"PARTIAL":"AVAILABLE",!allowed?"当前账号无对应业务数据读取权限":reason,allowed?missing:null);
    }
    private static void increment(Map<String,Integer> values,String key) { values.merge(key,1,Integer::sum); }
    private static List<NamedCount> counts(Map<String,Integer> values) { return values.entrySet().stream().map(e->new NamedCount(e.getKey(),e.getValue())).toList(); }
    private static String typeLabel(String code) {
        if(code==null)return "未知";
        return switch(code.toUpperCase(java.util.Locale.ROOT)) { case "UAV" -> "无人机";case "BIRD" -> "鸟";case "BALLOON" -> "气球";case "KITE" -> "风筝";case "UNKNOWN" -> "未知";case "IDENTIFYING" -> "识别中";case "SHIP" -> "船";case "VEHICLE" -> "车";default -> code; };
    }
    private static String riskLabel(String code) { return code==null?"未识别":switch(code) { case "CRITICAL" -> "超高风险";case "HIGH" -> "高风险";case "MEDIUM" -> "中风险";case "LOW" -> "低风险";default -> "未识别"; }; }
    private static String penaltyLabel(String code) { return switch(code) { case "WARNING" -> "警告";case "FINE" -> "罚款";case "WARNING_AND_FINE" -> "警告并罚款";default -> code; }; }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
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

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
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
        return new ResolvedReport(period, Instant.ofEpochMilli(report.generatedAt()), report);
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
        line(out, "元数据", "生成时间", "generated_at", report.generatedAt());
        report.availability().forEach((key,metric) -> line(out,"指标口径",key,metric.status(),metric.reason()));
        Summary summary = report.summary();
        line(out, "总览", "合计", "新增目标数", summary.total());
        line(out, "总览", "合计", "非法目标数", summary.illegal());
        line(out, "总览", "合计", "处罚案件数", summary.punish());
        line(out, "总览", "合计", "高风险目标数", summary.highRisk());
        line(out, "总览", "合计", "新增无人机目标数", summary.uav());
        line(out, "总览", "合计", "异常目标数", summary.abnormal());
        DeviceCounts devices = report.devices();
        if (devices != null) {
            line(out, "总览", "设备", "接入设备总数", devices.total());
            line(out, "总览", "设备", "在线设备数", devices.online());
            if (devices.onlineRate() != null) line(out, "总览", "设备", "在线率%", devices.onlineRate());
        }
        for (DayPoint day : report.days()) {
            line(out, "分日", day.date(), "新增目标数", day.total());
            line(out, "分日", day.date(), "非法飞行", day.illegal());
            line(out, "分日", day.date(), "处罚案件", day.punish());
            line(out, "分日", day.date(), "高风险", day.highRisk());
        }
        for (RegionPoint region : report.regions()) {
            line(out, "区域", region.name(), "新增目标数", region.total());
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
                .append(csv(value == null ? "暂不可统计" : String.valueOf(value))).append('\n');
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

    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "暂不可统计" : String.valueOf(value);
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
            List<NamedCount> altBands, Integer altTotal, List<RegionPoint> regions, List<NamedCount> byPenalty,
            List<PartnerRank> partners, long generatedAt, Map<String,MetricAvailability> availability) { }

    public record MetricAvailability(String status, String reason, Integer missingCount) { }

    public record Summary(Integer total, Integer illegal, Integer punish, Integer highRisk, Integer uav, Integer abnormal) { }

    public record DeviceCounts(int total, int online, Double onlineRate) { }

    public record DayPoint(String date, String md, Integer total, Integer illegal, Integer punish, Integer highRisk) { }

    public record NamedCount(String name, int value) { }

    public record RegionPoint(String name, Integer total, Integer illegal, Integer punish, Integer highRisk) { }

    public record PartnerRank(String name, int caseCount, java.math.BigDecimal fine) { }

    public record CsvExport(String filename, String body) { }
}
