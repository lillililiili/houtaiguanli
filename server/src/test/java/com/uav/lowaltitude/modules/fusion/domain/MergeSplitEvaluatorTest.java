package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.domain.MergeSplitEvaluator.Candidate;
import com.uav.lowaltitude.modules.fusion.domain.MergeSplitEvaluator.TargetSnapshot;

/**
 * 自动合并/分裂的**判定**（决策 16-1 / 16-2）。这里只判"这一帧算不算数"，不碰库、不发事件——
 * 写入与计帧在管线里，判定单独拿出来才测得动。
 */
class MergeSplitEvaluatorTest {

    private final IdentityStateMachine machine = new IdentityStateMachine(DemoFusionParams.demoV1());
    private final MergeSplitEvaluator evaluator = new MergeSplitEvaluator(machine);

    private static final Instant T0 = Instant.parse("2026-09-09T00:00:00Z");

    /** 基准点附近按米偏移造经纬度：滤波状态里的 x/y 各有各的原点，判定收的是经纬度。 */
    private static final double LON0 = 118.5, LAT0 = 37.4;

    private static double lonAt(double metersEast) {
        return LON0 + Math.toDegrees(metersEast / (6378137.0 * Math.cos(Math.toRadians(LAT0))));
    }

    private static double latAt(double metersNorth) {
        return LAT0 + Math.toDegrees(metersNorth / 6378137.0);
    }

    private TargetSnapshot stable(String id, double east, double north, double sigma, Instant firstSeen) {
        return new TargetSnapshot(id, TrackStatus.STABLE, lonAt(east), latAt(north), sigma, Double.NaN, Double.NaN, firstSeen);
    }

    @Test
    void pairsWithinTheSigmaGateAreMergeCandidates() {
        // demo-v1：merge_max_dist_sigma=2.0，σ 取两者较大者，所以门限 = 2σ。
        List<Candidate> candidates = evaluator.mergeCandidates(List.of(
                stable("a", 0, 0, 10, T0), stable("b", 0, 15, 10, T0.plusSeconds(1))));
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).aId()).isEqualTo("a");
        assertThat(candidates.get(0).bId()).isEqualTo("b");
    }

    @Test
    void pairsOutsideTheSigmaGateAreNotCandidates() {
        // 25m > 2×10m：超门限就不算这一帧，且**不重置**已有计数（重置逻辑不在判定里）。
        assertThat(evaluator.mergeCandidates(List.of(
                stable("a", 0, 0, 10, T0), stable("b", 0, 25, 10, T0)))).isEmpty();
    }

    @Test
    void theGateUsesTheLargerSigmaOfThePair() {
        // σ 取较大者：一边精度差就该放宽，否则精度差的那条永远合不上。
        assertThat(evaluator.mergeCandidates(List.of(
                stable("a", 0, 0, 5, T0), stable("b", 0, 15, 20, T0)))).hasSize(1);
    }

    @Test
    void onlyStableTracksMerge() {
        TargetSnapshot tentative = new TargetSnapshot("b", TrackStatus.TENTATIVE, lonAt(0), latAt(5), 10, Double.NaN, Double.NaN, T0);
        assertThat(evaluator.mergeCandidates(List.of(stable("a", 0, 0, 10, T0), tentative))).isEmpty();
    }

    @Test
    void tracksMovingApartAreNotMerged() {
        // 两个反向运动的目标只是擦肩而过，位置一时接近不代表是同一个。
        TargetSnapshot east = new TargetSnapshot("a", TrackStatus.STABLE, lonAt(0), latAt(0), 10, 12, 0, T0);
        TargetSnapshot west = new TargetSnapshot("b", TrackStatus.STABLE, lonAt(0), latAt(5), 10, -12, 0, T0);
        assertThat(evaluator.mergeCandidates(List.of(east, west))).isEmpty();
    }

    @Test
    void tracksMovingTogetherStayCandidates() {
        TargetSnapshot one = new TargetSnapshot("a", TrackStatus.STABLE, lonAt(0), latAt(0), 10, 12, 1, T0);
        TargetSnapshot two = new TargetSnapshot("b", TrackStatus.STABLE, lonAt(0), latAt(5), 10, 11, 2, T0);
        assertThat(evaluator.mergeCandidates(List.of(one, two))).hasSize(1);
    }

    @Test
    void survivorIsTheOneSeenEarlier() {
        Candidate candidate = evaluator.mergeCandidates(List.of(
                stable("b", 0, 0, 10, T0.plusSeconds(30)), stable("a", 0, 5, 10, T0))).get(0);
        assertThat(candidate.survivorId()).isEqualTo("a");
        assertThat(candidate.mergedId()).isEqualTo("b");
    }

    @Test
    void mergeNeedsEnoughConsecutiveFrames() {
        // demo-v1：merge_min_frames=4。第 4 帧才动手，前三帧只计数。
        assertThat(evaluator.mergeReady(3)).isFalse();
        assertThat(evaluator.mergeReady(4)).isTrue();
    }

    @Test
    void splitNeedsBothEnoughFramesAndEnoughSeparation() {
        // demo-v1：split_min_frames=4、split_min_separation_m=100。
        assertThat(evaluator.splitReady(4, 150)).isTrue();
        assertThat(evaluator.splitReady(3, 150)).as("帧数不够").isFalse();
        assertThat(evaluator.splitReady(4, 80)).as("间距不够").isFalse();
    }
}
