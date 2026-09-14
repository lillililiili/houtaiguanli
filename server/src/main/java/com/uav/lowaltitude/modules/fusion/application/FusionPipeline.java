package com.uav.lowaltitude.modules.fusion.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusedLayerWriter;
import com.uav.lowaltitude.modules.fusion.FusionContracts.PointKind;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TargetFrameResult;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter.Measurement;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter.State;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter.Update;
import com.uav.lowaltitude.modules.fusion.domain.AssociationCost;
import com.uav.lowaltitude.modules.fusion.domain.AssociationCost.Candidate;
import com.uav.lowaltitude.modules.fusion.domain.Associator;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.TrackState;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.Transition;
import com.uav.lowaltitude.modules.fusion.domain.SourceObservation;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper.Frame;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper.Item;
import com.uav.lowaltitude.modules.fusion.ingest.InboxSourceRouter;
import com.uav.lowaltitude.modules.fusion.infrastructure.AssociationPendingRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.ActiveTarget;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationRepository.DeviceMeta;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationRepository.SourceMeta;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.LinkRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.LinkState;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.TrackRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository;
import com.uav.lowaltitude.modules.fusion.domain.MergeSplitEvaluator;
import java.util.stream.Stream;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;

/**
 * 一帧（一个来源一个时刻的多目标观测）的处理：解析 → 按分区分组 → α-β 滤波 → 门限/匈牙利关联 → ID 状态机 → 写原始层 → 交融合层。
 * 事务边界在调用方（{@link FusionIngestWorker} 或种子的同步 drain）：整帧一个事务，任何一步失败整帧回滚并把 inbox 置 FAILED，
 * 不允许留下半帧数据（一半观测入库、另一半没有会让后续关联建立在残缺证据上）。
 * 跨 source_mode 或跨归属元组永不关联：回放与实测、不同辖区的目标不是同一物理对象的证据。
 * 迟到帧（observed_at 早于目标最新观测）照写原始层，但在 TargetFrameResult 里如实带上 observedAt，由融合层决定不回退 latest_state。
 */
@Service
public class FusionPipeline {
    public static final String ALGO_VERSION = "fusion-e1-v1";
    private static final Logger log = LoggerFactory.getLogger(FusionPipeline.class);

    private final ObservationRepository observations;
    private final RawTrackRepository rawTracks;
    private final IdentityRepository identities;
    private final AssociationPendingRepository pendings;
    private final FusionConfigLoader configLoader;
    private final ObjectProvider<FusedLayerWriter> fusedLayerWriter;
    private final InboxSourceRouter router;
    private final ObjectMapper json;
    private final LineageRepository lineages;
    private final FusionEventEmitter events;

    public FusionPipeline(ObservationRepository observations, RawTrackRepository rawTracks, IdentityRepository identities,
            AssociationPendingRepository pendings, FusionConfigLoader configLoader, ObjectProvider<FusedLayerWriter> fusedLayerWriter,
            InboxSourceRouter router, ObjectMapper json, LineageRepository lineages, FusionEventEmitter events) {
        this.observations = observations; this.rawTracks = rawTracks; this.identities = identities; this.pendings = pendings;
        this.configLoader = configLoader; this.fusedLayerWriter = fusedLayerWriter; this.router = router; this.json = json;
        this.lineages = lineages; this.events = events;
    }

    public record FrameOutcome(int observationCount, int targetCount, List<String> targetIds) { }

