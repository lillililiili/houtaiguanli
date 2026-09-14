package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class EoEdgeCommandService {
    public static final String BEGIN = "EO_BEGIN_TRACK", END = "EO_END_TRACK", CAMERA = "EO_CAMERA_STATUS";
    public static final String TOPIC_BEGIN = "eo.track.begin", TOPIC_END = "eo.track.end", TOPIC_CAMERA = "eo.camera.status";
    private final EoEdgeRepository edges;
    private final ObjectProvider<MqttSessionSupervisor> sessions;
    private final AppClock clock;
    private final ObjectMapper json;
    private final long commandTimeoutMillis;

    public EoEdgeCommandService(EoEdgeRepository edges, ObjectProvider<MqttSessionSupervisor> sessions, AppClock clock,
                                ObjectMapper json, org.springframework.core.env.Environment environment) {
        this.edges = edges; this.sessions = sessions; this.clock = clock; this.json = json;
        this.commandTimeoutMillis = Long.parseLong(environment.getProperty("app.eo-edge.command-timeout-millis", "10000"));
    }

    @Transactional
    public String enqueueBegin(Binding binding, String taskId, String targetId, String eventId, String notes,
                               Map<String, Object> bootstrap, String requestedBy) {
        return enqueueBegin(binding, taskId, targetId, eventId, notes, bootstrap, requestedBy, "FUSION_EVENT_AUTO_TRACK");
    }

    @Transactional
    public String enqueueBegin(Binding binding, String taskId, String targetId, String eventId, String notes,
                               Map<String, Object> bootstrap, String requestedBy, String commandReason) {
        long now = clock.nowMillis();
        String commandId = UUID.randomUUID().toString();
        String reason = commandReason == null || commandReason.isBlank() ? "FUSION_EVENT_AUTO_TRACK" : commandReason;
        edges.insertCommand(commandId, "EO-" + now + "-" + commandId.substring(0, 6).toUpperCase(), binding.opsDeviceId(),
                requestedBy, BEGIN, reason, binding.sourceMode(), "replay".equals(binding.sourceMode()),
                now + commandTimeoutMillis, now);
        try {
            edges.insertTask(taskId, targetId, eventId, binding.opsDeviceId(), commandId, notes, json.writeValueAsString(bootstrap), now);
        } catch (Exception ex) { throw new IllegalStateException("cannot store tracking bootstrap", ex); }
        edges.addOutbox(UUID.randomUUID().toString(), TOPIC_BEGIN, commandId, now);
        edges.addEvent(binding.opsDeviceId(), "EO_TRACK_QUEUED", "INFO", "BeginTracking 已排队", now, "replay".equals(binding.sourceMode()));
        return commandId;
    }

    @Transactional
    public String enqueue(Binding binding, String type, String topic, String reason) {
        return enqueue(binding, type, topic, reason, null);
    }

    @Transactional
    public String enqueue(Binding binding, String type, String topic, String reason, String requestedBy) {
        long now = clock.nowMillis();
        String commandId = UUID.randomUUID().toString();
        edges.insertCommand(commandId, "EO-" + now + "-" + commandId.substring(0, 6).toUpperCase(), binding.opsDeviceId(),
                requestedBy, type, reason, binding.sourceMode(), "replay".equals(binding.sourceMode()),
                now + commandTimeoutMillis, now);
        if (END.equals(type)) {
            var task = edges.openTask(binding.opsDeviceId());
            if (task == null) throw new IllegalStateException("TRACK_NOT_OPEN");
            edges.updateTask(String.valueOf(task.get("task_id")), String.valueOf(task.get("status")), "ENDING", commandId, now);
        }
        edges.addOutbox(UUID.randomUUID().toString(), topic, commandId, now);
        return commandId;
    }

    @Transactional
    public void dispatch(String topic, String commandId) {
        var command = edges.command(commandId);
        if (command == null || terminal(text(command, "status"))) return;
        Binding binding = edges.binding(text(command, "device_id"), true);
        if (binding == null || !binding.enabled()) throw new IllegalStateException("EO_DEVICE_UNAVAILABLE");
        long now = clock.nowMillis();
        String status = text(command, "status");
        if ("QUEUED".equals(status)) {
            edges.updateCommand(commandId, "QUEUED", "SENT", now, null, null);
            status = "SENT";
        }
        MqttSessionSupervisor supervisor = sessions.getIfAvailable();
        if (supervisor == null) throw new IllegalStateException("MQTT_DISABLED");
        supervisor.publish(binding.brokerId(), binding.dispatcherTopic(), payload(topic, binding, commandId, now));
    }

    public void timeout(String commandId, String detail) {
        var command = edges.command(commandId);
        if (command == null || terminal(text(command, "status"))) return;
        long now = clock.nowMillis();
        if (edges.updateCommand(commandId, text(command, "status"), "TIMED_OUT", now, "ADAPTER_TIMEOUT", detail) == 1) {
            edges.addEvent(text(command, "device_id"), "EO_COMMAND_TIMED_OUT", "ERROR", detail, now,
                    Boolean.TRUE.equals(command.get("simulated")) || "replay".equals(text(command, "source_mode")));
            var task = edges.openTask(text(command, "device_id"));
            if (task != null && BEGIN.equals(text(command, "command_type")))
                edges.updateTask(String.valueOf(task.get("task_id")), String.valueOf(task.get("status")), "FAILED", null, now);
        }
    }

    private byte[] payload(String topic, Binding binding, String commandId, long now) {
        if (TOPIC_CAMERA.equals(topic))
            return EoEdgeEnvelope.encode("CameraStatus", binding.edgeId(), now, EoEdgeEnvelope.cameraMetadata(binding.externalDeviceId()));
        var task = TOPIC_BEGIN.equals(topic) ? edges.taskByBegin(commandId) : edges.openTask(binding.opsDeviceId());
        if (task == null) throw new IllegalStateException("TRACK_NOT_OPEN");
        String taskId = String.valueOf(task.get("task_id"));
        if (TOPIC_END.equals(topic))
            return EoEdgeEnvelope.encode("EndTracking", binding.edgeId(), now, EoEdgeEnvelope.endMetadata(taskId, binding.externalDeviceId()));
        Map<String, Object> bootstrap = read(String.valueOf(task.get("bootstrap_json")));
        @SuppressWarnings("unchecked")
        Map<String, Object> objectData = (Map<String, Object>) bootstrap.get("objectData");
        @SuppressWarnings("unchecked")
        Map<String, Object> aiData = (Map<String, Object>) bootstrap.get("aiData");
        @SuppressWarnings("unchecked")
        Map<String, Object> extention = (Map<String, Object>) bootstrap.get("extention");
        return EoEdgeEnvelope.encode("BeginTracking", binding.edgeId(), now,
                EoEdgeEnvelope.beginMetadata(taskId, binding.externalDeviceId(), objectData, aiData, extention));
    }
    private Map<String, Object> read(String value) {
        try { return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
        catch (Exception ex) { throw new IllegalStateException("cannot read tracking bootstrap", ex); }
    }
    private static boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(status);
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? null : String.valueOf(value);
    }
}
