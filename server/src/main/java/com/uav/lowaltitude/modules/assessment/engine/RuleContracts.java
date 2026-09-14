package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 阶段 7 规则引擎的冻结接口。执行者 1（引擎核心、C02、C03）与执行者 2（C01、C06、复核）只通过这里的类型协作，
 * 避免并行开发时互相改对方文件。所有阈值来自 {@link RuleParams}，代码里不得出现裸数字。
 */
public final class RuleContracts {

    private RuleContracts() {
    }

    /** 引擎运行模式：ACTIVE 写投影并可能生成告警；SHADOW 只记录研判，用于新版本影子验证。 */
    public enum RunMode { ACTIVE, SHADOW }

    /** 研判主体：实时目标或（手动触发时）计划本身。 */
    public enum SubjectKind { TARGET, PLAN }

    /** 输入状态的新鲜度；STALE/NO_STATE 直接得出 NOT_APPLICABLE，避免用过期位置判定合法性。 */
    public enum Freshness { FRESH, STALE, NO_STATE, REPLAY }

    /** C01 计划匹配等级。 */
    public enum PlanMatchCode { FULL, PARTIAL, NONE, UNDETERMINED, NOT_APPLICABLE }

    /** C03 四态（含不适用）。 */
    public enum LegalStatus { LEGAL, ABNORMAL, ILLEGAL, UNDETERMINED, NOT_APPLICABLE }

    /** 单条检查结果码，与阶段 3 checks JSON 的 result_code 一致。 */
    public enum ResultCode { PASS, FAIL, UNDETERMINED, NOT_APPLICABLE }

    public record Subject(SubjectKind kind, String subjectId, String ownerOrgId, String districtId, String sourceMode) { }

    /**
     * 目标最新状态快照。高度按两种基准分开保存，缺失即 null；引擎不得用一种基准推另一种。
     * confidence 优先取 fusion_confidence，缺则 classification_confidence，两者皆缺为 null。
     */
    public record TargetState(String targetId, String trackId, String uavSn, BigDecimal longitude, BigDecimal latitude,
            BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal speedMps, BigDecimal headingDeg,
            BigDecimal confidence, OffsetDateTime observedAt, OffsetDateTime receivedAt,
            /* 阶段 8.5：融合后的飞手/遥控器位置（target_latest_state.pilot_location），C02-6 超视距的输入；无则 null。 */
            BigDecimal pilotLongitude, BigDecimal pilotLatitude,
            /* 决策 8.5-28：飞手位置对应的观测时刻（target_latest_state.pilot_observed_at），可保留的飞手位置必须可追溯；无则 null。 */
            OffsetDateTime pilotObservedAt) {
        /** 阶段 7 的旧签名：无飞手位置。 */
        public TargetState(String targetId, String trackId, String uavSn, BigDecimal longitude, BigDecimal latitude,
                BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal speedMps, BigDecimal headingDeg,
                BigDecimal confidence, OffsetDateTime observedAt, OffsetDateTime receivedAt) {
            this(targetId, trackId, uavSn, longitude, latitude, altitudeAmslM, heightAglM, speedMps, headingDeg, confidence, observedAt, receivedAt, null, null, null);
        }
        /** 阶段 8.5 的 14 参签名：有飞手位置、无其观测时刻。 */
        public TargetState(String targetId, String trackId, String uavSn, BigDecimal longitude, BigDecimal latitude,
                BigDecimal altitudeAmslM, BigDecimal heightAglM, BigDecimal speedMps, BigDecimal headingDeg,
                BigDecimal confidence, OffsetDateTime observedAt, OffsetDateTime receivedAt,
                BigDecimal pilotLongitude, BigDecimal pilotLatitude) {
            this(targetId, trackId, uavSn, longitude, latitude, altitudeAmslM, heightAglM, speedMps, headingDeg, confidence, observedAt, receivedAt, pilotLongitude, pilotLatitude, null);
        }
    }

    /** 最近轨迹质量：点数与最大相邻间隔（秒）；用于 C03 质量门。 */
    public record TrackQuality(int pointCount, Long maxGapSeconds, boolean bridged) { }

    /** 候选计划事实（含其航线版本的走廊与高度带）。 */
    public record PlanFact(String planId, String routeVersionId, String uavSn, OffsetDateTime startAt, OffsetDateTime endAt,
            BigDecimal corridorWidthM, BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum,
            String ownerOrgId, String districtId) { }

    /** C01 输出：匹配等级、选中的计划、各维度结果与原因码。 */
    public record PlanMatch(PlanMatchCode code, PlanFact plan, Map<String, String> dimensions, List<String> reasonCodes) {
        public static PlanMatch notApplicable() {
            return new PlanMatch(PlanMatchCode.NOT_APPLICABLE, null, Map.of(), List.of());
        }
    }

    /**
     * 目标与某个生效空域版本的空间关系事实。relation ∈ COVERS|TOUCHES|DISJOINT|UNKNOWN；
     * TOUCHES 表示边界接触，没有业务政策前既不算命中也不算未命中。
     */
    public record AirspaceHit(String airspaceId, String airspaceVersionId, String kindCode, String relation,
            BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum,
            OffsetDateTime validFrom, OffsetDateTime validTo, String unknownReason) { }

    /** 目标到航线中心线的距离（米）与半宽；任一未知时给出 unknownReason 且数值为 null。 */
    public record RouteDistance(String routeVersionId, BigDecimal distanceM, BigDecimal halfWidthM, String unknownReason) { }

    /** 唯一的 PostGIS 依赖出口；H2 测试用 @TestConfiguration 提供桩实现。 */
    public interface SpatialFactPort {
        List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf);

        RouteDistance distanceToRoute(TargetState state, String routeVersionId);

        boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime asOf);
    }

    /** 规则参数读取；缺参数是部署错误，抛 IllegalStateException 而不是当成业务未知。 */
    public interface RuleParams {
        String ruleSetVersionId();

        String paramStatus(String ruleCode, String key);

        BigDecimal number(String ruleCode, String key);

        int integer(String ruleCode, String key);

        boolean bool(String ruleCode, String key);

        String string(String ruleCode, String key);

        List<String> list(String ruleCode, String key);
    }

    public record ParamRef(String key, String value, String status) { }

    public record EvidenceRef(String kind, String id) { }

    /** 单条规则命中明细；facts 只放安全字段，message 为中文可读解释（含 DEMO 标注）。 */
    public record HitDetail(String ruleCode, String ruleVersionId, ResultCode resultCode, String reasonCode,
            BigDecimal severity, Map<String, Object> facts, List<ParamRef> params, List<EvidenceRef> evidence, String message) { }

    /** 评估上下文：所有输入在进入规则前收集完毕，规则本身不再访问数据库。 */
    public record EvaluationContext(Subject subject, TargetState state, TrackQuality track, PlanMatch planMatch,
            List<AirspaceHit> airspaces, OffsetDateTime asOf, Freshness freshness, RunMode mode, String sourceMode) { }

    /** 单条规则检查；实现类无 Spring 依赖，便于纯 Java 决策表测试。 */
    public interface RuleCheck {
        String ruleCode();

        int defaultPriority();

        HitDetail evaluate(EvaluationContext context, RuleParams params);
    }
}
