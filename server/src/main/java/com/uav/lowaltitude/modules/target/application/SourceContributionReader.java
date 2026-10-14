package com.uav.lowaltitude.modules.target.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigLoader;
import com.uav.lowaltitude.modules.fusion.application.EoFusionTaskContextPort;
import com.uav.lowaltitude.modules.fusion.domain.WeightedFuser;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.LinkRow;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TargetSourceLinkDto;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.SourceLinkRow;
import com.uav.lowaltitude.platform.time.AppClock;

/** Read accepted per-link snapshots; device heartbeats and unaccepted raw evidence cannot renew these facts. */
@Component
public class SourceContributionReader {
    private final RawTrackRepository tracks;
    private final ObjectMapper json;
    private final FusionConfigService config;
    private final AppClock clock;
    private final EoFusionTaskContextPort tasks;
    private final FusionConfigLoader configLoader;
    public SourceContributionReader(RawTrackRepository tracks, ObjectMapper json, FusionConfigService config, AppClock clock, EoFusionTaskContextPort tasks, FusionConfigLoader configLoader) {
        this.tracks = tracks; this.json = json; this.config = config; this.clock = clock; this.tasks = tasks;
        this.configLoader = configLoader;
    }
    public List<TargetSourceLinkDto> read(String targetId, List<SourceLinkRow> links) {
        if (links.isEmpty()) return List.of();
        var raw = tracks.findOpenRawTracks(links.stream().map(l -> new LinkRow(l.linkId(), targetId, l.sourceId(), l.deviceId(), l.sourceSessionKey(), l.externalTargetId())).toList());
        var params = configLoader.simulationParameters(config.params(null));
        Instant now = clock.now();
        long window = params.integer("identity", "short_lost_after_ms");
        Map<String, SourceEstimate> saved = new LinkedHashMap<>();
        for (var link : links) {
            var track = raw.get(link.linkId());
            if (track == null || track.filterStateJson() == null) continue;
            try {
                var state = json.readTree(track.filterStateJson());
                if (state.isTextual()) state = json.readTree(state.textValue());
                var node = state.get("source_estimate");
                if (node == null || node.isNull()) continue;
                var estimate = json.treeToValue(node, SourceEstimate.class);
                if (link.linkId().equals(estimate.linkId()) && link.sourceId().equals(estimate.sourceId())) saved.put(link.linkId(), estimate);
            } catch (Exception ignored) { /* Older snapshots without restorable facts remain unknown. */ }
        }
        Map<String, String> rejected = new LinkedHashMap<>();
        for (var e : saved.values()) {
            if (!"EO".equals(e.sourceType())) continue;
            var q = e.quality();
            if (q == null || e.observedAt() == null) { rejected.put(e.linkId(), "缺少可验证的当前跟踪任务"); continue; }
            var context = tasks.lookup(text(q, "eo_task_id"), e.sourceId(), text(q,"eo_device_id"), text(q,"eo_source_mode"),
                    text(q,"eo_owner_org_id"), text(q,"eo_district_id"), e.observedAt().toEpochMilli(), now.toEpochMilli());
            if (context == null || !context.accepted() || !targetId.equals(context.targetId())) rejected.put(e.linkId(), "观测关联待确认：当前跟踪任务已结束、变化或不可用");
        }
        // Pipeline combines at most one latest accepted estimate per source.
        Map<String, SourceEstimate> bySource = new LinkedHashMap<>();
        for (var e : saved.values()) if (current(e, now, window) && !rejected.containsKey(e.linkId())) {
            var before = bySource.get(e.sourceId());
            if (before == null || e.observedAt().isAfter(before.observedAt())) bySource.put(e.sourceId(), e);
        }
        List<SourceEstimate> current = List.copyOf(bySource.values());
        var fused = current.isEmpty() ? null : new WeightedFuser().fuse(current, params, null);
        List<TargetSourceLinkDto> out = new ArrayList<>();
        for (var link : links) {
            var e = saved.get(link.linkId());
            String status = e == null ? (raw.containsKey(link.linkId()) ? "UNKNOWN" : "HISTORICAL")
                    : e.observedAt() == null || e.observedAt().isAfter(now) ? "UNAVAILABLE" : current(e, now, window) ? "CURRENT" : "EXPIRED";
            if (rejected.containsKey(link.linkId()) || e != null && e.quality() != null && e.quality().containsKey("eo_fusion_exclusion")) status = "UNAVAILABLE";
            if ("CURRENT".equals(status) && bySource.get(e.sourceId()) != e) status = "HISTORICAL";
            List<String> attrs = new ArrayList<>();
            if ("CURRENT".equals(status) && fused != null) {
                if (fused.contributions().stream().anyMatch(c -> e.sourceId().equals(c.sourceId()) && java.util.Objects.equals(c.observationId(), e.observationId()) && c.weight() > 0)) attrs.add("position");
                var selection = fused.selection();
                if (e.sourceId().equals(selection.classSourceId())) attrs.add("class");
                if (e.sourceId().equals(selection.identitySourceId())) attrs.add("identity");
                if (e.sourceId().equals(selection.motionSourceId()) && (e.speedMps() != null || e.headingDeg() != null)) attrs.add("motion");
                if (attrs.isEmpty()) status = "UNAVAILABLE";
            }
            String reason = switch (status) {
                case "EXPIRED" -> "最近有效观测已超出融合时效";
                case "HISTORICAL" -> "此来源当前没有有效关联轨迹";
                case "UNAVAILABLE" -> "观测时间或贡献依据当前不可用";
                case "UNKNOWN" -> "缺少可还原的融合观测依据";
                default -> null;
            };
            if (rejected.containsKey(link.linkId())) reason = rejected.get(link.linkId());
            out.add(new TargetSourceLinkDto(link.linkId(), link.sourceId(), link.sourceCode(), link.sourceMode(), link.sourceSessionKey(),
                    link.externalTargetId(), link.deviceId(), link.protocolVersion(), link.sourceName(), link.sourceType(), link.schemaStatus(),
                    status, List.copyOf(attrs), e == null || e.observedAt() == null ? null : e.observedAt().toEpochMilli(), reason));
        }
        return List.copyOf(out);
    }
    private static String text(Map<String,Object> values, String key) {
        Object value = values.get(key); return value == null ? null : value.toString();
    }
    private static boolean current(SourceEstimate e, Instant now, long window) {
        return e.observedAt() != null && !e.observedAt().isAfter(now) && !e.observedAt().isBefore(now.minusMillis(window))
                && (e.quality() == null || !e.quality().containsKey("eo_fusion_exclusion"));
    }
}
