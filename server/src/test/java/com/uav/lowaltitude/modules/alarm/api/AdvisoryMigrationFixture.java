package com.uav.lowaltitude.modules.alarm.api;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 独立升级 schema 的历史模拟记录，验证迁移不改写旧内容/时间/关联。 */
final class AdvisoryMigrationFixture {
    private AdvisoryMigrationFixture() { }
    static Map<String,Object> seed(JdbcTemplate jdbc,String schema) {
        jdbc.update("INSERT INTO "+schema+".app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES('upgrade-org','upgrade-org','隔离升级机构',TRUE,1,1,0)");
        jdbc.update("INSERT INTO "+schema+".app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES('upgrade-district','upgrade-district','隔离升级区域',TRUE,1,1,0)");
        jdbc.update("INSERT INTO "+schema+".app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('UPGRADE_ROLE','UPGRADE_ROLE','',FALSE,TRUE,1,1,0,FALSE)");
        jdbc.update("INSERT INTO "+schema+".app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES('upgrade-user','upgrade-user','历史模拟记录员','UPGRADE_ROLE','ACTIVE','unused',0,'NONE',0,1,1,0)");
        jdbc.update("INSERT INTO "+schema+".integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES('upgrade-source','upgrade-source','历史模拟源',TRUE,'mock',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)");
        jdbc.update("INSERT INTO "+schema+".alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES('upgrade-alarm','upgrade-source','upgrade-source-alarm','UAV_INTRUSION','HIGH',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'mock','upgrade-org','upgrade-district',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO "+schema+".uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES('upgrade-event','upgrade-alarm','CONFIRMED','upgrade-org','upgrade-district',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1)");
        jdbc.update("INSERT INTO "+schema+".uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,urgent,simulated,delivery_status) VALUES('upgrade-record','upgrade-event',1,'SMS_SIMULATED',1700000000000,'upgrade-user','历史模拟飞手','历史模拟接收端','迁移前已存在的模拟短信，必须完整保留',FALSE,TRUE,'SIMULATED_DELIVERED')");
        return snapshot(jdbc,schema);
    }
    static Map<String,Object> snapshot(JdbcTemplate jdbc,String schema) {
        return jdbc.queryForMap("SELECT record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,simulated,delivery_status FROM "+schema+".uav_event_advisory WHERE record_id='upgrade-record'");
    }
}
