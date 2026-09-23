package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.*;

/** 真实权限/会话过滤链下验证新增目录不会扩大联系人与账号可见范围。 */
@SpringBootTest(properties={"app.handoff.channel=none","app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class DirectoryScopeApiTest {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json;
 String tag,ownOrg,otherOrg,district,ownContact,otherContact,ownBinding,otherBinding,ownUser,otherUser;
 String ownPhone="13800138000",otherPhone="13900139000",ownEmail="scope-own@example.invalid",otherEmail="scope-other@example.invalid";

 @BeforeEach void fixture() {
  tag="scope-"+UUID.randomUUID().toString().substring(0,8);
  ownOrg=id();otherOrg=id();district=id();ownContact=id();otherContact=id();ownBinding=id();otherBinding=id();
  for(String org:List.of(ownOrg,otherOrg))jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",org,tag+org.substring(0,4),tag+org);
  jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",district,tag,tag);
  contact(ownContact,ownOrg,ownPhone,ownEmail);contact(otherContact,otherOrg,otherPhone,otherEmail);
  String source=jdbc.queryForObject("SELECT source_id FROM integration_source ORDER BY source_id FETCH FIRST 1 ROW ONLY",String.class);
  for(String[] pair:List.of(new String[]{ownBinding,ownOrg},new String[]{otherBinding,otherOrg}))jdbc.update("INSERT INTO plan_source_binding(binding_id,source_id,external_org_code,org_id,enabled,created_at,updated_at,version) VALUES(?,?,?,?,TRUE,0,0,0)",pair[0],source,tag+pair[0],pair[1]);
  ownUser=user("NONE",ownOrg,null).userId();otherUser=user("NONE",otherOrg,null).userId();
 }

 @Test void assignedContactReadsExcludeOtherOrganizations() throws Exception {
  String token=user("ASSIGNED",ownOrg,"organizations").token();
  JsonNode items=data(auth(get("/api/v1/contacts").param("keyword",tag),token)).path("items");
  assertThat(ids(items,"contact_id")).containsExactly(ownContact);
  assertThat(data(auth(get("/api/v1/contacts/"+ownContact),token)).path("phone").asText()).isEqualTo(ownPhone);
  denied(auth(get("/api/v1/contacts/"+otherContact),token));
  JsonNode filtered=data(auth(get("/api/v1/contacts").param("org_id",otherOrg),token));
  assertThat(filtered.path("items")).isEmpty();
 }

 @Test void assignedWritesCannotChangeOrCreateOtherOrganizationsContacts() throws Exception {
  String token=user("ASSIGNED",ownOrg,"organizations").token();
  denied(write(patch("/api/v1/contacts/"+otherContact),token,body(otherOrg,0)));
  denied(write(post("/api/v1/contacts"),token,body(otherOrg,null)));
  assertThat(jdbc.queryForObject("SELECT name FROM business_contact WHERE contact_id=?",String.class,otherContact)).startsWith(tag);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_contact WHERE org_id=?",Long.class,otherOrg)).isEqualTo(1);
  assertThat(data(write(patch("/api/v1/contacts/"+ownContact),token,body(ownOrg,0))).path("name").asText()).isEqualTo("本单位修改");
 }

 @Test void noneScopeDoesNotExposeDirectoryOrPersonalData() throws Exception {
  String token=user("NONE",ownOrg,"organizations").token();
  for(String endpoint:List.of("/api/v1/contacts","/api/v1/organization-profiles","/api/v1/plan-source-bindings"))emptyOrForbidden(auth(get(endpoint),token));
  for(String kind:List.of("contacts","organizations","users"))emptyOrForbidden(auth(get("/api/v1/directory-options").param("kind",kind),token));
  denied(auth(get("/api/v1/contacts/"+ownContact),token));
  denied(write(post("/api/v1/contacts"),token,body(ownOrg,null)));
 }

 @Test void listsAndSelectorsUseTheSameAssignedOrganizationBoundary() throws Exception {
  String token=user("ASSIGNED",ownOrg,"organizations").token();
  assertThat(ids(data(auth(get("/api/v1/organization-profiles").param("keyword",tag),token)).path("items"),"org_id")).containsExactly(ownOrg);
  denied(auth(get("/api/v1/organization-profiles/"+otherOrg),token));
  assertThat(ids(data(auth(get("/api/v1/plan-source-bindings").param("keyword",tag),token)).path("items"),"binding_id")).containsExactly(ownBinding);
  for(String kind:List.of("organizations","contacts","users")) {
   JsonNode items=data(auth(get("/api/v1/directory-options").param("kind",kind).param("keyword",tag).param("size","100"),token)).path("items");
   assertThat(items).isNotEmpty();
   for(JsonNode item:items)assertThat(item.path("org_id").asText()).isEqualTo(ownOrg);
  }
  assertThat(ids(data(auth(get("/api/v1/directory-options").param("kind","users").param("keyword",tag).param("size","100"),token)).path("items"),"id")).contains(ownUser).doesNotContain(otherUser);
 }

 @Test void allScopeDirectoryManagersRetainCompleteBusinessContacts() throws Exception {
  String token=user("ALL",ownOrg,"organizations").token();
  assertThat(ids(data(auth(get("/api/v1/contacts").param("keyword",tag),token)).path("items"),"contact_id")).containsExactlyInAnyOrder(ownContact,otherContact);
  assertThat(data(auth(get("/api/v1/contacts/"+otherContact),token)).path("phone").asText()).isEqualTo(otherPhone);
 }

 private void contact(String id,String org,String phone,String email) {
  jdbc.update("INSERT INTO business_contact(contact_id,org_id,name,roles,phone,email,enabled,created_at,updated_at,version) VALUES(?,?,?,CAST(? AS JSON),?,?,TRUE,0,0,0)",id,org,tag+id,"[\"MAINTENANCE\"]",phone,email);
 }
 private Actor user(String scope,String org,String module) {
  String uid=id(),role="ROLE-SCOPE-"+id().substring(0,8),token=id();
  jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?, '',FALSE,TRUE,0,0,0,FALSE)",role,role);
  if(module!=null)jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,?,TRUE,CURRENT_TIMESTAMP)",role,module,"notificationSettings".equals(module)?"READ":"AUTH");
  jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,org_id,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",uid,tag+uid.substring(0,6),tag+uid,role,org,scope);
  if("ASSIGNED".equals(scope))jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",uid,org,district);
  jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",token,uid,System.currentTimeMillis()+3600000);
  return new Actor(uid,token);
 }
 private Map<String,Object> body(String org,Integer version) {var body=new HashMap<String,Object>();body.put("org_id",org);body.put("name","本单位修改");body.put("roles",List.of("MAINTENANCE"));body.put("phone",ownPhone);body.put("enabled",true);if(version!=null)body.put("expected_version",version);return body;}
 private JsonNode data(MockHttpServletRequestBuilder req)throws Exception{return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
 private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token){return req.header("Authorization","Bearer "+token);}
 private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder req,String token,Object body)throws Exception{return auth(req,token).header("Idempotency-Key",id()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));}
 private void denied(MockHttpServletRequestBuilder req)throws Exception{assertThat(mvc.perform(req).andReturn().getResponse().getStatus()).isIn(403,404);}
 private void emptyOrForbidden(MockHttpServletRequestBuilder req)throws Exception{var response=mvc.perform(req).andReturn().getResponse();assertThat(response.getStatus()).isIn(200,403);if(response.getStatus()==200)assertThat(json.readTree(response.getContentAsString()).path("data").path("items")).isEmpty();}
 private List<String> ids(JsonNode items,String field){var result=new ArrayList<String>();items.forEach(item->result.add(item.path(field).asText()));return result;}
 private String id(){return UUID.randomUUID().toString();}
 private record Actor(String userId,String token) { }
}
