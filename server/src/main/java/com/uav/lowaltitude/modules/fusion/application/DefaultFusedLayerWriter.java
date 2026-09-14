package com.uav.lowaltitude.modules.fusion.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository.SelectionRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionEventContextRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.FusedPoint;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.FusedTrackRow;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.LastPoint;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.LatestState;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 融合层写入（E1 在管线第 ⑥ 步、在它自己的一帧事务内调用；这里不开事务）。
 * 一帧一个目标：属性优选 + 加权融合 → 降级评估 → FUSED 层 track/track_point → target_latest_state →
 * target_attribute_selection / target_degradation → fusion_event。不递增 target.version（决策 8-6）。
 * fusion_event 的 payload 是最新状态摘要（契约 §4/§8，决策 10-1）：给 A 的光电跟踪触发用，不放原始观测。
 */
@Component
public class DefaultFusedLayerWriter implements FusedLayerWriter {
    private static final int COORD_SCALE = 7, METRIC_SCALE = 2, CONF_SCALE = 5, SPEED_SCALE = 3;
    /** 决策 10-1：高度基准（海拔 vs 椭球高）客户未答复前，摘要里的 altitude_raw 固定标 UNCONFIRMED。 */
    private static final String ALTITUDE_DATUM_UNCONFIRMED = "UNCONFIRMED";
    private final WeightedFuser fuser = new WeightedFuser();
    private final DegradationEvaluator degradations = new DegradationEvaluator();
    private final FusionConfigService config;
    private final FusedTrackRepository tracks;
    private final DegradationRepository states;
    private final FusionEventEmitter events;
    private final FusionEventContextRepository eventContext;
    private final AppClock clock;
    private final ObjectMapper json;

    public DefaultFusedLayerWriter(FusionConfigService config, FusedTrackRepository tracks, DegradationRepository states, FusionEventEmitter events,
            FusionEventContextRepository eventContext, AppClock clock, ObjectMapper json) {
        this.config = config; this.tracks = tracks; this.states = states; this.events = events; this.eventContext = eventContext; this.clock = clock; this.json = json;
    }

    @Override
    public void write(TargetFrameResult frame) {
        FusionParams params = config.params(frame.configVersion());
        OffsetDateTime observedAt = frame.observedAt().atOffset(ZoneOffset.UTC);
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        SelectionRow previousSelection = states.findSelection(frame.targetId());
        DegradationRow previousDegradation = states.findDegradation(frame.targetId());
        List<SourceEstimate> estimates = frame.estimates() == null ? List.of() : frame.estimates();
        FusedState fused = fuser.fuse(estimates, params, previousSelection == null ? null : previousSelection.positionSourceId());
        Degradation degradation = degradations.evaluate(estimates, frame.missFrames(), previousDegradation == null ? null : previousDegradation.deficit().doubleValue(), params);

        // 迟到帧：观测时刻早于已落库的最新状态时，只补融合层历史点，不回退 latest_state / 属性优选 / 降级——
        // 页面与告警看到的“当前状态”必须单调向前，否则乱序到达会让目标在地图上倒退。
        OffsetDateTime latestObserved = tracks.latestStateObservedAt(frame.targetId());
        boolean late = latestObserved != null && observedAt.isBefore(latestObserved);

        FusedTrackRow track = openTrack(frame, observedAt, now, params);
        writePoint(frame, track, fused, degradation, observedAt, now, params);
        if (late) return;
        // 关闭融合轨迹放在迟到判定之后：迟到的 TERMINATED 帧若用更早的 observed_at 关掉当前轨迹，
        // 下一帧就会另开一条 fused:<target>:<ms>，融合层被切成碎片（审查建议）。
        if (frame.status() == TrackStatus.TERMINATED) tracks.endTrack(track.trackId(), observedAt);

        boolean manualOverride = previousSelection != null && previousSelection.manualClassOverride();
        LatestState written = writeLatestState(frame, track, fused, degradation, manualOverride, previousSelection, observedAt, now);
        writeSelection(frame, fused, manualOverride, previousSelection, observedAt, now, params);
        writeDegradation(frame, degradation, previousDegradation, observedAt, now);
        emitEvents(frame, fused, degradation, previousDegradation, previousSelection, manualOverride, track, written, observedAt);
    }

    private FusedTrackRow openTrack(TargetFrameResult frame, OffsetDateTime observedAt, OffsetDateTime now, FusionParams params) {
        FusedTrackRow open = tracks.findOpenTrack(frame.targetId());
        if (open != null) return open;
        String trackId = UUID.randomUUID().toString();
        String external = "fused:" + frame.targetId() + ":" + observedAt.toInstant().toEpochMilli();
        tracks.insertTrack(trackId, frame.targetId(), external, observedAt, params.configVersion(), now);
        return new FusedTrackRow(trackId, frame.targetId(), external, observedAt, null, params.configVersion());
    }

