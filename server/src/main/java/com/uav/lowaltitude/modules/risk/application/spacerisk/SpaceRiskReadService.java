package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.BucketDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.MetricDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.PageDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.RuleVersionDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.RunDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.SpaceFactDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.SubtypeDto;
import com.uav.lowaltitude.modules.risk.api.SpaceRiskDtos.SummaryDto;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.CountRow;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.RunRow;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.SpaceFactRow;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.SubtypeRow;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.SummaryQuery;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/** 空间安全风险读侧与手动评估入口。读走 risk:read，评估走 risk:evaluate；范围谓词与风险列表同一份。 */
@Service
public class SpaceRiskReadService {
    static final String MODULE = "risk";
    private static final Set<String> SUMMARY_FILTERS = Set.of("from", "to", "owner_org_id", "district_id");
    private static final Set<String> RUN_FILTERS = Set.of("rule_code", "page", "size");
    private static final Set<String> EVALUATION_FIELDS = Set.of("rule_code", "window_from", "window_to");
    private static final Set<String> RULE_CODES = Set.of("C04", "C05");
    private static final String AVAILABILITY_NO_DATA = "NO_DATA";
    private static final int PAGE_DEFAULT = 20, PAGE_MAX = 100, ID_MAX = 36;
    /** KPI「鸟类事件」的口径：细类为鸟群的风险数。 */
    private static final String BIRD_SUBTYPE = "BIRD_FLOCK";
    private static final String STATE_PENDING = "PENDING_VERIFICATION";

    private final AccessControlService access;
    private final SpaceRiskRepository repository;
    private final RiskRepository risks;
    private final SpaceRiskEvaluationService evaluation;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public SpaceRiskReadService(AccessControlService access, SpaceRiskRepository repository, RiskRepository risks,
            SpaceRiskEvaluationService evaluation, IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper json) {
        this.access = access; this.repository = repository; this.risks = risks; this.evaluation = evaluation;
        this.idempotency = idempotency; this.audit = audit; this.clock = clock; this.json = json;
    }

    @Transactional(readOnly = true)
    public List<SubtypeDto> subtypes() {
        access.require(PermissionCode.RISK_READ);
        List<SubtypeDto> items = new ArrayList<>();
        for (SubtypeRow row : repository.listSubtypes()) {
            items.add(new SubtypeDto(row.subtypeCode(), row.displayName(), strings(row.aliasesJson()), row.enabled()));
        }
        return List.copyOf(items);
    }

