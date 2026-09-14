package com.uav.lowaltitude.modules.flight.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.ActualsDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.AltitudeRelationDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.HitDetailDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.LatestRisksDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.LegalityDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.MatchDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.RiskItemDto;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightActualsRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightActualsRepository.EvaluationRow;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskQuery;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskRow;
import com.uav.lowaltitude.platform.api.ApiException;

import static com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.AVAILABLE;
import static com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.FORBIDDEN;
import static com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.NO_EVALUATION;
import static com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.UNAVAILABLE;

/**
 * 计划与实际对照的聚合读取（决策 9-11）。
 *
 * 进门先用 flight:read 取到计划：看不到计划就是 404，不能靠后面几段的报错差异探测它存在。
 * 之后五段各自独立取权限——研判段要 assessment:read、风险段要 risk:read、授权段只要 flight:read——
 * 缺哪段就把哪段标成 FORBIDDEN 且不带任何数量；有权限但没有数据是空列表或 NO_EVALUATION，两者含义不同。
 *
 * 匹配与高度关系都读同一条"该计划最近一次 ACTIVE 研判"，不在这里重算：
 * 前端和本服务都不做几何与高度计算，页面上看到的必须是引擎当时按某个规则集版本得出的结论。
 */
@Service
public class FlightActualsService {
    /** 沿线风险只给最近 5 条：详情栏是"最近发生了什么"，完整列表在风险页。 */
    private static final int LATEST_RISK_LIMIT = 5;
    private static final String C01 = "C01", C02_7 = "C02-7";
    private static final int ID_MAX = 36;

    private final AccessControlService access;
    private final FlightReadRepository plans;
    private final FlightActualsRepository evaluations;
    private final RiskRepository risks;
    private final ObjectMapper json;

    public FlightActualsService(AccessControlService access, FlightReadRepository plans, FlightActualsRepository evaluations,
            RiskRepository risks, ObjectMapper json) {
        this.access = access; this.plans = plans; this.evaluations = evaluations; this.risks = risks; this.json = json;
    }

    @Transactional(readOnly = true)
    public ActualsDto actuals(String planId) {
        AccessDecision flight = access.require(PermissionCode.FLIGHT_READ);
        PlanRow plan = requirePlan(planId, flight);

        AccessDecision assessment = probe(PermissionCode.ASSESSMENT_READ);
        EvaluationRow evaluation = assessment == null ? null : evaluations.findLatestActiveEvaluation(plan.planId(), assessment);
        List<HitDetailDto> hits = evaluation == null ? List.of() : hitDetails(evaluation.hitDetailsJson());

        return new ActualsDto(plan.planId(), plan.planNo(), match(assessment, evaluation, hits),
                altitudeRelation(assessment, evaluation, hits), latestRisks(plan.planId()),
                legality(assessment, evaluation));
    }

    private PlanRow requirePlan(String planId, AccessDecision flight) {
        PlanRow plan = plans.findPlan(identifier(planId), flight);
        if (plan == null) throw new ApiException(HttpStatus.NOT_FOUND, "FLIGHT_PLAN_NOT_FOUND", "飞行计划不存在");
        return plan;
    }

    private MatchDto match(AccessDecision assessment, EvaluationRow evaluation, List<HitDetailDto> hits) {
        if (assessment == null) return MatchDto.only(FORBIDDEN);
        if (evaluation == null) return MatchDto.only(NO_EVALUATION);
        List<HitDetailDto> c01 = hits.stream().filter(hit -> C01.equals(hit.ruleCode())).toList();
        return new MatchDto(AVAILABLE, evaluation.planMatchCode(), evaluation.evaluationId(),
                millis(evaluation.evaluatedAt()), evaluation.paramStatus(), c01, evaluation.targetId());
    }

