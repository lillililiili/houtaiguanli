package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleParamLoader;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService;
import com.uav.lowaltitude.modules.risk.application.RiskIngestionService.TrustedRiskFact;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.AltitudeBand;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.CorridorRelation;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.Decision;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.Observation;
import com.uav.lowaltitude.modules.risk.application.spacerisk.C04DecisionTable.Trend;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort.AirportProximity;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort.SpaceObservation;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.RuleVersionRow;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.SpaceFactRow;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * C04/C05 评估：把空间事实交给决策表，命中的结论经 {@link RiskIngestionService} 入库。
 * 风险只能经 ingest 写：那里有唯一的来源校验、归属推导与 (source_id, source_risk_id) 幂等，
 * 绕过它直插 flight_risk 会绕过全部这些保证，并让同一次评估重复产生风险。
 */
@Service
public class SpaceRiskEvaluationService {
    public static final String RULE_SET_CODE = "SPACE-RISK-DEMO";
    public static final String RISK_TYPE = "SPACE_OBJECT";
    public static final String SOURCE_LIVE = "rule-engine-space-risk-live", SOURCE_MOCK = "rule-engine-space-risk-mock";
    public static final String STATUS_RUNNING = "RUNNING", STATUS_SUCCESS = "SUCCESS", STATUS_FAILED = "FAILED", STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String MESSAGE_SPATIAL_UNAVAILABLE = "SPATIAL_BACKEND_UNAVAILABLE";
    public static final String MESSAGE_PLAN_REQUIRED = "PLAN_REQUIRED";
    public static final String MESSAGE_NO_ACTIVE_RULE_SET = "NO_ACTIVE_RULE_SET";
    public static final String MESSAGE_DEMO_NOT_ALLOWED = "DEMO_PARAMS_NOT_ALLOWED";
    private static final String REASON_AIRPORT_ZONE = "SPACE_OBJECT_IN_AIRPORT_ZONE";

    private final C04DecisionTable decisionTable = new C04DecisionTable();
    private final SpaceRiskSpatialPort spatial;
    private final SpaceRiskRepository repository;
    private final RiskIngestionService ingestion;
    private final RuleParamLoader paramLoader;
    private final com.uav.lowaltitude.modules.assessment.engine.RuleEngineProperties ruleEngine;
    private final AppClock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public SpaceRiskEvaluationService(SpaceRiskSpatialPort spatial, SpaceRiskRepository repository,
            RiskIngestionService ingestion, RuleParamLoader paramLoader,
            com.uav.lowaltitude.modules.assessment.engine.RuleEngineProperties ruleEngine, AppClock clock,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.spatial = spatial; this.repository = repository; this.ingestion = ingestion;
        this.paramLoader = paramLoader; this.ruleEngine = ruleEngine; this.clock = clock; this.json = json;
    }

