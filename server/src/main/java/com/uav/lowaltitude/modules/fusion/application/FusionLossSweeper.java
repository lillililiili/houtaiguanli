package com.uav.lowaltitude.modules.fusion.application;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusedLayerWriter;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TargetFrameResult;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.TrackState;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.Transition;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.SilentTarget;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.StatusRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.StatusUpdate;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository;

/**
 * 按平台时钟推进长时间没有数据的目标（ZT-20 复测 2）。
 *
 * 管线只在处理一帧时顺带判断同一分区里本帧没命中的目标是否短失/终止。分区里只有一台设备、它停报以后再没有帧来推进，
 * 目标一直停在 STABLE，直到同一设备再报一帧才一步跳到 TERMINATED（复测：停报几分钟仍是 STABLE）。这里定时补上这一步，
 * 口径与管线相同：最近一次命中时平台收到那一帧的时刻（target_track_status.last_received_at）距今超过 short_lost_after_ms 短失、
 * 超过 terminate_after_ms 终止；推进时写的状态、血缘、原始轨迹结束与交给融合层的无源结果都与管线里没命中的目标一样。
 *
 * "距今"的"今"取平台时钟与 inbox 里最早一条还没处理完的帧的接收时刻中较早者：积压着的帧可能正要命中这个目标，
 * 不能先判它失联；另留 grace 余量，盖住"已收到、还没写进 inbox"的那一小段。回放数据集的接收时刻在回放时钟上
 * （几周前的固定时刻），只推进 horizon 以内收到过数据的目标，不去动这些历史目标；升级前的状态行没有到达时刻，也不在此列。
 *
 * 交给融合层的观测时刻用目标自己已知的最新时刻（状态里的最近观测与最新状态的观测时刻中较晚者），不用平台时钟：
 * 设备时钟慢两分钟的目标若把最新状态推到平台此刻，它恢复上报后的帧在融合层看来全是"迟到帧"，位置两分钟不更新。
 * 状态变化时刻（since）与血缘时刻用平台此刻：这是平台判定它失联的时刻。
 *
 * 并发：每个目标一个小事务，先 FOR UPDATE SKIP LOCKED 锁住状态行并按此刻已提交的值重查——融合正在写它就跳过，下一轮再看。
 * 管线写一帧是"原始轨迹 → 状态 → 融合层"，这里是"状态 → 原始轨迹 → 融合层"，两边可能互等；PostgreSQL 上这里每条语句
 * 等锁不超过 lock-timeout-millis（小于默认 1 秒的 deadlock_timeout），互等时总是这里先放弃、回滚、下一轮重来，
 * 融合那一帧不会被当成死锁的牺牲品判为失败。
 */
@Service
public class FusionLossSweeper {
    private static final Logger log = LoggerFactory.getLogger(FusionLossSweeper.class);

    private final IdentityRepository identities;
    private final RawTrackRepository rawTracks;
    private final FusedTrackRepository fusedTracks;
    private final FusionInboxRepository inbox;
    private final FusionConfigLoader configLoader;
    private final ObjectProvider<FusedLayerWriter> fusedLayerWriter;
    private final FusionProperties properties;
    private final ObjectMapper json;
    private final TransactionTemplate perTarget;

    public FusionLossSweeper(IdentityRepository identities, RawTrackRepository rawTracks, FusedTrackRepository fusedTracks, FusionInboxRepository inbox,
            FusionConfigLoader configLoader, ObjectProvider<FusedLayerWriter> fusedLayerWriter, FusionProperties properties, ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.identities = identities; this.rawTracks = rawTracks; this.fusedTracks = fusedTracks; this.inbox = inbox; this.configLoader = configLoader;
        this.fusedLayerWriter = fusedLayerWriter; this.properties = properties; this.json = json;
        this.perTarget = new TransactionTemplate(transactionManager);
    }

