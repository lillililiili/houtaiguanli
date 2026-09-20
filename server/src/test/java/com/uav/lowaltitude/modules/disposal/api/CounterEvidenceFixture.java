package com.uav.lowaltitude.modules.disposal.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 隔离测试前置依据；不创建人工观察、授权或执行结果。 */
final class CounterEvidenceFixture {
    static void seed(JdbcTemplate jdbc,String eventId) {
        var event=jdbc.queryForMap("select e.alarm_id,e.owner_org_id,e.district_id,a.target_id from uav_event e join alarm a on a.alarm_id=e.alarm_id where e.event_id=?",eventId);
        var at=Timestamp.from(Instant.now());
        String target=(String)event.get("target_id");
        if(target==null) {
            target=UUID.randomUUID().toString();
            jdbc.update("insert into target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'UAV','mock',?,?,?,?,0)",target,target,event.get("owner_org_id"),event.get("district_id"),at,at);
            jdbc.update("update alarm set target_id=? where alarm_id=?",target,event.get("alarm_id"));
        }
        if(jdbc.queryForObject("select count(*) from target_latest_state where target_id=?",Integer.class,target)==0)
            jdbc.update("insert into target_latest_state(target_id,observed_at,received_at,created_at,updated_at,unknown_fields) values(?,?,?,?,?,CAST('[]' AS JSON))",target,at,at,at,at);
        else jdbc.update("update target_latest_state set observed_at=? where target_id=?",at,target);
        var version=jdbc.queryForMap("select rule_set_id,rule_set_version_id from rule_set_version fetch first 1 rows only");
        String run=UUID.randomUUID().toString();
        jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,source_mode,created_at) values(?,?,?,'ACTIVE','SCHEDULED',?,?,'DONE','mock',?)",run,version.get("rule_set_id"),version.get("rule_set_version_id"),at,at,at);
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons) values(?,?,?,'ACTIVE','TARGET',?,?,?,?,'FRESH','FULL','ILLEGAL',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,'mock',?,'SUFFICIENT','test-v1',CAST('[]' AS JSON))",UUID.randomUUID().toString(),run,version.get("rule_set_version_id"),target,at,at,at,event.get("alarm_id"),event.get("owner_org_id"),event.get("district_id"),at);
    }
}
