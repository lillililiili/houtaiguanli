package com.uav.lowaltitude.modules.device.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.platform.security.AuthUser;

@Repository
public class DeviceMaintenanceRepository {

    public com.uav.lowaltitude.platform.report.BusinessReportSource.Dataset reportDataset(
            com.uav.lowaltitude.platform.report.BusinessReportSource.Range range, AuthUser actor) {
        Map<String,Object> params = new HashMap<>();
        String sql = "SELECT t.task_id AS id,t.device_name AS label,t.reported_at AS at_ms,"
            + "t.status AS state,t.health_code AS kind,CAST(NULL AS VARCHAR) AS severity,rd.name AS region,"
            + "CASE WHEN t.simulated=TRUE THEN 'mock' ELSE d.source_mode END AS source_mode,"
            + "t.device_no AS related,t.handling_note AS result,t.reason AS note"
            + " FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"
            + " LEFT JOIN app_district rd ON rd.district_id=t.district_id" + scope(actor, params);
        return com.uav.lowaltitude.platform.report.ReportDatasetReader.window(sql, params, range, "t.reported_at");
    }
    private final NamedParameterJdbcTemplate jdbc;
    public DeviceMaintenanceRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    public void lockDevice(String id) {
        jdbc.queryForList("SELECT device_id FROM ops_device WHERE device_id=:id FOR UPDATE", Map.of("id", id));
    }

    public Row submission(String actor, String key) {
        return one("SELECT t.* FROM ops_device_maintenance_submission s JOIN ops_device_maintenance_task t"
                + " ON t.task_id=s.task_id WHERE s.actor_id=:actor AND s.request_key=:key", Map.of("actor", actor, "key", key));
    }

    public Row pending(String planId, String deviceId) {
        return one("SELECT * FROM ops_device_maintenance_task WHERE active_key=:key", Map.of("key", planId + ":" + deviceId));
    }

    public void recordSubmission(String actor, String key, String taskId) {
        jdbc.update("INSERT INTO ops_device_maintenance_submission(actor_id,request_key,task_id) VALUES(:actor,:key,:task)",
                Map.of("actor", actor, "key", key, "task", taskId));
    }

    public void insert(Row r) {
        Map<String,Object> p = new HashMap<>();
        p.put("id",r.taskId); p.put("plan",r.planId); p.put("device",r.deviceId);
        p.put("org",r.ownerOrgId); p.put("district",r.districtId); p.put("plan_no",r.planNo);
        p.put("device_no",r.deviceNo); p.put("name",r.deviceName); p.put("reason",r.reason);
        p.put("connectivity",r.connectivity); p.put("health",r.healthCode); p.put("observed",r.observedAt);
        p.put("heartbeat",r.lastHeartbeatAt); p.put("simulated",r.simulated); p.put("actor",r.reportedBy);
        p.put("actor_name",r.reportedByName); p.put("at",r.reportedAt); p.put("active",r.planId+":"+r.deviceId);
        jdbc.update("""
                INSERT INTO ops_device_maintenance_task(task_id,plan_id,device_id,owner_org_id,district_id,plan_no,
                    device_no,device_name,reason,connectivity,health_code,observed_at,last_heartbeat_at,simulated,
                    status,active_key,reported_by,reported_by_name,reported_at,version)
                VALUES(:id,:plan,:device,:org,:district,:plan_no,:device_no,:name,:reason,:connectivity,:health,
                    :observed,:heartbeat,:simulated,'PENDING',:active,:actor,:actor_name,:at,1)
                """,p);
    }

    public Row find(String id, AuthUser actor) {
        Map<String,Object> p = new HashMap<>(); p.put("id",id);
        return one("SELECT t.* FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"
                + scope(actor,p) + " AND t.task_id=:id",p);
    }

