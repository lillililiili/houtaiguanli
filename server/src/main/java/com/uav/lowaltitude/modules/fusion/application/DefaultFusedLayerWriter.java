package com.uav.lowaltitude.modules.fusion.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusedLayerWriter;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.FusionContracts.PointKind;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TargetFrameResult;
import com.uav.lowaltitude.modules.fusion.FusionContracts.TrackStatus;
import com.uav.lowaltitude.modules.fusion.domain.AttributeSelector.UnknownField;
import com.uav.lowaltitude.modules.fusion.domain.DegradationEvaluator;
import com.uav.lowaltitude.modules.fusion.domain.DegradationEvaluator.Degradation;
import com.uav.lowaltitude.modules.fusion.domain.WeightedFuser;
import com.uav.lowaltitude.modules.fusion.domain.WeightedFuser.Contribution;
import com.uav.lowaltitude.modules.fusion.domain.WeightedFuser.FusedState;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository.DegradationRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository.DegradationWrite;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository.SelectionRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository.SelectionWrite;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionEventContextRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.FusedPoint;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.FusedTrackRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.LastPoint;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.LatestState;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.IdentityRepository.ClassRevision;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 融合层写入（E1 在管线第 ⑥ 步、在它自己的一帧事务内调用；这里不开事务）。
 * 一帧一个目标：属性优选 + 加权融合 → 降级评估 → FUSED 层 track/track_point → target_latest_state →
 * target_attribute_selection / target_degradation → fusion_event。不递增 target.version（决策 8-6）。
 * fusion_event 的 payload 是最新状态摘要（契约 §4/§8，决策 10-1）：给 A 的光电跟踪触发用，不放原始观测。
 * 一帧的多个目标按帧处理（ZT-06）：各目标的现状一次取回，融合点、最新状态、属性优选、降级各批量写一次，事件最后发；
 * 每个目标算出的内容、写入的内容与逐个写完全相同。
 * 融合类别变成明确的另一类时，目标头行的类别跟着改，并留一条系统改判记录（ZT-04）。
 * 报文时刻不可信（设备时钟慢、数据积压）的帧，最新状态带 observed_at 的 TIME_UNTRUSTED 提示（ZT-20）。
 */
@Component
public class DefaultFusedLayerWriter implements FusedLayerWriter {
    private static final int COORD_SCALE = 7, METRIC_SCALE = 2, CONF_SCALE = 5, SPEED_SCALE = 3;
    /** 决策 10-1：高度基准（海拔 vs 椭球高）客户未答复前，摘要里的 altitude_raw 固定标 UNCONFIRMED。 */
    private static final String ALTITUDE_DATUM_UNCONFIRMED = "UNCONFIRMED";
    /** 管线写进观测 quality 的"报文时刻不可信"标记（ZT-20）。 */
    static final String QUALITY_TIME_UNTRUSTED = "time_untrusted";
    private final WeightedFuser fuser = new WeightedFuser();
    private final DegradationEvaluator degradations = new DegradationEvaluator();
    private final FusionConfigService config;
    private final FusedTrackRepository tracks;
    private final DegradationRepository states;
    private final FusionEventEmitter events;
    private final FusionEventContextRepository eventContext;
    private final AppClock clock;
    private final ObjectMapper json;
    private final FusionConfigLoader simulatorParameters;
    private final IdentityRepository identities;

    public DefaultFusedLayerWriter(FusionConfigService config, FusedTrackRepository tracks, DegradationRepository states, FusionEventEmitter events,
            FusionEventContextRepository eventContext, AppClock clock, ObjectMapper json, FusionConfigLoader simulatorParameters, IdentityRepository identities) {
        this.config = config; this.tracks = tracks; this.states = states; this.events = events; this.eventContext = eventContext; this.clock = clock; this.json = json;
        this.simulatorParameters=simulatorParameters;
        this.identities = identities;
    }

