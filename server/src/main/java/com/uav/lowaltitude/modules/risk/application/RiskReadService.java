package com.uav.lowaltitude.modules.risk.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.platform.export.CsvExport;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.PageDto;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.RiskDto;
import com.uav.lowaltitude.modules.risk.domain.RiskState;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskQuery;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskRow;
import com.uav.lowaltitude.platform.api.ApiException;

@Service
public class RiskReadService {
    private static final Set<String> ALLOWED = Set.of("state", "severity", "plan_id", "occurred_from", "occurred_to",
            "owner_org_id", "district_id", "source_mode", "page", "size", "risk_type", "object_subtype",
            // 阶段 15 新增（决策 15-6 / 15-7）：写错的参数必须报错而不是被忽略。
            "sort", "order", "target_type");
    /* risk_type 在库里是自由文本（阶段 4 的 CHECK 只要求非空），已有数据用 ROUTE_DEVIATION 等值；
       这里不做白名单，否则会把合法的既有类型判成参数错误。SPACE_OBJECT 只是其中一个取值。 */
    private static final Set<String> STATES=Set.of("PENDING_VERIFICATION","PENDING_NOTIFICATION","NOTIFIED","ACKNOWLEDGED","EXCLUDED");
    private static final Set<String> SEVERITIES=Set.of("LOW","MEDIUM","HIGH","CRITICAL");
    private static final Set<String> SOURCE_MODES=Set.of("mock","replay","live");
    private final AccessControlService access;
    private final RiskRepository repository;
    private final com.uav.lowaltitude.platform.audit.AuditService audit;
    private final com.uav.lowaltitude.platform.time.AppClock clock;

    private final com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskReadService spaceRisk;

    public RiskReadService(AccessControlService access, RiskRepository repository,
            @org.springframework.context.annotation.Lazy com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskReadService spaceRisk,
            com.uav.lowaltitude.platform.audit.AuditService audit, com.uav.lowaltitude.platform.time.AppClock clock) {
        this.access = access; this.repository = repository; this.spaceRisk = spaceRisk;
        this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageDto<RiskDto> list(MultiValueMap<String, String> values) {
        // 鉴权必须先于参数解析，防止未授权调用者用 400/404 差异探测受保护接口。
        AccessDecision decision = access.require(PermissionCode.RISK_READ);
        // 原始请求只要出现 plan_id 就先要求关联读取动作；不能先解析其他坏参数泄露筛选能力。
        if(values.containsKey("plan_id"))access.require(PermissionCode.FLIGHT_READ);
        Request request = new Request(values);
        Page page = request.page();
        TimeRange occurred = request.timeRange();
        String planId=request.optional("plan_id",36);
        RiskQuery query = new RiskQuery(request.enumerated("state",STATES), request.enumerated("severity",SEVERITIES),
                planId, occurred.from, occurred.to, request.optional("owner_org_id", 36),
                request.optional("district_id", 36), request.enumerated("source_mode",SOURCE_MODES),
                request.optional("risk_type", 64), request.optional("object_subtype", 32),
                request.optional("target_type", 32));
        String sort = sortKey(values), order = orderDirection(values);
        long total = repository.count(query, decision);
        return new PageDto<>(repository.list(query, decision, page.offset(), page.size, sort, order).stream()
                .map(this::dto).toList(), page.page, page.size, total);
    }

    /**
     * 区域筛选项（决策 15-22）。权限与列表相同，不要求 `users.read`——
     * 原来页面拉系统管理的 `/districts`，业务角色没那项权限，筛选框是空的。
     */
    @Transactional(readOnly = true)
    public List<com.uav.lowaltitude.modules.risk.api.RiskDtos.DistrictOptionDto> districts() {
        AccessDecision decision = access.require(PermissionCode.RISK_READ);
        return repository.districts(decision).stream()
                .map(row -> new com.uav.lowaltitude.modules.risk.api.RiskDtos.DistrictOptionDto(
                        row.districtId(), row.name())).toList();
    }

    /** 与告警同一套白名单口径（决策 15-6）：白名单外直接 400，不悄悄回落到默认次序。 */
    private static String sortKey(MultiValueMap<String, String> values) {
        String sort = values.getFirst("sort");
        if (sort == null || sort.isBlank()) return null;
        if (!RiskRepository.SORT_KEYS.contains(sort)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "排序字段不在允许范围内");
        }
        return sort;
    }

