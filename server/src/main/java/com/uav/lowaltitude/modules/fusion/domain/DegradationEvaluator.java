package com.uav.lowaltitude.modules.fusion.domain;

import java.util.List;
import java.util.TreeSet;

import com.uav.lowaltitude.modules.fusion.FusionContracts.DegradationLevel;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.domain.AttributeSelector.UnknownField;

/**
 * 降级评估（纯 Java）：按本帧可用来源数给出等级与置信亏损；无源时逐帧累进，越过阈值即“不可判定”。
 * 只数来源、不看类型：任何一台设备单独看到都只算一路（单源），雷达、融合感知箱也一样；
 * 两台及以上不同来源同时看到才是多源（CDX-P04，2026-10-07 定：以前只有雷达或融合感知箱看到时按两路记账）。
 * 阈值全部来自 FusionParams.degradation.*；代码里没有裸阈值。
 */
public final class DegradationEvaluator {
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
        } else if (sources.size() > 1) {
            // 不足三源但多于一源：契约枚举没有“双源”等级，按最轻的部分降级 FUSION_BOX_ONLY 记账（见报告疑虑）。
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