    /** 本批各目标写入前的现状（一次取回）与攒着的批量写。 */
    private static final class Batch {
        final Map<String, SelectionRow> selections;
        final Map<String, DegradationRow> degradations;
        final Map<String, OffsetDateTime> latestObserved;
        final Map<String, FusedTrackRow> openTracks;
        final Map<String, Long> nextSeq;
        /** 目标头行当前的类别：融合类别与它不同才改判（不和上一帧的属性优选比，头行曾经没跟上的也能纠正过来）。 */
        final Map<String, String> headerClasses;
        final List<FusedPoint> points = new ArrayList<>();
        final List<String> endedTracks = new ArrayList<>();
        final List<OffsetDateTime> endedAt = new ArrayList<>();
        final List<LatestState> latest = new ArrayList<>();
        final List<SelectionWrite> selectionWrites = new ArrayList<>();
        final List<DegradationWrite> degradationWrites = new ArrayList<>();
        final List<ClassChange> classChanges = new ArrayList<>();
        final List<PendingEvents> events = new ArrayList<>();

        Batch(Map<String, SelectionRow> selections, Map<String, DegradationRow> degradations, Map<String, OffsetDateTime> latestObserved,
                Map<String, FusedTrackRow> openTracks, Map<String, Long> nextSeq, Map<String, String> headerClasses) {
            this.selections = selections; this.degradations = degradations; this.latestObserved = latestObserved; this.openTracks = openTracks; this.nextSeq = nextSeq;
            this.headerClasses = headerClasses;
        }
    }

    /** 融合类别变成了另一个明确类别：落库后再改目标头行并留记录。 */
    private record ClassChange(String targetId, String classCode, String classSourceId, OffsetDateTime observedAt, String configVersion) { }

    /** 一个目标本帧可能要发的事件：要等最新状态落库之后再组装摘要。 */
    private record PendingEvents(TargetFrameResult frame, FusedState fused, Degradation degradation, DegradationRow previousDegradation, SelectionRow previousSelection,
            boolean manualOverride, FusedTrackRow track, LatestState written, OffsetDateTime observedAt) { }

    @Override
    public void write(TargetFrameResult frame) {
        writeAll(List.of(frame));
    }

    /**
     * 同一目标在一批里出现多次（只有调用方自己拼批才会，管线一帧里每个目标只有一份结果）时退回逐个写，
     * 后一份要读到前一份写过的状态。
     */
    @Override
    public void writeAll(List<TargetFrameResult> frames) {
        if (frames == null || frames.isEmpty()) return;
        Set<String> distinct = new LinkedHashSet<>();
        for (TargetFrameResult frame : frames) distinct.add(frame.targetId());
        if (distinct.size() != frames.size()) {
            for (TargetFrameResult frame : frames) writeBatch(List.of(frame));
            return;
        }
        writeBatch(frames);
    }

    private void writeBatch(List<TargetFrameResult> frames) {
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        List<String> ids = frames.stream().map(TargetFrameResult::targetId).toList();
        Map<String, FusedTrackRow> openTracks = tracks.findOpenTracks(ids);
        Batch batch = new Batch(states.findSelections(ids), states.findDegradations(ids), tracks.latestStatesObservedAt(ids), openTracks,
                tracks.nextPointSeqs(openTracks.values().stream().map(FusedTrackRow::trackId).toList()), identities.objectTypes(ids));
        Map<String, FusionParams> paramsByKey = new HashMap<>();
        for (TargetFrameResult frame : frames) {
            FusionParams params = paramsByKey.computeIfAbsent(frame.configVersion() + "|" + frame.domain().sourceMode(), key -> params(frame));
            writeFrame(frame, params, now, batch);
        }
        tracks.insertPoints(batch.points);
        // 关闭融合轨迹放在本帧的点写完之后：与逐个写时"先写点、再关轨迹"的顺序一致。
        for (int i = 0; i < batch.endedTracks.size(); i++) tracks.endTrack(batch.endedTracks.get(i), batch.endedAt.get(i));
        tracks.upsertLatestStates(batch.latest);
        states.upsertSelections(batch.selectionWrites);
        states.upsertDegradations(batch.degradationWrites);
        for (ClassChange change : batch.classChanges) reviseClass(change, now);
        emitEvents(batch.events);
    }

    private FusionParams params(TargetFrameResult frame) {
        FusionParams params = config.params(frame.configVersion());
        if ("replay".equals(frame.domain().sourceMode())) params=simulatorParameters.simulationParameters(params);
        return params;
    }