    /** 一轮检查：candidates 个候选里推进了 aged 个（被锁着、重查后未到期或等锁超时的留到下一轮）；reference 是这一轮的"今"。 */
    public record SweepReport(int candidates, int aged, Instant reference) { }

    public SweepReport sweep(Instant now) {
        FusionParams params = configLoader.active();
        IdentityStateMachine machine = new IdentityStateMachine(params);
        FusionProperties.LossSweep settings = properties.getLossSweep();
        // 先定下"今"再取候选：比它早收到的帧此刻都已提交，它们对目标的命中在随后的读里都看得见。
        Long pending = inbox.oldestPendingReceivedAt(properties.getMaxAttempts());
        Instant reference = pending == null || pending >= now.toEpochMilli() ? now : Instant.ofEpochMilli(pending);
        long grace = settings.getGraceMillis();
        List<SilentTarget> candidates = identities.silentTargets(
                reference.minusMillis(machine.shortLostAfterMillis() + grace),
                reference.minusMillis(machine.terminateAfterMillis() + grace),
                now.minusMillis(settings.getHorizonMillis()), settings.getBatchSize());
        int aged = 0;
        for (SilentTarget candidate : candidates) {
            try {
                if (Boolean.TRUE.equals(perTarget.execute(status -> age(candidate, reference, now, params, machine, grace)))) aged++;
            } catch (RuntimeException ex) {
                // 等锁超时（正与融合写同一个目标）或其它失败：这一个目标回滚，下一轮重查。
                log.debug("fusion silent target deferred: target={}, error={}", candidate.targetId(), ex.toString());
            }
        }
        if (aged > 0) log.debug("fusion silent targets aged: aged={}, candidates={}, reference={}", aged, candidates.size(), reference);
        return new SweepReport(candidates.size(), aged, reference);
    }

    private boolean age(SilentTarget candidate, Instant reference, Instant now, FusionParams params, IdentityStateMachine machine, long grace) {
        identities.limitLockWait(properties.getLossSweep().getLockTimeoutMillis());
        StatusRow status = identities.lockStatusSkipLocked(candidate.targetId());
        if (status == null || status.lastReceivedAt() == null || !status.state().active()) return false;
        long gap = Duration.between(status.lastReceivedAt(), reference).toMillis();
        long due = status.state().status() == TrackStatus.SHORT_LOST ? machine.terminateAfterMillis() : machine.shortLostAfterMillis();
        if (gap <= due + grace) return false;
        Transition transition = machine.onMiss(status.state(), gap, now);
        if (!transition.changed()) return false;

        String targetId = candidate.targetId();
        TrackState next = transition.state();
        identities.insertLineage("STATUS", now, targetId, null, write(List.of(targetId)), write(List.of()),
                write(Map.of("status", next.status().name(), "no_data_ms", gap)), FusionPipeline.ALGO_VERSION, params.configVersion(), write(Map.of()));
        Instant observedAt = latestKnown(targetId, status.state());
        if (next.status() == TrackStatus.TERMINATED) rawTracks.endOpenRawTracks(targetId, observedAt);
        identities.updateStatuses(List.of(new StatusUpdate(targetId, next, status.primarySourceId())), now);
        FusedLayerWriter writer = fusedLayerWriter.getIfAvailable(() -> f -> { });
        writer.writeAll(List.of(new TargetFrameResult(targetId, candidate.domain(), observedAt, List.of(), next.status(), next.missFrames(), params.configVersion())));
        return true;
    }

    /** 目标自己已知的最新时刻：状态里的最近观测与最新状态的观测时刻中较晚者（都在目标自己的时间线上）。 */
    private Instant latestKnown(String targetId, TrackState state) {
        Instant known = state.lastObservedAt() != null ? state.lastObservedAt() : state.since();
        OffsetDateTime latest = fusedTracks.latestStateObservedAt(targetId);
        return latest != null && latest.toInstant().isAfter(known) ? latest.toInstant() : known;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
