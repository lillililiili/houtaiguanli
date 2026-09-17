package com.uav.lowaltitude.modules.alarm.api;

import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;

/** 隔离测试的明确计划/飞手关联，不改共享演示目录。 */
final class DirectoryAdvisoryFixture {
 static String create(JdbcTemplate jdbc,String org,String district) {
  String contact=UUID.randomUUID().toString(),plan=UUID.randomUUID().toString();
  jdbc.update("INSERT INTO business_contact(contact_id,org_id,name,roles,phone,enabled,verified_at,verification_basis,created_at,updated_at,version) VALUES(?,?,?,CAST('[\"PILOT\"]' AS JSON),?,TRUE,?,'隔离测试核验',0,0,0)",contact,org,"测试关联飞手","13800138000",System.currentTimeMillis()-1000);
  jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_id,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version,operator_org_id,pilot_contact_id) SELECT ?,?,'VALID',source_id,'mock',?, ?,route_version_id,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,?,? FROM flight_plan WHERE plan_id='seed-stage3-plan-legal'",plan,"AUTO-DIRECTORY-"+plan,Timestamp.from(Instant.now().minusSeconds(60)),Timestamp.from(Instant.now().plusSeconds(3600)),org,district,org,contact);
  jdbc.update("UPDATE notification_setting SET enabled=TRUE,channel_type='MOCK' WHERE setting_id IN('advisory-sms','advisory-voice')");
  return plan;
 }
 static void evaluation(JdbcTemplate jdbc,String event,String target,String plan,String org,String district,Instant at) {
  var version=jdbc.queryForMap("SELECT rule_set_id,rule_set_version_id FROM rule_set_version FETCH FIRST 1 ROWS ONLY");
  String run=UUID.randomUUID().toString();Timestamp time=Timestamp.from(at);
  jdbc.update("INSERT INTO rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,source_mode,created_at) VALUES(?,?,?,'ACTIVE','SCHEDULED',?,?,'DONE','mock',?)",run,version.get("rule_set_id"),version.get("rule_set_version_id"),time,time,time);
  String alarm=jdbc.queryForObject("SELECT alarm_id FROM uav_event WHERE event_id=?",String.class,event);
  jdbc.update("INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,plan_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at) VALUES(?,?,?,'ACTIVE','TARGET',?,?,?,?,?,'FRESH','FULL','ILLEGAL',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,'mock',?)",UUID.randomUUID().toString(),run,version.get("rule_set_version_id"),target,plan,time,time,time,alarm,org,district,time);
 }
}
