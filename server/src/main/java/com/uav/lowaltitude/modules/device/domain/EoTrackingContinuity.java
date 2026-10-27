package com.uav.lowaltitude.modules.device.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;

/** Evidence for an existing task, never coordinates for starting or redirecting a device. */
public final class EoTrackingContinuity {
    private EoTrackingContinuity() { }

    public record Target(String id, String mode, String org, String district, String objectType, String trackStatus) { }

    public static String block(Target target, Map<String, Object> task, Binding device, Map<String, Object> command,
                               long now, long reportMaxAge, long heartbeatMaxAge) {
        if (target == null || task == null || !Objects.equals(target.id(), task.get("target_id"))
                || !"OPEN".equals(task.get("status"))) return "EO_TASK_NOT_CURRENT";
        if (Set.of("MERGE", "SPLIT").contains(Objects.toString(target.trackStatus(), ""))) return "EO_TARGET_CHANGED";
        if (!Set.of("UAV", "BIRD").contains(Objects.toString(target.objectType(), ""))) return "EO_CLASS_UNSUPPORTED";
        if (device == null || !device.enabled() || !Objects.equals(device.opsDeviceId(), task.get("ops_device_id"))
                || target.org() == null || target.district() == null
                || !Objects.equals(device.ownerOrgId(), target.org()) || !Objects.equals(device.districtId(), target.district())
                || !sameMode(target.mode(), device.sourceMode())) return "EO_DEVICE_UNAVAILABLE";
        if (command == null || !"EO_BEGIN_TRACK".equals(command.get("command_type"))
                || !Objects.equals(task.get("begin_command_id"), command.get("command_id"))
                || !Objects.equals(device.opsDeviceId(), command.get("device_id"))
                || !Objects.equals(device.sourceMode(), command.get("source_mode"))
                || (!"live".equals(device.sourceMode()) && !Boolean.TRUE.equals(command.get("simulated")))
                || !"SUCCEEDED".equals(command.get("status"))) return "EO_RECEIPT_UNAVAILABLE";
        if (!fresh(device.lastHeartbeatAt(), now - heartbeatMaxAge, now) || Integer.valueOf(2).equals(device.workState()))
            return "EO_HEARTBEAT_STALE";
        long observed = number(task.get("last_report_observed_at"));
        long received = number(task.get("last_report_at"));
        if (!fresh(observed, now - reportMaxAge, now) || !fresh(received, now - reportMaxAge, now)
                || observed <= number(task.get("last_failure_observed_at"))
                || observed > received || observed < number(task.get("created_at"))
                || observed < number(command.get("issued_at"))) return "EO_REPORT_STALE";
        return null;
    }

    private static boolean sameMode(String target, String device) {
        return ("live".equals(target) && "live".equals(device))
                || (("mock".equals(target) || "replay".equals(target)) && "replay".equals(device));
    }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : Long.MIN_VALUE; }
    private static boolean fresh(Long value, long cutoff, long now) { return value != null && value >= cutoff && value <= now; }
}