    public FrameOutcome processFrame(InboxRow inbox) {
        FusionParams params = configLoader.active();
        AlphaBetaFilter filter = new AlphaBetaFilter(params);
        Associator associator = new Associator(new AssociationCost(params));
        IdentityStateMachine machine = new IdentityStateMachine(params);

        SourceMeta source = observations.findSource(inbox.sourceId());
        if (source == null || !source.enabled()) throw new IllegalStateException("回放来源不存在或已停用: " + inbox.sourceId());
        DeviceMeta device = observations.findDeviceForSource(inbox.sourceId());
        Frame frame = router.map(inbox);
        Instant receivedAt = Instant.ofEpochMilli(inbox.receivedAtMillis());
        FusionDomainKey domain = new FusionDomainKey(source.sourceMode(), device == null ? null : device.ownerOrgId(), device == null ? null : device.districtId());

        List<SourceObservation> parsed = new ArrayList<>();
        for (Item item : frame.items()) {
            parsed.add(new SourceObservation(UUID.randomUUID().toString(), inbox.inboxId(), source.sourceId(), source.sourceCode(), source.sourceType(), source.schemaStatus(),
                    device == null ? null : device.deviceId(), frame.sessionKey(), item.externalTargetId(), item.externalTrackId(), frame.observedAt(), receivedAt,
                    item.longitude(), item.latitude(), item.positionAccuracyM(), item.altitudeAmslM(), item.heightAglM(), item.speedMps(), item.headingDeg(),
                    item.classCode(), item.classConfidence(), item.identityClue(), null, item.latencyMs(), new LinkedHashMap<>(item.quality()), source.sourceMode(),
                    domain.ownerOrgId(), domain.districtId(), frame.recordNo(), item.pilotLongitude(), item.pilotLatitude(), item.classSource()));
        }

        // ① 滤波：每条观测按其 link 的现有状态更新；缺精度用目录缺省并在 quality 标记。
        List<Double> accuracies = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        for (SourceObservation observation : parsed) {
            LinkRow link = rawTracks.findLink(observation.sourceId(), observation.sourceSessionKey(), observation.externalTargetId());
            State existing = null;
            if (link != null) {
                TrackRow open = rawTracks.findOpenRawTrack(link.linkId(), link.targetId());
                existing = open == null || open.filterStateJson() == null ? null : State.fromMap(readMap(open.filterStateJson()));
            }
            Update update = observation.hasPosition()
                    ? filter.update(existing, new Measurement(observation.longitude(), observation.latitude(), observation.positionAccuracyM(), observation.observedMillis()), observation.sourceType())
                    : null;
            updates.add(update);
            double accuracy = update != null ? update.accuracyUsedM() : (observation.positionAccuracyM() == null ? Double.NaN : observation.positionAccuracyM());
            accuracies.add(accuracy);
            if (update != null && update.accuracyDefaulted()) observation.quality().put("accuracy_defaulted", true);
            if (!observation.hasPosition()) observation.quality().put("position", "REFERENCE_UNKNOWN");
            if (update != null && update.outOfOrder()) observation.quality().put("out_of_order", true);
            observation.quality().put("schema_status", observation.schemaStatus() == null ? "UNKNOWN" : observation.schemaStatus());
        }

        // ② 关联：先按已有 link 直连（同一来源同一外部目标号就是同一条 link），其余按门限 + 匈牙利匹配。
        List<ActiveTarget> active = identities.activeTargets(domain);
        Map<String, ActiveTarget> byTarget = new LinkedHashMap<>();
        for (ActiveTarget candidate : active) byTarget.put(candidate.target().targetId(), candidate);
        Map<String, State> predicted = predictStates(filter, active, frame.observedAt());

        String[] assignedTarget = new String[parsed.size()];
        List<Integer> unlinked = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            SourceObservation observation = parsed.get(i);
            LinkRow link = rawTracks.findLink(observation.sourceId(), observation.sourceSessionKey(), observation.externalTargetId());
            if (link != null) {
                String resolved = identities.resolveAlias(link.targetId());
                if (byTarget.containsKey(resolved)) { assignedTarget[i] = resolved; continue; }
            }
            unlinked.add(i);
        }
        if (!unlinked.isEmpty()) {
            List<SourceObservation> subset = new ArrayList<>();
            List<Double> subsetAccuracies = new ArrayList<>();
            for (int index : unlinked) { subset.add(parsed.get(index)); subsetAccuracies.add(accuracies.get(index)); }
            Set<String> taken = new LinkedHashSet<>();
            for (String target : assignedTarget) if (target != null) taken.add(target);
            List<Candidate> candidates = new ArrayList<>();
            for (ActiveTarget candidate : active) {
                if (taken.contains(candidate.target().targetId())) continue;
                State state = predicted.get(candidate.target().targetId());
                if (state == null) continue;
                candidates.add(new Candidate(candidate.target().targetId(), candidate.target().domain(), state.longitude(), state.latitude(), state.accuracyM(),
                        null, null, state.speedMps(), state.headingDeg(), candidate.target().objectTypeCode(),
                        candidate.status().state().lastObservedAt() == null ? frame.observedAt().toEpochMilli() : candidate.status().state().lastObservedAt().toEpochMilli(),
                        candidate.status().state().confirmHits(), candidate.status().state().missFrames()));
            }
            Associator.Result association = associator.associate(subset, subsetAccuracies, candidates);
            for (Associator.Match match : association.matches()) assignedTarget[unlinked.get(match.observationIndex())] = match.targetId();
            for (Associator.Ambiguity ambiguity : association.ambiguities()) {
                SourceObservation observation = subset.get(ambiguity.observationIndex());
                recordPending(domain, observation, ambiguity.candidateTargetIds(), "GATE_AMBIGUOUS", frame.observedAt());
            }
        }

