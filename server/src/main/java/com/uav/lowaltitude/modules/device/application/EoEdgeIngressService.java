package com.uav.lowaltitude.modules.device.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class EoEdgeIngressService {
    private final EoEdgeRepository edges;
    private final MqttRepository mqtt;
    private final AppClock clock;
    private final ObjectMapper json;

    public EoEdgeIngressService(EoEdgeRepository edges, MqttRepository mqtt, AppClock clock, ObjectMapper json) {
        this.edges = edges; this.mqtt = mqtt; this.clock = clock; this.json = json;
    }

    @Transactional
    public void receive(String broker, String owner, String topic, byte[] bytes, int packet, int qos, boolean retained,
                        boolean duplicate, long receivedAt) {
        if (!mqtt.fence(broker, owner, clock.nowMillis())) throw new IllegalStateException("MQTT_LEASE_LOST");
        var config = mqtt.broker(broker, false);
        if (config == null || !config.enabled()) throw new IllegalStateException("MQTT_DISABLED");
        Binding binding = null;
        String outcome = "REJECTED", reason;
        String inboxId = null;
        try {
            EoEdgeEnvelope m = EoEdgeEnvelope.decode(topic, bytes);
            binding = edges.bindingByExternal(m.edgeId(), m.deviceId(), true);
            if (binding == null) throw new EoEdgeEnvelope.Rejected("DEVICE_NOT_REGISTERED");
            if (!binding.enabled()) throw new EoEdgeEnvelope.Rejected("DEVICE_DISABLED");
            if (!binding.sourceMode().equals(config.sourceMode()) || !binding.brokerId().equals(broker))
                throw new EoEdgeEnvelope.Rejected("SOURCE_MODE_MISMATCH");
            if (retained) throw new EoEdgeEnvelope.Rejected("RETAINED_NOT_REALTIME");
            if (qos != 1) throw new EoEdgeEnvelope.Rejected("QOS1_REQUIRED");
            if (!m.supported()) throw new EoEdgeEnvelope.Rejected("PROTOCOL_EVENT_UNSUPPORTED");
            mqtt.transportDuplicate(broker, packet, topic, m.hash(), duplicate);
            if ("HeartBeat".equals(m.event())) {
                edges.heartbeat(binding, m, cameraJson(m), receivedAt);
                outcome = "ACCEPTED"; reason = "HEARTBEAT_UPDATED";
            } else if (m.trackingReport()) {
                TrackingResult tracking = tracking(binding, m, receivedAt);
                outcome = tracking.outcome; reason = tracking.reason; inboxId = tracking.inboxId;
            } else if ("EndTracking".equals(m.event())) {
                endTracking(binding, m, receivedAt);
                outcome = "ACCEPTED"; reason = "TRACK_ENDED";
            } else if ("CameraStatus".equals(m.event())) {
                cameraStatus(binding, m, receivedAt);
                outcome = "ACCEPTED"; reason = "CAMERA_STATUS";
            } else throw new EoEdgeEnvelope.Rejected("UNKNOWN_EVENT");
        } catch (EoEdgeEnvelope.Rejected ex) { reason = ex.getMessage(); }
        mqtt.diagnostic(broker, binding == null ? null : binding.opsDeviceId(), topic, EoEdgeEnvelope.hash(bytes),
                receivedAt, outcome, reason);
        if (binding != null) edges.counted(binding.opsDeviceId(), outcome);
    }

    private TrackingResult tracking(Binding binding, EoEdgeEnvelope m, long receivedAt) {
        var task = m.taskId() == null ? null : edges.task(m.taskId());
        if (task == null || !binding.opsDeviceId().equals(String.valueOf(task.get("ops_device_id"))))
            throw new EoEdgeEnvelope.Rejected("TRACK_NOT_OPEN");
        String status = String.valueOf(task.get("status"));
        if (!"OPEN".equals(status) && !"ENDING".equals(status)) throw new EoEdgeEnvelope.Rejected("TRACK_NOT_OPEN");
        if (m.codeStatus() != null && m.codeStatus() != 200) {
            completeBegin(binding, task, m, receivedAt, false, null);
            return new TrackingResult("ACCEPTED", "COMMAND_FAILED", null);
        }
        String existing = edges.existingHash(binding.source(), m.sourceMsgId());
        if (existing != null) {
            String outcome = existing.equals(m.hash()) ? "DUPLICATE" : "CONFLICT";
            return new TrackingResult(outcome, existing.equals(m.hash()) ? "SAME_MESSAGE" : "KEY_PAYLOAD_CONFLICT", null);
        }
        String inboxId = edges.inbox(binding, m, receivedAt);
        if (inboxId == null) {
            String again = edges.existingHash(binding.source(), m.sourceMsgId());
            String outcome = again != null && again.equals(m.hash()) ? "DUPLICATE" : "CONFLICT";
            return new TrackingResult(outcome, outcome.equals("DUPLICATE") ? "SAME_MESSAGE" : "KEY_PAYLOAD_CONFLICT", null);
        }
        edges.report(binding, receivedAt);
        completeBegin(binding, task, m, receivedAt, true, inboxId);
        return new TrackingResult("ACCEPTED", "INBOX_RECEIVED", inboxId);
    }

    private void completeBegin(Binding binding, java.util.Map<String, Object> task, EoEdgeEnvelope m, long now,
                               boolean success, String inboxId) {
        String commandId = text(task, "begin_command_id");
        if (commandId == null) return;
        var command = edges.command(commandId);
        if (command == null) return;
        String from = String.valueOf(command.get("status"));
        if (java.util.List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(from)) return;
        String to = success ? "SUCCEEDED" : "FAILED";
        String code = m.codeStatus() == null ? null : String.valueOf(m.codeStatus());
        if (edges.updateCommand(commandId, from, to, now, code, m.json()) == 1 && inboxId != null)
            edges.addReceipt(commandId, inboxId, code, now, m.json());
        if (!success) edges.updateTask(m.taskId(), String.valueOf(task.get("status")), "FAILED", null, now);
    }

    private void endTracking(Binding binding, EoEdgeEnvelope m, long receivedAt) {
        var task = edges.task(m.taskId());
        if (task == null || !binding.opsDeviceId().equals(text(task, "ops_device_id")))
            throw new EoEdgeEnvelope.Rejected("TRACK_NOT_OPEN");
        String from = text(task, "status");
        if ("OPEN".equals(from) || "ENDING".equals(from))
            edges.updateTask(m.taskId(), from, m.codeStatus() != null && m.codeStatus() != 200 ? "FAILED" : "ENDED",
                    text(task, "end_command_id"), receivedAt);
        String commandId = text(task, "end_command_id");
        if (commandId == null) commandId = commandId(binding.opsDeviceId(), "EO_END_TRACK");
        completeCommand(commandId, m, receivedAt);
        edges.camera(binding, cameraJson(m), m.workState() == null ? 0 : m.workState(), receivedAt);
    }

    private void cameraStatus(Binding binding, EoEdgeEnvelope m, long receivedAt) {
        completeCommand(commandId(binding.opsDeviceId(), "EO_CAMERA_STATUS"), m, receivedAt);
        edges.camera(binding, cameraJson(m), m.workState(), receivedAt);
    }

    private void completeCommand(String commandId, EoEdgeEnvelope m, long now) {
        if (commandId == null) return;
        var command = edges.command(commandId);
        if (command == null) return;
        String from = String.valueOf(command.get("status"));
        if (java.util.List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(from)) return;
        boolean success = m.codeStatus() != null && m.codeStatus() == 200;
        edges.updateCommand(commandId, from, success ? "SUCCEEDED" : "FAILED", now,
                m.codeStatus() == null ? null : String.valueOf(m.codeStatus()), m.json());
    }

    private String commandId(String deviceId, String type) {
        var command = edges.commandByType(deviceId, type);
        return command == null ? null : String.valueOf(command.get("command_id"));
    }
    private String cameraJson(EoEdgeEnvelope m) {
        try {
            JsonNode metadata = json.readTree(m.json()).path("metadata");
            JsonNode camera = metadata.path("cameraStatus");
            return camera.isMissingNode() || camera.isNull() ? null : camera.toString();
        } catch (Exception ex) { return null; }
    }
    private static String text(java.util.Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? null : String.valueOf(value);
    }
    private record TrackingResult(String outcome, String reason, String inboxId) { }
}
