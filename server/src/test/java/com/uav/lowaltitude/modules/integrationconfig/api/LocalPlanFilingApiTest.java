package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.JsonNode;

/** Reuses authenticated simulated-plan setup; all writes roll back per test. */
class LocalPlanFilingApiTest extends LocalInterfaceSimulatorApiTest {
 @Test void dedicatedSimulatorSourceIsRegisteredOnWriteAndNeverReenabledByInput() throws Exception {
  String source=com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos.SIMULATOR_SOURCE_ID;
  long count=jdbc.queryForObject("select count(*) from integration_source",Long.class);
  read(BASE+"/plan-options");assertThat(jdbc.queryForObject("select count(*) from integration_source",Long.class)).isEqualTo(count);
  var data=filing(false);data.put("source_id",source);var body=plan("dedicated-source");body.put("filing",data);
  String id=send("/plans",body,200).path("subject_id").asText();
  assertThat(read("/api/v1/flight-plans/"+id).path("source").path("source_name").asText()).isEqualTo("数据模拟器（飞行任务）");
  jdbc.update("update integration_source set enabled=false where source_id=?",source);
  body.put("message_id","disabled-dedicated-source");send("/plans",body,409);
  assertThat(jdbc.queryForObject("select enabled from integration_source where source_id=?",Boolean.class,source)).isFalse();
 }
 @Test void completeFilingIsReturnedByBusinessDetailAndSubjects() throws Exception {
  var body=plan("filing-complete");body.put("filing",filing(true));
  String id=send("/plans",body,200).path("subject_id").asText();
  var detail=read("/api/v1/flight-plans/"+id);
  assertThat(detail.path("filing").path("operator_name").asText()).isEqualTo("模拟申报单位");
  assertThat(detail.path("filing").path("pilot_name").asText()).isEqualTo("模拟申报飞手");
  assertThat(detail.path("filing").path("takeoff_longitude").asDouble()).isEqualTo(118.25);
  assertThat(detail.path("filing").path("landing_site_name").asText()).isEqualTo("模拟降落点");
  assertThat(detail.path("source").path("source_id").asText()).isNotBlank();
  var subjects=read("/api/v1/flight-plans/"+id+"/subjects");
  assertThat(subjects.path("association_status").asText()).isEqualTo("LINKED");
  assertThat(subjects.path("pilot_name").asText()).isEqualTo("测试飞手档案");
  assertThat(send("/plans",body,200).path("subject_id").asText()).isEqualTo(id);
 }
 @Test void historicalSupplementPreservesOriginalFactsAndChecksVersionAndReplay() throws Exception {
  var body=plan("filing-old");String id=send("/plans",body,200).path("subject_id").asText();
  var before=read("/api/v1/flight-plans/"+id);
  var update=new HashMap<String,Object>();update.put("message_id","supplement-one");update.put("expected_version",before.path("version").asLong());update.put("filing",filing(false));
  send("/plans/"+id+"/filing",update,200);
  send("/plans/"+id+"/filing",update,200);
  var after=read("/api/v1/flight-plans/"+id);
  for(String field:List.of("start_at","end_at","status_code","uav_sn","route"))assertThat(after.path(field)).isEqualTo(before.path(field));
  assertThat(after.path("filing").path("takeoff_site_name").asText()).isEqualTo("模拟起飞点");
  update.put("message_id","supplement-stale");send("/plans/"+id+"/filing",update,409);
  assertThat(send("/plans",body,200).path("subject_id").asText()).isEqualTo(id);
 }
 @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 void incompleteCoordinatesAreRejectedWithoutCreatingPlan() throws Exception {
  var body=plan("filing-bad-coordinates");var data=filing(false);data.remove("takeoff_latitude");body.put("filing",data);
  long before=jdbc.queryForObject("select count(*) from flight_plan",Long.class);send("/plans",body,400);
  assertThat(jdbc.queryForObject("select count(*) from flight_plan",Long.class)).isEqualTo(before);
 }
 @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 void invalidPilotRollsBackFilingAndMessage() throws Exception {
  var body=plan("filing-bad-pilot");var data=filing(true);jdbc.update("update business_contact set enabled=false where contact_id=?",data.get("pilot_contact_id"));body.put("filing",data);
  long before=jdbc.queryForObject("select count(*) from flight_plan",Long.class);send("/plans",body,409);
  assertThat(jdbc.queryForObject("select count(*) from flight_plan",Long.class)).isEqualTo(before);
  assertThat(jdbc.queryForObject("select count(*) from local_interface_message where external_id='filing-bad-pilot'",Long.class)).isZero();
 }
 @Test void livePlanCannotBeSupplemented() throws Exception {
  String id=send("/plans",plan("filing-live"),200).path("subject_id").asText();
  jdbc.update("update flight_plan set source_mode='live' where plan_id=?",id);
  send("/plans/"+id+"/filing",Map.of("message_id","live-edit","expected_version",0,"filing",filing(false)),409);
 }
 @Test void planOptionsOnlyExposeEnabledSimulatedSources() throws Exception {
  var options=read(BASE+"/plan-options");assertThat(options.path("plan_sources").isArray()).isTrue();
  for(var source:options.path("plan_sources"))assertThat(source.path("source_mode").asText()).isIn("mock","replay");
 }
 @Test void liveOrDisabledSourceCannotBeUsedAndExistingSourceCannotBeReplaced() throws Exception {
  var data=filing(false);String source=(String)data.get("source_id");var body=plan("source-guard");body.put("filing",data);
  jdbc.update("update integration_source set enabled=false where source_id=?",source);send("/plans",body,409);
  jdbc.update("update integration_source set enabled=true,source_mode='live' where source_id=?",source);send("/plans",body,409);
 }
 @Test void supplementCannotClearExistingSource() throws Exception {
  var data=filing(false);var body=plan("source-immutable");body.put("filing",data);String id=send("/plans",body,200).path("subject_id").asText();
  long version=read("/api/v1/flight-plans/"+id).path("version").asLong();data.remove("source_id");
  send("/plans/"+id+"/filing",Map.of("message_id","source-clear","expected_version",version,"filing",data),409);
 }
 @Test void interfaceOperatorWithoutDirectoryAuthorizationCannotAssociateArchives() throws Exception {
  var data=filing(true);var body=plan("directory-permission");body.put("filing",data);
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-FILING-OP','模拟录入','',FALSE,TRUE,0,0,0,FALSE)");
  jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-FILING-OP','interfaces','OP',TRUE),('ROLE-FILING-OP','flight:read','OP',FALSE),('ROLE-FILING-OP','route:read','OP',FALSE)");
  jdbc.update("UPDATE app_user SET role_code='ROLE-FILING-OP' WHERE account='admin1'");
  send("/plans",plan("operator-basic-plan"),200);
  send("/plans",body,403);
 }
 Map<String,Object> filing(boolean linked) throws Exception {
  String source=jdbc.queryForObject("select source_id from integration_source where enabled=true and source_mode in ('mock','replay') fetch first 1 rows only",String.class);
  var data=new HashMap<String,Object>();data.put("source_id",source);data.put("operator_name","模拟申报单位");data.put("pilot_name","模拟申报飞手");data.put("takeoff_site_name","模拟起飞点");data.put("landing_site_name","模拟降落点");data.put("takeoff_longitude",118.25);data.put("takeoff_latitude",37.25);data.put("landing_longitude",118.26);data.put("landing_latitude",37.26);
  if(linked){String org=jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'",String.class);
   var pilot=write("/api/v1/contacts",Map.of("org_id",org,"name","测试飞手档案","roles",List.of("PILOT"),"enabled",true));
   var binding=write("/api/v1/plan-source-bindings",Map.of("source_id",source,"org_id",org,"external_org_code",UUID.randomUUID().toString(),"enabled",true));
   data.put("operator_org_id",org);data.put("pilot_contact_id",pilot.path("contact_id").asText());data.put("source_binding_id",binding.path("binding_id").asText());
  }return data;
 }
 JsonNode read(String path) throws Exception {return json.readTree(mvc.perform(get(path).header("Authorization",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
 JsonNode write(String path,Object body) throws Exception {return json.readTree(mvc.perform(post(path).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
}