    /**
     * 一次评估：先落 RUNNING 行（无论成败都要留痕），再按规则代码执行，最后一次性收尾。
     * 收尾用条件更新（只能 RUNNING→终态），并发收尾不会把计数写第二遍。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SpaceRiskRepository.RunRow evaluate(String ruleCode, OffsetDateTime windowFrom, OffsetDateTime windowTo, String triggerKind, String actorId) {
        OffsetDateTime startedAt = clock.now().atOffset(ZoneOffset.UTC);
        String runId = UUID.randomUUID().toString();
        repository.insertRun(new SpaceRiskRepository.RunRow(runId, ruleCode, triggerKind, windowFrom, windowTo, STATUS_RUNNING,
                0, 0, 0, null, actorId, startedAt, null));
        // 没有 PostGIS 就没有可信的距离：如实记 UNAVAILABLE，不猜距离、也不把"没算"说成"没有风险"。
        if (!spatial.available()) {
            return finish(runId, STATUS_UNAVAILABLE, 0, 0, 0, MESSAGE_SPATIAL_UNAVAILABLE);
        }
        RuleVersionRow version = repository.activeRuleSetVersion(RULE_SET_CODE);
        // 决策 9-16：没有生效规则集就没有判定依据；这不是"评估失败"，而是"当前不可评估"。
        if (version == null) {
            return finish(runId, STATUS_UNAVAILABLE, 0, 0, 0, MESSAGE_NO_ACTIVE_RULE_SET);
        }
        // 演示阈值算出的风险在生产没有依据：allow-demo-active 未打开时不评估，也不产出任何风险。
        if ("DEMO".equals(version.paramStatus()) && !ruleEngine.isAllowDemoActive()) {
            return finish(runId, STATUS_UNAVAILABLE, 0, 0, 0, MESSAGE_DEMO_NOT_ALLOWED);
        }
        RuleParams params = paramLoader.load(version.ruleSetVersionId());
        try {
            return C04DecisionTable.RULE_CODE.equals(ruleCode)
                    ? runC04(runId, windowFrom, windowTo, params, version)
                    : runC05(runId, windowFrom, windowTo, params, version);
        } catch (RuntimeException ex) {
            return finish(runId, STATUS_FAILED, 0, 0, 0, message(ex));
        }
    }

    private SpaceRiskRepository.RunRow runC04(String runId, OffsetDateTime from, OffsetDateTime to, RuleParams params, RuleVersionRow version) {
        int pad = params.integer(C04DecisionTable.RULE_CODE, "plan_window_pad_min");
        List<SpaceObservation> observations = spatial.observations(from, to, pad);
        Set<String> targetsSeen = new LinkedHashSet<>();
        int created = 0, deduplicated = 0;
        for (SpaceObservation observation : observations) {
            targetsSeen.add(observation.targetId());
            CorridorRelation relation = decisionTable.relation(observation.distanceToRouteM(), observation.corridorHalfWidthM(), params);
            AltitudeBand band = decisionTable.band(observation.altitudeM(), observation.altitudeDatum(), observation.routeAltitudeDatum(), params);
            Trend trend = trend(observation.trend());
            Decision decision = decisionTable.decide(new Observation(relation, band, observation.planId() != null,
                    observation.objectCount(), trend), params);
            if (!decision.generate()) continue;
            String sourceRiskId = "C04:" + version.ruleSetVersionId() + ":" + observation.planId() + ":" + observation.targetId() + ":" + from.toInstant().toEpochMilli();
            String riskId = ingest(observation, decision, sourceRiskId, from);
            // space_risk_fact 只增：同一 source_risk_id 重复评估返回既有风险，事实行不重写，
            // 否则同一条风险的"判定依据"会被后一次评估的参数悄悄改掉。
            if (repository.factExists(riskId)) { deduplicated++; continue; }
            repository.insertFact(new SpaceFactRow(riskId, observation.subtypeCode(), null, ruleVersionId(version, C04DecisionTable.RULE_CODE),
                    version.ruleSetVersionId(), version.versionNo(), observation.distanceToRouteM(), relation.name(), band.name(),
                    observation.altitudeDatum(), observation.objectCount(), trend.name(), unknownReasons(decision),
                    observation.longitude(), observation.latitude(), observation.altitudeM(),
                    from, to, clock.now().atOffset(ZoneOffset.UTC)));
            created++;
        }
        return finish(runId, STATUS_SUCCESS, targetsSeen.size(), created, deduplicated, null);
    }

    /** C05：命中进离场缓冲或保护目标半径即为机场区域风险；没有活动计划时只统计，并如实说明缺前置条件。 */
    private SpaceRiskRepository.RunRow runC05(String runId, OffsetDateTime from, OffsetDateTime to, RuleParams params, RuleVersionRow version) {
        int pad = params.integer(C04DecisionTable.RULE_CODE, "plan_window_pad_min");
        BigDecimal procedureBuffer = params.number("C05", "procedure_buffer_m");
        BigDecimal protectedPad = params.number("C05", "protected_target_pad_m");
        List<AirportProximity> proximities = spatial.airportProximity(from, to, pad);
        Set<String> targetsSeen = new LinkedHashSet<>();
        int created = 0, deduplicated = 0, withoutPlan = 0;
        for (AirportProximity proximity : proximities) {
            targetsSeen.add(proximity.targetId());
            boolean hitProcedure = proximity.distanceToProcedureM() != null && proximity.distanceToProcedureM().compareTo(procedureBuffer) <= 0;
            boolean hitProtected = proximity.distanceToProtectedM() != null && proximity.distanceToProtectedM().compareTo(protectedPad) <= 0;
            if (!hitProcedure && !hitProtected) continue;
            if (proximity.planId() == null) { withoutPlan++; continue; }
            String sourceRiskId = "C05:" + version.ruleSetVersionId() + ":" + proximity.planId() + ":" + proximity.targetId() + ":" + from.toInstant().toEpochMilli();
            String riskId = ingestAirport(proximity, sourceRiskId, from);
            if (repository.factExists(riskId)) { deduplicated++; continue; }
            // C05 目前没有目标坐标查询（只算到程序/保护目标的距离），位置快照留空，页面不画点。
            repository.insertFact(new SpaceFactRow(riskId, proximity.subtypeCode(), null, ruleVersionId(version, "C05"), version.ruleSetVersionId(),
                    version.versionNo(), proximity.distanceToProcedureM(), CorridorRelation.UNKNOWN.name(), AltitudeBand.UNKNOWN.name(),
                    proximity.altitudeDatum(), proximity.objectCount(),
                    Trend.UNKNOWN.name(), write(List.of(C04DecisionTable.UNKNOWN_OBJECT_COUNT, C04DecisionTable.UNKNOWN_TREND)),
                    null, null, proximity.altitudeM(), from, to, clock.now().atOffset(ZoneOffset.UTC)));
            created++;
        }
        return finish(runId, STATUS_SUCCESS, targetsSeen.size(), created, deduplicated, withoutPlan > 0 ? MESSAGE_PLAN_REQUIRED : null);
    }

