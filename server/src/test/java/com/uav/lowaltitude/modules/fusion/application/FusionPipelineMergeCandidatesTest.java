package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter.State;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.TrackState;
import com.uav.lowaltitude.modules.fusion.domain.MergeSplitEvaluator.TargetSnapshot;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.ActiveTarget;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.StatusRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.TargetRow;

/**
 * 谁有资格参与自动合并（决策 16-8）。
 *
 * 管线的 ④ 会把**同分区所有活目标**都放进 `estimatesByTarget`（空列表，供 ⑤ 照常写帧），
 * 而 `autoMerge` 在 ④ 之后跑。16.1 我直接遍历那个 keySet，于是库里任何一个陈旧的 STABLE 目标
 * ——哪怕本轮一次都没被观测到——只要预测位置落在 2σ 内就会被算作候选，而且"每帧都在"，
 * 必然凑够 merge_min_frames。升级路径验收上就这么把活目标并进了阶段 2/8 的种子目标。
 *
 * 全新库上撞不到：那里没有这些陈旧 STABLE 目标同处一个分区。
 */
class FusionPipelineMergeCandidatesTest {

    private static final Instant T0 = Instant.parse("2026-09-09T00:00:00Z");
    private static final double LON0 = 118.5, LAT0 = 37.4;

    @Test
    void aStableTargetWithNoObservationThisFrameIsNeverAMergeCandidate() {
        Map<String, List<SourceEstimate>> estimates = new LinkedHashMap<>();
        Map<String, State> predicted = new LinkedHashMap<>();
        Map<String, ActiveTarget> byTarget = new LinkedHashMap<>();
        Map<String, TrackStatus> statuses = new LinkedHashMap<>();

        // 本帧真的被观测到的目标。
        put(estimates, predicted, byTarget, statuses, "hit", 0, true);
        // 陈旧目标：STABLE、有预测态、位置就在旁边 5 m，但本帧一条观测都没有。
        put(estimates, predicted, byTarget, statuses, "stale", 5, false);

        List<TargetSnapshot> snapshots = FusionPipeline.mergeSnapshots(estimates, predicted, byTarget, statuses);

        assertThat(snapshots).extracting(TargetSnapshot::targetId)
                .as("本帧没被观测到的目标不该参与合并").containsExactly("hit");
    }

    private void put(Map<String, List<SourceEstimate>> estimates, Map<String, State> predicted,
            Map<String, ActiveTarget> byTarget, Map<String, TrackStatus> statuses,
            String id, double metersNorth, boolean observedThisFrame) {
        // ④ 对未命中的活目标放的就是空列表（见 FusionPipeline 的 estimatesByTarget.putIfAbsent）。
        // 命中侧只需要"列表非空"——判定不读估计的内容。这里放一个 null 元素而不是造一条 SourceEstimate：
        // 那个记录的字段随阶段涨过好几次（22→24 个），照抄一遍会让这条用例被无关的签名变动带红；
        // 真要有人改成去读它，这里会当场 NPE，也是该知道的。
        estimates.put(id, observedThisFrame ? java.util.Collections.singletonList(null) : List.of());
        predicted.put(id, new State(LON0, LAT0, 0, metersNorth, 1, 0, T0.toEpochMilli(), 10, true, 5));
        byTarget.put(id, new ActiveTarget(
                new TargetRow(id, "目标-" + id, null, T0, T0, "mock", "org", "district", true),
                new StatusRow(id, new TrackState(TrackStatus.STABLE, T0, 3, 0, T0), "src", 0)));
        statuses.put(id, TrackStatus.STABLE);
    }

}