    @Transactional(readOnly = true)
    public SpaceFactDto spaceFact(String riskId) {
        AccessDecision decision = access.require(PermissionCode.RISK_READ);
        String id = id(riskId);
        // 先按范围确认风险可见：不可见对象一律 404，不能靠"有没有事实"泄露它的存在。
        if (risks.find(id, decision) == null) throw new ApiException(HttpStatus.NOT_FOUND, "RISK_NOT_FOUND", "飞行风险不存在");
        SpaceFactRow row = repository.findFact(id);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "SPACE_FACT_NOT_FOUND", "该风险没有空间事实记录");
        return dto(row);
    }

    @Transactional(readOnly = true)
    public SummaryDto summary(MultiValueMap<String, String> parameters) {
        AccessDecision decision = access.require(PermissionCode.RISK_READ);
        checkKeys(parameters, SUMMARY_FILTERS);
        OffsetDateTime[] range = timeRange(parameters, "from", "to");
        SummaryQuery query = new SummaryQuery(range[0], range[1], optional(parameters, "owner_org_id", ID_MAX), optional(parameters, "district_id", ID_MAX));
        long total = repository.countRisks(query, decision);
        List<CountRow> bySubtype = repository.countBy("subtype", query, decision);
        List<CountRow> bySeverity = repository.countBy("severity", query, decision);
        List<CountRow> byState = repository.countBy("state", query, decision);
        List<CountRow> byBand = repository.countBy("altitude_band", query, decision);
        SpaceRiskRepository.RuleVersionRow version = repository.activeRuleSetVersion(SpaceRiskEvaluationService.RULE_SET_CODE);
        return new SummaryDto(buckets(bySubtype), buckets(bySeverity), buckets(byState), buckets(byBand),
                metric(total, total), metric(sum(bySeverity, "HIGH", "CRITICAL"), total), metric(sum(bySeverity, "MEDIUM"), total),
                metric(sum(bySubtype, BIRD_SUBTYPE), total), metric(sum(byState, STATE_PENDING), total),
                metric(repository.routesInvolved(query, decision), total),
                version == null ? null : new RuleVersionDto(version.ruleSetCode(), version.versionNo(), version.paramStatus()),
                clock.nowMillis());
    }

    @Transactional(readOnly = true)
    public PageDto<RunDto> runs(MultiValueMap<String, String> parameters) {
        access.require(PermissionCode.RISK_READ);
        checkKeys(parameters, RUN_FILTERS);
        String ruleCode = enumerated(parameters, "rule_code", RULE_CODES);
        Page page = page(parameters);
        long total = repository.countRuns(ruleCode);
        List<RunDto> items = repository.listRuns(ruleCode, page.offset(), page.size()).stream().map(SpaceRiskReadService::dto).toList();
        return new PageDto<>(items, page.page(), page.size(), total);
    }

    /**
     * 手动触发评估：先鉴权再解析 body，再占用幂等键，最后执行。
     * 本方法需要事务（幂等占位与成功审计同事务）；评估自身在 REQUIRES_NEW 里跑，
     * 它的运行记录必须独立留下——即使本次请求随后失败回滚，"评估确实执行过"也是事实。
     */
    @Transactional
    public RunDto trigger(String rawBody, String idempotencyKey) {
        access.require(PermissionCode.RISK_READ);
        access.require(PermissionCode.RISK_EVALUATE);
        Request request = parse(rawBody);
        idempotency.claim(idempotencyKey, framed("space-risk-evaluation") + framed(request.ruleCode())
                + framed(Long.toString(request.windowFrom().toInstant().toEpochMilli())) + framed(Long.toString(request.windowTo().toInstant().toEpochMilli())));
        AuthUser actor = AuthContext.require();
        RunRow run = evaluation.evaluate(request.ruleCode(), request.windowFrom(), request.windowTo(), "MANUAL", actor.userId());
        audit.record(actor.userId(), actor.account(), actor.roleCode(), MODULE, "rule_evaluation_triggered", "rule_evaluation_run", run.runId(),
                "rule_code=" + request.ruleCode() + "; status=" + run.status() + "; risks_created=" + run.risksCreated(), "SUCCESS", "", "");
        return dto(run);
    }

    public SpaceFactDto factOrNull(String riskId) {
        SpaceFactRow row = repository.findFact(riskId);
        return row == null ? null : dto(row);
    }

    private static List<BucketDto> buckets(List<CountRow> rows) {
        return rows.stream().map(row -> new BucketDto(row.bucket(), row.total())).toList();
    }

    /** 分母为 0 时不给数字：页面显示"暂无数据"而不是 0，避免把"没统计到"读成"没有风险"。 */
    private static MetricDto metric(long value, long denominator) {
        return denominator == 0 ? new MetricDto(null, AVAILABILITY_NO_DATA) : new MetricDto(value, "AVAILABLE");
    }

    private static long sum(List<CountRow> rows, String... buckets) {
        Set<String> wanted = Set.of(buckets);
        return rows.stream().filter(row -> wanted.contains(row.bucket())).mapToLong(CountRow::total).sum();
    }

    private SpaceFactDto dto(SpaceFactRow row) {
        return new SpaceFactDto(row.riskId(), row.subtypeCode(), row.subtypeName(), row.ruleVersionId(), row.ruleSetVersionId(),
                row.ruleSetVersionNo(), row.distanceToRouteM(), row.corridorRelation(), row.altitudeBand(), row.altitudeDatum(),
                row.objectCount(), row.trend(), strings(row.unknownReasonsJson()), row.longitude(), row.latitude(), row.targetAltitudeRaw(),
                row.windowFrom().toInstant().toEpochMilli(), row.windowTo().toInstant().toEpochMilli());
    }

    private static RunDto dto(RunRow row) {
        return new RunDto(row.runId(), row.ruleCode(), row.triggerKind(), row.windowFrom().toInstant().toEpochMilli(),
                row.windowTo().toInstant().toEpochMilli(), row.status(), row.targetsSeen(), row.risksCreated(), row.risksDeduplicated(),
                row.message(), row.actorId(), row.startedAt().toInstant().toEpochMilli(),
                row.finishedAt() == null ? null : row.finishedAt().toInstant().toEpochMilli());
    }

    private List<String> strings(String value) {
        if (value == null) return List.of();
        try {
            JsonNode node = json.readTree(value);
            if (node != null && node.isTextual()) node = json.readTree(node.textValue());
            if (node == null || !node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) if (item.isTextual()) out.add(item.textValue());
            return List.copyOf(out);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
        }
    }

    private Request parse(String raw) {
        if (raw == null || raw.isBlank()) throw invalidRequest();
        try (JsonParser parser = json.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) throw invalidRequest();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!EVALUATION_FIELDS.contains(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD", "请求体包含未知字段 " + name);
            }
            if (node.size() != EVALUATION_FIELDS.size()) throw invalidRequest();
            JsonNode code = node.get("rule_code"), from = node.get("window_from"), to = node.get("window_to");
            if (code == null || !code.isTextual() || !RULE_CODES.contains(code.textValue().trim())) throw invalidRequest();
            if (from == null || !from.isIntegralNumber() || to == null || !to.isIntegralNumber()) throw invalidRequest();
            if (from.longValue() >= to.longValue()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效");
            return new Request(code.textValue().trim(), Instant.ofEpochMilli(from.longValue()).atOffset(ZoneOffset.UTC),
                    Instant.ofEpochMilli(to.longValue()).atOffset(ZoneOffset.UTC));
        } catch (IOException ex) {
            throw invalidRequest();
        }
    }

    private static void checkKeys(MultiValueMap<String, String> parameters, Set<String> allowed) {
        parameters.keySet().stream().filter(key -> !allowed.contains(key)).findFirst()
                .ifPresent(key -> { throw invalid(key + " 参数无效"); });
    }

    private static Page page(MultiValueMap<String, String> parameters) {
        int page = integer(parameters, "page", 1), size = integer(parameters, "size", PAGE_DEFAULT);
        if (page < 1 || size < 1 || size > PAGE_MAX) throw invalid("分页参数无效");
        try { return new Page(page, size, Math.multiplyExact(page - 1, size)); }
        catch (ArithmeticException ex) { throw invalid("分页参数无效"); }
    }

    private static int integer(MultiValueMap<String, String> parameters, String name, int fallback) {
        if (!parameters.containsKey(name)) return fallback;
        try { return Integer.parseInt(single(parameters, name)); }
        catch (NumberFormatException ex) { throw invalid("分页参数无效"); }
    }

    private static String optional(MultiValueMap<String, String> parameters, String name, int max) {
        if (!parameters.containsKey(name)) return null;
        String value = single(parameters, name);
        if (value.length() > max) throw invalid(name + " 参数无效");
        return value;
    }

    private static String enumerated(MultiValueMap<String, String> parameters, String name, Set<String> allowed) {
        String value = optional(parameters, name, 16);
        if (value != null && !allowed.contains(value)) throw invalid(name + " 参数无效");
        return value;
    }

    private static OffsetDateTime[] timeRange(MultiValueMap<String, String> parameters, String fromKey, String toKey) {
        boolean hasFrom = parameters.containsKey(fromKey), hasTo = parameters.containsKey(toKey);
        if (!hasFrom && !hasTo) return new OffsetDateTime[] { null, null };
        if (hasFrom != hasTo) throw badTime();
        try {
            long from = Long.parseLong(single(parameters, fromKey)), to = Long.parseLong(single(parameters, toKey));
            if (from >= to) throw badTime();
            return new OffsetDateTime[] { Instant.ofEpochMilli(from).atOffset(ZoneOffset.UTC), Instant.ofEpochMilli(to).atOffset(ZoneOffset.UTC) };
        } catch (NumberFormatException ex) {
            throw badTime();
        }
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        List<String> found = parameters.get(name);
        if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid(name + " 参数无效");
        return found.get(0).trim();
    }

    static String id(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > ID_MAX) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return id;
    }

    private static String framed(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + ":" + value; }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static ApiException invalidRequest() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确"); }
    private static ApiException badTime() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); }

    record Request(String ruleCode, OffsetDateTime windowFrom, OffsetDateTime windowTo) { }
    record Page(int page, int size, int offset) { }
}
