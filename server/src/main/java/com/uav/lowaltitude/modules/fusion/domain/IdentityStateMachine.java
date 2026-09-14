package com.uav.lowaltitude.modules.fusion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;

/**
 * 目标 ID 状态机（契约 target_track_status.status）：
 * 新目标 TENTATIVE → 连续命中 ≥ tentative_to_stable_hits → STABLE；无源 > short_lost_after_ms → SHORT_LOST；> terminate_after_ms → TERMINATED。
 * 合并保留 first_seen_at 更早者（同时首见取较小 ID，保证可重复）；分裂产生两个新 ID。MERGE/SPLIT/TERMINATED 对引擎是终态。
 * 观察期（TENTATIVE）内目标 ID 不变：状态机只累计命中，不重建目标。所有阈值来自 fusion_config。
 */
public final class IdentityStateMachine {
    private final int tentativeToStableHits;
    private final long shortLostAfterMillis;
    private final long terminateAfterMillis;
    private final int mergeMinFrames;
    private final double mergeMaxDistSigma;
    private final int splitMinFrames;
    private final double splitMinSeparationM;

    public IdentityStateMachine(FusionParams params) {
        tentativeToStableHits = params.integer("identity", "tentative_to_stable_hits");
        shortLostAfterMillis = params.integer("identity", "short_lost_after_ms");
        terminateAfterMillis = params.integer("identity", "terminate_after_ms");
        mergeMinFrames = params.integer("identity", "merge_min_frames");
        mergeMaxDistSigma = params.number("identity", "merge_max_dist_sigma");
        splitMinFrames = params.integer("identity", "split_min_frames");
        splitMinSeparationM = params.number("identity", "split_min_separation_m");
    }

    public record TrackState(TrackStatus status, Instant since, int confirmHits, int missFrames, Instant lastObservedAt) {
        public static TrackState created(Instant at) { return new TrackState(TrackStatus.TENTATIVE, at, 0, 0, null); }
        public boolean terminal() { return status == TrackStatus.MERGE || status == TrackStatus.SPLIT || status == TrackStatus.TERMINATED; }
        public boolean active() { return !terminal(); }
    }

    public record Transition(TrackState state, boolean changed) { }

    public record SplitIds(String childA, String childB) { }

    /** 本帧有来源命中。 */
    public Transition onHit(TrackState state, Instant observedAt) {
        if (state.terminal()) return new Transition(state, false);
        int hits = state.confirmHits() + 1;
        TrackStatus next = state.status();
        if (state.status() == TrackStatus.TENTATIVE && hits >= tentativeToStableHits) next = TrackStatus.STABLE;
        else if (state.status() == TrackStatus.SHORT_LOST) next = hits >= tentativeToStableHits ? TrackStatus.STABLE : TrackStatus.TENTATIVE;
        boolean changed = next != state.status();
        Instant lastObserved = state.lastObservedAt() == null || observedAt.isAfter(state.lastObservedAt()) ? observedAt : state.lastObservedAt();
        return new Transition(new TrackState(next, changed ? observedAt : state.since(), hits, 0, lastObserved), changed);
    }

    /** 本帧没有命中该目标：按距最近一次观测的间隙判断短失/终止。frameAt 是本帧时刻（回放用回放时钟，不用墙钟）。 */
    public Transition onFrame(TrackState state, Instant frameAt) {
        if (state.terminal()) return new Transition(state, false);
        int misses = state.missFrames() + 1;
        Instant reference = state.lastObservedAt() != null ? state.lastObservedAt() : state.since();
        long gap = Duration.between(reference, frameAt).toMillis();
        TrackStatus next = state.status();
        if (gap > terminateAfterMillis) next = TrackStatus.TERMINATED;
        else if (gap > shortLostAfterMillis) next = TrackStatus.SHORT_LOST;
        boolean changed = next != state.status();
        return new Transition(new TrackState(next, changed ? frameAt : state.since(), state.confirmHits(), misses, state.lastObservedAt()), changed);
    }

    /** 合并幸存者：first_seen_at 更早者；相同时取较小 ID（可重复、与处理顺序无关）。 */
    public String chooseSurvivor(String aId, Instant aFirstSeen, String bId, Instant bFirstSeen) {
        int byTime = aFirstSeen.compareTo(bFirstSeen);
        if (byTime != 0) return byTime < 0 ? aId : bId;
        return aId.compareTo(bId) <= 0 ? aId : bId;
    }

    public boolean mergeEligible(TrackState a, TrackState b) {
        return a.status() == TrackStatus.STABLE && b.status() == TrackStatus.STABLE;
    }

    public double mergeDistanceThreshold(double sigmaM) { return mergeMaxDistSigma * sigmaM; }

    public SplitIds newSplitIds(String originTargetId) {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        while (a.equals(originTargetId)) a = UUID.randomUUID().toString();
        while (b.equals(originTargetId) || b.equals(a)) b = UUID.randomUUID().toString();
        return new SplitIds(a, b);
    }

    public int tentativeToStableHits() { return tentativeToStableHits; }
    public long shortLostAfterMillis() { return shortLostAfterMillis; }
    public long terminateAfterMillis() { return terminateAfterMillis; }
    public int mergeMinFrames() { return mergeMinFrames; }
    public int splitMinFrames() { return splitMinFrames; }
    public double splitMinSeparationM() { return splitMinSeparationM; }
}
