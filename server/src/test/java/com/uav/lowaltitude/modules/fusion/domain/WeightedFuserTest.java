package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.PointKind;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.domain.WeightedFuser.FusedState;

/** 加权融合是纯 Java：位置按 1/σ² 与来源类型权重复合加权；高度同基准才合并；类别只信 EO；主源变化要标记。 */
class WeightedFuserTest {
    static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    static final double LON = 118.6, LAT = 37.4;

    @Test
    void fusedPositionLeansTowardsAccurateSourceAndAccuracyImproves() {
        SourceEstimate radar = estimate("radar", "RADAR", LON, LAT, 15.0, 100.0, null, 10.0, 90.0, null, null, null);
        SourceEstimate tdoa = estimate("tdoa", "TDOA", LON + 0.001, LAT, 60.0, null, null, null, null, null, null, "RF-1");
        FusedState fused = new WeightedFuser().fuse(List.of(radar, tdoa), MapParams.demo(), null);
        // 两源分居东西：15 m 源权重远大于 60 m 源，融合点必须更靠近雷达（经度偏移 < 一半）。
        assertThat(fused.longitude()).isBetween(LON, LON + 0.0005);
        assertThat(fused.latitude()).isCloseTo(LAT, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(fused.accuracyM()).isLessThan(15.0);
        assertThat(fused.accuracyM()).isGreaterThan(14.0);
        assertThat(fused.contributions()).extracting(c -> c.sourceId()).containsExactlyInAnyOrder("radar", "tdoa");
        double total = fused.contributions().stream().mapToDouble(c -> c.weight()).sum();
        assertThat(total).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        // 运动量同位置主源（雷达）；身份来自 TDOA。
        assertThat(fused.selection().positionSourceId()).isEqualTo("radar");
        assertThat(fused.speedMps()).isEqualTo(10.0);
        assertThat(fused.headingDeg()).isEqualTo(90.0);
        assertThat(fused.identityClue()).isEqualTo("RF-1");
        assertThat(fused.sourceSwitched()).isFalse();
    }

    @Test
    void altitudesAreFusedOnlyWithinTheSameDatum() {
        SourceEstimate amsl = estimate("radar", "RADAR", LON, LAT, 15.0, 120.0, null, null, null, null, null, null);
        SourceEstimate agl = estimate("box", "FUSION_BOX", LON, LAT, 20.0, null, 30.0, null, null, null, null, null);
        FusedState fused = new WeightedFuser().fuse(List.of(amsl, agl), MapParams.demo(), null);
        // AMSL 与 AGL 基准不同，不能互相平均或互推：各自只由同基准来源给出。
        assertThat(fused.altitudeAmslM()).isEqualTo(120.0);
        assertThat(fused.heightAglM()).isEqualTo(30.0);

        FusedState amslOnly = new WeightedFuser().fuse(List.of(amsl), MapParams.demo(), null);
        assertThat(amslOnly.heightAglM()).isNull();
        assertThat(amslOnly.unknownFields()).extracting(u -> u.field()).contains("height_agl_m");
    }

    @Test
    void classificationComesFromEoAndConfidenceOnlyFromEo() {
        SourceEstimate radar = estimate("radar", "RADAR", LON, LAT, 15.0, null, null, null, null, "BIRD", null, null);
        SourceEstimate eo = estimate("eo", "EO", LON, LAT, 25.0, null, null, null, null, "UAV", 0.9, null);
        FusedState fused = new WeightedFuser().fuse(List.of(radar, eo), MapParams.demo(), null);
        assertThat(fused.classCode()).isEqualTo("UAV");
        assertThat(fused.classConfidence()).isEqualTo(0.9);
        assertThat(fused.selection().classSourceId()).isEqualTo("eo");

        FusedState radarOnly = new WeightedFuser().fuse(List.of(radar), MapParams.demo(), null);
        assertThat(radarOnly.classCode()).isEqualTo("BIRD");
        assertThat(radarOnly.classConfidence()).as("雷达六值类别没有置信度").isNull();
        assertThat(radarOnly.unknownFields()).extracting(u -> u.field()).contains("classification_confidence");
    }

    @Test
    void positionSourceChangeIsFlagged() {
        SourceEstimate tdoa = estimate("tdoa", "TDOA", LON, LAT, 60.0, null, null, null, null, null, null, null);
        FusedState fused = new WeightedFuser().fuse(List.of(tdoa), MapParams.demo(), "radar");
        assertThat(fused.selection().positionSourceId()).isEqualTo("tdoa");
        assertThat(fused.sourceSwitched()).isTrue();
        assertThat(new WeightedFuser().fuse(List.of(tdoa), MapParams.demo(), "tdoa").sourceSwitched()).isFalse();
    }

    @Test
    void noPositionAtAllYieldsNullLocationAndUnknown() {
        SourceEstimate aoaOnly = estimate("aoa", "TDOA", null, null, null, null, null, null, null, null, null, "RF-2");
        FusedState fused = new WeightedFuser().fuse(List.of(aoaOnly), MapParams.demo(), null);
        assertThat(fused.longitude()).isNull();
        assertThat(fused.accuracyM()).isNull();
        assertThat(fused.unknownFields()).extracting(u -> u.field()).contains("location");
        assertThat(fused.identityClue()).isEqualTo("RF-2");
    }

    static SourceEstimate estimate(String sourceId, String type, Double lon, Double lat, Double acc, Double amsl, Double agl,
            Double speed, Double heading, String classCode, Double classConf, String identity) {
        return new SourceEstimate(sourceId, sourceId.toUpperCase(), type, "RADAR".equals(type) ? "CONFIRMED" : "DEMO",
                "link-" + sourceId, "raw-" + sourceId, "obs-" + sourceId, T0, lon, lat, acc, amsl, agl, speed, heading,
                classCode, classConf, identity, identity == null ? null : 0.8, PointKind.MEAS, Map.of());
    }

    /** 测试用固定参数：与 demo-v1 同形，只经 FusionParams 读取，缺键抛错。 */
    static final class MapParams implements FusionParams {
        private final Map<String, Map<String, Double>> groups;
        private final Map<String, Map<String, Double>> weights;

        MapParams(Map<String, Map<String, Double>> groups, Map<String, Map<String, Double>> weights) { this.groups = groups; this.weights = weights; }

        static MapParams demo() {
            return new MapParams(Map.of(
                    "filter", Map.of("alpha", 0.6, "beta", 0.25, "max_dt_ms", 3000.0, "pred_max_frames", 3.0, "bridge_max_gap_ms", 6000.0),
                    "quality", Map.of("latency_penalty_ms", 2000.0, "loss_window_frames", 10.0, "loss_penalty_per_miss", 0.08, "anomaly_zscore", 4.0, "anomaly_downweight", 0.25),
                    "degradation", Map.of("three_source_min", 3.0, "undetermined_deficit", 0.5, "single_source_deficit", 0.35, "fusion_box_only_deficit", 0.2, "lost_step_deficit", 0.1),
                    "identity", Map.of("short_lost_after_ms", 3000.0, "terminate_after_ms", 15000.0)),
                    Map.of(
                    "RADAR", Map.of("position", 0.45, "motion", 0.5, "class", 0.2, "identity", 0.0),
                    "EO", Map.of("position", 0.2, "motion", 0.1, "class", 0.6, "identity", 0.1),
                    "TDOA", Map.of("position", 0.25, "motion", 0.2, "class", 0.0, "identity", 0.5),
                    "FIVE_G_A", Map.of("position", 0.1, "motion", 0.2, "class", 0.2, "identity", 0.4),
                    "FUSION_BOX", Map.of("position", 0.4, "motion", 0.4, "class", 0.3, "identity", 0.0)));
        }

        @Override public String configVersion() { return "test-v1"; }
        @Override public double number(String group, String key) {
            Map<String, Double> g = groups.get(group);
            if (g == null || !g.containsKey(key)) throw new IllegalStateException("missing fusion param " + group + "." + key);
            return g.get(key);
        }
        @Override public int integer(String group, String key) { return (int) Math.round(number(group, key)); }
        @Override public Map<String, Double> accuracyDefaults() { return Map.of("RADAR", 15.0, "TDOA", 60.0, "EO", 25.0, "FIVE_G_A", 80.0, "FUSION_BOX", 20.0); }
        @Override public Map<String, Map<String, Double>> weights() { return weights; }
    }
}