    private static String orderDirection(MultiValueMap<String, String> values) {
        String order = values.getFirst("order");
        if (order == null || order.isBlank()) return null;
        if (!"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "排序方向只能是 asc 或 desc");
        }
        return order;
    }

    /** 列表导出（决策 15-7）：权限、范围、筛选、次序与列表完全一致；超上限拒绝而不是截断。 */
    // 导出要写一条审计，因此**不是只读事务**（决策 15-20）：
    // PostgreSQL 的只读事务禁止 INSERT（25006），标了 readOnly 的话这个接口在 PG 上必 500；
    // 而 H2 不落实只读语义，单测照样绿——又一处"H2 绿、PG 红"。
    @Transactional
    public org.springframework.http.ResponseEntity<byte[]> export(MultiValueMap<String, String> values) {
        AccessDecision decision = access.require(PermissionCode.RISK_READ);
        Request request = new Request(values);
        TimeRange occurred = request.timeRange();
        RiskQuery query = new RiskQuery(request.enumerated("state", STATES), request.enumerated("severity", SEVERITIES),
                request.optional("plan_id", 36), occurred.from, occurred.to, request.optional("owner_org_id", 36),
                request.optional("district_id", 36), request.enumerated("source_mode", SOURCE_MODES),
                request.optional("risk_type", 64), request.optional("object_subtype", 32),
                request.optional("target_type", 32));
        String sort = sortKey(values), order = orderDirection(values);
        long total = repository.count(query, decision);
        if (total > CsvExport.MAX_ROWS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXPORT_TOO_LARGE",
                    "导出行数超过 " + CsvExport.MAX_ROWS + " 条，请先缩小筛选范围");
        }
        List<List<String>> cells = repository.listForExport(query, decision, CsvExport.MAX_ROWS, sort, order).stream()
                .map(RiskReadService::exportRow).toList();
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "risks", "risks_exported", "risk", null,
                "filters=state=" + query.state() + ",severity=" + query.severity() + ",risk_type=" + query.riskType()
                        + ",target_type=" + query.targetType() + ",sort=" + sort + ",order=" + order
                        + "; rows=" + cells.size(), "SUCCESS", "", "");
        return CsvExport.response(CsvExport.fileName("risks", java.time.Instant.ofEpochMilli(clock.nowMillis())),
                EXPORT_HEADERS, cells);
    }

    /** 列头中文，与页面列一致。 */
    private static final List<String> EXPORT_HEADERS = List.of(
            "风险编号", "风险类型", "等级", "状态", "事由", "发生时间", "接收时间", "所属组织", "所属区域", "来源");

    private static List<String> exportRow(RiskRepository.RiskRow row) {
        // 枚举列翻中文（决策 15-32），与告警同一套字典，免得两张表对同一个码给出两个说法。
        return java.util.Arrays.asList(row.displayNo(),
                com.uav.lowaltitude.platform.export.CsvLabels.riskType(row.riskType()),
                com.uav.lowaltitude.platform.export.CsvLabels.severity(row.severity()),
                com.uav.lowaltitude.platform.export.CsvLabels.riskState(row.state()),
                row.reasonText(), exportTime(row.occurredAt()), exportTime(row.receivedAt()),
                row.ownerOrgName(), row.districtName(), row.sourceCode());
    }

    private static String exportTime(java.time.OffsetDateTime at) {
        return at == null ? null : EXPORT_TIME.format(at.toInstant());
    }

    private static final java.time.format.DateTimeFormatter EXPORT_TIME = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai"));

    @Transactional(readOnly = true)
    public RiskDto detail(String riskId) {
        AccessDecision decision = access.require(PermissionCode.RISK_READ);
        RiskRow row = repository.find(id(riskId), decision);
        if (row == null) throw notFound();
        return dto(row);
    }

    /** 空间事实是可空追加字段：没有事实的风险（阶段 4 的作业风险）整段缺省，不返回空对象。 */
    private com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.SpaceFactDto spaceFact(RiskRow row) {
        return spaceRisk == null ? null : spaceRisk.factOrNull(row.riskId());
    }

    public RiskDto dto(RiskRow row) {
        // 风险读取不隐含关联领域权限；即使有权限，也要再次校验关联对象仍属于风险的同一有效范围元组。
        String planId=visible(PermissionCode.FLIGHT_READ)&&repository.planReferenceVisible(row)?row.planId():null;
        String routeVersionId=visible(PermissionCode.ROUTE_READ)&&repository.routeReferenceVisible(row)?row.routeVersionId():null;
        String assessmentId=visible(PermissionCode.ASSESSMENT_READ)&&repository.assessmentReferenceVisible(row)?row.assessmentId():null;
        boolean targetVisible=visible(PermissionCode.TARGET_READ);
        String targetId=targetVisible&&repository.targetReferenceVisible(row)?row.targetId():null;
        String trackId=targetVisible&&repository.trackReferenceVisible(row)?row.trackId():null;
        return new RiskDto(row.riskId(), row.sourceRiskId(), planId, routeVersionId, assessmentId,
                targetId, trackId, row.riskType(), row.severity(), row.state(), row.reasonCode(), row.reasonText(),
                millis(row.occurredAt()), requiredMillis(row.receivedAt()), row.observedAltitudeM(), row.observedAltitudeDatum(),
                row.heightRelation(), row.sourceCode(), row.sourceMode(), row.ownerOrgId(), row.districtId(), row.version(),
                RiskState.verifiable(row.state())&&visible(PermissionCode.RISK_VERIFY) ? List.of("VERIFY") : List.of(),
                row.sourceName(), row.ownerOrgName(), row.districtName(), planId == null ? null : row.planNo(), targetId == null ? null : row.targetNo(),
                spaceFact(row), row.displayNo());
    }

    private boolean visible(PermissionCode permission){try{access.require(permission);return true;}catch(ApiException ignored){return false;}}

    public static String id(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 36) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return id;
    }
    public static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "RISK_NOT_FOUND", "飞行风险不存在"); }
    private static Long millis(OffsetDateTime value) { return value == null ? null : value.toInstant().toEpochMilli(); }
    private static long requiredMillis(OffsetDateTime value) { if (value == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误"); return value.toInstant().toEpochMilli(); }

    static final class Request {
        private final MultiValueMap<String, String> values;
        Request(MultiValueMap<String, String> values) {
            this.values = values;
            values.keySet().stream().filter(key -> !ALLOWED.contains(key)).findFirst().ifPresent(key -> { throw invalid(key + " 参数无效"); });
        }
        Page page() { int page=integer("page",1),size=integer("size",20); if(page<1||size<1||size>100)throw invalid("分页参数无效"); return new Page(page,size); }
        String optional(String name,int max) { if(!values.containsKey(name))return null; String value=single(name); if(value.length()>max)throw invalid(name+" 参数无效"); return value; }
        String enumerated(String name,Set<String> allowed){String value=optional(name,32);if(value!=null&&!allowed.contains(value))throw invalid(name+" 参数无效");return value;}
        TimeRange timeRange() {
            boolean hasFrom=values.containsKey("occurred_from"),hasTo=values.containsKey("occurred_to");
            if(!hasFrom&&!hasTo)return new TimeRange(null,null); if(hasFrom!=hasTo)throw badTime();
            try { OffsetDateTime from=Instant.ofEpochMilli(Long.parseLong(single("occurred_from"))).atOffset(ZoneOffset.UTC);
                OffsetDateTime to=Instant.ofEpochMilli(Long.parseLong(single("occurred_to"))).atOffset(ZoneOffset.UTC);
                if(!from.isBefore(to))throw badTime(); return new TimeRange(from,to); }
            catch(NumberFormatException ex){throw badTime();}
        }
        private int integer(String name,int fallback){if(!values.containsKey(name))return fallback;try{return Integer.parseInt(single(name));}catch(NumberFormatException ex){throw invalid("分页参数无效");}}
        private String single(String name){List<String> found=values.get(name);if(found==null||found.size()!=1||found.get(0)==null||found.get(0).isBlank())throw invalid(name+" 参数无效");return found.get(0).trim();}
        static ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
        static ApiException badTime(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TIME_RANGE","时间范围无效");}
    }
    record Page(int page,int size){int offset(){try{return Math.multiplyExact(page-1,size);}catch(ArithmeticException ex){throw Request.invalid("分页参数无效");}}}
    record TimeRange(OffsetDateTime from,OffsetDateTime to){ }
}
