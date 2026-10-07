package com.uav.lowaltitude.modules.fusion.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.uav.lowaltitude.modules.fusion.domain.IdentitySerials;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.TrackState;
import com.uav.lowaltitude.modules.fusion.domain.IdentityStateMachine.Transition;
import com.uav.lowaltitude.modules.fusion.domain.MergeSplitEvaluator;
import com.uav.lowaltitude.modules.fusion.domain.SourceObservation;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper.Frame;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper.Item;
import com.uav.lowaltitude.modules.fusion.ingest.InboxSourceRouter;
import com.uav.lowaltitude.modules.fusion.infrastructure.AssociationPendingRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.ActiveTarget;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.StatusRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.StatusUpdate;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationRepository.DeviceMeta;
import com.uav.lowaltitude.modules.fusion.infrastructure.ObservationRepository.SourceMeta;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.LinkRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.LinkState;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.PointInsert;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.TrackRow;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/**
 * 一帧（一个来源一个时刻的多目标观测）的处理：解析 → 按分区分组 → α-β 滤波 → 门限/匈牙利关联 → ID 状态机 → 写原始层 → 交融合层。
 * 事务边界在调用方（{@link FusionIngestWorker} 或种子的同步 drain）：整帧一个事务，任何一步失败整帧回滚并把 inbox 置 FAILED，
 * 不允许留下半帧数据（一半观测入库、另一半没有会让后续关联建立在残缺证据上）。
 * 唯一的例外是门限歧义的待定记录（association_pending）：它只是"这次关联没把握"的旁注，写不进去也不影响本帧的观测与关联结果，
 * 所以在保存点里写，失败只记日志，不拖垮整帧（ZT-01：曾因它整帧失败，TDOA 一路的身份证据全部丢失）。
 * 跨 source_mode 或跨归属元组永不关联：回放与实测、不同辖区的目标不是同一物理对象的证据。
 * 机身序列号不同的两架无人机永不关联、永不自动合并（ZT-01，见 {@link IdentitySerials}）。
 * 迟到帧（observed_at 早于目标最新观测）照写原始层，但在 TargetFrameResult 里如实带上 observedAt，由融合层决定不回退 latest_state。
 *
 * 吞吐（ZT-06）：一帧里的 link、未结束 RAW 轨迹、活跃目标的滤波快照各一次查询取回，观测、轨迹点、滤波快照、目标与状态推进
 * 按帧批量写；逐条观测各查各写时，一帧 100 个目标就是两千多次数据库往返。读写的先后关系与逐条处理时一致。
 */
@Service
public class FusionPipeline {
    public static final String ALGO_VERSION = "fusion-e1-v1";
    /** association_pending.pending_key 的列宽：候选目标多时键会超长，超长的键改用摘要，同一组候选每帧仍得到同一个键。 */
    static final int PENDING_KEY_MAX_LENGTH = 256;
    private static final Logger log = LoggerFactory.getLogger(FusionPipeline.class);

    private final SimulationPolicy simulation;
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
    private final FusionProperties properties;
    /** 待定记录的保存点：在调用方的整帧事务里开嵌套事务；没有外层事务时（单测直接调用）退化为独立的小事务。 */
    private final TransactionTemplate savepoint;

