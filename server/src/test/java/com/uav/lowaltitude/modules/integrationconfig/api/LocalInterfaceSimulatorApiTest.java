package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.hamcrest.Matchers.hasItem;

@SpringBootTest(properties="app.handoff.channel=mock") @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class LocalInterfaceSimulatorApiTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
 @Autowired com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort channel;
 String token;
 static final String BASE="/api/v1/local-interface-simulator";
 @BeforeEach void login() throws Exception { token="Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText(); }
 @Test void planInputPersistsAndReplaysButConflictingMessageCannotOverwrite() throws Exception {
  var body=plan("input-plan-one");
  var first=send("/plans",body,200);String id=first.path("subject_id").asText();
  assertThat(id).isNotBlank();assertThat(first.path("result").path("source_mode").asText()).isEqualTo("mock");
  assertThat(send("/plans",body,200).path("subject_id").asText()).isEqualTo(id);
  body.put("uav_sn","CHANGED");send("/plans",body,409);
  assertThat(jdbc.queryForObject("select count(*) from flight_plan where plan_id=?",Long.class,id)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select status_code from flight_plan where plan_id=?",String.class,id)).isEqualTo("PENDING");
 }
 @Test void sameFlightWithNewMessageAndLifecycleStatusReusesOriginalWithoutRewritingHistory() throws Exception {
  for(String mode:List.of("mock","replay")){
   var body=plan("dedup-"+UUID.randomUUID());body.put("source_mode",mode);body.put("status_code","PENDING");
   var original=send("/plans",body,200);String id=original.path("subject_id").asText();
   var oldRows=jdbc.queryForList("select * from flight_plan where plan_id=?",id);
   for(String state:List.of("EXECUTING","COMPLETED")){
    body.put("message_id","retry-"+UUID.randomUUID());body.put("status_code",state);
    assertThat(send("/plans",body,200).path("subject_id").asText()).isEqualTo(id);
   }
   assertThat(jdbc.queryForList("select * from flight_plan where plan_id=?",id)).isEqualTo(oldRows);
   assertThat(jdbc.queryForObject("select count(*) from flight_plan where uav_sn=? and source_mode=?",Long.class,body.get("uav_sn"),mode)).isEqualTo(1);
   body.put("status_code","PENDING");body.put("message_id","other-"+UUID.randomUUID());body.put("uav_sn","OTHER-"+UUID.randomUUID());
   assertThat(send("/plans",body,200).path("subject_id").asText()).isNotEqualTo(id);
  }
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"mock,false", "replay,false", "live,true"})
 void equivalentPlanWithNewMessageRechecksSourceAvailability(String mode,boolean enabled) throws Exception {
  String source=UUID.randomUUID().toString();
  jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values(?,?,?,true,'mock',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",source,source,"Plan source fixture");
  var body=plan("source-guard-"+UUID.randomUUID());body.put("filing",Map.of("source_id",source));
  String id=send("/plans",body,200).path("subject_id").asText();
  var original=jdbc.queryForList("select * from flight_plan where plan_id=?",id);
  jdbc.update("update integration_source set enabled=?,source_mode=? where source_id=?",enabled,mode,source);
  body.put("message_id","source-retry-"+UUID.randomUUID());
  send("/plans",body,409);
  assertThat(jdbc.queryForObject("select count(*) from local_interface_message where external_id=?",Long.class,body.get("message_id"))).isZero();
  assertThat(jdbc.queryForList("select * from flight_plan where plan_id=?",id)).isEqualTo(original);
  assertThat(jdbc.queryForObject("select enabled from integration_source where source_id=?",Boolean.class,source)).isEqualTo(enabled);
  jdbc.update("update integration_source set enabled=true,source_mode='mock' where source_id=?",source);
  assertThat(send("/plans",body,200).path("subject_id").asText()).isEqualTo(id);
  assertThat(jdbc.queryForList("select * from flight_plan where plan_id=?",id)).isEqualTo(original);
 }
 @Test void duplicateRegistryExcludesOnlyDuplicateFromListButPreservesItsDetail() throws Exception {
  var body=plan("canonical-"+UUID.randomUUID());body.put("uav_sn","UAV-"+UUID.randomUUID());
  String first=send("/plans",body,200).path("subject_id").asText();
  body.put("message_id","different-"+UUID.randomUUID());body.put("end_at",((Number)body.get("end_at")).longValue()+1000);
  String duplicate=send("/plans",body,200).path("subject_id").asText();
  jdbc.update("insert into flight_plan_duplicate(duplicate_plan_id,canonical_plan_id,reason) values(?,?,?)",duplicate,first,"TEST_FIXTURE");
  mvc.perform(get("/api/v1/flight-plans").param("uav_sn",body.get("uav_sn").toString()).header("Authorization",token))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
   .andExpect(jsonPath("$.data.items[0].plan_id").value(first));
  mvc.perform(get("/api/v1/flight-plans/"+duplicate).header("Authorization",token)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("select count(*) from flight_plan where uav_sn=?",Long.class,body.get("uav_sn"))).isEqualTo(2);
 }
 @Test void repairLinksOnlyEquivalentSimulatorPlansAndKeepsOriginalRowsAndReceipts() throws Exception {
  String source="local-flight-plan-simulator";
  jdbc.update("insert into integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) select ?,?,'Test simulator',true,'mock',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0 where not exists(select 1 from integration_source where source_id=?)",source,source,source);
  for(String mode:List.of("mock","replay")){
   var body=plan("sim-map-plan-"+UUID.randomUUID());body.put("source_mode",mode);body.put("uav_sn","UAV-"+UUID.randomUUID());body.put("status_code","PENDING");
   String original=send("/plans",body,200).path("subject_id").asText();
   jdbc.update("update flight_plan set source_id=? where plan_id=?",source,original);
   var row=jdbc.queryForMap("select * from flight_plan where plan_id=?",original);
   var receipt=jdbc.queryForMap("select * from local_interface_message where subject_id=?",original);
   String duplicate=UUID.randomUUID().toString();
   row.put("plan_id",duplicate);row.put("plan_no","LEGACY-"+duplicate);
   row.put("created_at",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(1)));
   insertFixture("flight_plan",row);
   body.put("message_id","sim-map-plan-"+UUID.randomUUID());body.put("status_code","COMPLETED");
   receipt.put("message_id",UUID.randomUUID().toString());receipt.put("external_id",body.get("message_id"));receipt.put("subject_id",duplicate);
   receipt.put("payload",json.writeValueAsString(body));insertFixture("local_interface_message",receipt);
   String unrelated=UUID.randomUUID().toString();row.put("plan_id",unrelated);row.put("plan_no","OTHER-"+unrelated);row.put("uav_sn","OTHER-"+unrelated);insertFixture("flight_plan",row);
   var beforePlans=jdbc.queryForList("select * from flight_plan order by plan_id");
   var beforeReceipts=jdbc.queryForList("select * from local_interface_message order by message_id");
   for(int attempt=0;attempt<2;attempt++)jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
    var context=org.mockito.Mockito.mock(org.flywaydb.core.api.migration.Context.class);
    org.mockito.Mockito.when(context.getConnection()).thenReturn(connection);
    try{new db.migration.V202610060005__repair_duplicate_simulator_plans().migrate(context);}catch(Exception e){throw new IllegalStateException(e);}
    return null;
   });
   assertThat(jdbc.queryForList("select canonical_plan_id from flight_plan_duplicate where duplicate_plan_id=?",String.class,duplicate)).containsExactly(original);
   assertThat(jdbc.queryForObject("select count(*) from flight_plan_duplicate where duplicate_plan_id=?",Long.class,unrelated)).isZero();
   assertThat(jdbc.queryForList("select * from flight_plan order by plan_id")).isEqualTo(beforePlans);
   assertThat(jdbc.queryForList("select * from local_interface_message order by message_id")).isEqualTo(beforeReceipts);
  }
 }
 private void insertFixture(String table,Map<String,Object> row){
  jdbc.update("insert into "+table+"("+String.join(",",row.keySet())+") values("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
 }
 @Test void planInputAcceptsEmbeddedRouteGeometryWithoutPriorRouteRegistration() throws Exception {
  var scope=jdbc.queryForMap("select owner_org_id,district_id from route where source_mode='mock' and enabled=true and owner_org_id is not null fetch first 1 rows only");
  long start=System.currentTimeMillis()+300000,end=start+3600000;
  var route=new LinkedHashMap<String,Object>();route.put("name","上级计划直接携带航线");
  route.put("geometry",Map.of("type","LineString","coordinates",List.of(List.of(118.60,37.46),List.of(118.61,37.46))));
  route.put("corridor_width_m",100);route.put("min_altitude_m",20);route.put("max_altitude_m",120);route.put("altitude_datum","AMSL");
  route.put("owner_org_id",scope.get("owner_org_id"));route.put("district_id",scope.get("district_id"));
  var body=new LinkedHashMap<String,Object>();body.put("message_id","embedded-route-plan");body.put("route",route);body.put("uav_sn","SIM-EMBEDDED-ROUTE");body.put("start_at",start);body.put("end_at",end);
  var received=send("/plans",body,200);String planId=received.path("subject_id").asText();
  String version=jdbc.queryForObject("select route_version_id from flight_plan where plan_id=?",String.class,planId);
  assertThat(version).isNotBlank();
  assertThat(jdbc.queryForObject("select count(*) from route_version where route_version_id=?",Long.class,version)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select source_mode from route where route_id=(select route_id from route_version where route_version_id=?)",String.class,version)).isEqualTo("replay");
  assertThat(received.path("result").path("route_version_id").asText()).isEqualTo(version);
 }
 @Test void explicitReplayPlanKeepsSourceAndMessageIdentity() throws Exception {
  var body=plan("input-plan-replay");body.put("source_mode","replay");
  var first=send("/plans",body,200);String id=first.path("subject_id").asText();
  assertThat(first.path("result").path("source_mode").asText()).isEqualTo("replay");
  assertThat(jdbc.queryForObject("select source_mode from flight_plan where plan_id=?",String.class,id)).isEqualTo("replay");
  assertThat(send("/plans",body,200).path("subject_id").asText()).isEqualTo(id);
  body.put("source_mode","mock");send("/plans",body,409);
  assertThat(jdbc.queryForObject("select source_mode from flight_plan where plan_id=?",String.class,id)).isEqualTo("replay");
 }
 @Test void explicitSimulatedPlanStatesPersistWithoutChangingOtherPlans() throws Exception {
  String baseline=send("/plans",plan("status-baseline"),200).path("subject_id").asText();
  long now=System.currentTimeMillis();
  // Keep referenced versions immutable: create an isolated route whose validity covers past scenarios.
  String route=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
  Object template=plan("status-route").get("route_version_id");
  var at=java.time.Instant.ofEpochMilli(now).atOffset(java.time.ZoneOffset.UTC);
  jdbc.update("insert into route(route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) select ?,?,'QA state route',true,r.source_id,'mock',r.owner_org_id,r.district_id,?,?,0 from route r join route_version v on v.route_id=r.route_id where v.route_version_id=?",route,"QA-"+route,at,at,template);
  jdbc.update("insert into route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at) select ?,?,1,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,?,? from route_version where route_version_id=?",version,route,at.minusDays(1),at,template);
  for(String state:List.of("PENDING","EXECUTING","COMPLETED","CANCELLED")){
   var body=plan("status-"+state);body.put("source_mode","replay");body.put("status_code",state);
   body.put("route_version_id",version);
   if("EXECUTING".equals(state)){body.put("start_at",now-60000);body.put("end_at",now+3600000);}
   if("COMPLETED".equals(state)){body.put("start_at",now-3600000);body.put("end_at",now-60000);}
   var first=send("/plans",body,200);String id=first.path("subject_id").asText();
   assertThat(jdbc.queryForObject("select status_code from flight_plan where plan_id=?",String.class,id)).isEqualTo(state);
   mvc.perform(get("/api/v1/flight-plans/"+id).header("Authorization",token)).andExpect(status().isOk())
    .andExpect(jsonPath("$.data.status_code").value(state)).andExpect(jsonPath("$.data.source_mode").value("replay"));
   assertThat(send("/plans",body,200).path("subject_id").asText()).isEqualTo(id);
  }
  assertThat(jdbc.queryForObject("select status_code from flight_plan where plan_id=?",String.class,baseline)).isEqualTo("PENDING");
 }
 @Test void invalidSimulatedStateOrTimeIsRejectedAndStateReplayCannotOverwrite() throws Exception {
  for(String state:List.of("OTHER","","APPROVED","EXECUTING","COMPLETED")){
   var body=plan("invalid-status-"+state);body.put("status_code",state);send("/plans",body,400);
  }
  var body=plan("status-conflict");body.put("status_code","PENDING");
  String id=send("/plans",body,200).path("subject_id").asText();
  body.put("status_code","CANCELLED");send("/plans",body,409);
  assertThat(jdbc.queryForObject("select status_code from flight_plan where plan_id=?",String.class,id)).isEqualTo("PENDING");
 }
 @Test void simulationInputCannotCreateLiveOrUnknownModePlans() throws Exception {
  for(String mode:List.of("live","unknown","")){
   var body=plan("input-plan-mode-"+mode);body.put("source_mode",mode);send("/plans",body,400);
  }
 }
 @Test void pastWeatherPeriodsRemainReadableWithoutRewritingPublicationTime() throws Exception {
  var plan=send("/plans",plan("weather-plan"),200);String id=plan.path("subject_id").asText();
  long published=System.currentTimeMillis()-2*86400000L;var period=Map.of("from",published,"to",published+3600000,"summary","模拟小雨","temperature_c",22,"wind_speed_ms",4,"gust_ms",6,"wind_direction_deg",180,"precipitation_probability_pct",80,"humidity_pct",75);
  var input=Map.of("message_id","weather-one","plan_id",id,"area_name","模拟区域","published_at",published,"periods",List.of(period));
  var received=send("/weather",input,200);
  assertThat(received.path("result").path("status").asText()).isEqualTo("READY");
  assertThat(send("/weather",input,200).path("message_id")).isEqualTo(received.path("message_id"));
  var first=mvc.perform(get("/api/v1/flight-plans/"+id+"/weather-forecast").header("Authorization",token))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"))
   .andExpect(jsonPath("$.data.message").doesNotExist()).andExpect(jsonPath("$.data.forecast.source_mode").value("mock"))
   .andExpect(jsonPath("$.data.forecast.published_at").value(published))
   .andExpect(jsonPath("$.data.forecast.periods[0].from").value(published))
   .andExpect(jsonPath("$.data.forecast.periods[0].to").value(published+3600000))
   .andExpect(jsonPath("$.data.forecast.periods[0].summary").value("模拟小雨")).andReturn().getResponse().getContentAsString();
  var second=mvc.perform(get("/api/v1/flight-plans/"+id+"/weather-forecast").header("Authorization",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(json.readTree(second).path("data")).isEqualTo(json.readTree(first).path("data"));
 }
 @Test void areaWeatherCanBeSubmittedWithoutPlanAndIsMatchedWhenReadingThatArea() throws Exception {
  var plan=send("/plans",plan("weather-area-plan"),200);String planId=plan.path("subject_id").asText();
  String area=jdbc.queryForObject("select d.name from flight_plan p join app_district d on d.district_id=p.district_id where p.plan_id=?",String.class,planId);
  long published=System.currentTimeMillis()-3600000L;
  var period=Map.of("from",published,"to",published+3600000,"summary","区域多云","temperature_c",22,"wind_speed_ms",4,"gust_ms",6,"wind_direction_deg",180,"precipitation_probability_pct",20,"humidity_pct",75);
  var input=new HashMap<String,Object>();input.put("message_id","weather-area-one");input.put("area_name",area);input.put("published_at",published);input.put("periods",List.of(period));
  var received=send("/weather",input,200);
  assertThat(received.path("result").path("status").asText()).isEqualTo("READY");
  assertThat(received.path("subject_id").asText()).isNotEqualTo(planId);
  mvc.perform(get("/api/v1/flight-plans/"+planId+"/weather-forecast").header("Authorization",token))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"))
   .andExpect(jsonPath("$.data.forecast.area_name").value(area))
   .andExpect(jsonPath("$.data.forecast.periods[0].summary").value("区域多云"));
 }
 @Test void forecastRuleCreatesPendingRisksOnceAndWaitsForManualVerification() throws Exception {
  var plan=send("/plans",plan("weather-rule-plan"),200);String planId=plan.path("subject_id").asText();
  String area=jdbc.queryForObject("select d.name from flight_plan p join app_district d on d.district_id=p.district_id where p.plan_id=?",String.class,planId);
  long now=System.currentTimeMillis(),published=now-1000,from=now+300000,to=now+1200000;
  var period=Map.of("from",from,"to",to,"summary","雷雨伴强风","temperature_c",22,"wind_speed_ms",12,"gust_ms",18,"wind_direction_deg",180,"precipitation_probability_pct",80,"humidity_pct",90);
  var input=new HashMap<String,Object>();input.put("message_id","weather-rule-one");input.put("plan_id",planId);input.put("area_name",area);input.put("published_at",published);input.put("periods",List.of(period));
  send("/weather",input,200);
  assertThat(jdbc.queryForObject("select count(*) from flight_risk where plan_id=? and source_id like 'weather-forecast-rule-%'",Long.class,planId)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from flight_risk where plan_id=? and state_code='PENDING_VERIFICATION'",Long.class,planId)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select count(*) from weather_forecast_risk_fact where risk_id in (select risk_id from flight_risk where plan_id=?)",Long.class,planId)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select reason_text from flight_risk where plan_id=? and source_id like 'weather-forecast-rule-%'",String.class,planId)).contains("WEATHER_THUNDERSTORM").contains("WEATHER_STRONG_WIND");
  send("/weather",input,200);
  assertThat(jdbc.queryForObject("select count(*) from flight_risk where plan_id=? and source_id like 'weather-forecast-rule-%'",Long.class,planId)).isEqualTo(1);
 }
 @Test void rejectsInvalidInputAndUnauthenticatedAccess() throws Exception {
  mvc.perform(get(BASE+"/context")).andExpect(status().isUnauthorized());
  var body=plan("invalid");body.put("end_at",body.get("start_at"));send("/plans",body,400);
  send("/bindings",Map.of("source_kind","UAV_EVENT","source_id","missing","enabled",true),404);
 }
 @Test void realDispatchRequiresDeliveryBeforeAckAndCannotRegress() throws Exception {
  var h=jdbc.queryForMap("select * from handoff where source_kind='RISK' and source_mode='mock' fetch first 1 rows only");
  String hid=(String)h.get("handoff_id"), sid=(String)h.get("source_id");
  send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
  var at=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
  var out=channel.deliver(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch(hid,"RISK",sid,"RISK_NOTICE",(String)h.get("recipient_id"),"上级","{}",at));
  assertThat(out.deliveryStatus()).isEqualTo("SUBMITTED");
  jdbc.update("update handoff_delivery set delivery_status='SUBMITTED',receipt_status='PENDING',blocked_reason=?,submitted_at=?,delivered_at=null,acknowledged_at=null where handoff_id=?",out.blockedReason(),at,hid);
  String mid=jdbc.queryForObject("select message_id from local_interface_message where subject_id=? and direction='OUT'",String.class,hid);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",0,"outcome","ACKNOWLEDGED"),409);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",0,"outcome","DELIVERED"),200);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",1,"outcome","ACKNOWLEDGED"),200);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",1,"outcome","ACKNOWLEDGED"),200);
  send("/messages/"+mid+"/receipt",Map.of("expected_version",2,"outcome","FAILED"),409);
  assertThat(jdbc.queryForObject("select receipt_status from handoff_delivery where handoff_id=? order by attempt_no desc fetch first 1 rows only",String.class,hid)).isEqualTo("ACKNOWLEDGED");
 }
 @Test void expiredExplicitReceiverDoesNotFallBackToAutomaticMockSuccess() throws Exception {
  var h=jdbc.queryForMap("select * from handoff where source_kind='RISK' and source_mode='mock' fetch first 1 rows only");
  String sid=(String)h.get("source_id");send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
  jdbc.update("update local_interface_binding set expires_at=1 where source_kind='RISK' and source_id=?",sid);
  var out=channel.deliver(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch((String)h.get("handoff_id"),"RISK",sid,"RISK_NOTICE",(String)h.get("recipient_id"),"上级","{}",java.time.OffsetDateTime.now()));
  assertThat(out.deliveryStatus()).isEqualTo("PENDING_DELIVERY");assertThat(out.deliveredAt()).isNull();
 }
 @Test void interfaceReadOnlyCannotPushPlans() throws Exception {
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-EXT-READ','接口只读','',FALSE,TRUE,0,0,0,FALSE)");
  jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-EXT-READ','interfaces','READ',TRUE)");
  jdbc.update("UPDATE app_user SET role_code='ROLE-EXT-READ' WHERE account='admin1'");
  send("/plans",plan("no-permission"),403);
 }
 @Test void contextDoesNotRequireUnrelatedBusinessPermissions() throws Exception {
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-EXT-ONLY','接口操作','',FALSE,TRUE,0,0,0,FALSE)");
  jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-EXT-ONLY','interfaces','OP',TRUE)");
  jdbc.update("UPDATE app_user SET role_code='ROLE-EXT-ONLY' WHERE account='admin1'");
  mvc.perform(get(BASE+"/context").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.routes").isEmpty()).andExpect(jsonPath("$.data.unavailable_sections").isNotEmpty());
 }
 @Test void contextExposesExpiredRouteSeparatelyForMapPlanDiagnostics() throws Exception {
  String route=UUID.randomUUID().toString(),version=UUID.randomUUID().toString(),template=jdbc.queryForObject("select v.route_version_id from route_version v join route r on r.route_id=v.route_id where r.source_mode='mock' and r.enabled=true and r.owner_org_id is not null fetch first 1 rows only",String.class);
  var now=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
  jdbc.update("insert into route(route_id,route_no,name,enabled,source_id,source_mode,owner_org_id,district_id,created_at,updated_at,version) select ?,?,'QA expired context route',true,r.source_id,'mock',r.owner_org_id,r.district_id,?,?,0 from route r join route_version v on v.route_id=r.route_id where v.route_version_id=?",route,"QA-expired-"+route,now,now,template);
  jdbc.update("insert into route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,valid_from,valid_to,change_reason,created_at) select ?,?,1,centerline,corridor_width_m,min_altitude_m,max_altitude_m,altitude_datum,?,?, 'QA expired context',? from route_version where route_version_id=?",version,route,now.minusHours(2),now.minusHours(1),now,template);
  mvc.perform(get(BASE+"/context").header("Authorization",token)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.routes[*].route_version_id").value(org.hamcrest.Matchers.not(hasItem(version))))
   .andExpect(jsonPath("$.data.expired_routes[*].route_version_id").value(hasItem(version)));
 }
 @Test void expiredBindingCanBeTakenOverByAnotherAuthorizedOperator() throws Exception {
  var h=jdbc.queryForMap("select * from handoff where source_kind='RISK' and source_mode='mock' fetch first 1 rows only");
  String sid=(String)h.get("source_id");send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
  String other=jdbc.queryForObject("select user_id from app_user where account<>'admin1' fetch first 1 rows only",String.class);
  jdbc.update("update local_interface_binding set created_by=?,expires_at=1 where source_kind='RISK' and source_id=?",other,sid);
  send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
 }
 @Test void interruptedDeliveryAssociationIsUnknownAndCannotAcceptReceipt() throws Exception {
  var h=jdbc.queryForMap("select * from handoff where source_kind='RISK' and source_mode='mock' fetch first 1 rows only");
  String hid=(String)h.get("handoff_id"),sid=(String)h.get("source_id");send("/bindings",Map.of("source_kind","RISK","source_id",sid,"enabled",true),200);
  channel.deliver(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.HandoffDispatch(hid,"RISK",sid,"RISK_NOTICE",(String)h.get("recipient_id"),"上级","{}",java.time.OffsetDateTime.now()));
  String mid=jdbc.queryForObject("select message_id from local_interface_message where subject_id=? and direction='OUT'",String.class,hid);
  mvc.perform(get(BASE+"/context").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.messages[?(@.message_id=='"+mid+"')].state").value("UNKNOWN"));
  send("/messages/"+mid+"/receipt",Map.of("expected_version",0,"outcome","DELIVERED"),409);
 }
 Map<String,Object> plan(String message) {
  String rv=jdbc.queryForObject("select v.route_version_id from route_version v join route r on r.route_id=v.route_id where r.source_mode='mock' and r.enabled=true and r.owner_org_id is not null fetch first 1 rows only",String.class);
  var body=new HashMap<String,Object>();body.put("message_id",message);body.put("route_version_id",rv);body.put("uav_sn","SIM-INPUT-001");body.put("start_at",System.currentTimeMillis()+300000);body.put("end_at",System.currentTimeMillis()+3900000);return body;
 }
 JsonNode send(String path,Object body,int expected) throws Exception {
  var result=mvc.perform(post(BASE+path).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().is(expected)).andReturn();
  return json.readTree(result.getResponse().getContentAsString()).path("data");
 }
}