        // ③ 身份与原始层写入。
        Map<String, List<SourceEstimate>> estimatesByTarget = new LinkedHashMap<>();
        Map<String, Instant> observedByTarget = new LinkedHashMap<>();
        Map<String, String> splitOrigins = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            SourceObservation observation = parsed.get(i);
            observations.insert(observation);
            String targetId = assignedTarget[i];
            if (targetId == null) {
                // 决策 16-2：同一来源在同一帧里还有另一条回波、且那条落在一个**本帧之前就存在**的目标上，
                // 那么这条新回波很可能是那个目标分裂出来的，而不是凭空冒出来的第三方。记下来交给 autoSplit 计帧。
                String origin = sameSourceOrigin(parsed, assignedTarget, byTarget, i);
                targetId = createTarget(observation, machine, frame.observedAt());
                if (origin != null) splitOrigins.put(targetId, origin);
            }
            else identities.touchTarget(targetId, observation.observedAt(), receivedAt);
            assignedTarget[i] = targetId;
            SourceEstimate estimate = writeRawLayer(observation, updates.get(i), accuracies.get(i), targetId, params, receivedAt);
            estimatesByTarget.computeIfAbsent(targetId, k -> new ArrayList<>()).add(estimate);
            observedByTarget.merge(targetId, observation.observedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }

