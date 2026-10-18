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
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;

@Repository
public class DeviceMaintenanceRepository {

    // Rank before pagination so legacy processing reports cannot hide the active v2 case.
    // Use stable business time within each group; automatic checks must not reshuffle the list.
    private static final String CASE_PRIORITY_ORDER = "CASE WHEN t.status='PENDING' AND t.workflow_version=2 THEN 0"
            + " WHEN t.status='PENDING' THEN 1 ELSE 2 END,";

    /**
     * 报表“处理结果”读处置单的办结记录（2026-10-10 流程）：已办结写办结说明及恢复依据；
     * 历史已反馈写当时反馈；未办结写当前阶段；旧数据没有记录写“无处理记录”，不写“未知”。
     */
    static final String RESULT = "CASE"
        + " WHEN t.workflow_state='COMPLETED' THEN '已办结'"
        + "||COALESCE('：'||NULLIF(TRIM(t.handling_note),''),'')"
        + "||CASE WHEN t.recovery_result='PASS' THEN '（设备已恢复）' ELSE '' END"
        + " WHEN t.status='HANDLED' THEN COALESCE('历史反馈：'||NULLIF(TRIM(t.handling_note),''),'无处理记录')"
        + " WHEN t.workflow_state='READY_TO_COMPLETE' THEN '设备已恢复，待确认办结'"
        + " WHEN t.workflow_state='PROCESSING' THEN '处理中'||COALESCE('（'||NULLIF(TRIM(t.assigned_to_name),'')||'）','')"
        + " WHEN t.workflow_state IN ('PENDING','PENDING_VERIFICATION') THEN '尚未办结'"
        + " ELSE '无处理记录' END";
    public com.uav.lowaltitude.platform.report.BusinessReportSource.Dataset reportDataset(
            com.uav.lowaltitude.platform.report.BusinessReportSource.Range range, AuthUser actor) {
        Map<String,Object> params = new HashMap<>();
        String sql = "SELECT t.task_id AS id,t.device_name AS label,t.reported_at AS at_ms,"
            + "t.workflow_state AS state,t.health_code AS kind,CAST(NULL AS VARCHAR) AS severity,rd.name AS region,"
            + "CASE WHEN t.simulated=TRUE THEN 'mock' ELSE d.source_mode END AS source_mode,"
            + "COALESCE(t.device_no,d.device_no) AS related," + RESULT + " AS result,t.reason AS note"
            + " FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"
            + " LEFT JOIN app_district rd ON rd.district_id=t.district_id" + scope(actor, params);
        return com.uav.lowaltitude.platform.report.ReportDatasetReader.window(sql, params, range, "t.reported_at");
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final DeviceMaintenanceCaseRepository cases;
    private final FlightReadRepository plans;
    public DeviceMaintenanceRepository(JdbcTemplate jdbc,DeviceMaintenanceCaseRepository cases,FlightReadRepository plans) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);this.cases=cases;this.plans=plans;
    }

    private String caseOrder(AccessDecision planAccess,Map<String,Object> parameters) {
        String visibleReport = "mr.plan_id IS NULL";
        if(planAccess!=null) {
            var visible=plans.visiblePlanIds(planAccess);
            parameters.putAll(visible.parameters());
            visibleReport+=" OR mr.plan_id IN ("+visible.sql()+")";
        }
        // Match report_summary.latest_manual_report before pagination. Polling, health reports and
        // automatic plan checks are not operator reports and must never move a case to the top.
        return CASE_PRIORITY_ORDER+"COALESCE((SELECT MAX(mr.reported_at) FROM ops_maintenance_report mr"
            +" WHERE mr.task_id=t.task_id AND mr.actor_id IS NOT NULL AND ("+visibleReport+")),t.reported_at) DESC,"
            +"t.reported_at DESC,t.handled_at DESC NULLS LAST,t.task_id DESC";
    }

    public void lockDevice(String id) {
        jdbc.queryForList("SELECT device_id FROM ops_device WHERE device_id=:id FOR UPDATE", Map.of("id", id));
    }

    public Row submission(String actor, String key) {
        return one("SELECT t.* FROM ops_device_maintenance_submission s JOIN ops_device_maintenance_task t"
                + " ON t.task_id=s.task_id WHERE s.actor_id=:actor AND s.request_key=:key", Map.of("actor", actor, "key", key));
    }

    public Row pending(String planId, String deviceId) {
        return one("SELECT * FROM ops_device_maintenance_task WHERE active_key=:key", Map.of("key", cases.activeKey(deviceId)));
    }

    public void recordSubmission(String actor, String key, String taskId,String planId) {
        Map<String,Object> parameters=new HashMap<>();parameters.put("actor",actor);parameters.put("key",key);
        parameters.put("task",taskId);parameters.put("plan",planId);
        jdbc.update("INSERT INTO ops_device_maintenance_submission(actor_id,request_key,task_id,plan_id) VALUES(:actor,:key,:task,:plan)",
                parameters);
    }

    /** Called only after device read authorization; never cross the device's frozen source identity. */
    public Row currentSourceCase(String device) {
        return one("""
            SELECT t.* FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id
            WHERE t.device_id=:device AND t.workflow_version=2
              AND t.workflow_source_id IS NOT DISTINCT FROM d.source_id
              AND t.workflow_source_mode=d.source_mode AND t.simulated=d.simulated
            ORDER BY CASE WHEN t.status='PENDING' THEN 0 ELSE 1 END,t.reported_at DESC,t.task_id DESC
            FETCH FIRST 1 ROW ONLY
            """,Map.of("device",device));
    }

    public Long myDeviceReportAt(String task,String actor) {
        return jdbc.queryForObject("SELECT MAX(reported_at) FROM ops_maintenance_report WHERE task_id=:task AND actor_id=:actor AND plan_id IS NULL",
            Map.of("task",task,"actor",actor),Long.class);
    }

    public void insert(Row r) {
        Map<String,Object> p = new HashMap<>();
        p.put("id",r.taskId); p.put("plan",r.planId); p.put("device",r.deviceId);
        p.put("org",r.ownerOrgId); p.put("district",r.districtId); p.put("plan_no",r.planNo);
        p.put("device_no",r.deviceNo); p.put("name",r.deviceName); p.put("reason",r.reason);
        p.put("connectivity",r.connectivity); p.put("health",r.healthCode); p.put("observed",r.observedAt);
        p.put("heartbeat",r.lastHeartbeatAt); p.put("simulated",r.simulated); p.put("actor",r.reportedBy);
        p.put("actor_name",r.reportedByName); p.put("at",r.reportedAt); p.put("active",cases.activeKey(r.deviceId));
        p.put("trigger",r.reportedBy==null?"SYSTEM":"USER");
        jdbc.update("""
                INSERT INTO ops_device_maintenance_task(task_id,plan_id,device_id,owner_org_id,district_id,plan_no,
                    device_no,device_name,reason,connectivity,health_code,observed_at,last_heartbeat_at,simulated,
                    status,active_key,reported_by,reported_by_name,reported_at,version,workflow_source_mode,trigger_type,
                    workflow_version,workflow_source_id,recovery_since_at)
                VALUES(:id,:plan,:device,:org,:district,:plan_no,:device_no,:name,:reason,:connectivity,:health,
                    :observed,:heartbeat,:simulated,'PENDING',:active,:actor,:actor_name,:at,1,
                    (SELECT source_mode FROM ops_device WHERE device_id=:device),:trigger,2,
                    (SELECT source_id FROM ops_device WHERE device_id=:device),:at)
                """,p);
    }

    public Row find(String id, AuthUser actor) {
        Map<String,Object> p = new HashMap<>(); p.put("id",id);
        return one("SELECT t.* FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"
                + scope(actor,p) + " AND t.task_id=:id",p);
    }
    public Row findInternal(String id){return one("SELECT * FROM ops_device_maintenance_task WHERE task_id=:id",Map.of("id",id));}

    public long count(String status, AuthUser actor) {
        return count(status,actor,null);
    }
    public long count(String status, AuthUser actor,String device) {
        Map<String,Object> p = new HashMap<>();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"
                + scope(actor,p) + status(status,p)+deviceFilter(device,p),p,Long.class);
        return total == null ? 0 : total;
    }

    public List<Row> list(String status, int page, int size, AuthUser actor) {
        return list(status,page,size,actor,null,null);
    }
    public List<Row> list(String status, int page, int size, AuthUser actor,String device,AccessDecision planAccess) {
        Map<String,Object> p = new HashMap<>(); p.put("offset",(page-1)*size); p.put("size",size);
        return jdbc.query("SELECT t.* FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"
                + scope(actor,p) + status(status,p)+deviceFilter(device,p)
                + " ORDER BY " + caseOrder(planAccess,p) + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",p,DeviceMaintenanceRepository::row);
    }

    public List<Row> forPlan(String planId,String deviceId,int page,int size,AuthUser actor,AccessDecision planAccess) {
        Map<String,Object> p=new HashMap<>();p.put("plan",planId);p.put("device",deviceId);p.put("offset",(page-1)*size);p.put("size",size);
        // The plan entry and the workbench must choose the same current case for a device.
        return jdbc.query("SELECT t.* FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"+scope(actor,p)+" AND (t.plan_id=:plan OR EXISTS(SELECT 1 FROM ops_maintenance_plan l WHERE l.task_id=t.task_id AND l.plan_id=:plan))"+(deviceId==null?"":" AND t.device_id=:device")
                + " ORDER BY " + caseOrder(planAccess,p) + " OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",p,DeviceMaintenanceRepository::row);
    }
    public long countForPlan(String planId,String deviceId,AuthUser actor) {
        Map<String,Object> p=new HashMap<>();p.put("plan",planId);p.put("device",deviceId);
        return jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task t JOIN ops_device d ON d.device_id=t.device_id"+scope(actor,p)+" AND (t.plan_id=:plan OR EXISTS(SELECT 1 FROM ops_maintenance_plan l WHERE l.task_id=t.task_id AND l.plan_id=:plan))"+(deviceId==null?"":" AND t.device_id=:device"),p,Long.class);
    }

    public int handle(String id, long version, String note, AuthUser actor, long at) {
        return jdbc.update("UPDATE ops_device_maintenance_task SET status='HANDLED',active_key=NULL,handled_by=:actor,"
                + "handled_by_name=:name,handled_at=:at,handling_note=:note,version=version+1"
                + " WHERE task_id=:id AND status='PENDING' AND version=:version",
                Map.of("actor",actor.userId(),"name",actor.name(),"at",at,"note",note,"id",id,"version",version));
    }

    private static String status(String status, Map<String,Object> p) {
        if ("ALL".equals(status)) return "";
        if ("ACTIVE".equals(status)) return " AND t.status='PENDING'";
        if ("NOT_STARTED".equals(status)) return " AND t.workflow_state='PENDING'";
        if (java.util.Set.of("PROCESSING","PENDING_VERIFICATION","READY_TO_COMPLETE","COMPLETED").contains(status)) {
            p.put("status",status); return " AND t.workflow_state=:status";
        }
        p.put("status",status); return " AND t.status=:status";
    }
    private static String deviceFilter(String device,Map<String,Object> p){if(device==null)return "";p.put("device_filter",device);return " AND t.device_id=:device_filter";}

    // 待办按通知所属计划的组织/区域限制；MQTT 设备同时受既有设备归属范围约束。
    static String scope(AuthUser actor, Map<String,Object> p) {
        String base = " WHERE d.deleted_at IS NULL"
                + " AND (t.workflow_version=2 OR (EXISTS(SELECT 1 FROM app_org o WHERE o.org_id=t.owner_org_id AND o.enabled=TRUE)"
                + " AND EXISTS(SELECT 1 FROM app_district dd WHERE dd.district_id=t.district_id AND dd.enabled=TRUE)))";
        if ("ALL".equals(actor.scopeMode())) return base;
        if (!"ASSIGNED".equals(actor.scopeMode())) return base+" AND 1=0";
        p.put("scope_actor",actor.userId());
        return base + """
                 AND (t.workflow_version=2 OR EXISTS(SELECT 1 FROM app_user_data_scope us
                    WHERE us.user_id=:scope_actor AND us.org_id=t.owner_org_id AND us.district_id=t.district_id))
                 AND ((t.workflow_version=1 AND NOT EXISTS(SELECT 1 FROM mqtt_device_binding mb WHERE mb.ops_device_id=d.device_id))
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
    public String workflowState(String id) {
        return jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=:id",Map.of("id",id),String.class);
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
