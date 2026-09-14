package com.uav.lowaltitude.modules.fusion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.domain.AttributeSelector.Selection;
import com.uav.lowaltitude.modules.fusion.domain.AttributeSelector.UnknownField;

/**
 * 加权融合（纯 Java，无 Spring，不依赖 SQL 几何）：位置在局部 ENU 米坐标里按 1/σ² 与来源类型权重复合加权，
 * 输出 WGS-84 与 fused_accuracy_m；高度只在同一基准内合并；类别/身份/运动由 {@link AttributeSelector} 优选。
 */
public final class WeightedFuser {
    /** 地球平均半径（米），只用于度↔米的单位换算。 */
    private static final double EARTH_RADIUS_M = 6_371_000d;
    private final AttributeSelector selector = new AttributeSelector();

    public record Contribution(String sourceId, String observationId, double weight) { }

    public record FusedState(Double longitude, Double latitude, Double accuracyM, Double altitudeAmslM, Double heightAglM,
            Double speedMps, Double headingDeg, String classCode, Double classConfidence, String identityClue, Double identityConfidence,
            boolean sourceSwitched, Selection selection, List<Contribution> contributions, List<UnknownField> unknownFields) { }

    public FusedState fuse(List<SourceEstimate> estimates, FusionParams params, String previousPositionSourceId) {
        Selection selection = selector.select(estimates, params, previousPositionSourceId);
        List<UnknownField> unknown = new ArrayList<>(selection.unknownFields());
        List<SourceEstimate> positioned = estimates.stream().filter(AttributeSelector::positioned).toList();
        Double longitude = null, latitude = null, accuracy = null;
        List<Contribution> contributions = new ArrayList<>();
        if (positioned.isEmpty()) {
            // 只有方位（AOA）或没有位置的来源不参与位置融合：位置保持缺失，不写 (0,0)。
            unknown.add(new UnknownField("location", AttributeSelector.REASON_NOT_REPORTED));
        } else {
            double lon0 = positioned.get(0).longitude(), lat0 = positioned.get(0).latitude();
            double metersPerDegLat = EARTH_RADIUS_M * Math.PI / 180d;
            double metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians(lat0));
            double[] weights = new double[positioned.size()];
            double total = 0, inverseVariance = 0;
            for (int i = 0; i < positioned.size(); i++) {
                SourceEstimate e = positioned.get(i);
                weights[i] = AttributeSelector.positionWeight(e, params);
                total += weights[i];
                inverseVariance += 1 / (e.accuracyM() * e.accuracyM());
            }
            if (total <= 0) {
                // 质量因子把全部来源压到 0 时退回纯 1/σ² 加权，而不是让位置消失。
                total = 0;
                for (int i = 0; i < positioned.size(); i++) { weights[i] = 1 / (positioned.get(i).accuracyM() * positioned.get(i).accuracyM()); total += weights[i]; }
            }
            double x = 0, y = 0;
            for (int i = 0; i < positioned.size(); i++) {
                SourceEstimate e = positioned.get(i);
                double w = weights[i] / total;
                x += w * (e.longitude() - lon0) * metersPerDegLon;
                y += w * (e.latitude() - lat0) * metersPerDegLat;
                contributions.add(new Contribution(e.sourceId(), e.observationId(), w));
            }
            longitude = lon0 + x / metersPerDegLon;
            latitude = lat0 + y / metersPerDegLat;
            // fused_accuracy = √(1/Σ(1/σ²))：只由各源精度决定，类型权重不改变精度语义。
            accuracy = Math.sqrt(1 / inverseVariance);
        }
        // AGL 与 AMSL 是两个基准：互相平均或互推都会把地形高度当成海拔误差；各自只在同基准来源内融合。
        Double amsl = altitude(estimates, SourceEstimate::altitudeAmslM);
        Double agl = altitude(estimates, SourceEstimate::heightAglM);
        if (amsl == null) unknown.add(new UnknownField("altitude_amsl_m", AttributeSelector.REASON_NOT_REPORTED));
        if (agl == null) unknown.add(new UnknownField("height_agl_m", AttributeSelector.REASON_NOT_REPORTED));
        return new FusedState(longitude, latitude, accuracy, amsl, agl, selection.speedMps(), selection.headingDeg(),
                selection.classCode(), selection.classConfidence(), selection.identityClue(), selection.identityConfidence(),
                selection.sourceSwitched(), selection, List.copyOf(contributions), List.copyOf(unknown));
    }

    private static Double altitude(List<SourceEstimate> estimates, Function<SourceEstimate, Double> getter) {
        double sum = 0, total = 0;
        boolean any = false;
        for (SourceEstimate e : estimates) {
            Double value = getter.apply(e);
            if (value == null) continue;
            double w = e.accuracyM() != null && e.accuracyM() > 0 ? 1 / (e.accuracyM() * e.accuracyM()) : 1;
            sum += w * value; total += w; any = true;
        }
        return any ? sum / total : null;
    }
}