    /** 有位置的实测/桥接帧写 MEAS/BRIDGE；无源帧在 pred_max_frames 内写 PRED（位置保留最后可信点，不外推）。 */
    private void writePoint(TargetFrameResult frame, FusedTrackRow track, FusedState fused, Degradation degradation, OffsetDateTime observedAt, OffsetDateTime now, FusionParams params) {
        String contributing = write(fused.contributions().stream().map(c -> Map.of("source_id", c.sourceId(), "observation_id", c.observationId() == null ? "" : c.observationId(), "weight", round(c.weight(), CONF_SCALE))).toList());
        if (fused.longitude() != null) {
            PointKind kind = frame.estimates().stream().anyMatch(e -> e.kind() == PointKind.MEAS) ? PointKind.MEAS : PointKind.BRIDGE;
            String observationId = frame.estimates().stream().filter(e -> e.sourceId().equals(fused.selection().positionSourceId())).map(SourceEstimate::observationId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            tracks.insertPoint(new FusedPoint(UUID.randomUUID().toString(), track.trackId(), tracks.nextPointSeq(track.trackId()), observedAt, now, fused.longitude(), fused.latitude(),
                    decimal(fused.altitudeAmslM(), METRIC_SCALE), decimal(fused.heightAglM(), METRIC_SCALE), now, kind.name(), observationId, decimal(fused.accuracyM(), METRIC_SCALE),
                    contributing, fused.selection().positionSourceId(), fused.sourceSwitched(), degradation.level().name()));
            return;
        }
        if (!frame.estimates().isEmpty() || frame.missFrames() > params.integer("filter", "pred_max_frames")) return;
        LastPoint last = tracks.lastPoint(track.trackId());
        if (last == null || last.locationText() == null) return;
        double[] lonLat = parse(last.locationText());
        if (lonLat == null) return;
        tracks.insertPoint(new FusedPoint(UUID.randomUUID().toString(), track.trackId(), last.pointSeq() + 1, observedAt, now, lonLat[0], lonLat[1], last.altitudeAmslM(), last.heightAglM(), now,
                PointKind.PRED.name(), null, last.positionAccuracyM(), "[]", null, false, degradation.level().name()));
    }

    /** 返回实际写入 target_latest_state 的记录：事件摘要必须与页面/告警看到的最新状态是同一份数据。 */
    private LatestState writeLatestState(TargetFrameResult frame, FusedTrackRow track, FusedState fused, Degradation degradation, boolean manualOverride, SelectionRow previous, OffsetDateTime observedAt, OffsetDateTime now) {
        // 人工修订过类别的目标：置信度固定为 1（人工结论），来源类别不再覆盖。
        BigDecimal classConfidence = manualOverride ? BigDecimal.ONE : decimal(fused.classConfidence(), CONF_SCALE);
        Set<UnknownField> unknown = new LinkedHashSet<>();
        fused.unknownFields().stream().filter(u -> !(manualOverride && "classification_confidence".equals(u.field()))).forEach(unknown::add);
        unknown.addAll(degradation.unknownFields());
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
            tracks.upsertLatestState(kept);
            return kept;
        }
        LatestState state = new LatestState(frame.targetId(), fused.longitude(), fused.latitude(), decimal(fused.altitudeAmslM(), METRIC_SCALE), decimal(fused.heightAglM(), METRIC_SCALE),
                decimal(fused.speedMps(), SPEED_SCALE), decimal(fused.headingDeg(), METRIC_SCALE), classConfidence, decimal(degradation.fusionConfidence(), CONF_SCALE),
                observedAt, now, write(unknownList(unknown)), now,
                pilot == null ? null : pilot[0], pilot == null ? null : pilot[1], pilot == null ? null : observedAt, pilotDecided);
        tracks.upsertLatestState(state);
        return state;
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
    private void writeSelection(TargetFrameResult frame, FusedState fused, boolean manualOverride, SelectionRow previous, OffsetDateTime observedAt, OffsetDateTime now, FusionParams params) {
        if (frame.estimates() == null || frame.estimates().isEmpty()) return;
        String classCode = manualOverride ? previous.classCode() : fused.classCode();
        BigDecimal classConfidence = manualOverride ? previous.classConfidence() : decimal(fused.classConfidence(), CONF_SCALE);
        String classSource = manualOverride ? null : fused.selection().classSourceId();
        states.upsertSelection(frame.targetId(), fused.selection().positionSourceId(), classSource, fused.selection().identitySourceId(), fused.selection().motionSourceId(),
                classCode, classConfidence, fused.identityClue(), observedAt, params.configVersion(), manualOverride, now);
    }

    private void writeDegradation(TargetFrameResult frame, Degradation degradation, DegradationRow previous, OffsetDateTime observedAt, OffsetDateTime now) {
        boolean sameLevel = previous != null && previous.level().equals(degradation.level().name()) && previous.determined() == degradation.determined();
        OffsetDateTime since = sameLevel ? previous.since() : observedAt;
        states.upsertDegradation(frame.targetId(), degradation.level().name(), write(degradation.availableSourceIds()), round(degradation.deficit(), CONF_SCALE), degradation.determined(), since, now);
    }

    /**
     * STATUS_STABLE（本轨迹段首次）与 UNDETERMINED（可判定 → 不可判定的转入）带同一份最新状态摘要。
     * 摘要在 {@link #summary} 里按需组装：只有真的要发事件时才去查目标编号、告警与风险，不在每帧路径上多查三张表。
     */
    private void emitEvents(TargetFrameResult frame, FusedState fused, Degradation degradation, DegradationRow previousDegradation, SelectionRow previousSelection,
            boolean manualOverride, FusedTrackRow track, LatestState written, OffsetDateTime observedAt) {
        boolean stableFirstTime = frame.status() == TrackStatus.STABLE && !events.stableAlreadyEmitted(frame.targetId(), observedAt, track.startedAt());
        boolean wasDetermined = previousDegradation == null || previousDegradation.determined();
        boolean becameUndetermined = !degradation.determined() && wasDetermined;
        if (!stableFirstTime && !becameUndetermined) return;
        Map<String, Object> payload = summary(frame, fused, degradation, previousSelection, manualOverride, written, observedAt);
        if (stableFirstTime) events.emit(FusionEventEmitter.STATUS_STABLE, frame.targetId(), observedAt, payload);
        if (becameUndetermined) events.emit(FusionEventEmitter.UNDETERMINED, frame.targetId(), observedAt, payload);
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