    public long count(String status, AuthUser actor) {
        Map<String,Object> p = new HashMap<>();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"
                + scope(actor,p) + status(status,p),p,Long.class);
        return total == null ? 0 : total;
    }

    public List<Row> list(String status, int page, int size, AuthUser actor) {
        Map<String,Object> p = new HashMap<>(); p.put("offset",(page-1)*size); p.put("size",size);
        return jdbc.query("SELECT t.* FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"
                + scope(actor,p) + status(status,p)
                + " ORDER BY t.reported_at DESC,t.task_id DESC OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",p,DeviceMaintenanceRepository::row);
    }

    public int handle(String id, long version, String note, AuthUser actor, long at) {
        return jdbc.update("UPDATE ops_device_maintenance_task SET status='HANDLED',active_key=NULL,handled_by=:actor,"
                + "handled_by_name=:name,handled_at=:at,handling_note=:note,version=version+1"
                + " WHERE task_id=:id AND status='PENDING' AND version=:version",
                Map.of("actor",actor.userId(),"name",actor.name(),"at",at,"note",note,"id",id,"version",version));
    }

    private static String status(String status, Map<String,Object> p) {
        if ("ALL".equals(status)) return "";
        p.put("status",status); return " AND t.status=:status";
    }

    // 待办按通知所属计划的组织/区域限制；MQTT 设备同时受既有设备归属范围约束。
    private static String scope(AuthUser actor, Map<String,Object> p) {
        String base = " WHERE d.deleted_at IS NULL"
                + " AND EXISTS(SELECT 1 FROM app_org o WHERE o.org_id=t.owner_org_id AND o.enabled=TRUE)"
                + " AND EXISTS(SELECT 1 FROM app_district dd WHERE dd.district_id=t.district_id AND dd.enabled=TRUE)";
        if ("ALL".equals(actor.scopeMode())) return base;
        if (!"ASSIGNED".equals(actor.scopeMode())) return base+" AND 1=0";
        p.put("scope_actor",actor.userId());
        return base + """
                 AND EXISTS(SELECT 1 FROM app_user_data_scope us
                    WHERE us.user_id=:scope_actor AND us.org_id=t.owner_org_id AND us.district_id=t.district_id)
                 AND (NOT EXISTS(SELECT 1 FROM mqtt_device_binding mb WHERE mb.ops_device_id=d.device_id)
                    OR EXISTS(SELECT 1 FROM device_business_scope bs JOIN app_user_data_scope us
                        ON us.org_id=bs.owner_org_id AND us.district_id=bs.district_id
                        JOIN app_org o ON o.org_id=bs.owner_org_id AND o.enabled=TRUE
                        JOIN app_district dd ON dd.district_id=bs.district_id AND dd.enabled=TRUE
                        WHERE bs.ops_device_id=d.device_id AND us.user_id=:scope_actor))
                """;
    }

    private Row one(String sql, Map<String,?> params) {
        List<Row> rows = jdbc.query(sql,params,DeviceMaintenanceRepository::row);
        return rows.isEmpty()?null:rows.get(0);
    }
    private static Long number(ResultSet r,String column) throws SQLException { long v=r.getLong(column);return r.wasNull()?null:v; }
    private static Row row(ResultSet r,int index) throws SQLException {
        return new Row(r.getString("task_id"),r.getString("plan_id"),r.getString("device_id"),r.getString("owner_org_id"),
                r.getString("district_id"),r.getString("plan_no"),r.getString("device_no"),r.getString("device_name"),
                r.getString("reason"),r.getString("connectivity"),r.getString("health_code"),number(r,"observed_at"),
                number(r,"last_heartbeat_at"),r.getBoolean("simulated"),r.getString("status"),r.getString("reported_by"),
                r.getString("reported_by_name"),r.getLong("reported_at"),r.getString("handled_by_name"),number(r,"handled_at"),
                r.getString("handling_note"),r.getLong("version"));
    }
    public record Row(String taskId,String planId,String deviceId,String ownerOrgId,String districtId,String planNo,
            String deviceNo,String deviceName,String reason,String connectivity,String healthCode,Long observedAt,
            Long lastHeartbeatAt,boolean simulated,String status,String reportedBy,String reportedByName,long reportedAt,
            String handledByName,Long handledAt,String handlingNote,long version) { }
}
