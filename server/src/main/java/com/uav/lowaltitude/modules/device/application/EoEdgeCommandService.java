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
    private final EoTrackingPolicy policy;
    private final com.uav.lowaltitude.modules.device.infrastructure.EoTrackingRepository tracking;
    private final com.uav.lowaltitude.modules.identity.infrastructure.UserMapper users;
    private final com.uav.lowaltitude.modules.identity.application.AccessService access;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public EoEdgeCommandService(EoEdgeRepository edges, ObjectProvider<MqttSessionSupervisor> sessions, AppClock clock,
                                ObjectMapper json, org.springframework.core.env.Environment environment,
                                EoTrackingPolicy policy, com.uav.lowaltitude.modules.device.infrastructure.EoTrackingRepository tracking,
                                com.uav.lowaltitude.modules.identity.infrastructure.UserMapper users,
                                com.uav.lowaltitude.modules.identity.application.AccessService access,
                                org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.edges = edges; this.sessions = sessions; this.clock = clock; this.json = json;
        this.commandTimeoutMillis = Long.parseLong(environment.getProperty("app.eo-edge.command-timeout-millis", "10000"));
        this.policy=policy;this.tracking=tracking;
        this.users=users;this.access=access;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        if (END.equals(type)) {
            var existing=edges.openTask(binding.opsDeviceId());
            if(existing!=null && "ENDING".equals(text(existing,"status"))) {
                String previousId=text(existing,"end_command_id");
                var previous=edges.command(previousId);
                // An authorized operator may resend the idempotent stop for the
                // same still-occupied task. Automatic loops never retry blindly.
                boolean retry=requestedBy!=null && !requestedBy.isBlank() && previous!=null
                        && java.util.Set.of("TIMED_OUT","FAILED","CANCELLED","UNKNOWN").contains(text(previous,"status"));
                if(!retry) return previousId;
            }
        }
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

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void dispatch(String topic, String commandId) {
        Dispatch prepared=transactions.execute(status -> prepare(topic,commandId));
        if(prepared==null) return;
        transactions.executeWithoutResult(transaction -> {
            // Serialize publication against pause/end and competing dispatcher instances. SENT was committed first.
            edges.lockCursor();
            var current=edges.command(commandId);
            if(current==null || !"SENT".equals(text(current,"status"))) return;
            if(TOPIC_BEGIN.equals(topic)) {
                var task=edges.taskByBegin(commandId);
                if(task==null || !"OPEN".equals(text(task,"status"))) {
                    edges.updateCommand(commandId,"SENT","CANCELLED",clock.nowMillis(),"TRACK_STOPPED_BEFORE_SEND","发送前任务已停止");
                    return;
                }
            }
            try {prepared.supervisor().publish(prepared.binding().brokerId(),prepared.binding().dispatcherTopic(),prepared.payload());}
            catch(RuntimeException ex) {
                edges.updateCommand(commandId,"SENT","TIMED_OUT",clock.nowMillis(),"PUBLISH_RESULT_UNKNOWN","发送结果未知，需要设备回执核查");
            }
        });
    }
    private Dispatch prepare(String topic,String commandId) {
        edges.lockCursor();
        var command = edges.command(commandId);
        if (command == null || terminal(text(command, "status"))) return null;
        // There is no protocol idempotency guarantee. SENT is never republished.
        if (!"QUEUED".equals(text(command,"status"))) return null;
        Binding binding = edges.binding(text(command, "device_id"), true);
        if (binding == null || !binding.enabled()) throw new IllegalStateException("EO_DEVICE_UNAVAILABLE");
        long now = clock.nowMillis();
        if(TOPIC_END.equals(topic)) {
            var current=edges.openTask(binding.opsDeviceId());
            if(current==null || !commandId.equals(text(current,"end_command_id"))) {
                edges.updateCommand(commandId,"QUEUED","CANCELLED",now,"TRACK_NOT_CURRENT","原停止任务已非当前任务");
                return null;
            }
        }
        if (TOPIC_BEGIN.equals(topic)) {
            var task=edges.taskByBegin(commandId);
            String target=task==null?null:text(task,"target_id");
            var snapshot=target==null?null:tracking.snapshot(target);
            boolean auto=task!=null && "AUTO".equals(text(task,"origin"));
            boolean invalid=task==null || !"OPEN".equals(text(task,"status")) || policy.block(snapshot)!=null
                    || !policy.deviceReady(binding) || !binding.sourceMode().equals(EoTrackingPolicy.mode(snapshot))
                    || !binding.ownerOrgId().equals(text(snapshot,"owner_org_id")) || !binding.districtId().equals(text(snapshot,"district_id"))
                    || (!auto && !operatorAllowed(command,binding))
                    || (auto && (!policy.enabledFor(snapshot) || tracking.paused(target) || policy.demand(target).isEmpty()));
            if(invalid || ((Number)command.get("deadline_at")).longValue()<now) {
                edges.updateCommand(commandId,"QUEUED","CANCELLED",now,"TRACK_ELIGIBILITY_CHANGED","下发前资格已失效");
                if(task!=null && "OPEN".equals(text(task,"status"))) edges.updateTask(text(task,"task_id"),"OPEN","FAILED",null,now);
                return null;
            }
            // Refresh bootstrap from the current observation after rechecking eligibility.
            try { edges.refreshBootstrap(text(task,"task_id"),json.writeValueAsString(policy.bootstrap(snapshot))); }
            catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException(ex);}
        }
        MqttSessionSupervisor supervisor = sessions.getIfAvailable();
        if (supervisor == null) throw new IllegalStateException("MQTT_DISABLED");
        byte[] payload=payload(topic,binding,commandId,now);
        if(edges.updateCommand(commandId,"QUEUED","SENT",now,null,null)!=1) return null;
        return new Dispatch(binding,supervisor,payload);
    }
    private record Dispatch(Binding binding,MqttSessionSupervisor supervisor,byte[] payload) { }

    @Transactional
    public void timeout(String commandId, String detail) {
        edges.lockCursor();
        var command = edges.command(commandId);
        if (command == null || terminal(text(command, "status"))) return;
        long now = clock.nowMillis();
        if (edges.updateCommand(commandId, text(command, "status"), "TIMED_OUT", now, "ADAPTER_TIMEOUT", detail) == 1) {
            edges.addEvent(text(command, "device_id"), "EO_COMMAND_TIMED_OUT", "ERROR", detail, now,
                    Boolean.TRUE.equals(command.get("simulated")) || "replay".equals(text(command, "source_mode")));
            var task = edges.openTask(text(command, "device_id"));
            if (task != null && BEGIN.equals(text(command, "command_type")) && "QUEUED".equals(text(command,"status")))
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
    private boolean operatorAllowed(Map<String,Object> command,Binding binding) {
        String id=text(command,"requested_by");
        if(id==null) return false;
        var user=users.findById(id);
        return user!=null && "ACTIVE".equals(user.getStatus()) && tracking.roleEnabled(user.getRoleCode()) && access.permissionCodes(user.getRoleCode()).contains("devices.op")
                && tracking.deviceScope(binding.opsDeviceId(),id,user.getScopeMode());
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
