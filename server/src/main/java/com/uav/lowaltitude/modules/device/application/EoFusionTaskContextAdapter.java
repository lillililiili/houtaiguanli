package com.uav.lowaltitude.modules.device.application;

import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.fusion.application.EoFusionTaskContextPort;

@Component
public class EoFusionTaskContextAdapter implements EoFusionTaskContextPort {
    private final EoEdgeRepository edges;
    private final JdbcTemplate jdbc;
    private final EoTrackingPolicy policy;
    public EoFusionTaskContextAdapter(EoEdgeRepository edges, JdbcTemplate jdbc, EoTrackingPolicy policy) {
        this.edges = edges; this.jdbc = jdbc; this.policy = policy;
    }
    @Override public Context lookup(String taskId, String sourceId, String deviceId, String mode,
            String org, String district, long observed, long received) {
        return read(taskId,sourceId,deviceId,mode,org,district,observed,received,false);
    }
    @Override public Context lookupForFusion(String taskId, String sourceId, String deviceId, String mode,
            String org, String district, long observed, long received) {
        return read(taskId,sourceId,deviceId,mode,org,district,observed,received,true);
    }
    private Context read(String taskId,String sourceId,String deviceId,String mode,String org,String district,
            long observed,long received,boolean lock) {
        var task = taskId == null ? null : edges.task(taskId);
        if (task == null || !"OPEN".equals(task.get("status"))) return rejected("EO_TASK_NOT_OPEN");
        // Dispatch control locks its target before the binding. Follow that order;
        // never acquire the shared dispatch cursor from this fusion transaction.
        if (lock) jdbc.queryForList("SELECT target_id FROM target WHERE target_id=? FOR UPDATE", task.get("target_id"));
        var binding = edges.binding(String.valueOf(task.get("ops_device_id")), lock);
        if (lock) {
            jdbc.queryForList("SELECT task_id FROM eo_tracking_task WHERE task_id=? FOR UPDATE", taskId);
            task = edges.task(taskId);
            if (task == null || !"OPEN".equals(task.get("status"))) return rejected("EO_TASK_NOT_OPEN");
        }
        if (binding == null || !binding.enabled() || !Objects.equals(sourceId, binding.sourceId())
                || !Objects.equals(deviceId, binding.deviceId()) || !Objects.equals(mode, binding.sourceMode())
                || !Objects.equals(org, binding.ownerOrgId()) || !Objects.equals(district, binding.districtId()))
            return rejected("EO_TASK_SOURCE_MISMATCH");
        if (binding.lastHeartbeatAt() == null || binding.lastHeartbeatAt() < policy.heartbeatCutoff())
            return rejected("EO_DEVICE_HEARTBEAT_EXPIRED");
        var command = edges.command(String.valueOf(task.get("begin_command_id")));
        if (command == null || !"SUCCEEDED".equals(command.get("status"))) return rejected("EO_TASK_RECEIPT_UNKNOWN");
        if (!(task.get("created_at") instanceof Number created) || observed < created.longValue() || observed > received
                || command.get("issued_at") instanceof Number issued && observed < issued.longValue())
            return rejected("EO_TASK_TIME_INVALID");
        String target = String.valueOf(task.get("target_id"));
        Integer changed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM target_lineage WHERE occurred_at>=? AND
                ((op='SPLIT' AND (origin_target_id=? OR survivor_target_id=?))
                 OR (op='MERGE' AND (survivor_target_id=? OR CAST(member_target_ids AS VARCHAR) LIKE ?)))
                """, Integer.class, new Timestamp(created.longValue()), target, target, target, "%\"" + target + "\"%");
        if (changed != null && changed > 0) return rejected("EO_TASK_TARGET_CHANGED");
        return new Context(target, null);
    }
    private static Context rejected(String reason) { return new Context(null, reason); }
}
