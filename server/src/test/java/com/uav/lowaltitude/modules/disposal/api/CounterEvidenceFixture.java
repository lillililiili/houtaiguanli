package com.uav.lowaltitude.modules.disposal.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 隔离测试前置依据；不创建人工观察、授权或执行结果。 */
public final class CounterEvidenceFixture {
    public static void seed(JdbcTemplate jdbc, String eventId) {
        var event = jdbc.queryForMap("select e.alarm_id,e.owner_org_id,e.district_id,a.target_id from uav_event e join alarm a on a.alarm_id=e.alarm_id where e.event_id=?", eventId);
        var at = Timestamp.from(Instant.now());
        String target = (String) event.get("target_id");
        if (target == null) {
            target = UUID.randomUUID().toString();
            jdbc.update("insert into target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'UAV','mock',?,?,?,?,0)",
                    target, target, event.get("owner_org_id"), event.get("district_id"), at, at);
            jdbc.update("update alarm set target_id=? where alarm_id=?", target, event.get("alarm_id"));
        }
        if (jdbc.queryForObject("select count(*) from target_latest_state where target_id=?", Integer.class, target) == 0)
            jdbc.update("insert into target_latest_state(target_id,observed_at,received_at,created_at,updated_at,unknown_fields) values(?,?,?,?,?,CAST('[]' AS JSON))",
                    target, at, at, at, at);
        else jdbc.update("update target_latest_state set observed_at=?,received_at=? where target_id=?", at, at, target);
        var version = ensureFreshSeconds(jdbc, at);
        String run = UUID.randomUUID().toString();
        jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,source_mode,created_at) values(?,?,?,'ACTIVE','SCHEDULED',?,?,'DONE','mock',?)",
                run, version.get("rule_set_id"), version.get("rule_set_version_id"), at, at, at);
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons) values(?,?,?,'ACTIVE','TARGET',?,?,?,?,'FRESH','FULL','ILLEGAL',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,'mock',?,'SUFFICIENT','test-v1',CAST('[]' AS JSON))",
                UUID.randomUUID().toString(), run, version.get("rule_set_version_id"), target, at, at, at, event.get("alarm_id"), event.get("owner_org_id"), event.get("district_id"), at);
    }

    /**
     * 反制资格读取生效规则集的 C03.fresh_seconds。隔离 PostgreSQL schema 只有空间风险规则集、没有该参数时，
     * 必须自备一条，否则会在真正写入观测/研判之前就被拒成“缺少时效配置”。
     */
    private static Map<String, Object> ensureFreshSeconds(JdbcTemplate jdbc, Timestamp at) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT s.rule_set_id, p.rule_set_version_id FROM rule_param p JOIN rule_set s ON s.active_version_id=p.rule_set_version_id WHERE p.rule_code='C03' AND p.param_key='fresh_seconds'");
        if (!existing.isEmpty()) return existing.get(0);
        String setId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        jdbc.update("insert into rule_set(rule_set_id,rule_set_code,name,created_at,updated_at) values(?,?,?,?,?)",
                setId, "COUNTER-EVIDENCE-" + setId.substring(0, 8), "隔离测试反制依据", at, at);
        jdbc.update("insert into rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,source_mode,created_at,published_at) values(?,?,1,'PUBLISHED','DEMO',?,'mock',?,?)",
                versionId, setId, at, at, at);
        jdbc.update("update rule_set set active_version_id=? where rule_set_id=?", versionId, setId);
        jdbc.update("insert into rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,param_status) values(?,?,'C03','fresh_seconds','86400','INTEGER','DEMO')",
                versionId + ":C03:fresh_seconds", versionId);
        return Map.of("rule_set_id", setId, "rule_set_version_id", versionId);
    }
}