    private void writeFrame(TargetFrameResult frame, FusionParams params, OffsetDateTime now, Batch batch) {
        OffsetDateTime observedAt = frame.observedAt().atOffset(ZoneOffset.UTC);
        SelectionRow previousSelection = batch.selections.get(frame.targetId());
        DegradationRow previousDegradation = batch.degradations.get(frame.targetId());
        List<SourceEstimate> estimates = frame.estimates() == null ? List.of() : frame.estimates();
        FusedState fused = fuser.fuse(estimates, params, previousSelection == null ? null : previousSelection.positionSourceId());
        Degradation degradation = degradations.evaluate(estimates, frame.missFrames(), previousDegradation == null ? null : previousDegradation.deficit().doubleValue(), params);

        // 迟到帧：观测时刻早于已落库的最新状态时，只补融合层历史点，不回退 latest_state / 属性优选 / 降级——
        // 页面与告警看到的“当前状态”必须单调向前，否则乱序到达会让目标在地图上倒退。
        OffsetDateTime latestObserved = batch.latestObserved.get(frame.targetId());
        // 无源帧（这一帧没看到它）没有新的观测，不算迟到帧：观测时刻不早于已落库的最新状态，判短失、判终止照常写进融合层
        // （关融合轨迹、发事件），也不把最新状态的观测时刻往回拨。
        if (estimates.isEmpty() && latestObserved != null && observedAt.isBefore(latestObserved)) observedAt = latestObserved;
        boolean late = latestObserved != null && observedAt.isBefore(latestObserved);

        FusedTrackRow track = openTrack(frame, observedAt, now, params, batch);
        writePoint(frame, track, fused, degradation, observedAt, now, params, batch);
        if (late) return;
        // 关闭融合轨迹放在迟到判定之后：迟到的 TERMINATED 帧若用更早的 observed_at 关掉当前轨迹，
        // 下一帧就会另开一条 fused:<target>:<ms>，融合层被切成碎片（审查建议）。
        if (frame.status() == TrackStatus.TERMINATED) { batch.endedTracks.add(track.trackId()); batch.endedAt.add(observedAt); }

        boolean manualOverride = previousSelection != null && previousSelection.manualClassOverride();
        LatestState written = writeLatestState(frame, track, fused, degradation, manualOverride, previousSelection, observedAt, now, batch);
        writeSelection(frame, fused, manualOverride, previousSelection, observedAt, now, params, batch);
        writeDegradation(frame, degradation, previousDegradation, observedAt, now, batch);
        batch.events.add(new PendingEvents(frame, fused, degradation, previousDegradation, previousSelection, manualOverride, track, written, observedAt));
    }

    private FusedTrackRow openTrack(TargetFrameResult frame, OffsetDateTime observedAt, OffsetDateTime now, FusionParams params, Batch batch) {
        FusedTrackRow open = batch.openTracks.get(frame.targetId());
        if (open != null) return open;
        String trackId = UUID.randomUUID().toString();
        String external = "fused:" + frame.targetId() + ":" + observedAt.toInstant().toEpochMilli();
        tracks.insertTrack(trackId, frame.targetId(), external, observedAt, params.configVersion(), now);
        FusedTrackRow created = new FusedTrackRow(trackId, frame.targetId(), external, observedAt, null, params.configVersion());
        batch.openTracks.put(frame.targetId(), created);
        return created;
    }

