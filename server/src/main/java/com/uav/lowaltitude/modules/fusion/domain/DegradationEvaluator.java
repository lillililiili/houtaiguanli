package com.uav.lowaltitude.modules.fusion.domain;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.uav.lowaltitude.modules.fusion.FusionContracts.DegradationLevel;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.domain.AttributeSelector.UnknownField;

/**
 * 降级评估（纯 Java）：按本帧可用来源数与类型给出等级与置信亏损；无源时逐帧累进，越过阈值即“不可判定”。
 * 阈值全部来自 FusionParams.degradation.*；代码里没有裸阈值。
 */
public final class DegradationEvaluator {
    /** 融合箱与雷达是“本地主传感器”一路：只剩它们时是 FUSION_BOX_ONLY 而不是普通单源。 */
    public static final Set<String> PRIMARY_SENSOR_TYPES = Set.of("RADAR", "FUSION_BOX");

    public record Degradation(DegradationLevel level, List<String> availableSourceIds, double deficit, boolean determined,
            Double fusionConfidence, List<UnknownField> unknownFields) { }

    /**
     * @param previousDeficit 上一帧的亏损（首帧为 null）；只有无源帧才在其上累进，有源帧一律按等级重算。
     */
    public Degradation evaluate(List<SourceEstimate> estimates, int missFrames, Double previousDeficit, FusionParams params) {
        TreeSet<String> sources = new TreeSet<>();
        for (SourceEstimate e : estimates) sources.add(e.sourceId());
        DegradationLevel level;
        double deficit;
        if (sources.isEmpty()) {
            level = DegradationLevel.NONE;
            deficit = Math.min(1, (previousDeficit == null ? 0 : previousDeficit) + params.number("degradation", "lost_step_deficit"));
        } else if (sources.size() >= params.integer("degradation", "three_source_min")) {
            level = DegradationLevel.THREE_SOURCE;
            deficit = 0;
        } else if (estimates.stream().allMatch(e -> PRIMARY_SENSOR_TYPES.contains(e.sourceType())) || sources.size() > 1) {
            // 只剩本地主传感器，或不足三源但多于一源：契约枚举没有“双源”等级，按最轻的部分降级 FUSION_BOX_ONLY 记账（见报告疑虑）。
            level = DegradationLevel.FUSION_BOX_ONLY;
            deficit = params.number("degradation", "fusion_box_only_deficit");
        } else {
            level = DegradationLevel.SINGLE_SOURCE;
            deficit = params.number("degradation", "single_source_deficit");
        }
        boolean determined = deficit < params.number("degradation", "undetermined_deficit");
        // 不可判定时置信度必须为 null：0 表示“确定不可信”，null 才是“无法给出”；页面按 UNSUPPORTED 展示。
        Double confidence = determined ? 1 - deficit : null;
        List<UnknownField> unknown = determined ? List.of() : List.of(new UnknownField("fusion_confidence", AttributeSelector.REASON_UNSUPPORTED));
        return new Degradation(level, List.copyOf(sources), deficit, determined, confidence, unknown);
    }
}
