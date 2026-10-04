package com.uav.lowaltitude.modules.alarm.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Appends immutable test evaluations; works with PostgreSQL's append-only safeguards enabled. */
final class NoCounterEvidenceFixture {
    static String append(JdbcTemplate jdbc,String eventId,String legal,String reasons,String unknown,String mode,Instant evaluated,Instant observed) {
        String template=jdbc.queryForObject("SELECT r.evaluation_id FROM rule_evaluation r JOIN alarm a ON a.target_id=r.target_id JOIN uav_event e ON e.alarm_id=a.alarm_id WHERE e.event_id=? ORDER BY r.evaluated_at DESC,r.evaluation_id DESC FETCH FIRST 1 ROWS ONLY",String.class,eventId);
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons) "
                +"SELECT ?,run_id,rule_set_version_id,'ACTIVE','TARGET',target_id,?,?,?,'FRESH','FULL',?,CAST(? AS JSON),CAST('[]' AS JSON),CAST(? AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),NULL,owner_org_id,district_id,?,?, 'SUFFICIENT','test-v1',CAST('[]' AS JSON) FROM rule_evaluation WHERE evaluation_id=?",
                id,Timestamp.from(observed),Timestamp.from(evaluated),Timestamp.from(evaluated),legal,reasons,unknown,mode,Timestamp.from(evaluated),template);
        return id;
    }
}