    /** 有位置的实测/桥接帧写 MEAS/BRIDGE；无源帧在 pred_max_frames 内写 PRED（位置保留最后可信点，不外推）。 */
    private void writePoint(TargetFrameResult frame, FusedTrackRow track, FusedState fused, Degradation degradation, OffsetDateTime observedAt, OffsetDateTime now, FusionParams params,
            Batch batch) {
        String contributing = write(fused.contributions().stream().map(c -> Map.of("source_id", c.sourceId(), "observation_id", c.observationId() == null ? "" : c.observationId(), "weight", round(c.weight(), CONF_SCALE))).toList());
        if (fused.longitude() != null) {
            PointKind kind = frame.estimates().stream().anyMatch(e -> e.kind() == PointKind.MEAS) ? PointKind.MEAS : PointKind.BRIDGE;
            String observationId = frame.estimates().stream().filter(e -> e.sourceId().equals(fused.selection().positionSourceId())).map(SourceEstimate::observationId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            long seq = batch.nextSeq.getOrDefault(track.trackId(), 0L);
            batch.nextSeq.put(track.trackId(), seq + 1);
            batch.points.add(new FusedPoint(UUID.randomUUID().toString(), track.trackId(), seq, observedAt, now, fused.longitude(), fused.latitude(),
                    decimal(fused.altitudeAmslM(), METRIC_SCALE), decimal(fused.heightAglM(), METRIC_SCALE), now, kind.name(), observationId, decimal(fused.accuracyM(), METRIC_SCALE),
                    contributing, fused.selection().positionSourceId(), fused.sourceSwitched(), degradation.level().name()));
            return;
        }
        if (!frame.estimates().isEmpty() || frame.missFrames() > params.integer("filter", "pred_max_frames")) return;
        // PRED 只出现在失联目标上，量小，照旧逐条读写：位置取库里最后一个点。
        LastPoint last = tracks.lastPoint(track.trackId());
        if (last == null || last.locationText() == null) return;
        double[] lonLat = parse(last.locationText());
        if (lonLat == null) return;
        tracks.insertPoint(new FusedPoint(UUID.randomUUID().toString(), track.trackId(), last.pointSeq() + 1, observedAt, now, lonLat[0], lonLat[1], last.altitudeAmslM(), last.heightAglM(), now,
                PointKind.PRED.name(), null, last.positionAccuracyM(), "[]", null, false, degradation.level().name()));
    }

    /** 返回实际写入 target_latest_state 的记录：事件摘要必须与页面/告警看到的最新状态是同一份数据。 */
    private LatestState writeLatestState(TargetFrameResult frame, FusedTrackRow track, FusedState fused, Degradation degradation, boolean manualOverride, SelectionRow previous,
            OffsetDateTime observedAt, OffsetDateTime now, Batch batch) {
        // 人工修订过类别的目标：置信度固定为 1（人工结论），来源类别不再覆盖。
        BigDecimal classConfidence = manualOverride ? BigDecimal.ONE : decimal(fused.classConfidence(), CONF_SCALE);
        Set<UnknownField> unknown = new LinkedHashSet<>();
        fused.unknownFields().stream().filter(u -> !(manualOverride && "classification_confidence".equals(u.field()))).forEach(unknown::add);
        unknown.addAll(degradation.unknownFields());
        // 本帧的观测时刻来自报文时刻不可信的来源（ZT-20）：位置照常更新，但如实标出这个时刻靠不住，页面不能当实时数据显示。
        if (timeUntrusted(frame)) unknown.add(new UnknownField("observed_at", "TIME_UNTRUSTED"));
        // 本帧有没有身份主源，决定它有没有资格改写飞手位置两列（决策 8.5-27）。
        boolean pilotDecided = fused.selection() != null && fused.selection().identitySourceId() != null;
        double[] pilot = pilotDecided ? pilotOfIdentitySource(frame, fused) : null;
        if (fused.longitude() == null) {
            // 无位置帧：位置保留本帧所在融合轨迹的最后可信点，只刷新其它字段；这里不写 (0,0)。
            // 必须用本帧的 track 而不是再查"开放轨迹"：TERMINATED 帧在此之前已把该轨迹 ended_at 关闭，
            // 再查开放轨迹会得到 null，最新状态就会丢掉位置（真实 PostgreSQL 验收时暴露）。
            LastPoint keep = tracks.lastPoint(track.trackId());
            double[] lonLat = keep == null || keep.locationText() == null ? null : parse(keep.locationText());
            if (lonLat != null) unknown.removeIf(u -> "location".equals(u.field()));
            LatestState kept = new LatestState(frame.targetId(), lonLat == null ? null : lonLat[0], lonLat == null ? null : lonLat[1], decimal(fused.altitudeAmslM(), METRIC_SCALE),
                    decimal(fused.heightAglM(), METRIC_SCALE), decimal(fused.speedMps(), SPEED_SCALE), decimal(fused.headingDeg(), METRIC_SCALE), classConfidence,
                    decimal(degradation.fusionConfidence(), CONF_SCALE), observedAt, now, write(unknownList(unknown)), now,
                    pilot == null ? null : pilot[0], pilot == null ? null : pilot[1], pilot == null ? null : observedAt, pilotDecided);
            batch.latest.add(kept);
            return kept;
        }
        LatestState state = new LatestState(frame.targetId(), fused.longitude(), fused.latitude(), decimal(fused.altitudeAmslM(), METRIC_SCALE), decimal(fused.heightAglM(), METRIC_SCALE),
                decimal(fused.speedMps(), SPEED_SCALE), decimal(fused.headingDeg(), METRIC_SCALE), classConfidence, decimal(degradation.fusionConfidence(), CONF_SCALE),
                observedAt, now, write(unknownList(unknown)), now,
                pilot == null ? null : pilot[0], pilot == null ? null : pilot[1], pilot == null ? null : observedAt, pilotDecided);
        batch.latest.add(state);
        return state;
    }

    /** 决定本帧观测时刻的那些来源估计（观测时刻等于帧时刻）里，有没有被管线标为报文时刻不可信的。 */
    static boolean timeUntrusted(TargetFrameResult frame) {
        if (frame.estimates() == null || frame.observedAt() == null) return false;
        return frame.estimates().stream().anyMatch(e -> frame.observedAt().equals(e.observedAt()) && e.quality() != null
                && Boolean.TRUE.equals(e.quality().get(QUALITY_TIME_UNTRUSTED)));
    }

    /**
     * 飞手位置只取身份主源（决策 8.5-5）：C02-6 要的是"这个目标的飞手在哪"，
     * 只有身份类来源（TDOA/DCD/RID）带这个字段。取任意来源会把别的传感器的站址当成飞手位置；
     * 身份主源缺失或它没给飞手位置就返回空，不回退到其它来源，也不补 (0,0)。
     */
    private static double[] pilotOfIdentitySource(TargetFrameResult frame, FusedState fused) {
        String identitySourceId = fused.selection().identitySourceId();
        return frame.estimates().stream()
                .filter(e -> identitySourceId.equals(e.sourceId()) && e.pilotLongitude() != null && e.pilotLatitude() != null)
                .findFirst()
                .map(e -> new double[] { e.pilotLongitude(), e.pilotLatitude() })
                .orElse(null);
    }

    /**
     * 决策 8.5-27：整帧没有来源时不改写属性优选。"这一帧没有任何来源"不是"从来不知道这些属性来自哪一路"，
     * 后者会把已有归属抹成 NULL，页面读起来像从未选过源。中断这件事由 target_degradation 那行如实记录。
     */
    private void writeSelection(TargetFrameResult frame, FusedState fused, boolean manualOverride, SelectionRow previous, OffsetDateTime observedAt, OffsetDateTime now, FusionParams params,
            Batch batch) {
        if (frame.estimates() == null || frame.estimates().isEmpty()) return;
        String classCode = manualOverride ? previous.classCode() : fused.classCode();
        BigDecimal classConfidence = manualOverride ? previous.classConfidence() : decimal(fused.classConfidence(), CONF_SCALE);
        String classSource = manualOverride ? null : fused.selection().classSourceId();
        batch.selectionWrites.add(new SelectionWrite(frame.targetId(), fused.selection().positionSourceId(), classSource, fused.selection().identitySourceId(), fused.selection().motionSourceId(),
                classCode, classConfidence, fused.identityClue(), observedAt, params.configVersion(), manualOverride, now));
        // ZT-04：融合类别是一个明确类别、且与目标头行不同（识别中→无人机、无人机→鸟），目标头行要跟着改。
        // 没有类别证据或来源互相矛盾时融合类别为空，那不是"改判"，保留已有类别；人工修订过的目标不动。
        if (!manualOverride && definiteClass(fused.classCode()) && !fused.classCode().equals(batch.headerClasses.get(frame.targetId()))) {
            batch.classChanges.add(new ClassChange(frame.targetId(), fused.classCode(), classSource, observedAt, params.configVersion()));
            batch.headerClasses.put(frame.targetId(), fused.classCode());
        }
    }

    private static boolean definiteClass(String classCode) {
        return classCode != null && !classCode.isBlank() && !"UNKNOWN".equals(classCode);
    }

    /**
     * 系统改判类别（ZT-04）：目标头行改成新类别（不递增 version，决策 8-6），在血缘里留一条 SYSTEM 的 CLASS_REVISION
     * （何时、由什么改成什么、依据哪一路来源），并发 CLASS_REVISED 事件。已有的告警、通知、研判一律不动：
     * 它们记录的是当时的事实，页面通过这条改判记录写明"类别已变化"。头行本来就是这个类别时（例如建目标时已是无人机）不留记录。
     */
    private void reviseClass(ClassChange change, OffsetDateTime now) {
        ClassRevision revision = identities.reviseSystemClass(change.targetId(), change.classCode(), now.toInstant());
        if (revision == null) return;
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("previous_class_code", revision.previousClassCode() == null ? "" : revision.previousClassCode());
        basis.put("new_class_code", revision.newClassCode());
        basis.put("reason", "FUSED_CLASS_CHANGED");
        if (change.classSourceId() != null) basis.put("class_source_id", change.classSourceId());
        String lineageId = identities.insertLineage("CLASS_REVISION", change.observedAt().toInstant(), change.targetId(), null, write(List.of(change.targetId())),
                write(List.of()), write(basis), FusionPipeline.ALGO_VERSION, change.configVersion(), write(Map.of()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("class_code", revision.newClassCode());
        payload.put("previous_class_code", revision.previousClassCode() == null ? "" : revision.previousClassCode());
        payload.put("lineage_id", lineageId);
        payload.put("operator_kind", "SYSTEM");
        events.emit(FusionEventEmitter.CLASS_REVISED, change.targetId(), change.observedAt(), payload);
    }

    private void writeDegradation(TargetFrameResult frame, Degradation degradation, DegradationRow previous, OffsetDateTime observedAt, OffsetDateTime now, Batch batch) {
        boolean sameLevel = previous != null && previous.level().equals(degradation.level().name()) && previous.determined() == degradation.determined();
        OffsetDateTime since = sameLevel ? previous.since() : observedAt;
        batch.degradationWrites.add(new DegradationWrite(frame.targetId(), degradation.level().name(), write(degradation.availableSourceIds()), round(degradation.deficit(), CONF_SCALE),
                degradation.determined(), since, now));
    }

    /**
     * STATUS_STABLE（本轨迹段首次）与 UNDETERMINED（可判定 → 不可判定的转入）带同一份最新状态摘要。
     * 摘要在 {@link #summary} 里按需组装：只有真的要发事件时才去查目标编号、告警与风险，不在每帧路径上多查三张表。
     * "本轨迹段是否已发过 STABLE"对本批所有 STABLE 目标一次问完（ZT-06）；事件在最新状态落库之后才组装，摘要读到的是本帧写入的数据。
     */
    private void emitEvents(List<PendingEvents> pending) {
        Map<String, OffsetDateTime> stableSince = new LinkedHashMap<>();
        for (PendingEvents p : pending) {
            if (p.frame().status() == TrackStatus.STABLE) stableSince.put(p.frame().targetId(), p.track().startedAt() == null ? p.observedAt() : p.track().startedAt());
        }
        Set<String> stableEmitted = events.stableAlreadyEmitted(stableSince);
        for (PendingEvents p : pending) {
            boolean stableFirstTime = p.frame().status() == TrackStatus.STABLE && !stableEmitted.contains(p.frame().targetId());
            boolean wasDetermined = p.previousDegradation() == null || p.previousDegradation().determined();
            boolean becameUndetermined = !p.degradation().determined() && wasDetermined;
            if (!stableFirstTime && !becameUndetermined) continue;
            Map<String, Object> payload = summary(p.frame(), p.fused(), p.degradation(), p.previousSelection(), p.manualOverride(), p.written(), p.observedAt());
            if (stableFirstTime) events.emit(FusionEventEmitter.STATUS_STABLE, p.frame().targetId(), p.observedAt(), payload);
            if (becameUndetermined) events.emit(FusionEventEmitter.UNDETERMINED, p.frame().targetId(), p.observedAt(), payload);
        }
    }

    /**
     * 契约 §4/§8 的摘要 payload（决策 10-1）。取不到的键一律不出现，不写 null——A 端按"键在不在"区分未知与零值。
     * <ul>
     *   <li>latest_state 与刚写入 target_latest_state 的记录同源；飞手位置在本帧未表态时取库里保留的值（决策 8.5-27）。</li>
     *   <li>altitude_raw 是位置主源（其次身份主源）的 quality.altitude_raw 原值，高度基准客户未答复，固定标 UNCONFIRMED；
     *       融合后的 altitude_amsl_m 不进摘要，A 的光电跟踪需要的是设备原值。</li>
     *   <li>class_code 与属性优选一致：人工修订优先，其次本帧融合类别，无源帧沿用已保留的归属；三者都没有时回退到
     *       target.object_type_code（决策 10-11）——demo-v1 权重下只有 TDOA/AOA/DCD/RID 的目标永远选不出类别来源，
     *       A 的光电跟踪至少要拿到首次观测的类别。目标行也没有时不出键。</li>
     *   <li>alarm_active / max_risk_severity 只读 uav_event / flight_risk 现状，不改任何状态。</li>
     * </ul>
     */
    private Map<String, Object> summary(TargetFrameResult frame, FusedState fused, Degradation degradation, SelectionRow previousSelection, boolean manualOverride,
            LatestState written, OffsetDateTime observedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", frame.status().name());
        payload.put("degradation_level", degradation.level().name());
        payload.put("determined", degradation.determined());
        putIfPresent(payload, "target_no", eventContext.targetNo(frame.targetId()));
        String classCode = manualOverride ? previousSelection.classCode() : fused.classCode();
        if (classCode == null && previousSelection != null) classCode = previousSelection.classCode();
        if (classCode == null) classCode = eventContext.objectTypeCode(frame.targetId());
        putIfPresent(payload, "class_code", classCode);

        Map<String, Object> latest = new LinkedHashMap<>();
        putIfPresent(latest, "longitude", written.longitude());
        putIfPresent(latest, "latitude", written.latitude());
        putIfPresent(latest, "altitude_raw", altitudeRaw(frame, fused));
        latest.put("altitude_datum", ALTITUDE_DATUM_UNCONFIRMED);
        putIfPresent(latest, "speed_mps", written.speedMps());
        putIfPresent(latest, "heading_deg", written.headingDeg());
        latest.put("observed_at", observedAt.toInstant().toEpochMilli());
        double[] pilot = written.pilotDecided()
                ? (written.pilotLongitude() == null || written.pilotLatitude() == null ? null : new double[] { written.pilotLongitude(), written.pilotLatitude() })
                : eventContext.retainedPilotLocation(frame.targetId());
        if (pilot != null) {
            Map<String, Object> pilotLocation = new LinkedHashMap<>();
            pilotLocation.put("longitude", pilot[0]);
            pilotLocation.put("latitude", pilot[1]);
            latest.put("pilot_location", pilotLocation);
        }
        payload.put("latest_state", latest);

        payload.put("alarm_active", eventContext.alarmActive(frame.targetId()));
        putIfPresent(payload, "max_risk_severity", eventContext.maxOpenRiskSeverity(frame.targetId()));
        return payload;
    }

    /** 位置主源优先、其次身份主源的 quality.altitude_raw；两者都没给就返回 null（不出键），不拿融合高度顶替。 */
    private static Number altitudeRaw(TargetFrameResult frame, FusedState fused) {
        if (fused.selection() == null || frame.estimates() == null) return null;
        for (String sourceId : new String[] { fused.selection().positionSourceId(), fused.selection().identitySourceId() }) {
            if (sourceId == null) continue;
            for (SourceEstimate estimate : frame.estimates()) {
                if (!sourceId.equals(estimate.sourceId()) || estimate.quality() == null) continue;
                Object raw = estimate.quality().get("altitude_raw");
                if (raw instanceof Number number) return number;
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static List<Map<String, String>> unknownList(Set<UnknownField> unknown) {
        List<Map<String, String>> out = new ArrayList<>();
        for (UnknownField u : unknown) out.add(Map.of("field", u.field(), "reason_code", u.reasonCode()));
        return out;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("fusion payload unserializable", ex); }
    }

    private static BigDecimal decimal(Double value, int scale) {
        return value == null || value.isNaN() ? null : round(value, scale);
    }
    private static BigDecimal round(double value, int scale) { return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP); }

    /** 解析 H2/PG 回读的 POINT 文本（'SRID=4326;POINT (lon lat)' 或 WKB 十六进制时返回 null）。 */
    static double[] parse(String text) {
        int open = text.indexOf('('), close = text.indexOf(')');
        if (open < 0 || close < open) return null;
        String[] parts = text.substring(open + 1, close).trim().split("\\s+");
        if (parts.length != 2) return null;
        try { return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) }; }
        catch (NumberFormatException ex) { return null; }
    }
}
