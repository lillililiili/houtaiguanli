package com.uav.lowaltitude.modules.device.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoFusionObservationRepository;
import com.uav.lowaltitude.modules.fusion.application.EoFusionTaskContextPort;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigLoader;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class EoFusionObservationSummaryService {
    public record Summary(String status, String message, OffsetDateTime observedAt) { }
    private final EoEdgeRepository edges;
    private final EoFusionObservationRepository observations;
    private final EoFusionTaskContextPort tasks;
    private final FusionConfigLoader config;
    private final AppClock clock;
    private final ObjectMapper json;
    public EoFusionObservationSummaryService(EoEdgeRepository edges,EoFusionObservationRepository observations,
            EoFusionTaskContextPort tasks,FusionConfigLoader config,AppClock clock,ObjectMapper json) {
        this.edges=edges;this.observations=observations;this.tasks=tasks;this.config=config;this.clock=clock;this.json=json;
    }
    public Summary current(Map<String,Object> task) {
        if (task == null || !"OPEN".equals(task.get("status"))) return null;
        String taskId = String.valueOf(task.get("task_id"));
        var binding = edges.binding(String.valueOf(task.get("ops_device_id")), false);
        if (binding == null) return new Summary("UNKNOWN", "光电识别观测状态未知", null);
        var latest = observations.latest(binding.sourceId(), binding.deviceId(), taskId);
        if (latest == null) return new Summary("NONE", "尚无可用识别观测", null);
        OffsetDateTime observedAt = latest.observedAt().atOffset(ZoneOffset.UTC);
        long now = clock.nowMillis(), observed = latest.observedAt().toEpochMilli();
        if (observed > now) return new Summary("UNKNOWN", "光电识别观测时间待确认", observedAt);
        if (observed < now - config.active().integer("identity", "short_lost_after_ms"))
            return new Summary("EXPIRED", "光电识别观测已过期", observedAt);
        JsonNode quality;
        try {
            quality=json.readTree(latest.qualityJson());
            if (quality != null && quality.isTextual()) quality=json.readTree(quality.asText());
        } catch (Exception error) { return new Summary("UNKNOWN", "光电识别观测状态未知", observedAt); }
        if (quality == null || !quality.isObject() || !taskId.equals(quality.path("eo_task_id").asText())
                || !String.valueOf(task.get("target_id")).equals(quality.path("eo_target_id").asText())) {
            if (quality != null && quality.hasNonNull("eo_fusion_exclusion"))
                return rejected(quality.path("eo_fusion_exclusion").asText(), observedAt);
            return new Summary("UNKNOWN", "光电识别观测关联待确认", observedAt);
        }
        if (quality.hasNonNull("eo_fusion_exclusion")) return rejected(quality.path("eo_fusion_exclusion").asText(), observedAt);
        var context = tasks.lookup(taskId,binding.sourceId(),binding.deviceId(),binding.sourceMode(),
                binding.ownerOrgId(),binding.districtId(),observed,latest.receivedAt().toEpochMilli());
        if (!context.accepted() || !String.valueOf(task.get("target_id")).equals(context.targetId()))
            return rejected(context.exclusionReason(), observedAt);
        return new Summary("CURRENT", "光电识别观测有效", observedAt);
    }
    private static Summary rejected(String reason, OffsetDateTime at) {
        return new Summary("REJECTED", "EO_POSITION_UNKNOWN".equals(reason)
                ? "尚无可用位置识别观测" : "光电观测关联待确认", at);
    }
}
