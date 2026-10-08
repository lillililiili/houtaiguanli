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
    private final boolean stopRetryEnabled;
    private final int stopRetryMaximum;
    private final long stopRetryDelayMillis;
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
        this.stopRetryEnabled = environment.getProperty("app.eo-edge.stop-retry.enabled", Boolean.class, true);
        this.stopRetryMaximum = environment.getProperty("app.eo-edge.stop-retry.max-attempts", Integer.class, 3);
        this.stopRetryDelayMillis = environment.getProperty("app.eo-edge.stop-retry.delay-millis", Long.class, 30000L);
        if (stopRetryMaximum < 0 || stopRetryMaximum > 10 || stopRetryDelayMillis < 1000)
            throw new IllegalArgumentException("Invalid EO stop recovery limits");
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
                // Unchecked repeated requests keep the original command. Automatic
                // recovery has its own bounded, task/source/connection-checked entry.
                boolean retry=requestedBy!=null && !requestedBy.isBlank() && previous!=null
                        && java.util.Set.of("TIMED_OUT","FAILED","CANCELLED","UNKNOWN").contains(text(previous,"status"));
                if(!retry) return previousId;
            }
        }
        return enqueueCommand(binding, type, topic, reason, requestedBy, now);
    }

    /** Continues an already authorized stop; never treats timeout/idle heartbeat as a stop receipt. */
    @Transactional
    public boolean retryStop(String taskId) {
        if (!stopRetryEnabled || stopRetryMaximum == 0) return false;
        if (edges.lockCursor() == null) return false;
        var task = edges.task(taskId);
        if (task == null || !"ENDING".equals(text(task, "status"))) return false;
        Binding binding = edges.binding(text(task, "ops_device_id"), true);
        // Only the replay adapter currently guarantees idempotent EndTracking(taskId).
        if (binding == null || !binding.enabled() || !"replay".equals(binding.sourceMode())) return false;
        var current = edges.openTask(binding.opsDeviceId());
        if (current == null || !taskId.equals(text(current, "task_id")) || !"ENDING".equals(text(current, "status"))) return false;
        var previous = edges.command(text(current, "end_command_id"));
        if (!matchingSimulatedCommand(previous, binding, END) || !stopRecoveryContext(current, binding)
                || !List.of("TIMED_OUT", "FAILED").contains(text(previous, "status"))) return false;
        long now = clock.nowMillis();
        if (!(previous.get("completed_at") instanceof Number completed) || completed.longValue() > now - stopRetryDelayMillis
                || binding.lastHeartbeatAt() < completed.longValue()) return false;
        String previousId = text(previous, "command_id");
        if (!edges.claimStopRetry(taskId, previousId, stopRetryMaximum, now)) return false;
        String next = enqueueCommand(binding, END, TOPIC_END, "AUTO_STOP_RECOVERY", null, now);
        edges.addEvent(binding.opsDeviceId(), "EO_STOP_AUTO_RETRY", "WARN",
                "自动重试停止同一跟踪任务 " + taskId + "；原指令 " + previousId + "；新指令 " + next, now, true);
        return true;
    }

    private static boolean matchingSimulatedCommand(Map<String, Object> command, Binding binding, String type) {
        return command != null && type.equals(text(command, "command_type"))
                && binding.opsDeviceId().equals(text(command, "device_id"))
                && binding.sourceMode().equals(text(command, "source_mode")) && Boolean.TRUE.equals(command.get("simulated"));
    }

    private boolean stopRecoveryContext(Map<String, Object> task, Binding binding) {
        if (!stopRetryEnabled || binding == null || !binding.enabled() || !"replay".equals(binding.sourceMode())
                || binding.lastHeartbeatAt() == null || binding.lastHeartbeatAt() < policy.heartbeatCutoff()
                || binding.lastHeartbeatAt() > clock.nowMillis() || !edges.stopRecoveryConnected(binding)
                || !matchingSimulatedCommand(edges.command(text(task, "begin_command_id")), binding, BEGIN)) return false;
        var target = tracking.snapshot(text(task, "target_id"));
        return target != null && binding.sourceMode().equals(EoTrackingPolicy.mode(target))
                && java.util.Objects.equals(binding.ownerOrgId(), text(target, "owner_org_id"))
                && java.util.Objects.equals(binding.districtId(), text(target, "district_id"));
    }

    public String unconfirmedStopMessage(Map<String, Object> task) {
        String message = "尚未确认设备停止，保留占用";
        var binding = edges.binding(text(task, "ops_device_id"), false);
        var command = edges.command(text(task, "end_command_id"));
        if (!stopRetryEnabled || stopRetryMaximum == 0 || binding == null || !"replay".equals(binding.sourceMode())
                || command == null || !List.of("TIMED_OUT", "FAILED").contains(text(command, "status")))
            return message + "，请核查设备回执";
        int attempts = task.get("stop_retry_count") instanceof Number count ? count.intValue() : 0;
        return attempts >= stopRetryMaximum ? message + "；自动重试已达上限（" + attempts + "次），请人工核查"
                : message + "；连接及任务核查通过后自动重试（已重试" + attempts + "/" + stopRetryMaximum + "次）";
    }

    private String enqueueCommand(Binding binding, String type, String topic, String reason, String requestedBy, long now) {
        String commandId = UUID.randomUUID().toString();
        edges.insertCommand(commandId, "EO-" + now + "-" + commandId.substring(0, 6).toUpperCase(), binding.opsDeviceId(),
                requestedBy, type, reason, binding.sourceMode(), "replay".equals(binding.sourceMode()),
                now + commandTimeoutMillis, now);
        if (END.equals(type)) {
            var task = edges.openTask(binding.opsDeviceId());
            if (task == null) throw new IllegalStateException("TRACK_NOT_OPEN");
            if (edges.updateTask(String.valueOf(task.get("task_id")), String.valueOf(task.get("status")), "ENDING", commandId, now) != 1)
                throw new IllegalStateException("TRACK_CHANGED_BEFORE_STOP");
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
            if (TOPIC_END.equals(topic)) {
                var binding = edges.binding(text(current, "device_id"), true);
                var task = edges.openTask(text(current, "device_id"));
                if (task == null || !"ENDING".equals(text(task, "status")) || !commandId.equals(text(task, "end_command_id"))) {
                    edges.updateCommand(commandId,"SENT","CANCELLED",clock.nowMillis(),"TRACK_NOT_CURRENT","原停止任务已非当前任务");
                    return;
                }
                if (binding == null || !binding.enabled() || !binding.brokerId().equals(prepared.binding().brokerId())
                        || !binding.edgeId().equals(prepared.binding().edgeId())
                        || !binding.externalDeviceId().equals(prepared.binding().externalDeviceId())
                        || !binding.sourceMode().equals(prepared.binding().sourceMode())
                        || ("AUTO_STOP_RECOVERY".equals(text(current, "reason"))
                            && (!matchingSimulatedCommand(current, binding, END) || !stopRecoveryContext(task, binding)))) {
                    edges.updateCommand(commandId,"SENT","CANCELLED",clock.nowMillis(),"TRACK_ELIGIBILITY_CHANGED","停止指令发送前设备或来源已变化");
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
            if(current==null || !"ENDING".equals(text(current,"status")) || !commandId.equals(text(current,"end_command_id"))) {
                edges.updateCommand(commandId,"QUEUED","CANCELLED",now,"TRACK_NOT_CURRENT","原停止任务已非当前任务");
                return null;
            }
            if ("AUTO_STOP_RECOVERY".equals(text(command, "reason"))
                    && (!matchingSimulatedCommand(command, binding, END) || !stopRecoveryContext(current, binding)
                        || ((Number) command.get("deadline_at")).longValue() < now)) {
                edges.updateCommand(commandId,"QUEUED","CANCELLED",now,"TRACK_ELIGIBILITY_CHANGED","自动停止重试下发前条件已变化");
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
            if (BEGIN.equals(text(command, "command_type")) && "QUEUED".equals(text(command, "status"))) {
                // An unsent begin can fail only its own still-open task. A pending stop
                // retains occupancy until its receipt; an old begin cannot fail a later task.
                var task = edges.taskByBegin(commandId);
                if (task != null && "OPEN".equals(text(task, "status")))
                    edges.updateTask(text(task, "task_id"), "OPEN", "FAILED", null, now);
            }
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