        // ④ 状态推进：本帧命中的目标走 onHit，同分区其余活跃目标走 onFrame（可能转 SHORT_LOST/TERMINATED）。
        Map<String, TrackStatus> statuses = new LinkedHashMap<>();
        Map<String, Integer> missFrames = new LinkedHashMap<>();
        for (Map.Entry<String, List<SourceEstimate>> entry : estimatesByTarget.entrySet()) {
            IdentityRepository.StatusRow status = identities.findStatus(entry.getKey());
            TrackState state = status == null ? TrackState.created(frame.observedAt()) : status.state();
            Transition transition = machine.onHit(state, observedByTarget.get(entry.getKey()));
            String primarySource = entry.getValue().get(0).sourceId();
            identities.updateStatus(entry.getKey(), transition.state(), primarySource, receivedAt);
            if (status != null && status.primarySourceId() != null && !status.primarySourceId().equals(primarySource)) {
                identities.insertLineage("SWITCH", frame.observedAt(), entry.getKey(), null, write(List.of(entry.getKey())), write(List.of()),
                        write(Map.of("from_source_id", status.primarySourceId(), "to_source_id", primarySource)), ALGO_VERSION, params.configVersion(), write(Map.of()));
            }
            if (transition.changed()) {
                identities.insertLineage("STATUS", frame.observedAt(), entry.getKey(), null, write(List.of(entry.getKey())), write(List.of()),
                        write(Map.of("status", transition.state().status().name())), ALGO_VERSION, params.configVersion(), write(Map.of()));
            }
            statuses.put(entry.getKey(), transition.state().status());
            missFrames.put(entry.getKey(), transition.state().missFrames());
        }
        for (ActiveTarget candidate : active) {
            String targetId = candidate.target().targetId();
            if (estimatesByTarget.containsKey(targetId)) continue;
            Transition transition = machine.onFrame(candidate.status().state(), frame.observedAt());
            if (transition.changed() || transition.state().missFrames() != candidate.status().state().missFrames()) {
                identities.updateStatus(targetId, transition.state(), candidate.status().primarySourceId(), receivedAt);
            }
            if (transition.changed()) {
                identities.insertLineage("STATUS", frame.observedAt(), targetId, null, write(List.of(targetId)), write(List.of()),
                        write(Map.of("status", transition.state().status().name())), ALGO_VERSION, params.configVersion(), write(Map.of()));
                if (transition.state().status() == TrackStatus.TERMINATED) rawTracks.endOpenRawTracks(targetId, frame.observedAt());
            }
            statuses.put(targetId, transition.state().status());
            missFrames.put(targetId, transition.state().missFrames());
            estimatesByTarget.putIfAbsent(targetId, List.of());
            observedByTarget.putIfAbsent(targetId, frame.observedAt());
        }

        // ④.5 自动合并（决策 16-1）：放在状态推进之后、交融合层之前——此时本帧的预测位置、状态、首见时刻都在手上，
        // 被并目标要在进 ⑤ 之前从本帧结果里摘掉，否则融合层会为一个已经不存在的目标再写一帧。
        autoMerge(domain, frame.observedAt(), params, machine, predicted, byTarget, statuses, estimatesByTarget, observedByTarget);
        autoSplit(domain, frame.observedAt(), params, machine, splitOrigins, estimatesByTarget);