    public FusionPipeline(ObservationRepository observations, RawTrackRepository rawTracks, IdentityRepository identities,
            AssociationPendingRepository pendings, FusionConfigLoader configLoader, ObjectProvider<FusedLayerWriter> fusedLayerWriter,
            InboxSourceRouter router, ObjectMapper json, LineageRepository lineages, FusionEventEmitter events, FusionProperties properties,
            SimulationPolicy simulation, PlatformTransactionManager transactionManager) {
        this.simulation = simulation;
        this.observations = observations; this.rawTracks = rawTracks; this.identities = identities; this.pendings = pendings;
        this.configLoader = configLoader; this.fusedLayerWriter = fusedLayerWriter; this.router = router; this.json = json;
        this.lineages = lineages; this.events = events;
        this.properties = properties;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    public record FrameOutcome(int observationCount, int targetCount, List<String> targetIds) { }

    /** 帧开始时一条 link 的状态：门限中心、已知序列号、最近单源估计都从这一份读，不再按目标逐个回查。 */
    private record LinkSnapshot(String targetId, String linkId, State state, Object savedEstimate, String serial) { }

    /** 本帧的门限歧义：观测行写入之后才能记待定（association_pending.observation_id 外键指向 source_observation）。 */
    private record Ambiguous(SourceObservation observation, List<String> candidateTargetIds) { }

    public FrameOutcome processFrame(InboxRow inbox) {
        FusionParams params = configLoader.active();
        AlphaBetaFilter filter = new AlphaBetaFilter(params);
        Associator associator = new Associator(new AssociationCost(params));
        IdentityStateMachine machine = new IdentityStateMachine(params);

        SourceMeta source = observations.findSource(inbox.sourceId());
        if (source == null || !source.enabled()) throw new IllegalStateException("回放来源不存在或已停用: " + inbox.sourceId());
        simulation.requireSourceMode(source.sourceMode());
        DeviceMeta device = observations.findDeviceForSource(inbox.sourceId());
        if ("SIM_NORMALIZED".equals(source.sourceType())) {
            if (!"replay".equals(source.sourceMode()) || !inbox.source().startsWith("sim-normalized:"))
                throw new IllegalStateException("NORMALIZED_SOURCE_SCOPE_CHANGED");
            try {
                var envelope=json.readTree(inbox.payloadJson());
                if(envelope.isTextual())envelope=json.readTree(envelope.asText());
                device=observations.requireNormalizedDevice(inbox.sourceId(),envelope.path("device_id").asText(null),
                        envelope.path("owner_org_id").asText(null),envelope.path("district_id").asText(null));
            } catch(java.io.IOException error) { throw new IllegalStateException("NORMALIZED_SOURCE_SCOPE_CHANGED",error); }
        }
        Frame frame = router.map(inbox);
        Instant receivedAt = Instant.ofEpochMilli(inbox.receivedAtMillis());
        if (frame.observedAt().isAfter(receivedAt.plusMillis(properties.getMaxFutureSkewMillis()))) {
            throw new IllegalStateException("OBSERVATION_TIME_IN_FUTURE: observed_at exceeds received_at and permitted clock skew");
        }
        FusionDomainKey domain = new FusionDomainKey(source.sourceMode(), device == null ? null : device.ownerOrgId(), device == null ? null : device.districtId());

        List<SourceObservation> parsed = new ArrayList<>();
        for (Item item : frame.items()) {
            parsed.add(new SourceObservation(UUID.randomUUID().toString(), inbox.inboxId(), source.sourceId(), source.sourceCode(), source.sourceType(), source.schemaStatus(),
                    device == null ? null : device.deviceId(), frame.sessionKey(), item.externalTargetId(), item.externalTrackId(), frame.observedAt(), receivedAt,
                    item.longitude(), item.latitude(), item.positionAccuracyM(), item.altitudeAmslM(), item.heightAglM(), item.speedMps(), item.headingDeg(),
                    item.classCode(), item.classConfidence(), item.identityClue(), null, item.latencyMs(), new LinkedHashMap<>(item.quality()), source.sourceMode(),
                    domain.ownerOrgId(), domain.districtId(), frame.recordNo(), item.pilotLongitude(), item.pilotLatitude(), item.classSource()));
        }
        markUntrustedTime(parsed, frame.observedAt(), receivedAt);

        // 本帧涉及的 link 与它们未结束的 RAW 轨迹一次取回；之后本帧新建/改挂/续写都同步到这两份缓存，
        // 后面的观测读到的就是前面观测写过之后的样子，与逐条查库一致。
        Map<String, LinkRow> links = new HashMap<>(rawTracks.findLinks(source.sourceId(), frame.sessionKey(), externalIds(parsed)));
        Map<String, TrackRow> openTracks = new HashMap<>(rawTracks.findOpenRawTracks(links.values()));

        // ① 滤波：每条观测按其 link 的现有状态更新；缺精度用目录缺省并在 quality 标记。
        List<Double> accuracies = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        for (SourceObservation observation : parsed) {
            LinkRow link = linkOf(links, observation);
            TrackRow open = link == null ? null : openTracks.get(link.linkId());
            State existing = open == null || open.filterStateJson() == null ? null : State.fromMap(readMap(open.filterStateJson()));
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
        List<LinkSnapshot> snapshots = linkSnapshots(active);
        Map<String, State> predicted = predictStates(filter, snapshots, frame.observedAt());
        Map<String, Set<String>> serialsByTarget = serialsByTarget(snapshots);

        String[] assignedTarget = new String[parsed.size()];
        List<Integer> unlinked = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            SourceObservation observation = parsed.get(i);
            LinkRow link = linkOf(links, observation);
            if (link != null) {
                // 活跃目标不会是别名（被并目标的状态是 MERGE，不在活跃集里），只有不在活跃集里的才需要沿别名找幸存者。
                String resolved = byTarget.containsKey(link.targetId()) ? link.targetId() : identities.resolveAlias(link.targetId());
                if (byTarget.containsKey(resolved)) {
                    // 同一来源同一外部目标号报出了另一个序列号：来源把编号给了另一架飞机，这条 link 不能再沿用，按新观测重新关联。
                    if (IdentitySerials.conflicts(IdentitySerials.of(observation), serialsByTarget.get(resolved))) {
                        observation.quality().put("identity_conflict", true);
                        unlinked.add(i);
                        continue;
                    }
                    assignedTarget[i] = resolved;
                    continue;
                }
            }
            unlinked.add(i);
        }
        List<Ambiguous> ambiguous = new ArrayList<>();
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
                        candidate.status().state().confirmHits(), candidate.status().state().missFrames(),
                        serialsByTarget.getOrDefault(candidate.target().targetId(), Set.of())));
            }
            Associator.Result association = associator.associate(subset, subsetAccuracies, candidates);
            for (Associator.Match match : association.matches()) assignedTarget[unlinked.get(match.observationIndex())] = match.targetId();
            for (Associator.Ambiguity ambiguity : association.ambiguities()) {
                SourceObservation observation = subset.get(ambiguity.observationIndex());
                observation.quality().put("class_association_ambiguous", true);
                ambiguous.add(new Ambiguous(observation, ambiguity.candidateTargetIds()));
            }
        }

        // ③ 观测先落库（待定记录的外键指向它），再记待定、推进目标、写原始层。
        observations.insertAll(parsed);
        for (Ambiguous entry : ambiguous) recordPendingSafely(domain, entry.observation(), entry.candidateTargetIds(), "GATE_AMBIGUOUS", frame.observedAt());
        Map<String, Instant> touched = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            if (assignedTarget[i] != null) touched.merge(assignedTarget[i], parsed.get(i).observedAt(), FusionPipeline::later);
        }
        identities.touchTargets(touched, receivedAt);

        Map<String, List<SourceEstimate>> estimatesByTarget = new LinkedHashMap<>();
        Map<String, Instant> observedByTarget = new LinkedHashMap<>();
        Map<String, String> splitOrigins = new LinkedHashMap<>();
        Set<String> created = new LinkedHashSet<>();
        Set<String> relinked = new HashSet<>();
        Map<String, String> stateWrites = new LinkedHashMap<>();
        List<PointInsert> points = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            SourceObservation observation = parsed.get(i);
            String targetId = assignedTarget[i];
            if (targetId == null) {
                // 决策 16-2：同一来源在同一帧里还有另一条回波、且那条落在一个**本帧之前就存在**的目标上，
                // 那么这条新回波很可能是那个目标分裂出来的，而不是凭空冒出来的第三方。记下来交给 autoSplit 计帧。
                String origin = sameSourceOrigin(parsed, assignedTarget, byTarget, i);
                targetId = createTarget(observation, frame.observedAt(), params);
                created.add(targetId);
                if (origin != null) splitOrigins.put(targetId, origin);
            }
            if ("SIM_NORMALIZED".equals(observation.sourceType()) && "replay".equals(observation.sourceMode())) {
                identities.applySimulatorIdentity(targetId, observation.classCode(), observation.identityClue(),
                        "BALLOON".equals(observation.quality().get("subtype")) ? "BALLOON" : null, observation.observedAt());
            }
            assignedTarget[i] = targetId;
            SourceEstimate estimate = writeRawLayer(observation, updates.get(i), accuracies.get(i), targetId, params, receivedAt,
                    links, openTracks, relinked, stateWrites, points);
            estimatesByTarget.computeIfAbsent(targetId, k -> new ArrayList<>()).add(estimate);
            observedByTarget.merge(targetId, observation.observedAt(), FusionPipeline::later);
        }
        rawTracks.updateFilterStates(stateWrites);
        rawTracks.insertPoints(points);

        // ④ 只由实际命中推进观测；其他来源的报文不构成该目标的失联证据。
        Map<String, TrackStatus> statuses = new LinkedHashMap<>();
        Map<String, Integer> missFrames = new LinkedHashMap<>();
        List<StatusUpdate> statusWrites = new ArrayList<>();
        for (Map.Entry<String, List<SourceEstimate>> entry : estimatesByTarget.entrySet()) {
            // 帧开始时取回的活跃目标状态就是此刻库里的状态（本帧到这里只新建了目标，没有改过已有目标的状态）；本帧新建的目标是初始状态。
            ActiveTarget known = byTarget.get(entry.getKey());
            StatusRow status = known != null ? known.status()
                    : new StatusRow(entry.getKey(), TrackState.created(frame.observedAt()), null, 0);
            Transition transition = machine.onHit(status.state(), observedByTarget.get(entry.getKey()));
            String primarySource = entry.getValue().get(0).sourceId();
            statusWrites.add(new StatusUpdate(entry.getKey(), transition.state(), primarySource, receivedAt));
            if (status.primarySourceId() != null && !status.primarySourceId().equals(primarySource)) {
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
            long gap = sinceLastHit(candidate.status(), frame.observedAt(), receivedAt);
            boolean hitBefore = candidate.status().lastReceivedAt() != null || candidate.status().state().lastObservedAt() != null;
            if (hitBefore && gap <= machine.shortLostAfterMillis()) continue;
            Transition transition = machine.onMiss(candidate.status().state(), gap, frame.observedAt());
            if (transition.changed() || transition.state().missFrames() != candidate.status().state().missFrames()) {
                statusWrites.add(new StatusUpdate(targetId, transition.state(), candidate.status().primarySourceId()));
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
        // 状态推进必须在自动合并之前落库：合并把被并目标的状态改成 MERGE，晚写的命中状态会把它覆盖回活跃。
        identities.updateStatuses(statusWrites, receivedAt);

        // ④.5 自动合并（决策 16-1）：放在状态推进之后、交融合层之前——此时本帧的预测位置、状态、首见时刻都在手上，
        // 被并目标要在进 ⑤ 之前从本帧结果里摘掉，否则融合层会为一个已经不存在的目标再写一帧。
        Map<String, Set<String>> mergeSerials = serialsAfterFrame(snapshots, relinked, parsed, assignedTarget);
        autoMerge(domain, frame.observedAt(), params, machine, predicted, byTarget, statuses, estimatesByTarget, observedByTarget, mergeSerials);
        autoSplit(domain, frame.observedAt(), params, machine, splitOrigins, estimatesByTarget);

        // ⑤ 交给融合层（E2）。E2 未落地时 ObjectProvider 取不到 Bean，用无操作实现，原始层照常入库。
        FusedLayerWriter writer = fusedLayerWriter.getIfAvailable(() -> f -> { });
        Map<String, List<SourceEstimate>> savedByTarget = savedEstimates(snapshots, relinked, estimatesByTarget);
        List<String> targetIds = new ArrayList<>();
        List<TargetFrameResult> results = new ArrayList<>();
        for (Map.Entry<String, List<SourceEstimate>> entry : estimatesByTarget.entrySet()) {
            targetIds.add(entry.getKey());
            List<SourceEstimate> estimates = recentEstimates(observedByTarget.get(entry.getKey()), entry.getValue(),
                    savedByTarget.getOrDefault(entry.getKey(), List.of()), machine.shortLostAfterMillis());
            results.add(new TargetFrameResult(entry.getKey(), domain, observedByTarget.get(entry.getKey()), estimates,
                    statuses.getOrDefault(entry.getKey(), TrackStatus.TENTATIVE), missFrames.getOrDefault(entry.getKey(), 0), params.configVersion()));
        }
        writer.writeAll(results);
        return new FrameOutcome(parsed.size(), targetIds.size(), List.copyOf(targetIds));
    }

    /**
     * 本帧离该目标最近一次命中隔了多久，用来判断它是否短失/终止（ZT-20）。各设备的时钟可能不准：拿别的设备的报文时刻
     * 去比，时钟慢两分钟的雷达报上来的目标一出现就被判终止，下一帧又新建一个。所以按平台收到数据的时刻比——这是所有来源
     * 共用的一个时钟（回放数据集的到达时刻同样在回放时钟上）。升级前建的状态还没有最近命中的到达时刻，仍按报文时刻比。
     */
    static long sinceLastHit(StatusRow status, Instant frameObservedAt, Instant frameReceivedAt) {
        if (status.lastReceivedAt() != null) return Duration.between(status.lastReceivedAt(), frameReceivedAt).toMillis();
        Instant reference = status.state().lastObservedAt() != null ? status.state().lastObservedAt() : status.state().since();
        return Duration.between(reference, frameObservedAt).toMillis();
    }

    /**
     * 报文时刻比平台接收时刻晚太多（ZT-20）：设备时钟慢了，或者数据在路上积压了。观测照常入库、照常关联，
     * 但在 quality 里写明 time_untrusted 与 arrival_lag_ms；融合层据此给最新状态挂上 observed_at 的 TIME_UNTRUSTED 提示，
     * 页面写"数据过期/设备时间不准"，而不是把几分钟前的位置当成实时数据显示。
     */
    private void markUntrustedTime(List<SourceObservation> parsed, Instant observedAt, Instant receivedAt) {
        long threshold = properties.getTimeUntrustedLagMillis();
        if (threshold <= 0) return;
        long lag = receivedAt.toEpochMilli() - observedAt.toEpochMilli();
        if (lag <= threshold) return;
        for (SourceObservation observation : parsed) {
            observation.quality().put("time_untrusted", true);
            observation.quality().put("arrival_lag_ms", lag);
        }
    }

    private String createTarget(SourceObservation observation, Instant frameAt, FusionParams params) {
        String targetId = UUID.randomUUID().toString();
        String targetNo = identities.nextTargetNo(observation.observedAt());
        identities.insertTarget(targetId, targetNo, observation.classCode(), null, observation.observedAt(), observation.domain(), observation.receivedAt());
        identities.insertStatus(targetId, TrackState.created(frameAt), observation.receivedAt());
        identities.insertLineage("CREATE", observation.observedAt(), targetId, null, write(List.of(targetId)), write(List.of()),
                write(Map.of("source_code", observation.sourceCode(), "external_target_id", observation.externalTargetId())), ALGO_VERSION,
                params.configVersion(), write(Map.of()));
        return targetId;
    }

    /**
     * 写一条观测的原始层：link（新建或改挂）→ RAW 轨迹（续写或重开）→ 轨迹点 → 滤波快照。
     * link / 未结束轨迹读本帧缓存并在写后同步；轨迹点与续写轨迹的快照先攒着，由调用方在本帧观测写完后一次批量落库
     * （新开的轨迹直接带最终快照插入，轨迹点写入时它已存在）。快照的最终取值与逐条两次 UPDATE 的旧写法完全相同。
     */
    private SourceEstimate writeRawLayer(SourceObservation observation, Update update, double accuracyM, String targetId, FusionParams params, Instant receivedAt,
            Map<String, LinkRow> links, Map<String, TrackRow> openTracks, Set<String> relinked, Map<String, String> stateWrites, List<PointInsert> points) {
        LinkRow link = linkOf(links, observation);
        String linkId;
        TrackRow open;
        if (link == null) {
            linkId = UUID.randomUUID().toString();
            rawTracks.insertLink(linkId, targetId, observation.sourceId(), observation.deviceId(), observation.sourceSessionKey(), observation.externalTargetId(), observation.receivedAt());
            if (observation.externalTargetId() != null) {
                links.put(observation.externalTargetId(), new LinkRow(linkId, targetId, observation.sourceId(), observation.deviceId(),
                        observation.sourceSessionKey(), observation.externalTargetId()));
            }
            open = null;
        } else {
            linkId = link.linkId();
            if (!targetId.equals(link.targetId())) {
                rawTracks.relink(linkId, targetId);
                relinked.add(linkId);
                links.put(link.externalTargetId(), new LinkRow(linkId, targetId, link.sourceId(), link.deviceId(), link.sourceSessionKey(), link.externalTargetId()));
                // 改挂是少见路径：该 link 在新目标名下是否还有没结束的旧轨迹，照旧查库确认。
                open = rawTracks.findOpenRawTrack(linkId, targetId);
            } else {
                open = openTracks.get(linkId);
            }
        }
        String stateJson = update == null ? null : write(update.state().toMap());
        boolean newTrack = open == null || (update != null && update.reinitialized() && open.filterStateJson() != null);
        String trackId = newTrack ? UUID.randomUUID().toString() : open.trackId();
        if (newTrack && open != null) rawTracks.endTrack(open.trackId(), observation.observedAt());
        if (observation.hasPosition()) {
            points.add(new PointInsert(UUID.randomUUID().toString(), trackId, observation.inboxId(), observation.pointSeq(), observation.observedAt(), receivedAt,
                    observation.longitude(), observation.latitude(), observation.altitudeAmslM(), observation.heightAglM(), observation.observationId(), accuracyM, PointKind.MEAS.name()));
        }
        State state = update == null ? null : update.state();
        // 三元表达式两侧一个是 Double、一个是 double 会触发自动拆箱：没有位置的来源（AOA 只给方位）在这里会 NPE。
        // 显式装箱保留"没有位置"这件事，让它一路带到融合层，而不是在管线里炸掉整帧。
        Double estimateLongitude = state == null ? observation.longitude() : Double.valueOf(state.longitude());
        Double estimateLatitude = state == null ? observation.latitude() : Double.valueOf(state.latitude());
        SourceEstimate estimate = new SourceEstimate(observation.sourceId(), observation.sourceCode(), observation.sourceType(), observation.schemaStatus(), linkId, trackId,
                observation.observationId(), observation.observedAt(), estimateLongitude, estimateLatitude,
                state == null ? null : state.accuracyM(), observation.altitudeAmslM(), observation.heightAglM(),
                observation.speedMps() != null ? observation.speedMps() : (state == null ? null : state.speedMps()),
                observation.headingDeg() != null ? observation.headingDeg() : (state == null ? null : state.headingDeg()),
                observation.classCode(), observation.classConfidence(), observation.identityClue(), observation.identityConfidence(),
                PointKind.MEAS, Map.copyOf(observation.quality()), observation.pilotLongitude(), observation.pilotLatitude(), observation.classSource());

        // 持久化单源最近真实观测，进程重启后仍能组合异步来源。迟到帧不得回退快照：保留原快照。
        String finalState;
        if (state != null && !update.outOfOrder()) {
            Map<String, Object> snapshot = new LinkedHashMap<>(state.toMap());
            snapshot.put("source_estimate", estimate);
            finalState = write(snapshot);
        } else if (open != null && update != null && update.outOfOrder() && open.filterStateJson() != null) {
            finalState = open.filterStateJson();
        } else {
            finalState = newTrack ? stateJson : (stateJson != null ? stateJson : open.filterStateJson());
        }
        if (newTrack) {
            String externalTrackId = (observation.externalTrackId() == null ? observation.externalTargetId() : observation.externalTrackId()) + ":" + observation.observedMillis();
            rawTracks.insertRawTrack(trackId, targetId, linkId, externalTrackId, observation.observedAt(), params.configVersion(), finalState);
            openTracks.put(linkId, new TrackRow(trackId, targetId, linkId, externalTrackId, observation.observedAt(), finalState));
        } else {
            if (finalState != null && !finalState.equals(open.filterStateJson())) stateWrites.put(trackId, finalState);
            openTracks.put(linkId, new TrackRow(trackId, targetId, linkId, open.externalTrackId(), open.startedAt(), finalState));
        }
        return estimate;
    }

    /** 一帧里各观测的外部目标号（去空），用于一次取回本帧涉及的 link。 */
    private static List<String> externalIds(List<SourceObservation> parsed) {
        Set<String> ids = new LinkedHashSet<>();
        for (SourceObservation observation : parsed) if (observation.externalTargetId() != null) ids.add(observation.externalTargetId());
        return List.copyOf(ids);
    }

    private static LinkRow linkOf(Map<String, LinkRow> links, SourceObservation observation) {
        return observation.externalTargetId() == null ? null : links.get(observation.externalTargetId());
    }

    /** 活跃目标全部 link 的帧初快照：一次查询，滤波状态与单源估计各解析一次。 */
    private List<LinkSnapshot> linkSnapshots(List<ActiveTarget> active) {
        List<String> ids = new ArrayList<>();
        for (ActiveTarget candidate : active) ids.add(candidate.target().targetId());
        List<LinkSnapshot> out = new ArrayList<>();
        for (LinkState link : rawTracks.linkStates(ids)) {
            if (link.filterStateJson() == null) continue;
            Map<String, Object> map = readMap(link.filterStateJson());
            Object saved = map.get("source_estimate");
            String serial = saved instanceof Map<?, ?> estimate && estimate.get("quality") instanceof Map<?, ?> quality
                    ? IdentitySerials.normalize(quality.get(IdentitySerials.QUALITY_KEY)) : null;
            out.add(new LinkSnapshot(link.targetId(), link.linkId(), State.fromMap(map), saved, serial));
        }
        return out;
    }

    private static Map<String, State> predictStates(AlphaBetaFilter filter, List<LinkSnapshot> snapshots, Instant at) {
        Map<String, State> out = new HashMap<>();
        for (LinkSnapshot link : snapshots) {
            State state = filter.predict(link.state(), at.toEpochMilli());
            State existing = out.get(link.targetId());
            // 一个目标多条 link 时取精度更好的那条作为门限中心。
            if (existing == null || state.accuracyM() < existing.accuracyM()) out.put(link.targetId(), state);
        }
        return out;
    }

    /** 各活跃目标在帧开始时已报出的机身序列号（ZT-01）。 */
    private static Map<String, Set<String>> serialsByTarget(List<LinkSnapshot> snapshots) {
        Map<String, Set<String>> out = new HashMap<>();
        for (LinkSnapshot link : snapshots) {
            if (link.serial() != null) out.computeIfAbsent(link.targetId(), k -> new LinkedHashSet<>()).add(link.serial());
        }
        return out;
    }

    /**
     * 本帧写完之后各目标的已知序列号：帧初快照里仍挂在该目标名下的 link，加上本帧落到它头上的观测。
     * 自动合并用它判断"是不是两架不同的飞机"。
     */
    private static Map<String, Set<String>> serialsAfterFrame(List<LinkSnapshot> snapshots, Set<String> relinked, List<SourceObservation> parsed, String[] assignedTarget) {
        Map<String, Set<String>> out = new HashMap<>();
        for (LinkSnapshot link : snapshots) {
            if (link.serial() != null && !relinked.contains(link.linkId())) out.computeIfAbsent(link.targetId(), k -> new LinkedHashSet<>()).add(link.serial());
        }
        for (int i = 0; i < parsed.size(); i++) {
            String serial = IdentitySerials.of(parsed.get(i));
            if (serial != null && assignedTarget[i] != null) out.computeIfAbsent(assignedTarget[i], k -> new LinkedHashSet<>()).add(serial);
        }
        return out;
    }

    /** 本帧被命中目标的其他 link 上保存的单源最近估计（帧初快照；本帧改挂走的 link 不再算原目标的）。 */
    private Map<String, List<SourceEstimate>> savedEstimates(List<LinkSnapshot> snapshots, Set<String> relinked, Map<String, List<SourceEstimate>> estimatesByTarget) {
        Map<String, List<SourceEstimate>> out = new HashMap<>();
        for (LinkSnapshot link : snapshots) {
            if (link.savedEstimate() == null || relinked.contains(link.linkId())) continue;
            List<SourceEstimate> current = estimatesByTarget.get(link.targetId());
            // 未命中的失联帧不能用缓存伪造新的实测点，这些目标不需要解析。
            if (current == null || current.isEmpty()) continue;
            out.computeIfAbsent(link.targetId(), k -> new ArrayList<>()).add(json.convertValue(link.savedEstimate(), SourceEstimate.class));
        }
        return out;
    }

    private static List<SourceEstimate> recentEstimates(Instant at, List<SourceEstimate> current, List<SourceEstimate> saved, long freshnessMillis) {
        Map<String, SourceEstimate> bySource = new LinkedHashMap<>();
        // 未命中的失联帧不能用缓存伪造新的实测点。
        if (current.isEmpty()) return List.of();
        for (SourceEstimate estimate : saved) {
            if (estimate.observedAt().isAfter(at) || estimate.observedAt().isBefore(at.minusMillis(freshnessMillis))) continue;
            bySource.merge(estimate.sourceId(), estimate, (a, b) -> a.observedAt().isAfter(b.observedAt()) ? a : b);
        }
        for (SourceEstimate estimate : current) bySource.put(estimate.sourceId(), estimate);
        return List.copyOf(bySource.values());
    }

    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }

    /**
     * 自动合并（决策 16-1）：同域两个 STABLE 目标连续 `merge_min_frames` 帧落在 `merge_max_dist_sigma·σ` 内就并掉。
     *
     * 与人工合并（`FusionCommandService.merge`）写的是同一套东西，但有三处按 16-1 特意不同：
     * `operator_kind=SYSTEM`（没有操作人）、**不递增 `target.version`**（沿用 8-6：系统写入不与人工写入争版本，
     * 否则用户正在编辑的乐观锁会被后台随机打断）、**links 不迁移**（原始层的 link 记的是"哪条来源轨迹属于谁"，
     * 迁移会让历史回放对不上；别名解析已经能把旧 id 指到 survivor）。
     * 两边都报出了机身序列号且不同的不合并（ZT-01）：几十米外同向飞的两架无人机贴得再久也是两架。
     */
    private void autoMerge(FusionDomainKey domain, Instant frameAt, FusionParams params, IdentityStateMachine machine,
            Map<String, State> predicted, Map<String, ActiveTarget> byTarget, Map<String, TrackStatus> statuses,
            Map<String, List<SourceEstimate>> estimatesByTarget, Map<String, Instant> observedByTarget, Map<String, Set<String>> serials) {
        MergeSplitEvaluator evaluator = new MergeSplitEvaluator(machine);
        List<MergeSplitEvaluator.TargetSnapshot> snapshots =
                mergeSnapshots(estimatesByTarget, predicted, byTarget, statuses, serials);
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
        return mergeSnapshots(estimatesByTarget, predicted, byTarget, statuses, Map.of());
    }

    /** 同上，并带上各目标已知的机身序列号（ZT-01）：两边序列号不同的不进合并候选。 */
    static List<MergeSplitEvaluator.TargetSnapshot> mergeSnapshots(
            Map<String, List<SourceEstimate>> estimatesByTarget, Map<String, State> predicted,
            Map<String, ActiveTarget> byTarget, Map<String, TrackStatus> statuses, Map<String, Set<String>> serials) {
        List<MergeSplitEvaluator.TargetSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, List<SourceEstimate>> entry : estimatesByTarget.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            State state = predicted.get(entry.getKey());
            ActiveTarget candidate = byTarget.get(entry.getKey());
            // 本帧新建的目标没有预测态，也还是 TENTATIVE，不参与合并。
            if (state == null || candidate == null) continue;
            snapshots.add(new MergeSplitEvaluator.TargetSnapshot(entry.getKey(), statuses.get(entry.getKey()),
                    state.longitude(), state.latitude(), state.accuracyM(), state.vx(), state.vy(),
                    candidate.target().firstSeenAt(), serials.getOrDefault(entry.getKey(), Set.of())));
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

    /**
     * 门限歧义的待定记录在保存点里写（ZT-01）：它只是旁注，写失败（键冲突、库约束）只回滚这一条并记日志，
     * 本帧的观测、关联与原始层照常提交；整帧因为一条旁注失败，丢掉的是这一路来源这一帧的全部证据。
     */
    private void recordPendingSafely(FusionDomainKey domain, SourceObservation observation, List<String> candidateTargetIds, String reason, Instant at) {
        try {
            savepoint.executeWithoutResult(status -> recordPending(domain, observation, candidateTargetIds, reason, at));
        } catch (RuntimeException ex) {
            log.warn("fusion association pending not recorded: source={}, external_target_id={}, reason={}, error={}",
                    observation.sourceId(), observation.externalTargetId(), reason, ex.toString());
        }
    }

    private void recordPending(FusionDomainKey domain, SourceObservation observation, List<String> candidateTargetIds, String reason, Instant at) {
        String pendingKey = pendingKey(reason, observation.sourceId() + "|" + observation.externalTargetId() + "|"
                + String.join(",", candidateTargetIds.stream().sorted().toList()));
        AssociationPendingRepository.PendingRow existing = pendings.findOpen(domain.asKey(), pendingKey);
        if (existing == null) pendings.insert(domain.asKey(), observation.observationId(), write(candidateTargetIds), reason, pendingKey, at);
        else pendings.touch(existing.pendingId(), at, existing.framesSeen() + 1);
    }

    /** reason|主体；超过列宽（候选目标多、外部编号长）时主体换成 SHA-256 摘要，同一主体每帧得到同一个键。 */
    static String pendingKey(String reason, String subject) {
        String key = reason + "|" + subject;
        if (key.length() <= PENDING_KEY_MAX_LENGTH) return key;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            return reason + "|sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

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
