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
import com.uav.lowaltitude.platform.query.StatisticsScope;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ReportingService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final List<String> RISK_LEVELS = List.of("超高风险", "高风险", "中风险", "低风险", "未识别");
    static final List<String> ALT_BANDS = List.of("0 以下", "0-50", "50-120", "120-300", "300-600", "600 以上");

    private final AccessService access;
    private final ReportingRepository repository;
    private final AppClock clock;
    private final com.uav.lowaltitude.modules.identity.application.AccessControlService domainAccess;
    private final com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository targetRepository;
    private final com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository legalityRepository;
    private final com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository;
    private final AuditService auditService;
    private final ReportPeriodResolver periodResolver;
    private final OperationsWorkbookWriter workbookWriter;
    private final ObservationMetricsService observationMetrics;

    public ReportingService(AccessService access, ReportingRepository repository, AppClock clock,
            com.uav.lowaltitude.modules.identity.application.AccessControlService domainAccess,
            com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository targetRepository,
            com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository legalityRepository,
            com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository, AuditService auditService, ReportPeriodResolver periodResolver,
            OperationsWorkbookWriter workbookWriter, ObservationMetricsService observationMetrics) {
        this.access = access;
        this.repository = repository;
        this.clock = clock;
        this.domainAccess = domainAccess;
        this.targetRepository = targetRepository;
        this.legalityRepository = legalityRepository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
        this.periodResolver = periodResolver;
        this.workbookWriter = workbookWriter;
        this.observationMetrics = observationMetrics;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public OperationsReport operations(String fromText, String toText) {
        return operations(fromText, toText, null);
    }

    public record OrganizationOption(String orgId, String name) { }
    public List<OrganizationOption> organizations() {
        access.requireBusinessData("statistics.read");
        AuthUser user = AuthContext.require();
        return repository.organizations(new Scope("ALL".equals(user.scopeMode()), user.userId())).stream()
                .map(row -> new OrganizationOption(row.orgId(), row.name())).toList();
    }

    public OperationsReport operations(String fromText, String toText, String ownerOrgId) {
        return operations(fromText, toText, ownerOrgId, true);
    }

    /** Page callers may omit the unused track metrics; full reports and exports keep the default. */
    public OperationsReport operations(String fromText, String toText, String ownerOrgId, boolean includeObservations) {
        access.requireBusinessData("statistics.read");
        AuthUser user = AuthContext.require();
        DateRange range = range(fromText, toText);
        if (ownerOrgId != null && (ownerOrgId.isBlank() || ownerOrgId.length() > 36
                || organizations().stream().noneMatch(option -> option.orgId().equals(ownerOrgId))))
            throw bad("INVALID_ORGANIZATION", "单位不存在或不在当前权限范围内");
        Scope scope = new Scope("ALL".equals(user.scopeMode()), user.userId(), ownerOrgId);
        boolean targetsAllowed = allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.TARGET_READ);
        boolean legalityAllowed = targetsAllowed && allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.ASSESSMENT_READ);
        boolean risksAllowed = targetsAllowed && allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.RISK_READ);
        boolean casesAllowed = allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.PUNISHMENT_READ);
        boolean devicesAllowed = access.permissionCodes(user.roleCode()).contains("devices.read");
        var targets = targetsAllowed ? repository.targets(range.from(),range.to(),scope) : List.<ReportingRepository.TargetFact>of();
        var cases = casesAllowed ? repository.cases(range.from(),range.to(),scope) : List.<ReportingRepository.CaseFact>of();
        TargetStates states = states(targets, legalityAllowed);
        Map<String,int[]> days = new LinkedHashMap<>();
        for(LocalDate date=range.from();!date.isAfter(range.to());date=date.plusDays(1)) days.put(date.toString(),new int[4]);
        int[] discoveryHours = new int[24];
        Map<String,int[]> regions = new LinkedHashMap<>();
        // Regions come only from scoped business facts; never prefill geographic sample names.
        Map<String,Integer> types=new LinkedHashMap<>(), risks=new LinkedHashMap<>(), altitudes=new LinkedHashMap<>(), penalties=new LinkedHashMap<>();
        RISK_LEVELS.forEach(name -> risks.put(name,0)); ALT_BANDS.forEach(name -> altitudes.put(name,0));
        var modes=new java.util.TreeSet<String>();
        int illegal=0,highRisk=0,uav=0,abnormal=0,altTotal=0,unknownLegality=0,unknownRisk=0;
        for(var target:targets) {
            modes.add(target.sourceMode());
            String legal=states.legal(target.id()), risk=states.risk(target.id());
            boolean bad="ILLEGAL".equals(legal), high="HIGH".equals(risk)||"CRITICAL".equals(risk);
            if(bad) illegal++; if(high) highRisk++;
            if(legal==null||(!"LEGAL".equals(legal)&&!"ILLEGAL".equals(legal)&&!"ABNORMAL".equals(legal)&&!"NOT_APPLICABLE".equals(legal))) { unknownLegality++; }
            if("ABNORMAL".equals(legal)) abnormal++;
            if(risk==null || !List.of("LOW","MEDIUM","HIGH","CRITICAL").contains(risk)) unknownRisk++;
            if("UAV".equalsIgnoreCase(target.type())) uav++;
            increment(types,typeLabel(target.type())); increment(risks,riskLabel(risk));
            var firstSeen = target.firstSeenAt().atZoneSameInstant(ZONE);
            discoveryHours[firstSeen.getHour()]++;
            var day=days.get(firstSeen.toLocalDate().toString());
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
        availability.put("total",metric(targetsAllowed,0,"按首次发现时间去重统计新增目标；被合并进其他目标的不另计"));
        availability.put("illegal",metric(legalityAllowed,unknownLegality,"与合法性研判页同一取法：每架无人机只取最新一次研判，判非法的计入"));
        availability.put("high_risk",metric(risksAllowed,unknownRisk,"数的是目标附近空中异物这类风险，不是告警等级：按生成时最新风险等级统计高风险及超高风险目标；没有风险记录的目标不计入"));
        availability.put("punish",metric(casesAllowed,0,"按立案时间统计案件，移送及通知不计作立案"));
        availability.put("discovery_hours",metric(targetsAllowed,0,"按北京时间首次发现小时汇总所选日期内新增目标；同一目标仅计一次，与新增目标总数同源"));
        availability.put("by_type",metric(targetsAllowed,0,"生成时目标类型"));
        availability.put("airborne_types",metric(targetsAllowed,0,"按已识别空中目标计算占比；人员、车、船、遥控器不计入，未知及识别中单列待识别"));
        availability.put("by_risk",metric(risksAllowed,unknownRisk,"按目标附近空中异物这类风险的等级分档，不是告警等级；没有风险记录的目标归入未识别"));
        availability.put("by_duration",new MetricAvailability("UNAVAILABLE","尚无可靠的飞行时长汇总，不能用观测时间跨度代替",null));
        availability.put("by_track",new MetricAvailability("UNAVAILABLE","尚无完整实际飞行里程依据；已观测里程单独列示",null));
        availability.put("alt_bands",metric(targetsAllowed,targets.size()-altTotal,"仅统计最新状态中有效海拔高度，缺失海拔不以离地高度替代"));
        availability.put("by_penalty",metric(casesAllowed,undecided,"仅统计有效决定书对应的已确认处罚结果；未形成有效结果的案件不计入"));
        availability.put("partners",metric(casesAllowed,undecided,"金额单位为元；主体存在未形成有效处罚结果的案件时金额暂不可统计"));
        availability.put("devices",metric(devicesAllowed,0,"当前权限范围设备台账与状态快照，排除已删除设备和系统自带的演示样例设备"));
        DeviceCounts devices=null;
        if(devicesAllowed) { var row=deviceRepository.overview(ownerOrgId,com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository.CountScope.STATISTICS,null);int total=number(row,"total"),online=number(row,"online");devices=new DeviceCounts(total,online,total==0?null:Math.round(online*1000.0/total)/10.0); }
        List<DayPoint> dayPoints=days.entrySet().stream().map(e->new DayPoint(e.getKey(),e.getKey().substring(5),value(targetsAllowed,e.getValue()[0]),value(legalityAllowed,e.getValue()[1]),value(casesAllowed,e.getValue()[2]),value(risksAllowed,e.getValue()[3]))).toList();
        List<RegionPoint> regionPoints=regions.entrySet().stream().sorted((a,b)->Integer.compare(b.getValue()[0],a.getValue()[0])).map(e->new RegionPoint(e.getKey(),value(targetsAllowed,e.getValue()[0]),value(legalityAllowed,e.getValue()[1]),value(casesAllowed,e.getValue()[2]),value(risksAllowed,e.getValue()[3]))).toList();
        var observations=includeObservations ? observationMetrics.operations(range.from(),range.to(),ownerOrgId) : null;
        if (observations != null) modes.addAll(observations.sourceModes());
        return new OperationsReport(range.from().toString(),range.to().toString(),modes.isEmpty()?"unknown":modes.size()==1?modes.first():"mixed",modes.contains("mock")||modes.contains("replay"),
            new Summary(value(targetsAllowed,targets.size()),value(legalityAllowed,illegal),value(casesAllowed,cases.size()),value(risksAllowed,highRisk),value(targetsAllowed,uav),value(legalityAllowed,abnormal)),devices,dayPoints,
            risksAllowed?counts(risks):List.of(),targetsAllowed?counts(types):List.of(),List.of(),List.of(),targetsAllowed?counts(altitudes):List.of(),value(targetsAllowed,altTotal),regionPoints,counts(penalties),partners,clock.now().toEpochMilli(),availability,ownerOrgId,
            observations, targetsAllowed ? java.util.stream.IntStream.range(0,24)
                .mapToObj(hour -> new HourPoint(hour,discoveryHours[hour])).toList() : List.of(), airborneTypes(targets, targetsAllowed));
    }

    /**
     * 某一天新增的目标，与运行统计选这一天时同一份取数（ZT-17 复测 2）。数据大屏的"今日感知目标"和"重点目标风险态势"用它，
     * 与运行统计的"新增目标数""各异物风险等级分布"才对得上：同一批目标（按首次发现时间归属，被合并的目标不另计，
     * 同一套来源与数据范围），同一套风险分档。只要求能读目标，风险分档另要能读风险，不要求能打开运行统计菜单。
     * 不能读目标时返回 null；能读目标、不能读风险时 risks 为 null。
     */
    public DayTargets dayTargets(LocalDate day) {
        if (!allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.TARGET_READ)) return null;
        AuthUser user = AuthContext.require();
        var targets = repository.targets(day, day, new Scope("ALL".equals(user.scopeMode()), user.userId()));
        int simulated = (int) targets.stream().filter(target -> StatisticsScope.SIMULATOR.equals(target.sourceMode())).count();
        if (!allowed(com.uav.lowaltitude.modules.identity.domain.PermissionCode.RISK_READ)) return new DayTargets(targets.size(), simulated, null);
        TargetStates states = states(targets, false);
        int critical=0,high=0,medium=0,low=0,unknown=0;
        for (var target : targets) {
            // 分档同"各异物风险等级分布"（riskLabel）：超高、高、中、低，其余（含没有风险记录）为未识别。
            String risk = states.risk(target.id());
            if ("CRITICAL".equals(risk)) critical++;
            else if ("HIGH".equals(risk)) high++;
            else if ("MEDIUM".equals(risk)) medium++;
            else if ("LOW".equals(risk)) low++;
            else unknown++;
        }
        return new DayTargets(targets.size(), simulated, new RiskTiers(critical, high, medium, low, unknown));
    }

    /**
     * 每个目标生成时的最新研判与风险，只认计入统计的记录；运行统计和大屏共用。
     * 研判结论取合法性研判页“全部无人机”给这个目标的那一条（正式模式、每架无人机只取最新一次、按当前类别只看无人机，
     * 同一套范围；2026-10-08 新-2 第 3 点），研判页选“全部”时的非法数就是这里的非法目标数。原先取这个目标任何模式里
     * 最后写入的一条，影子模式后写的结论、按写入时间而不是研判时间排的先后，都会让两边差几个。
     */
    private TargetStates states(List<ReportingRepository.TargetFact> targets, boolean withLegality) {
        Map<String,com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TargetSummariesRow> summaries = new java.util.HashMap<>();
        Map<String,com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.LatestLegality> legality = new java.util.HashMap<>();
        var formalEvaluations=new java.util.HashSet<String>(); var formalRisks=new java.util.HashSet<String>();
        var legalityScope = withLegality ? domainAccess.require(com.uav.lowaltitude.modules.identity.domain.PermissionCode.ASSESSMENT_READ) : null;
        for (int start=0; start<targets.size(); start+=500) {
            var batchIds=targets.subList(start,Math.min(start+500,targets.size())).stream().map(ReportingRepository.TargetFact::id).toList();
            if (legalityScope != null) {
                formalEvaluations.addAll(repository.formalEvaluationIds(batchIds));
                legality.putAll(legalityRepository.latestOnPage(batchIds, legalityScope));
            }
            formalRisks.addAll(repository.formalRiskIds(batchIds));
            summaries.putAll(targetRepository.summaries(batchIds));
        }
        return new TargetStates(summaries, legality, formalEvaluations, formalRisks);
    }

    private record TargetStates(Map<String,com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TargetSummariesRow> summaries,
            Map<String,com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository.LatestLegality> legality,
            java.util.Set<String> formalEvaluations, java.util.Set<String> formalRisks) {
        String legal(String targetId) {
            var latest=legality.get(targetId);
            return latest==null||!formalEvaluations.contains(latest.evaluationId())?null:latest.legalStatus();
        }
        String risk(String targetId) {
            var state=summaries.get(targetId);
            return state==null||state.risk()==null||!formalRisks.contains(state.risk().riskId())?null:state.risk().severity();
        }
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
        return switch(code.toUpperCase(java.util.Locale.ROOT)) { case "UAV" -> "无人机";case "BIRD" -> "鸟";case "BALLOON" -> "气球";case "KITE" -> "风筝";case "UNKNOWN" -> "未知";case "IDENTIFYING" -> "识别中";case "SHIP" -> "船";case "VEHICLE" -> "车";case "PERSON" -> "人员";case "REMOTE_CONTROLLER" -> "遥控器";default -> code; };
    }

    private static AirborneTypes airborneTypes(List<ReportingRepository.TargetFact> targets, boolean allowed) {
        if (!allowed) return new AirborneTypes(List.of(), null, null);
        Map<String,Integer> counts = new LinkedHashMap<>();
        int unidentified = 0;
        for (var target : targets) {
            String type = target.type() == null ? "UNKNOWN" : target.type().toUpperCase(java.util.Locale.ROOT);
            switch (type) {
                // BALLOON/KITE support existing classified historical records, not new target enums.
                case "UAV", "BIRD", "BALLOON", "KITE" -> increment(counts, typeLabel(type));
                case "PERSON", "VEHICLE", "SHIP", "REMOTE_CONTROLLER" -> { }
                default -> unidentified++;
            }
        }
        return new AirborneTypes(counts(counts), counts.values().stream().mapToInt(Integer::intValue).sum(), unidentified);
    }
    private static String riskLabel(String code) { return code==null?"未识别":switch(code) { case "CRITICAL" -> "超高风险";case "HIGH" -> "高风险";case "MEDIUM" -> "中风险";case "LOW" -> "低风险";default -> "未识别"; }; }
    private static String penaltyLabel(String code) { return switch(code) { case "WARNING" -> "警告";case "FINE" -> "罚款";case "WARNING_AND_FINE" -> "警告并罚款";default -> code; }; }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public CsvExport exportCsv(String fromText, String toText, String ip, String userAgent) {
        return exportCsv(fromText, toText, null, ip, userAgent);
    }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public CsvExport exportCsv(String fromText, String toText, String ownerOrgId, String ip, String userAgent) {
        OperationsReport report = operations(fromText, toText, ownerOrgId);
        AuthUser actor = AuthContext.require();
        auditService.recordStandalone(actor.userId(), actor.account(), actor.roleCode(), "statistics",
                "stats_export_requested", "stats", "CSV",
                "from=" + report.from() + "; to=" + report.to() + "; owner_org_id=" + ownerOrgId + "; simulated=" + report.simulated(),
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
        line(out, "元数据", "单位范围", "owner_org_id", report.ownerOrgId() == null ? "当前权限内全部单位" : report.ownerOrgId());
        line(out, "元数据", "数据来源", "source_mode", report.sourceMode());
        line(out, "元数据", "数据来源", "simulated", String.valueOf(report.simulated()));
        line(out, "元数据", "生成时间", "generated_at", report.generatedAt());
        report.availability().forEach((key,metric) -> line(out,"指标口径",key,metric.status(),metric.reason()));
        var observed = report.observationMetrics();
        if (observed != null) {
            line(out,"监测统计","状态",observed.status(),observed.reason());
            line(out,"监测统计","口径","说明",observed.basis());
            line(out,"监测统计","数据来源","来源模式",String.join("、",observed.sourceModes()));
            line(out,"监测统计","配置依据","版本",String.join("、",observed.configVersions()));
            line(out,"监测统计","合计","有效监测时长(秒)",observed.durationSeconds());
            line(out,"监测统计","合计","已观测里程(米)",observed.distanceMeters());
            line(out,"监测统计","合计","参与累计目标数",observed.measuredTargets());
            for (var day:observed.days()) {
                line(out,"监测统计",day.date(),"有效监测时长(秒)",day.durationSeconds());
                line(out,"监测统计",day.date(),"已观测里程(米)",day.distanceMeters());
            }
            for (var exclusion:observed.exclusions()) line(out,"监测统计","未计入",exclusion.reason(),exclusion.count());
        }
        Summary summary = report.summary();
        line(out, "总览", "合计", "新增目标数", summary.total());
        line(out, "总览", "合计", "非法目标数", summary.illegal());
        line(out, "总览", "合计", "处罚案件数", summary.punish());
        line(out, "总览", "合计", "异物高风险目标数", summary.highRisk());
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
            line(out, "分日", day.date(), "异物高风险", day.highRisk());
        }
        for (RegionPoint region : report.regions()) {
            line(out, "区域", region.name(), "新增目标数", region.total());
            line(out, "区域", region.name(), "非法飞行", region.illegal());
            line(out, "区域", region.name(), "处罚案件", region.punish());
            line(out, "区域", region.name(), "异物高风险", region.highRisk());
        }
        for (NamedCount item : report.byRisk()) line(out, "异物风险等级", item.name(), "数量", item.value());
        for (HourPoint item : report.discoveryHours()) line(out, "目标发现时段（北京时间）", item.label(), "新增目标数", item.total());
        for (NamedCount item : report.byType()) line(out, "目标类型", item.name(), "数量", item.value());
        for (NamedCount item : report.airborneTypes().items()) line(out, "空中目标类型", item.name(), "数量", item.value());
        line(out, "空中目标类型", "合计", "数量", report.airborneTypes().total());
        line(out, "待识别目标", "未计入空中目标占比", "数量", report.airborneTypes().unidentified());
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
            List<PartnerRank> partners, long generatedAt, Map<String,MetricAvailability> availability, String ownerOrgId,
            com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics.Result observationMetrics, List<HourPoint> discoveryHours,
            AirborneTypes airborneTypes) { }

    public record AirborneTypes(List<NamedCount> items, Integer total, Integer unidentified) { }

    public record MetricAvailability(String status, String reason, Integer missingCount) { }

    public record Summary(Integer total, Integer illegal, Integer punish, Integer highRisk, Integer uav, Integer abnormal) { }

    public record DeviceCounts(int total, int online, Double onlineRate) { }

    public record DayPoint(String date, String md, Integer total, Integer illegal, Integer punish, Integer highRisk) { }

    public record HourPoint(int hour, int total) {
        public String label() { return String.format(java.util.Locale.ROOT, "%02d:00-%02d:00", hour, hour + 1); }
    }

    public record NamedCount(String name, int value) { }

    public record RegionPoint(String name, Integer total, Integer illegal, Integer punish, Integer highRisk) { }

    public record PartnerRank(String name, int caseCount, java.math.BigDecimal fine) { }

    public record CsvExport(String filename, String body) { }

    /** 某一天新增的目标数、其中来自设备模拟器的个数、各风险等级的个数（不能读风险时为 null）。 */
    public record DayTargets(int total, int simulated, RiskTiers risks) { }

    /** 与"各异物风险等级分布"同一套分档：超高风险、高风险、中风险、低风险、未识别。 */
    public record RiskTiers(int critical, int high, int medium, int low, int unknown) { }
}