        // ⑤ 交给融合层（E2）。E2 未落地时 ObjectProvider 取不到 Bean，用无操作实现，原始层照常入库。
        FusedLayerWriter writer = fusedLayerWriter.getIfAvailable(() -> f -> { });
        List<String> targetIds = new ArrayList<>();
        for (Map.Entry<String, List<SourceEstimate>> entry : estimatesByTarget.entrySet()) {
            targetIds.add(entry.getKey());
            writer.write(new TargetFrameResult(entry.getKey(), domain, observedByTarget.get(entry.getKey()), entry.getValue(),
                    statuses.getOrDefault(entry.getKey(), TrackStatus.TENTATIVE), missFrames.getOrDefault(entry.getKey(), 0), params.configVersion()));
        }
        return new FrameOutcome(parsed.size(), targetIds.size(), List.copyOf(targetIds));
    }

    private String createTarget(SourceObservation observation, IdentityStateMachine machine, Instant frameAt) {
        String targetId = UUID.randomUUID().toString();
        String targetNo = identities.nextTargetNo(observation.observedAt());
        identities.insertTarget(targetId, targetNo, observation.classCode(), null, observation.observedAt(), observation.domain(), observation.receivedAt());
        identities.insertStatus(targetId, TrackState.created(frameAt), observation.receivedAt());
        identities.insertLineage("CREATE", observation.observedAt(), targetId, null, write(List.of(targetId)), write(List.of()),
                write(Map.of("source_code", observation.sourceCode(), "external_target_id", observation.externalTargetId())), ALGO_VERSION,
                configLoader.active().configVersion(), write(Map.of()));
        return targetId;
    }

    private SourceEstimate writeRawLayer(SourceObservation observation, Update update, double accuracyM, String targetId, FusionParams params, Instant receivedAt) {
        LinkRow link = rawTracks.findLink(observation.sourceId(), observation.sourceSessionKey(), observation.externalTargetId());
        String linkId;
        if (link == null) {
            linkId = UUID.randomUUID().toString();
            rawTracks.insertLink(linkId, targetId, observation.sourceId(), observation.deviceId(), observation.sourceSessionKey(), observation.externalTargetId(), observation.receivedAt());
        } else {
            linkId = link.linkId();
            if (!targetId.equals(link.targetId())) rawTracks.relink(linkId, targetId);
        }
        TrackRow open = rawTracks.findOpenRawTrack(linkId, targetId);
        String trackId;
        String stateJson = update == null ? null : write(update.state().toMap());
        if (open == null || (update != null && update.reinitialized() && open.filterStateJson() != null)) {
            if (open != null) rawTracks.endTrack(open.trackId(), observation.observedAt());
            trackId = UUID.randomUUID().toString();
            String externalTrackId = (observation.externalTrackId() == null ? observation.externalTargetId() : observation.externalTrackId()) + ":" + observation.observedMillis();
            rawTracks.insertRawTrack(trackId, targetId, linkId, externalTrackId, observation.observedAt(), params.configVersion(), stateJson);
        } else {
            trackId = open.trackId();
            if (stateJson != null) rawTracks.updateFilterState(trackId, stateJson);
        }
        if (observation.hasPosition()) {
            rawTracks.insertPoint(UUID.randomUUID().toString(), trackId, observation.inboxId(), observation.pointSeq(), observation.observedAt(), receivedAt,
                    observation.longitude(), observation.latitude(), observation.altitudeAmslM(), observation.heightAglM(), observation.observationId(), accuracyM, PointKind.MEAS.name());
        }
        State state = update == null ? null : update.state();
        // 三元表达式两侧一个是 Double、一个是 double 会触发自动拆箱：没有位置的来源（AOA 只给方位）在这里会 NPE。
        // 显式装箱保留"没有位置"这件事，让它一路带到融合层，而不是在管线里炸掉整帧。
        Double estimateLongitude = state == null ? observation.longitude() : Double.valueOf(state.longitude());
        Double estimateLatitude = state == null ? observation.latitude() : Double.valueOf(state.latitude());
        return new SourceEstimate(observation.sourceId(), observation.sourceCode(), observation.sourceType(), observation.schemaStatus(), linkId, trackId,
                observation.observationId(), observation.observedAt(), estimateLongitude, estimateLatitude,
                state == null ? null : state.accuracyM(), observation.altitudeAmslM(), observation.heightAglM(),
                observation.speedMps() != null ? observation.speedMps() : (state == null ? null : state.speedMps()),
                observation.headingDeg() != null ? observation.headingDeg() : (state == null ? null : state.headingDeg()),
                observation.classCode(), observation.classConfidence(), observation.identityClue(), observation.identityConfidence(),
                PointKind.MEAS, Map.copyOf(observation.quality()), observation.pilotLongitude(), observation.pilotLatitude(), observation.classSource());
    }

    private Map<String, State> predictStates(AlphaBetaFilter filter, List<ActiveTarget> active, Instant at) {
        Map<String, State> out = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (ActiveTarget candidate : active) ids.add(candidate.target().targetId());
        for (LinkState link : rawTracks.linkStates(ids)) {
            if (link.filterStateJson() == null) continue;
            State state = filter.predict(State.fromMap(readMap(link.filterStateJson())), at.toEpochMilli());
            State existing = out.get(link.targetId());
            // 一个目标多条 link 时取精度更好的那条作为门限中心。
            if (existing == null || state.accuracyM() < existing.accuracyM()) out.put(link.targetId(), state);
        }
        return out;
    }

    /**
     * 自动合并（决策 16-1）：同域两个 STABLE 目标连续 `merge_min_frames` 帧落在 `merge_max_dist_sigma·σ` 内就并掉。
     *
     * 与人工合并（`FusionCommandService.merge`）写的是同一套东西，但有三处按 16-1 特意不同：
     * `operator_kind=SYSTEM`（没有操作人）、**不递增 `target.version`**（沿用 8-6：系统写入不与人工写入争版本，
     * 否则用户正在编辑的乐观锁会被后台随机打断）、**links 不迁移**（原始层的 link 记的是"哪条来源轨迹属于谁"，
     * 迁移会让历史回放对不上；别名解析已经能把旧 id 指到 survivor）。
     */
    private void autoMerge(FusionDomainKey domain, Instant frameAt, FusionParams params, IdentityStateMachine machine,
            Map<String, State> predicted, Map<String, ActiveTarget> byTarget, Map<String, TrackStatus> statuses,
            Map<String, List<SourceEstimate>> estimatesByTarget, Map<String, Instant> observedByTarget) {
        MergeSplitEvaluator evaluator = new MergeSplitEvaluator(machine);
        List<MergeSplitEvaluator.TargetSnapshot> snapshots =
                mergeSnapshots(estimatesByTarget, predicted, byTarget, statuses);
        if (snapshots.size() < 2) return;

        int expireFrames = params.integer("association", "pending_expire_frames");
        OffsetDateTime at = frameAt.atOffset(ZoneOffset.UTC);
        Set<String> merged = new LinkedHashSet<>();
        for (MergeSplitEvaluator.Candidate candidate : evaluator.mergeCandidates(snapshots)) {
            // 一帧里同一个目标只并一次：a+b 并完之后 b 已经不在了，b+c 这一对本帧不能再动。
            if (merged.contains(candidate.aId()) || merged.contains(candidate.bId())) continue;
            String pendingKey = "MANY_TO_ONE|" + String.join(",", Stream.of(candidate.aId(), candidate.bId()).sorted().toList());
            AssociationPendingRepository.PendingRow open = pendings.findOpen(domain.asKey(), pendingKey);
            int framesSeen = 1;
            String pendingId;
            if (open == null) {
                pendingId = pendings.insert(domain.asKey(), null, write(List.of(candidate.aId(), candidate.bId())),
                        "MANY_TO_ONE", pendingKey, frameAt);
            } else {
                pendingId = open.pendingId();
                framesSeen = open.framesSeen() + 1;
                pendings.touch(pendingId, frameAt, framesSeen);
            }
            if (framesSeen > expireFrames) {
                // 计到过期帧数还没达阈说明判定被别的条件挡着，留着只会一直占位。
                pendings.resolve(pendingId, "EXPIRED", frameAt);
                continue;
            }
            if (!evaluator.mergeReady(framesSeen)) continue;

            String survivor = candidate.survivorId(), loser = candidate.mergedId();
            String lineageId = UUID.randomUUID().toString();
            Map<String, Object> snapshotJson = new LinkedHashMap<>();
            for (String id : List.of(survivor, loser)) {
                ActiveTarget row = byTarget.get(id);
                if (row != null) snapshotJson.put(id, Map.of("target_no", row.target().targetNo(),
                        "object_type_code", row.target().objectTypeCode() == null ? "" : row.target().objectTypeCode(),
                        "version", row.status().version()));
            }
            lineages.insertLineage(new LineageRepository.LineageInsert(lineageId, "MERGE", at, survivor, null,
                    write(List.of(loser)), write(List.of(survivor)),
                    write(Map.of("reason", "auto", "distance_m", candidate.distanceM(), "threshold_m", candidate.thresholdM(),
                            "frames_seen", framesSeen)),
                    ALGO_VERSION, params.configVersion(), "SYSTEM", null, null, write(snapshotJson), at));
            // 被并目标行不删不改名：告警、事件、风险里的历史外键必须继续可解析，只加别名与状态。
            lineages.upsertAlias(loser, survivor, lineageId, at);
            lineages.redirectAliases(loser, survivor, lineageId, at);
            lineages.upsertTrackStatus(loser, "MERGE", at);
            events.emit(FusionEventEmitter.MERGED, survivor, at,
                    Map.of("lineage_id", lineageId, "merged_target_ids", List.of(loser), "operator_kind", "SYSTEM"));
            pendings.resolve(pendingId, "MERGED", frameAt);

            estimatesByTarget.remove(loser);
            observedByTarget.remove(loser);
            statuses.remove(loser);
            merged.add(loser);
        }
    }

    /**
     * 本帧有资格参与合并的目标（决策 16-8）。
     *
     * **必须本帧真的被观测命中**：④ 会把同分区**所有活目标**都放进 `estimatesByTarget`（空列表，供 ⑤ 照常写帧），
     * 直接遍历那个 keySet 的话，库里任何一个陈旧 STABLE 目标——哪怕本轮一次都没被观测到——
     * 只要预测位置落在 2σ 内就成了候选，而且"每帧都在"，必然凑够 `merge_min_frames`。
     * 升级路径验收上就这么把活目标并进了阶段 2/8 的种子目标；全新库撞不到，因为那里没有陈旧目标同处一个分区。
     */
    static List<MergeSplitEvaluator.TargetSnapshot> mergeSnapshots(
            Map<String, List<SourceEstimate>> estimatesByTarget, Map<String, State> predicted,
            Map<String, ActiveTarget> byTarget, Map<String, TrackStatus> statuses) {
        List<MergeSplitEvaluator.TargetSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, List<SourceEstimate>> entry : estimatesByTarget.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            State state = predicted.get(entry.getKey());
            ActiveTarget candidate = byTarget.get(entry.getKey());
            // 本帧新建的目标没有预测态，也还是 TENTATIVE，不参与合并。
            if (state == null || candidate == null) continue;
            snapshots.add(new MergeSplitEvaluator.TargetSnapshot(entry.getKey(), statuses.get(entry.getKey()),
                    state.longitude(), state.latitude(), state.accuracyM(), state.vx(), state.vy(),
                    candidate.target().firstSeenAt()));
        }
        return snapshots;
    }

    /** 本帧同一来源的另一条回波落在了哪个既有目标上；没有就返回 null。 */
    private String sameSourceOrigin(List<SourceObservation> parsed, String[] assignedTarget,
            Map<String, ActiveTarget> byTarget, int index) {
        String sourceId = parsed.get(index).sourceId();
        for (int j = 0; j < parsed.size(); j++) {
            if (j == index || !sourceId.equals(parsed.get(j).sourceId())) continue;
            String other = assignedTarget[j];
            // 必须是本帧之前就存在的目标：两条都是本帧新建时谁也不是谁分裂出来的。
            if (other != null && byTarget.containsKey(other)) return other;
        }
        return null;
    }

    /**
     * 自动分裂（决策 16-2）：同源同帧的第二回波先记 `association_pending(ONE_TO_MANY)` 计帧，
     * 连续 `split_min_frames` 帧且间距 ≥ `split_min_separation_m` 才认。
     *
     * **原目标保留 ID 继续存活**，不按契约原文"终止原目标 + 两个新 ID"：态势页上一直在跟的目标突然换号，
     * 比多一个目标更难解释。达阈后给新目标补一行 `op=SPLIT, origin_target_id=原目标` 的血缘。
     *
     * 注意是**补一行**而不是改写创建时那行 CREATE：`target_lineage` 在 PostgreSQL 上由
     * `trg_stage8_lineage_append_only` 守着只增不改，UPDATE 会直接被拒——而 H2 没有这个触发器，
     * 照"改写"写会是又一个"H2 绿、PG 红"。CREATE 那行记的是创建当时的事实，本就不该抹掉。
     */
    private void autoSplit(FusionDomainKey domain, Instant frameAt, FusionParams params, IdentityStateMachine machine,
            Map<String, String> splitOrigins, Map<String, List<SourceEstimate>> estimatesByTarget) {
        MergeSplitEvaluator evaluator = new MergeSplitEvaluator(machine);
        for (Map.Entry<String, String> entry : splitOrigins.entrySet()) {
            String pendingKey = "ONE_TO_MANY|" + entry.getValue() + "," + entry.getKey();
            if (pendings.findOpen(domain.asKey(), pendingKey) == null) {
                pendings.insert(domain.asKey(), null, write(List.of(entry.getValue(), entry.getKey())),
                        "ONE_TO_MANY", pendingKey, frameAt);
            }
        }
        int expireFrames = params.integer("association", "pending_expire_frames");
        OffsetDateTime at = frameAt.atOffset(ZoneOffset.UTC);
        for (AssociationPendingRepository.PendingRow pending : pendings.listOpen(domain.asKey(), "ONE_TO_MANY")) {
            String[] pair = pending.pendingKey().substring("ONE_TO_MANY|".length()).split(",", 2);
            if (pair.length != 2) continue;
            String origin = pair[0], child = pair[1];
            double separation = separationM(estimatesByTarget, origin, child);
            if (Double.isNaN(separation)) continue;   // 本帧没同时看到这两个，不计也不重置
            int framesSeen = splitOrigins.containsKey(child) ? pending.framesSeen() : pending.framesSeen() + 1;
            if (framesSeen != pending.framesSeen()) pendings.touch(pending.pendingId(), frameAt, framesSeen);
            if (framesSeen > expireFrames) { pendings.resolve(pending.pendingId(), "EXPIRED", frameAt); continue; }
            if (!evaluator.splitReady(framesSeen, separation)) continue;

            identities.insertLineage("SPLIT", frameAt, child, origin, write(List.of(child)), write(List.of(origin)),
                    write(Map.of("reason", "auto", "separation_m", separation, "frames_seen", framesSeen)),
                    ALGO_VERSION, params.configVersion(), write(Map.of()));
            events.emit(FusionEventEmitter.SPLIT, child, at,
                    Map.of("origin_target_id", origin, "separation_m", separation, "operator_kind", "SYSTEM"));
            pendings.resolve(pending.pendingId(), "SPLIT", frameAt);
        }
    }

    /** 本帧这两个目标之间的间距；有一个没被看到就返回 NaN。 */
    private static double separationM(Map<String, List<SourceEstimate>> estimatesByTarget, String a, String b) {
        List<SourceEstimate> ea = estimatesByTarget.get(a), eb = estimatesByTarget.get(b);
        if (ea == null || eb == null || ea.isEmpty() || eb.isEmpty()) return Double.NaN;
        SourceEstimate pa = ea.get(0), pb = eb.get(0);
        if (pa.longitude() == null || pa.latitude() == null || pb.longitude() == null || pb.latitude() == null) return Double.NaN;
        double[] offset = AlphaBetaFilter.toEnu(pa.longitude(), pa.latitude(), pb.longitude(), pb.latitude());
        return Math.hypot(offset[0], offset[1]);
    }

    private void recordPending(FusionDomainKey domain, SourceObservation observation, List<String> candidateTargetIds, String reason, Instant at) {
        String pendingKey = reason + "|" + observation.sourceId() + "|" + observation.externalTargetId() + "|" + String.join(",", candidateTargetIds.stream().sorted().toList());
        AssociationPendingRepository.PendingRow existing = pendings.findOpen(domain.asKey(), pendingKey);
        if (existing == null) pendings.insert(domain.asKey(), observation.observationId(), write(candidateTargetIds), reason, pendingKey, at);
        else pendings.touch(existing.pendingId(), at, existing.framesSeen() + 1);
    }

    /** 回放信封 frame 部分。 */
    /**
     * 读 JSON 列：H2 把 CAST(? AS JSON) 的字符串存成 JSON 文本，读回是"带引号的字符串"，PostgreSQL 直接是对象；
     * 两种形态都要能解开，否则同一份代码在 H2 绿、在真实库红（阶段 7 的教训）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try {
            JsonNode node = json.readTree(value);
            if (node != null && node.isTextual()) node = json.readTree(node.textValue());
            if (node == null || !node.isObject()) throw new IllegalStateException("滤波状态不是 JSON 对象");
            return json.convertValue(node, Map.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("滤波状态无法解析", ex);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("融合中间结果无法序列化", ex); }
    }
}
