package com.uav.lowaltitude.modules.fusion.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;

/**
 * 自动合并/分裂的判定（决策 16-1 / 16-2）：只回答"这一帧算不算数"，不碰库、不发事件。
 * 计帧与写入在管线里，判定单独拿出来才测得动。
 */
public class MergeSplitEvaluator {

    private final IdentityStateMachine machine;

    public MergeSplitEvaluator(IdentityStateMachine machine) { this.machine = machine; }

    /**
     * 预测到本帧时刻的目标。
     *
     * 位置收**经纬度**而不是滤波状态里的 x/y：`AlphaBetaFilter.State` 的 x/y 是相对**该轨迹自己的** ENU 原点
     * （`lon0/lat0` 逐轨迹保存）的米，两条轨迹的原点不同，直接拿 x/y 相减得到的不是它们之间的距离。
     * vx/vy 则可以直接比——ENU 的轴向都是东/北，与原点无关。
     */
    public record TargetSnapshot(String targetId, TrackStatus status, double longitude, double latitude, double sigmaM,
            double vx, double vy, Instant firstSeenAt) { }

    public record Candidate(String aId, String bId, String survivorId, String mergedId, double distanceM,
            double thresholdM) { }

    /**
     * 本帧的合并候选对：两者均 STABLE、预测位置互距 ≤ `merge_max_dist_sigma × max(σa,σb)`、且运动不相悖。
     *
     * σ 取两者**较大**者：一边精度差就该放宽门限，否则精度差的那条永远合不上。
     *
     * "运动一致"按契约要"若有速度/航向"参与判断，但参数集里没有对应阈值。这里只做**不引入新参数**的
     * 一条排除：两边都有速度且速度方向相悖（点积为负）时不算这一帧——那是两个目标擦肩而过，
     * 位置一时接近不代表是同一个。真正的"连续性"由 merge_min_frames 连续计帧保证，不需要再造一个角度阈值。
     */
    public List<Candidate> mergeCandidates(List<TargetSnapshot> snapshots) {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            for (int j = i + 1; j < snapshots.size(); j++) {
                TargetSnapshot a = snapshots.get(i), b = snapshots.get(j);
                if (a.status() != TrackStatus.STABLE || b.status() != TrackStatus.STABLE) continue;
                double sigma = Math.max(a.sigmaM(), b.sigmaM());
                if (Double.isNaN(sigma)) continue;
                double threshold = machine.mergeDistanceThreshold(sigma);
                double[] offset = AlphaBetaFilter.toEnu(a.longitude(), a.latitude(), b.longitude(), b.latitude());
                double distance = Math.hypot(offset[0], offset[1]);
                if (distance > threshold) continue;
                if (movingApart(a, b)) continue;
                String survivor = machine.chooseSurvivor(a.targetId(), a.firstSeenAt(), b.targetId(), b.firstSeenAt());
                String merged = survivor.equals(a.targetId()) ? b.targetId() : a.targetId();
                candidates.add(new Candidate(a.targetId(), b.targetId(), survivor, merged, distance, threshold));
            }
        }
        return List.copyOf(candidates);
    }

    private boolean movingApart(TargetSnapshot a, TargetSnapshot b) {
        if (Double.isNaN(a.vx()) || Double.isNaN(a.vy()) || Double.isNaN(b.vx()) || Double.isNaN(b.vy())) return false;
        return a.vx() * b.vx() + a.vy() * b.vy() < 0;
    }

    /** 连续命中帧数是否够合并。 */
    public boolean mergeReady(int framesSeen) { return framesSeen >= machine.mergeMinFrames(); }

    /** 分裂要**同时**满足帧数与间距：只够帧数就分，抖动一下就会多出一个目标。 */
    public boolean splitReady(int framesSeen, double separationM) {
        return framesSeen >= machine.splitMinFrames() && separationM >= machine.splitMinSeparationM();
    }
}
