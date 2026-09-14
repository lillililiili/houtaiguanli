package com.uav.lowaltitude.modules.punishment.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** 处罚案件用例的共用夹具：造一条"已核实 + 已完成处置 + 已移送"的完整链路，直到可以立案为止。 */
final class PunishmentFixture {
    static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    private final JdbcTemplate jdbc;

    PunishmentFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 返回一条可立案的处罚交接 id。 */
    String punishmentHandoff() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alarmId = "pc-alarm-" + suffix, eventId = "pc-event-" + suffix, handoffId = "pc-handoff-" + suffix;
        String recipientId = "pc-recipient-" + suffix;
        Timestamp at = Timestamp.from(Instant.parse("2026-09-08T02:00:00Z"));
        String admin = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select 'pc-src','PC-TEST','处罚案件测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='pc-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'pc-src',?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarmId, "告警-案件-" + suffix, at, at, ORG, DISTRICT, at);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'CONFIRMED',?,?,?,?,1)", eventId, alarmId, ORG, DISTRICT, at, at);
        jdbc.update("insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at)"
                + " values (?,?,'UAV_PUNISHMENT',true,?,?)", recipientId, "演示处罚接收方", at, at);
        jdbc.update("insert into handoff (handoff_id,source_kind,source_id,risk_id,event_id,handoff_type,recipient_id,"
                + "source_version,owner_org_id,district_id,source_mode,submitted_by,created_at)"
                + " values (?,'UAV_EVENT',?,null,?,'UAV_PUNISHMENT',?,1,?,?,'live',?,?)",
                handoffId, eventId, eventId, recipientId, ORG, DISTRICT, admin, at);
        return handoffId;
    }

    String[] user(String tag, List<String> permissions, boolean otherScope) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-PC-" + tag + "-" + suffix;
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,?,false,current_timestamp)", role, permission,
                    permission.endsWith(":read") ? "READ" : "OP");
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "pc-" + tag.toLowerCase() + "-" + suffix, "案件" + tag, role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId,
                otherScope ? "seed-stage3-other-org" : ORG, otherScope ? "seed-stage3-other-district" : DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, userId, System.currentTimeMillis() + 3_600_000);
        return new String[]{token, userId};
    }

    void cleanup() {
        jdbc.update("delete from penalty_decision_document where case_id in (select case_id from punishment_case where event_id like 'pc-event-%')");
        jdbc.update("delete from penalty_discretion where case_id in (select case_id from punishment_case where event_id like 'pc-event-%')");
        jdbc.update("delete from punishment_review where case_id in (select case_id from punishment_case where event_id like 'pc-event-%')");
        jdbc.update("delete from punishment_case_lead where case_id in (select case_id from punishment_case where event_id like 'pc-event-%')");
        jdbc.update("delete from punishment_case_event where case_id in (select case_id from punishment_case where event_id like 'pc-event-%')");
        jdbc.update("delete from punishment_case where event_id like 'pc-event-%'");
        jdbc.update("delete from handoff_delivery where handoff_id like 'pc-handoff-%'");
        jdbc.update("delete from handoff_material_snapshot where handoff_id like 'pc-handoff-%'");
        jdbc.update("delete from handoff where handoff_id like 'pc-handoff-%'");
        jdbc.update("delete from handoff_recipient where recipient_id like 'pc-recipient-%'");
        jdbc.update("delete from uav_event where event_id like 'pc-event-%'");
        jdbc.update("delete from alarm where alarm_id like 'pc-alarm-%'");
        // 自己造的会话/范围/授权也要清：同一缓存上下文里后跑的用例会数 app_role_permission
        // （如 DeviceBusinessScopeTest "除管理员外无人持有 handoff:*"），留着就是把夹具冒充成产品授权。
        // app_user / app_role 行不删：审计与案件事件表以 FK 引用这些用户，删了会撞 FK；空角色与无会话的用户对任何断言都是惰性的。
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where role_code like 'ROLE-PC-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where role_code like 'ROLE-PC-%')");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-PC-%'");
    }
}
