package com.uav.lowaltitude.modules.device.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;

@Service
public class EoAutoTrackService {
    private final EoEdgeRepository edges;
    private final EoEdgeCommandService commands;
    private final ObjectMapper json;
    private final boolean mqttEnabled;
    private final boolean autoTrackEnabled;
    private final int batch;

    public EoAutoTrackService(EoEdgeRepository edges, EoEdgeCommandService commands, ObjectMapper json,
                              @Value("${app.mqtt.enabled:true}") boolean mqttEnabled,
                              @Value("${app.eo-edge.auto-track.enabled:false}") boolean autoTrackEnabled,
                              @Value("${app.eo-edge.auto-track-batch:20}") int batch) {
        this.edges = edges; this.commands = commands; this.json = json;
        this.mqttEnabled = mqttEnabled; this.autoTrackEnabled = autoTrackEnabled; this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${app.eo-edge.poll-millis:1000}")
    public void scheduled() {
        if (mqttEnabled && autoTrackEnabled) poll();
    }

    @Transactional
    public int poll() {
        var cursor = edges.lockCursor();
        if (cursor == null) return 0;
        int handled = 0;
        for (var task : edges.automaticTasksToEnd(batch)) {
            Binding device = edges.binding(String.valueOf(task.get("ops_device_id")), true);
            if (device == null || !device.enabled()) continue;
            commands.enqueue(device, EoEdgeCommandService.END, EoEdgeCommandService.TOPIC_END,
                    "AUTO_TRACK_CONDITION_CLEARED");
            handled++;
        }
        for (var row : edges.autoTrackCandidates(batch)) {
            if (consider(row)) handled++;
        }
        return handled;
    }

    private boolean consider(Map<String, Object> row) {
        String eventId = String.valueOf(row.get("event_id"));
        String targetId = String.valueOf(row.get("target_id"));
        JsonNode payload = node(row.get("payload_text"));
        JsonNode latest = payload.path("latest_state");
        if (!latest.path("longitude").isNumber() || !latest.path("latitude").isNumber()) return false;
        String classCode = payload.path("class_code").isTextual() ? payload.path("class_code").asText() : null;
        if (!"UAV".equals(classCode) && !"BIRD".equals(classCode)) return false;
        if (edges.targetHasOpenTask(targetId)) return false;
        var target = edges.target(targetId);
        if (target == null || target.get("owner_org_id") == null || target.get("district_id") == null) return false;
        Binding device = edges.idleDevice(String.valueOf(target.get("owner_org_id")), String.valueOf(target.get("district_id")));
        if (device == null) return false;
        String notes = missingNotes(latest);
        Map<String, Object> bootstrap = bootstrap(targetId, classCode, latest, payload);
        commands.enqueueBegin(device, UUID.randomUUID().toString(), targetId, eventId, notes, bootstrap, null);
        return true;
    }

    private Map<String, Object> bootstrap(String targetId, String classCode, JsonNode latest, JsonNode payload) {
        Map<String, Object> objectData = new LinkedHashMap<>();
        objectData.put("latitude", latest.path("latitude").asDouble());
        objectData.put("longitude", latest.path("longitude").asDouble());
        objectData.put("altitude", latest.path("altitude_raw").isNumber() ? latest.path("altitude_raw").asDouble() : 0d);
        double[] ned = ned(latest);
        objectData.put("speedX", ned[0]);
        objectData.put("speedY", ned[1]);
        objectData.put("speedZ", 0d);
        objectData.put("dataId", targetId);
        objectData.put("length", 0d);
        objectData.put("width", 0d);
        objectData.put("height", 0d);
        objectData.put("objectType", "UAV".equals(classCode) ? 30 : 40);
        objectData.put("probability", payload.path("class_confidence").isNumber() ? payload.path("class_confidence").asDouble() : 0d);
        Map<String, Object> aiData = new LinkedHashMap<>();
        aiData.put("className", "UAV".equals(classCode) ? "drone" : "bird");
        aiData.put("isDetect", 1);
        aiData.put("isTrack", 1);
        Map<String, Object> extention = new LinkedHashMap<>();
        extention.put("mode", "full-auto");
        extention.put("bootstrapSourceId", targetId);
        extention.put("bootstrapSourceType", 0);
        extention.put("msgId", UUID.randomUUID().toString());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objectData", objectData);
        root.put("aiData", aiData);
        root.put("extention", extention);
        return root;
    }

    private static double[] ned(JsonNode latest) {
        if (!latest.path("speed_mps").isNumber() || !latest.path("heading_deg").isNumber()) return new double[] {0d, 0d};
        double speed = latest.path("speed_mps").asDouble();
        double rad = Math.toRadians(latest.path("heading_deg").asDouble());
        return new double[] {speed * Math.cos(rad), speed * Math.sin(rad)};
    }

    private static String missingNotes(JsonNode latest) {
        StringBuilder notes = new StringBuilder();
        if (!latest.path("speed_mps").isNumber() || !latest.path("heading_deg").isNumber()) notes.append("HORIZONTAL_SPEED_NOT_PROVIDED ");
        notes.append("VERTICAL_SPEED_NOT_PROVIDED SIZE_NOT_PROVIDED");
        return notes.toString().trim();
    }

    private JsonNode node(Object value) {
        try {
            JsonNode parsed = json.readTree(String.valueOf(value));
            if (parsed != null && parsed.isTextual()) parsed = json.readTree(parsed.asText());
            return parsed == null || parsed.isMissingNode() ? json.createObjectNode() : parsed;
        } catch (Exception ex) { return json.createObjectNode(); }
    }
}
