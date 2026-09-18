package com.uav.lowaltitude.modules.automationrule.api;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AutomationRuleApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    String session;
    static final String BASE="/api/v1/automation-rule-groups/";

    @BeforeEach void login() throws Exception {
        session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
    Map<String,Object> rule(long version) {
        return new HashMap<>(Map.of("name","置信度测试","item_code","confidence","value","95",
                "hold_seconds",3,"enabled",true,"expected_version",version));
    }
    Map<String,Object> settings(long version) {
        return new HashMap<>(Map.of("scope_mode","ALL","airspace_ids",List.of(),"schedule_mode","ALL_DAY",
                "start_time","08:00","end_time","20:00","timezone","Asia/Shanghai",
                "insufficient_wait_seconds",15,"actions",List.of(),"expected_version",version));
    }
    @Test void emptyConfigurationDoesNotSeedThresholdsOrClaimExecution() throws Exception {
        for(String category:List.of("verify","counter","dispose")) {
            var g=ok(auth(get(BASE+category)));
            assertThat(g.path("rules")).isEmpty();
            assertThat(g.path("version").asLong()).isZero();
            assertThat(g.path("execution_status").asText()).isEqualTo("DISABLED");
            assertThat(g.path("catalog").size()).isGreaterThan(0);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_rule_change",Long.class)).isZero();
    }
    @Test void createUpdateDisableAndHistoryUsePersistedVersionAndActor() throws Exception {
        var created=ok(write(post(BASE+"verify/rules"),rule(0)));
        String id=created.path("rules").get(0).path("rule_id").asText();
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertThat(created.path("rules").get(0).path("updated_by").asText()).isEqualTo("admin1");
        var changed=rule(1);changed.put("value","96");changed.put("hold_seconds",5);
        ok(write(put(BASE+"verify/rules/"+id),changed));
        var disabled=ok(write(patch(BASE+"verify/rules/"+id+"/enabled"),Map.of("enabled",false,"expected_version",2)));
        assertThat(disabled.path("rules").get(0).path("enabled").asBoolean()).isFalse();
        assertThat(ok(auth(get(BASE+"verify"))).path("rules").get(0).path("value").asText()).isEqualTo("96");
        var history=ok(auth(get(BASE+"verify/history?page=1&size=2")));
        assertThat(history.path("total").asLong()).isEqualTo(3);
        assertThat(history.path("items").size()).isEqualTo(2);
        assertThat(history.path("items").get(0).path("version").asLong()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT after_json FROM automation_rule_change WHERE category='verify' AND version=1",String.class)).contains("95");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='automation_rule_changed'",Long.class)).isEqualTo(3);
    }
    @Test void duplicateItemsStaleVersionAndRepeatedRequestNeverDoubleWrite() throws Exception {
        var body=rule(0);String key=UUID.randomUUID().toString();
        var created=ok(write(post(BASE+"verify/rules"),body,key));String id=created.path("rules").get(0).path("rule_id").asText();
        mvc.perform(write(post(BASE+"verify/rules"),body,key)).andExpect(status().isConflict());
        mvc.perform(write(put(BASE+"verify/rules/"+id),rule(0))).andExpect(status().isConflict());
        body=rule(1);body.put("name","不同名称但同一判定项");
        mvc.perform(write(post(BASE+"verify/rules"),body)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_rule_condition WHERE category='verify'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_rule_change WHERE category='verify'",Long.class)).isEqualTo(1);
    }
    @Test void numericalFixedAndCategoryValidationRejectUnknownOrInvalidData() throws Exception {
        for(String value:List.of("101","-1","95.5","NaN","")) {var b=rule(0);b.put("value",value);mvc.perform(write(post(BASE+"verify/rules"),b)).andExpect(status().isBadRequest());}
        var b=rule(0);b.put("hold_seconds",61);mvc.perform(write(post(BASE+"verify/rules"),b)).andExpect(status().isBadRequest());
        b=rule(0);b.put("item_code","freshness");b.put("value","30");mvc.perform(write(post(BASE+"verify/rules"),b)).andExpect(status().isBadRequest());
        b=rule(0);b.put("item_code","identity");b.put("hold_seconds",0);mvc.perform(write(post(BASE+"verify/rules"),b)).andExpect(status().isBadRequest());
        mvc.perform(write(post(BASE+"counter/rules"),rule(0))).andExpect(status().isBadRequest());
        mvc.perform(auth(get(BASE+"unknown"))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_rule_change",Long.class)).isZero();
    }
    @Test void crossMidnightIsPersistedButInvalidTimesAndScopesAreRejected() throws Exception {
        var b=settings(0);b.put("schedule_mode","DAILY");b.put("start_time","20:00");b.put("end_time","08:00");
        var g=ok(write(put(BASE+"verify/settings"),b));
        assertThat(g.path("settings").path("end_time").asText()).isEqualTo("08:00");
        assertThat(g.path("settings").path("timezone").asText()).isEqualTo("Asia/Shanghai");
        b=settings(1);b.put("schedule_mode","DAILY");b.put("end_time","08:00");mvc.perform(write(put(BASE+"verify/settings"),b)).andExpect(status().isBadRequest());
        b=settings(1);b.put("start_time","25:00");mvc.perform(write(put(BASE+"verify/settings"),b)).andExpect(status().isBadRequest());
        b=settings(1);b.put("scope_mode","AIRSPACES");mvc.perform(write(put(BASE+"verify/settings"),b)).andExpect(status().isBadRequest());
        b.put("airspace_ids",List.of("not-an-airspace"));mvc.perform(write(put(BASE+"verify/settings"),b)).andExpect(status().isBadRequest());
        assertThat(ok(auth(get(BASE+"verify"))).path("version").asLong()).isEqualTo(1);
    }
    @Test void selectedAirspaceRetainsIdentityAndActionsAreCategorySpecific() throws Exception {
        String id=jdbc.queryForObject("SELECT airspace_id FROM airspace ORDER BY airspace_id LIMIT 1",String.class);
        var b=settings(0);b.put("scope_mode","AIRSPACES");b.put("airspace_ids",List.of(id));
        var g=ok(write(put(BASE+"verify/settings"),b));assertThat(g.path("settings").path("airspace_ids").get(0).asText()).isEqualTo(id);
        b=settings(0);mvc.perform(write(put(BASE+"dispose/settings"),b)).andExpect(status().isBadRequest());
        b.put("actions",List.of("pilot","notify"));g=ok(write(put(BASE+"dispose/settings"),b));
        assertThat(g.path("settings").path("actions").get(0).asText()).isEqualTo("pilot");
        b=settings(1);b.put("actions",List.of("jamming"));mvc.perform(write(put(BASE+"dispose/settings"),b)).andExpect(status().isBadRequest());
        b=settings(0);b.put("actions",List.of("notify"));mvc.perform(write(put(BASE+"counter/settings"),b)).andExpect(status().isBadRequest());
    }
    @Test void anonymousReadOnlyAndScopedAccountsCannotMutateGlobalConfiguration() throws Exception {
        mvc.perform(get(BASE+"verify")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE+"verify/runs")).andExpect(status().isUnauthorized());
        limited("READ","ALL");
        assertThat(ok(auth(get(BASE+"verify"))).path("can_manage").asBoolean()).isFalse();
        assertThat(ok(auth(get(BASE+"verify/runs"))).path("items")).isEmpty();
        mvc.perform(auth(get(BASE+"verify/runs?size=101"))).andExpect(status().isBadRequest());
        mvc.perform(write(post(BASE+"verify/rules"),rule(0))).andExpect(status().isForbidden());
        limited("AUTH","ASSIGNED");mvc.perform(auth(get(BASE+"verify"))).andExpect(status().isForbidden());
        mvc.perform(auth(get(BASE+"verify/runs"))).andExpect(status().isForbidden());
        mvc.perform(write(post(BASE+"verify/rules"),rule(0))).andExpect(status().isForbidden());
    }
    private void limited(String level,String scope) {
        String uid=UUID.randomUUID().toString(),role="AR-"+uid.substring(0,8);session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)",role,role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'responsePlans',?,TRUE)",role,level);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",uid,role,role,role,scope);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",session,uid,System.currentTimeMillis()+3600000);
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) { return request.header("Authorization","Bearer "+session); }
    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request,Object body) throws Exception { return write(request,body,UUID.randomUUID().toString()); }
    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request,Object body,String key) throws Exception { return auth(request).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)); }
    JsonNode ok(MockHttpServletRequestBuilder request) throws Exception { return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data"); }
}