    /**
     * 高度关系取 C02-7 的明细：引擎只在目标高度能落到计划航线的基准上时才写 target_altitude_m，
     * 因此"同基准"这件事已经由它保证；缺这个数就只能是 UNDETERMINED，并把不可判定原因原样带出。
     */
    private AltitudeRelationDto altitudeRelation(AccessDecision assessment, EvaluationRow evaluation, List<HitDetailDto> hits) {
        if (assessment == null) return AltitudeRelationDto.only(FORBIDDEN);
        if (evaluation == null) return AltitudeRelationDto.only(NO_EVALUATION);
        HitDetailDto hit = hits.stream().filter(item -> C02_7.equals(item.ruleCode())).findFirst().orElse(null);
        if (hit == null) return AltitudeRelationDto.only(UNAVAILABLE);
        Map<String, Object> facts = hit.facts() == null ? Map.of() : hit.facts();
        String datum = text(facts.get("altitude_datum"));
        BigDecimal min = decimal(facts.get("min_altitude_m")), max = decimal(facts.get("max_altitude_m"));
        BigDecimal target = decimal(facts.get("target_altitude_m"));
        if (target == null || min == null || max == null) {
            return new AltitudeRelationDto(AVAILABLE, "UNDETERMINED", datum, min, max, null,
                    hit.reasonCode() == null ? "ALTITUDE_DATUM_OR_RANGE_UNKNOWN" : hit.reasonCode());
        }
        String relation = target.compareTo(max) > 0 ? "ABOVE" : target.compareTo(min) < 0 ? "BELOW" : "WITHIN";
        return new AltitudeRelationDto(AVAILABLE, relation, datum, min, max, target, null);
    }

    private LatestRisksDto latestRisks(String planId) {
        AccessDecision risk = probe(PermissionCode.RISK_READ);
        if (risk == null) return LatestRisksDto.only(FORBIDDEN);
        RiskQuery query = new RiskQuery(null, null, planId, null, null, null, null, null, null, null, null);
        List<RiskItemDto> items = risks.list(query, risk, 0, LATEST_RISK_LIMIT, null, null).stream().map(FlightActualsService::risk).toList();
        return new LatestRisksDto(AVAILABLE, items);
    }

    private LegalityDto legality(AccessDecision assessment, EvaluationRow evaluation) {
        if (assessment == null) return LegalityDto.only(FORBIDDEN);
        if (evaluation == null) return LegalityDto.only(NO_EVALUATION);
        return new LegalityDto(AVAILABLE, evaluation.legalStatus(), evaluation.evaluationId(),
                millis(evaluation.evaluatedAt()), evaluation.paramStatus());
    }


    private static RiskItemDto risk(RiskRow row) {
        return new RiskItemDto(row.riskId(), row.sourceRiskId(), row.riskType(), row.severity(), row.state(),
                row.reasonCode(), row.reasonText(), millis(row.occurredAt()), row.receivedAt().toInstant().toEpochMilli());
    }

    /**
     * hit_details 在 PostgreSQL 是 jsonb、在 H2 是 JSON，驱动可能把它作为"被引号包住的字符串"返回，
     * 因此先 readTree，若拿到的是文本节点再解一次。存量 JSON 读不动时不编造空结论，直接给 500。
     */
    private List<HitDetailDto> hitDetails(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return List.of();
        try {
            JsonNode root = json.readTree(rawJson);
            if (root.isTextual()) root = json.readTree(root.textValue());
            if (!root.isArray()) throw invalidStoredJson();
            List<HitDetailDto> details = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.isObject()) throw invalidStoredJson();
                details.add(new HitDetailDto(node.path("rule_code").asText(null), node.path("result_code").asText(null),
                        node.path("reason_code").isNull() ? null : node.path("reason_code").asText(null),
                        node.path("message").isNull() ? null : node.path("message").asText(null), facts(node.get("facts"))));
            }
            return List.copyOf(details);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalidStoredJson();
        }
    }

    private Map<String, Object> facts(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return Map.of();
        Map<String, Object> facts = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> facts.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
        return Map.copyOf(facts);
    }

    private AccessDecision probe(PermissionCode permission) {
        try { return access.require(permission); } catch (ApiException ignored) { return null; }
    }

    static String identifier(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > ID_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        }
        return id;
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal number) return number;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String text && !text.isBlank()) {
            try { return new BigDecimal(text.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static Long millis(OffsetDateTime value) { return value == null ? null : value.toInstant().toEpochMilli(); }

    private static ApiException invalidStoredJson() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
    }
}