    private String ingest(SpaceObservation observation, Decision decision, String sourceRiskId, OffsetDateTime from) {
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        String mode = repository.targetSourceMode(observation.targetId());
        return ingestion.ingest(new TrustedRiskFact(sourceId(mode), sourceRiskId, observation.planId(), observation.routeVersionId(),
                null, observation.targetId(), null, RISK_TYPE, decision.severity(), decision.reasonCode(),
                reasonText(observation, decision), observation.observedAt(), now,
                // 高度与基准必须成对：只有数值没有基准时两者都不写，绝不猜基准。
                observation.altitudeDatum() == null ? null : observation.altitudeM(), observation.altitudeDatum(), mode));
    }

    private String ingestAirport(AirportProximity proximity, String sourceRiskId, OffsetDateTime from) {
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        String text = "机场区域异物：" + proximity.airportName()
                + (proximity.distanceToProcedureM() == null ? "" : "，距进离场程序 " + proximity.distanceToProcedureM().setScale(0, java.math.RoundingMode.HALF_UP) + " 米")
                + (proximity.distanceToProtectedM() == null ? "" : "，距保护目标 " + proximity.distanceToProtectedM().setScale(0, java.math.RoundingMode.HALF_UP) + " 米");
        String mode = repository.targetSourceMode(proximity.targetId());
        return ingestion.ingest(new TrustedRiskFact(sourceId(mode), sourceRiskId, proximity.planId(), proximity.routeVersionId(),
                null, proximity.targetId(), null, RISK_TYPE, "MEDIUM", REASON_AIRPORT_ZONE, text, proximity.observedAt(), now,
                proximity.altitudeDatum() == null ? null : proximity.altitudeM(), proximity.altitudeDatum(), mode));
    }

    private static String reasonText(SpaceObservation observation, Decision decision) {
        StringBuilder text = new StringBuilder("空中异物");
        if (observation.targetNo() != null) text.append("（").append(observation.targetNo()).append("）");
        if (observation.distanceToRouteM() != null) {
            text.append("距航线中心线 ").append(observation.distanceToRouteM().setScale(0, java.math.RoundingMode.HALF_UP)).append(" 米");
        }
        if (observation.objectCount() != null) text.append("，规模约 ").append(observation.objectCount());
        if (decision.escalated()) text.append("；因数量或趋势上调一级");
        return text.toString();
    }

    /** PostGIS 是计算能力，不是目标来源；回放和模拟目标绝不能因用了真实空间计算就变成 live。 */
    private String sourceId(String mode) {
        return switch (mode) {
            case "live" -> SOURCE_LIVE;
            case "mock" -> SOURCE_MOCK;
            case "replay" -> "rule-engine-space-risk-replay";
            default -> throw new IllegalStateException("未知目标来源模式");
        };
    }

    private static String ruleVersionId(RuleVersionRow version, String ruleCode) {
        return "space-risk-" + ruleCode.toLowerCase(java.util.Locale.ROOT) + "-v" + version.versionNo();
    }

    private static Trend trend(String value) {
        try { return value == null ? Trend.UNKNOWN : Trend.valueOf(value); }
        catch (IllegalArgumentException ex) { return Trend.UNKNOWN; }
    }

    /** 决策 9-18：把"缺了哪些事实"随风险一起存下来，页面才能解释为什么没有上调等级。 */
    private String unknownReasons(Decision decision) { return write(decision.unknownReasons()); }

    private String write(List<String> values) {
        try { return json.writeValueAsString(values); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("unknown_reasons unserializable", ex); }
    }

    private static String message(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 500));
    }

    private SpaceRiskRepository.RunRow finish(String runId, String status, int targetsSeen, int created, int deduplicated, String message) {
        repository.finishRun(runId, status, targetsSeen, created, deduplicated, message, clock.now().atOffset(ZoneOffset.UTC));
        return repository.findRun(runId);
    }
}
