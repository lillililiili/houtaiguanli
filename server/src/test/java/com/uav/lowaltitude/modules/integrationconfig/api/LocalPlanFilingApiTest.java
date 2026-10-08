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
  // A task carrying its own pilot and reporting unit says which permission the archive step needs.
  var carriedBody=plan("directory-permission-carried");carriedBody.put("filing",carried(filing(false),"13800000000"));
  assertThat(error("/plans",carriedBody,403)).contains("单位管理权限");
 }
 @org.springframework.beans.factory.annotation.Autowired com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService notifications;
 @Test void carriedPilotAndReportingUnitAreFoundOrCreatedAndLinked() throws Exception {
  long orgs=count("app_org"),contacts=count("business_contact"),bindings=count("plan_source_binding");
  var body=plan("carried-first");body.put("filing",carried(filing(false),"138 0000 0000"));
  String id=send("/plans",body,200).path("subject_id").asText();
  var subjects=read("/api/v1/flight-plans/"+id+"/subjects");
  assertThat(subjects.path("association_status").asText()).isEqualTo("LINKED");
  assertThat(subjects.path("pilot_name").asText()).isEqualTo("模拟申报飞手");
  assertThat(subjects.path("operator_org_name").asText()).isEqualTo("D2 模拟运营单位");
  assertThat(subjects.path("reporting_org_name").asText()).isEqualTo("D2 模拟报送单位");
  var pilot=jdbc.queryForMap("select * from business_contact where contact_id=?",subjects.path("pilot_contact_id").asText());
  assertThat(pilot.get("verified_at")).isNotNull();
  assertThat((String)pilot.get("verification_basis")).startsWith("随上级任务下发：").contains(read("/api/v1/flight-plans/"+id).path("plan_no").asText());
  assertThat(jdbc.queryForObject("select org_code from app_org where org_id=?",String.class,subjects.path("reporting_org_id").asText())).isEqualTo("D2-REPORTING");
  assertThat(List.of(count("app_org"),count("business_contact"),count("plan_source_binding"))).containsExactly(orgs+2,contacts+1,bindings+1);
  // A later task with the same pilot (phone written differently) and the same reporting code reuses both records.
  var second=plan("carried-second");second.put("uav_sn","SIM-INPUT-D2");second.put("filing",carried(filing(false),"138-0000-0000"));
  var again=read("/api/v1/flight-plans/"+send("/plans",second,200).path("subject_id").asText()+"/subjects");
  assertThat(again.path("pilot_contact_id").asText()).isEqualTo(subjects.path("pilot_contact_id").asText());
  assertThat(again.path("source_binding_id").asText()).isEqualTo(subjects.path("source_binding_id").asText());
  assertThat(List.of(count("app_org"),count("business_contact"),count("plan_source_binding"))).containsExactly(orgs+2,contacts+1,bindings+1);
  // The pilot contact itself no longer blocks the advisory SMS for the task.
  var target=notifications.forPilotPlan("ADVISORY_SMS",id);
  assertThat(target.contactId()).isEqualTo(subjects.path("pilot_contact_id").asText());
  assertThat(Objects.toString(target.blockedReason(),"")).doesNotContain("飞手");
 }
 @Test void carriedPilotNeedsNameAndUnitAndSelectedArchivesStillWin() throws Exception {
  var body=plan("carried-check");
  var data=carried(filing(false),"13800000000");data.remove("pilot_name");body.put("filing",data);
  assertThat(error("/plans",body,400)).contains("飞手姓名");
  data=carried(filing(false),"13800000000");data.remove("operator_name");body.put("filing",data);
  assertThat(error("/plans",body,400)).contains("申报单位名称");
  data=carried(filing(false),"13800000000");data.remove("reporting_org_code");body.put("filing",data);
  assertThat(error("/plans",body,400)).contains("编码");
  data=carried(filing(false),"call me");body.put("filing",data);
  assertThat(error("/plans",body,400)).isEqualTo("飞手手机号格式不正确");
  var linked=filing(true);long contacts=count("business_contact");
  linked.put("pilot_phone","13900000000");body.put("filing",linked);
  String id=send("/plans",body,200).path("subject_id").asText();
  assertThat(read("/api/v1/flight-plans/"+id+"/subjects").path("pilot_name").asText()).isEqualTo("测试飞手档案");
  assertThat(count("business_contact")).isEqualTo(contacts);
 }
 @Test void sameNamedOperatorUnitsAreNotGuessedAndDisabledPilotIsReported() throws Exception {
  var data=carried(filing(false),"13700000000");
  for(int i=0;i<2;i++)write("/api/v1/organization-profiles",Map.of("name","D2 模拟运营单位","organization_type","OPERATOR"));
  var body=plan("carried-ambiguous");body.put("filing",data);
  assertThat(error("/plans",body,409)).contains("不止一个");
  String org=jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'",String.class);
  var pilot=write("/api/v1/contacts",Map.of("org_id",org,"name","停用飞手","roles",List.of("PILOT"),"phone","13700000000","enabled",false));
  data.put("operator_org_id",org);body.put("filing",data);body.put("message_id","carried-disabled");
  assertThat(error("/plans",body,409)).contains("不可用");
  assertThat(jdbc.queryForObject("select count(*) from business_contact where phone='13700000000'",Long.class)).isEqualTo(1);
  assertThat(pilot.path("enabled").asBoolean()).isFalse();
 }
 Map<String,Object> carried(Map<String,Object> data,String phone){
  data.put("operator_name","D2 模拟运营单位");data.put("pilot_phone",phone);data.put("reporting_org_code","D2-REPORTING");data.put("reporting_org_name","D2 模拟报送单位");return data;
 }
 long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
 String error(String path,Object body,int expected) throws Exception {
  return json.readTree(mvc.perform(post(BASE+path).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("error").path("message").asText();
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
