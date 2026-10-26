package com.uav.lowaltitude.modules.identity.infrastructure;

import java.sql.Timestamp;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalSafetySql;
import com.uav.lowaltitude.platform.api.ApiException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Current work blocks retirement; the mere existence of a historical ownership reference does not. */
@Repository
public class OrganizationDeletionRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final NoCounterRepository noCounter;

    public OrganizationDeletionRepository(JdbcTemplate jdbc, NoCounterRepository noCounter) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.noCounter = noCounter;
    }

    public Map<String, Object> lockOrganization(String orgId) {
        try {
            var rows = jdbc.queryForList("SELECT * FROM app_org WHERE org_id=? FOR UPDATE NOWAIT", orgId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException failure) {
            // PostgreSQL NOWAIT may be translated as UncategorizedSQLException by the configured translator.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof CannotAcquireLockException
                        || (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState()))) {
                    throw new ApiException(HttpStatus.CONFLICT, "ORG_DELETE_BUSY", "该单位正在办理业务，请稍后刷新重试");
                }
            }
            throw failure;
        }
    }

    public Map<String, Object> profile(String orgId) {
        var rows = jdbc.queryForList("SELECT * FROM organization_profile WHERE org_id=?", orgId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public int deletePreviews(String orgId) {
        return jdbc.update("DELETE FROM app_org_delegation_preview WHERE org_id=?", orgId);
    }

    public int retireOrganization(String orgId, int version, String actor, long at) {
        return jdbc.update("UPDATE app_org SET enabled=FALSE,deleted_at=?,deleted_by=?,updated_at=?,version=version+1"
                + " WHERE org_id=? AND version=? AND deleted_at IS NULL", at, actor, at, orgId, version);
    }

    /** Keep the old configuration as history while invalidating appointments, roles and sessions atomically. */
    public Map<String, Object> retireAuthorization(String orgId, long at) {
        Map<String, Object> values = Map.of("org", orgId, "at", at);
        String affected = "SELECT u.user_id FROM app_user u WHERE u.org_id=:org"
                + " OR u.role_code IN (SELECT role_code FROM app_role WHERE owner_org_id=:org)"
                + " OR u.user_id IN (SELECT administrator_user_id FROM app_org_delegation WHERE org_id=:org)"
                + " OR (u.scope_mode='ASSIGNED' AND u.user_id IN (SELECT user_id FROM app_user_data_scope WHERE org_id=:org))";
        var changes = new LinkedHashMap<String, Object>();
        changes.put("revoked_sessions", named.update("UPDATE app_session SET expire_at=0 WHERE expire_at>0 AND user_id IN (" + affected + ")", values));
        changes.put("invalidated_users", named.update("UPDATE app_user SET permission_version=permission_version+1 WHERE user_id IN (" + affected + ")", values));
        changes.put("previous_delegation", jdbc.queryForList("SELECT * FROM app_org_delegation WHERE org_id=?", orgId));
        changes.put("previous_roles", jdbc.queryForList("SELECT role_code,name,enabled,version FROM app_role WHERE owner_org_id=?", orgId));
        changes.put("revoked_appointments", jdbc.update("UPDATE app_org_delegation SET administrator_user_id=NULL,version=version+1,updated_at=?"
                + " WHERE org_id=? AND administrator_user_id IS NOT NULL", at, orgId));
        changes.put("disabled_roles", jdbc.update("UPDATE app_role SET enabled=FALSE,version=version+1,updated_at=?"
                + " WHERE owner_org_id=? AND enabled=TRUE", at, orgId));
        return changes;
    }

    public List<String> blockingReferences(String orgId, long now) {
        var parameters = Map.<String, Object>of("org", orgId, "now", new Timestamp(now), "nowMillis", now, "prediction_now", now);
        var reasons = new ArrayList<String>();
        for (Check check : CHECKS) {
            if (!named.query(check.sql() + " FETCH FIRST 1 ROWS ONLY", parameters, (row, index) -> 1).isEmpty())
                reasons.add(check.description());
        }
        // Reuse current no-counter rules: a past decision cannot close a subsequently changed risk.
        var openEvents = named.queryForList(OPEN_EVENTS, parameters, String.class);
        var openCases = named.query(OPEN_CASES, parameters, (row, index) -> new OpenCase(row.getString("event_id"), row.getBoolean("no_counter_allowed")));
        if (openEvents.stream().anyMatch(event -> !noCounter.active(event))
                || openCases.stream().anyMatch(risk -> !risk.noCounterAllowed() || !noCounter.active(risk.eventId())))
            reasons.add("尚未结束的告警处置");
        return reasons;
    }

    private static String deviceScope(String device) {
        return "(EXISTS (SELECT 1 FROM device_business_scope ds WHERE ds.ops_device_id=" + device
                + " AND ds.owner_org_id=:org) OR EXISTS (SELECT 1 FROM device d WHERE d.device_id=" + device
                + " AND d.owner_org_id=:org))";
    }
    private static String targetScope(String target) {
        return "EXISTS (SELECT 1 FROM target t WHERE t.target_id=" + target + " AND t.owner_org_id=:org)";
    }
    private record Check(String description, String sql) { }
    private record OpenCase(String eventId, boolean noCounterAllowed) { }

    private static final List<Check> CHECKS = List.of(
        new Check("未删除人员（包括停用账号）", "SELECT 1 FROM app_user u WHERE u.status<>'DELETED' AND (u.org_id=:org"
                + " OR u.role_code IN (SELECT role_code FROM app_role WHERE owner_org_id=:org))"),
        new Check("未删除的下级单位", "SELECT 1 FROM app_org WHERE parent_id=:org AND deleted_at IS NULL"),
        new Check("待审批的人员登记", "SELECT 1 FROM pending_user_registration p JOIN access_change_request c ON c.change_id=p.change_id"
                + " WHERE p.org_id=:org AND c.status='PENDING'"),
        new Check("尚未结束的飞行任务", "SELECT 1 FROM flight_plan p WHERE (p.owner_org_id=:org OR p.operator_org_id=:org"
                + " OR EXISTS (SELECT 1 FROM plan_source_binding b WHERE b.binding_id=p.source_binding_id AND b.org_id=:org))"
                + " AND p.status_code NOT IN ('COMPLETED','CANCELLED')"
                + " AND NOT EXISTS (SELECT 1 FROM flight_plan_duplicate d WHERE d.duplicate_plan_id=p.plan_id)"),
        new Check("待核实、待通知或待回执的飞行风险", "SELECT 1 FROM flight_risk WHERE owner_org_id=:org AND state_code NOT IN ('EXCLUDED','ACKNOWLEDGED')"),
        new Check("尚未结束的预测风险", "SELECT 1 FROM prediction_episode p WHERE p.owner_org_id=:org"
                + " AND (p.status IN ('CANDIDATE','ACTIVE','UNKNOWN') OR (p.status='ENTERED'"
                + " AND NOT EXISTS (SELECT 1 FROM prediction_event_link l WHERE l.episode_id=p.episode_id AND l.role='OBSERVED')))"),
        new Check("待审批、执行中或结果待核查的反制", "SELECT 1 FROM disposal_authorization a WHERE (a.owner_org_id=:org OR "
                + deviceScope("a.device_id") + ") AND (a.status IN ('REQUESTED','APPROVED','EXECUTING') OR (" + DisposalSafetySql.UNCERTAIN_START + "))"),
        new Check("在途设备指令（包括停止指令）", "SELECT 1 FROM device_command c WHERE c.status IN ('QUEUED','SENT','ACCEPTED')"
                + " AND (" + deviceScope("c.device_id") + " OR EXISTS (SELECT 1 FROM disposal_authorization a"
                + " WHERE a.owner_org_id=:org AND (a.authorization_id=c.authorization_id OR EXISTS (SELECT 1 FROM countermeasure_4ch_command x WHERE x.command_id=c.command_id AND x.authorization_id=a.authorization_id)"
                + " OR EXISTS (SELECT 1 FROM lingyun_control_command x WHERE x.command_id=c.command_id AND x.authorization_id=a.authorization_id))))"),
        new Check("尚未完成停机的设备执行", "SELECT 1 FROM disposal_device_run r JOIN disposal_authorization a ON a.authorization_id=r.authorization_id"
                + " WHERE a.status='EXECUTING' AND (a.owner_org_id=:org OR " + deviceScope("r.device_id") + ")"),
        new Check("预测处置的停止结果尚未确认", "SELECT 1 FROM disposal_emergency_stop s JOIN disposal_emergency_stop_device d ON d.stop_id=s.stop_id"
                + " JOIN uav_event e ON e.event_id=s.event_id LEFT JOIN device_command c ON c.command_id=d.command_id"
                + " WHERE s.request_origin='SYSTEM_PREDICTION_STOP' AND d.stop_status<>'NOT_REQUIRED'"
                + " AND (c.command_id IS NULL OR c.status<>'SUCCEEDED') AND (e.owner_org_id=:org OR " + deviceScope("d.device_id") + ")"),
        new Check("尚未结束的光电跟踪", "SELECT 1 FROM eo_tracking_task e WHERE e.status IN ('OPEN','ENDING') AND ("
                + deviceScope("e.ops_device_id") + " OR " + targetScope("e.target_id") + ")"),
        new Check("排队或切换中的光电任务", "SELECT 1 FROM eo_dispatch_request e WHERE e.status IN ('WAITING','STOPPING') AND ("
                + deviceScope("e.device_id") + " OR " + targetScope("e.target_id") + ")"),
        new Check("尚未结束的设备调测", "SELECT 1 FROM commission_task c WHERE c.status IN ('CREATED','CONNECTING','CONNECTED','READY','RUNNING')"
                + " AND (c.management_org_id=:org OR " + deviceScope("c.device_id") + ")"),
        new Check("未办结的设备异常", "SELECT 1 FROM ops_device_maintenance_task m WHERE m.status='PENDING'"
                + " AND (m.owner_org_id=:org OR " + deviceScope("m.device_id")
                + " OR EXISTS (SELECT 1 FROM ops_maintenance_plan mp JOIN flight_plan p ON p.plan_id=mp.plan_id"
                + " WHERE mp.task_id=m.task_id AND (p.owner_org_id=:org OR p.operator_org_id=:org)))"),
        new Check("尚未完成的短信或电话通知", "SELECT 1 FROM uav_event e WHERE e.owner_org_id=:org AND ("
                + "EXISTS (SELECT 1 FROM uav_auto_sms_task s WHERE s.event_id=e.event_id AND s.status='SENDING')"
                + " OR EXISTS (SELECT 1 FROM uav_auto_voice_task v WHERE v.event_id=e.event_id AND v.status IN ('CALLING','UNKNOWN')))"),
        new Check("待回执的设备异常通知", "SELECT 1 FROM ops_device_maintenance_notice_attempt n JOIN ops_device_maintenance_task m ON m.task_id=n.task_id"
                + " WHERE n.outcome_state IN ('SUBMITTED','UNKNOWN') AND (m.owner_org_id=:org OR " + deviceScope("m.device_id") + ")"
                + " AND NOT EXISTS (SELECT 1 FROM ops_device_maintenance_notice_attempt newer WHERE newer.task_id=n.task_id AND newer.attempt_no>n.attempt_no)"),
        new Check("待办理、失败待处理或待回执的移送通知", "SELECT 1 FROM handoff h WHERE h.owner_org_id=:org"
                + " AND NOT EXISTS (SELECT 1 FROM handoff_notification_decision n WHERE n.handoff_id=h.handoff_id AND n.state='DO_NOT_NOTIFY')"
                + " AND (EXISTS (SELECT 1 FROM handoff_notification_decision n WHERE n.handoff_id=h.handoff_id AND n.state='PENDING_DECISION')"
                + " OR NOT EXISTS (SELECT 1 FROM handoff_delivery d WHERE d.handoff_id=h.handoff_id"
                + " AND NOT EXISTS (SELECT 1 FROM handoff_delivery newer WHERE newer.handoff_id=d.handoff_id AND newer.attempt_no>d.attempt_no)"
                + " AND (d.receipt_status='ACKNOWLEDGED' OR (d.delivery_status='DELIVERED' AND d.receipt_status='NOT_EXPECTED'))))"),
        new Check("尚未结束的处罚案件", "SELECT 1 FROM punishment_case WHERE owner_org_id=:org AND status NOT IN ('CLOSED','WITHDRAWN')")
    );

    // Align with AlarmAttentionSql: expiry alone never means completed/departed.
    private static final String CHANGE_AT = "COALESCE((SELECT MAX(esc.created_at) FROM alarm_escalation esc WHERE esc.alarm_id=a.alarm_id),a.received_at)";
    private static final String OPEN_CASES = "SELECT " + UavRiskCaseIdentity.ACTION_EVENT + " AS event_id,"
            + "(c.episode_id IS NULL AND (SELECT COUNT(*) FROM uav_risk_case_member cm JOIN uav_risk_case cc ON cc.risk_case_id=cm.risk_case_id"
            + " WHERE cm.event_id=" + UavRiskCaseIdentity.ACTION_EVENT + " AND cc.superseded_by IS NULL)=1) AS no_counter_allowed"
            + " FROM uav_risk_case c JOIN uav_event root_event ON root_event.event_id=c.root_event_id"
            + " LEFT JOIN prediction_episode p ON p.episode_id=c.episode_id"
            + " WHERE c.owner_org_id=:org AND c.superseded_by IS NULL"
            + " AND " + UavRiskCaseIdentity.VERIFICATION + "<>'FALSE_POSITIVE'"
            + " AND (p.status IS NULL OR p.status<>'CLEARED')"
            + " AND NOT (" + UavRiskCaseIdentity.CASE_COMPLETED + ") AND NOT (" + UavRiskCaseIdentity.CASE_DEPARTED + ")";
    private static final String OPEN_EVENTS = "SELECT e.event_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id"
            + " WHERE e.owner_org_id=:org AND e.state_code<>'FALSE_POSITIVE'"
            + " AND NOT EXISTS(SELECT 1 FROM uav_risk_case_member cm JOIN uav_risk_case cc ON cc.risk_case_id=cm.risk_case_id"
            + " WHERE cm.event_id=e.event_id AND cc.superseded_by IS NULL AND cc.owner_org_id=e.owner_org_id"
            + " AND cc.district_id=e.district_id AND cc.source_mode=a.source_mode)"
            + " AND NOT EXISTS (SELECT 1 FROM prediction_event_link pl JOIN prediction_episode pp ON pp.episode_id=pl.episode_id"
            + " WHERE pl.event_id=e.event_id AND pl.role='PREDICTED' AND pp.status='CLEARED')"
            + " AND NOT EXISTS (SELECT 1 FROM disposal_authorization da WHERE da.subject_kind='UAV_EVENT' AND da.subject_id=e.event_id"
            + " AND da.owner_org_id=e.owner_org_id AND da.district_id=e.district_id AND da.source_mode=a.source_mode"
            + " AND da.action_type IN ('COUNTERMEASURE','JAMMING') AND da.channel<>'MANUAL' AND da.status='COMPLETED'"
            + " AND da.updated_at>=" + CHANGE_AT
            + " AND NOT EXISTS (SELECT 1 FROM disposal_authorization newer WHERE newer.subject_kind=da.subject_kind AND newer.subject_id=da.subject_id"
            + " AND newer.owner_org_id=da.owner_org_id AND newer.district_id=da.district_id AND newer.source_mode=da.source_mode"
            + " AND newer.action_type IN ('COUNTERMEASURE','JAMMING') AND newer.channel<>'MANUAL'"
            + " AND (newer.requested_at>da.requested_at OR (newer.requested_at=da.requested_at AND newer.authorization_id>da.authorization_id))))"
            + " AND NOT (e.state_code='CONFIRMED' AND EXISTS (SELECT 1 FROM uav_auto_voice_task v WHERE v.event_id=e.event_id"
            + " AND v.departure_evaluated_at>=CAST(EXTRACT(EPOCH FROM " + CHANGE_AT + ")*1000 AS BIGINT))"
            + " AND NOT EXISTS (SELECT 1 FROM rule_evaluation r WHERE r.target_id=a.target_id AND r.owner_org_id=e.owner_org_id"
            + " AND r.district_id=e.district_id AND r.source_mode=a.source_mode AND r.mode='ACTIVE' AND r.subject_kind='TARGET'"
            + " AND r.evaluated_at<=:now AND r.legal_status='ILLEGAL' AND NOT EXISTS (SELECT 1 FROM rule_evaluation nr"
            + " WHERE nr.target_id=r.target_id AND nr.owner_org_id=r.owner_org_id AND nr.district_id=r.district_id"
            + " AND nr.source_mode=r.source_mode AND nr.mode='ACTIVE' AND nr.subject_kind='TARGET' AND nr.evaluated_at<=:now"
            + " AND (nr.evaluated_at>r.evaluated_at OR (nr.evaluated_at=r.evaluated_at AND nr.evaluation_id>r.evaluation_id)))))";
}
