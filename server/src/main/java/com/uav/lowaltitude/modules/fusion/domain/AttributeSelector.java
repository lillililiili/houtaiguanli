package com.uav.lowaltitude.modules.fusion.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;

/**
 * 属性优选（纯 Java，无 Spring）：决定一个目标本帧的位置、类别、身份、运动分别取自哪一路来源。
 * 所有权重与阈值只经 {@link FusionParams} 读取；代码里没有任何来源类型的硬编码优先级——
 * “类别取 EO、身份取 TDOA/5G-A”是 demo-v1 权重表的结果，而不是写死的分支。
 */
public final class AttributeSelector {
    public static final String ATTR_POSITION = "position", ATTR_MOTION = "motion", ATTR_CLASS = "class", ATTR_IDENTITY = "identity";
    public static final String REASON_NOT_REPORTED = "NOT_REPORTED", REASON_UNSUPPORTED = "UNSUPPORTED";
    public static final String TYPE_EO = "EO";
    /** 身份线索只信射频类来源：TDOA 与 5G-A；光电的“识别编号”不是唯一身份，不进 identity_clue。 */
    public static final Set<String> IDENTITY_TYPES = Set.of("TDOA", "FIVE_G_A");
    /** SourceEstimate.quality 里由 E1 写入的质量键。 */
    public static final String QUALITY_LATENCY_MS = "latency_ms", QUALITY_MISSES = "misses", QUALITY_ANOMALY_Z = "anomaly_z";

    public record UnknownField(String field, String reasonCode) { }

    public record Selection(String positionSourceId, String classSourceId, String identitySourceId, String motionSourceId,
            String classCode, Double classConfidence, String identityClue, Double identityConfidence,
            Double speedMps, Double headingDeg, boolean sourceSwitched, List<UnknownField> unknownFields) { }

    public Selection select(List<SourceEstimate> estimates, FusionParams params, String previousPositionSourceId) {
        List<UnknownField> unknown = new ArrayList<>();
        Optional<SourceEstimate> position = estimates.stream().filter(AttributeSelector::positioned)
                .max(Comparator.comparingDouble((SourceEstimate e) -> positionWeight(e, params)).thenComparing(SourceEstimate::sourceId, Comparator.reverseOrder()));
        String positionSourceId = position.map(SourceEstimate::sourceId).orElse(null);
        // 运动量与位置主源同源：速度/航向若混用别的来源，会与融合位置的时间基准和精度不一致。
        Double speed = position.map(SourceEstimate::speedMps).orElse(null);
        Double heading = position.map(SourceEstimate::headingDeg).orElse(null);
        if (speed == null) unknown.add(new UnknownField("speed_mps", REASON_NOT_REPORTED));
        if (heading == null) unknown.add(new UnknownField("heading_deg", REASON_NOT_REPORTED));

        Optional<SourceEstimate> classSource = estimates.stream().filter(e -> e.classCode() != null && effectiveWeight(e, ATTR_CLASS, params) > 0)
                .max(Comparator.comparingDouble((SourceEstimate e) -> effectiveWeight(e, ATTR_CLASS, params)).thenComparing(SourceEstimate::sourceId, Comparator.reverseOrder()));
        String classCode = classSource.map(SourceEstimate::classCode).orElse(null);
        // 类别置信度只来自 EO：雷达六值类别与融合箱类别没有置信度语义，不能把权重当置信度上报。
        Double classConfidence = classSource.filter(e -> TYPE_EO.equals(e.sourceType())).map(SourceEstimate::classConfidence).orElse(null);
        if (classConfidence == null) unknown.add(new UnknownField("classification_confidence", REASON_NOT_REPORTED));

        Optional<SourceEstimate> identitySource = estimates.stream().filter(e -> e.identityClue() != null && IDENTITY_TYPES.contains(e.sourceType()))
                .max(Comparator.comparingDouble((SourceEstimate e) -> effectiveWeight(e, ATTR_IDENTITY, params)).thenComparing(SourceEstimate::sourceId, Comparator.reverseOrder()));

        boolean switched = previousPositionSourceId != null && positionSourceId != null && !previousPositionSourceId.equals(positionSourceId);
        return new Selection(positionSourceId, classSource.map(SourceEstimate::sourceId).orElse(null),
                identitySource.map(SourceEstimate::sourceId).orElse(null), positionSourceId, classCode, classConfidence,
                identitySource.map(SourceEstimate::identityClue).orElse(null), identitySource.map(SourceEstimate::identityConfidence).orElse(null),
                speed, heading, switched, List.copyOf(unknown));
    }

    static boolean positioned(SourceEstimate e) {
        return e.longitude() != null && e.latitude() != null && e.accuracyM() != null && e.accuracyM() > 0;
    }

    /** 位置主源按 权重×质量/σ² 选取：精度差一个数量级的来源即使类型权重高也不该做主源。 */
    static double positionWeight(SourceEstimate e, FusionParams params) {
        return effectiveWeight(e, ATTR_POSITION, params) / (e.accuracyM() * e.accuracyM());
    }

    /** w_eff = weights[type][attr] · q_latency · q_loss · q_anomaly；缺来源类型的权重是部署错误，直接抛出。 */
    static double effectiveWeight(SourceEstimate e, String attribute, FusionParams params) {
        Map<String, Double> byType = params.weights().get(e.sourceType());
        if (byType == null || !byType.containsKey(attribute)) {
            throw new IllegalStateException("missing fusion weight for source type " + e.sourceType() + "." + attribute);
        }
        return byType.get(attribute) * qualityFactor(e, params);
    }

    /**
     * 质量因子：延迟、丢帧、异常各自把权重往下压。契约给的下限 0.2 不在 demo-v1 参数目录里，
     * 代码不得出现裸阈值，因此这里只夹在 [0,1]；全部来源都被压到 0 时由 WeightedFuser 退回纯 1/σ² 加权。
     */
    static double qualityFactor(SourceEstimate e, FusionParams params) {
        Map<String, Object> quality = e.quality() == null ? Map.of() : e.quality();
        double factor = 1;
        Double latency = number(quality.get(QUALITY_LATENCY_MS));
        if (latency != null) factor *= clamp(1 - latency / params.number("quality", "latency_penalty_ms"));
        Double misses = number(quality.get(QUALITY_MISSES));
        if (misses != null) factor *= clamp(1 - misses * params.number("quality", "loss_penalty_per_miss"));
        Double anomaly = number(quality.get(QUALITY_ANOMALY_Z));
        if (anomaly != null && anomaly > params.number("quality", "anomaly_zscore")) factor *= params.number("quality", "anomaly_downweight");
        return clamp(factor);
    }

    private static double clamp(double value) { return value < 0 ? 0 : (value > 1 ? 1 : value); }

    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
