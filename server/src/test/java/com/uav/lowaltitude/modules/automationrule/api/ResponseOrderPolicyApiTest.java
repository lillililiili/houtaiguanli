package com.uav.lowaltitude.modules.automationrule.api;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ResponseOrderPolicyApiTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    String session;static final String BASE="/api/v1/response-order-policy";
    @BeforeEach void login()throws Exception{session=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();}
    Map<String,Object> input(){var p=new LinkedHashMap<String,Object>();p.put("expected_version",1);p.put("enabled",true);p.put("minimum_risk","HIGH");p.put("prediction_enabled",true);p.put("observed_enabled",true);p.put("scope_mode","ALL");p.put("airspace_ids",List.of());p.put("schedule_mode","ALL_DAY");p.put("start_time","00:00");p.put("end_time","00:00");p.put("timezone","Asia/Shanghai");p.put("max_wait_seconds",60);return p;}
    @Test void offByDefaultSaveIsVersionedAuditedAndDoesNotEnableAutomaticRules()throws Exception{
        mvc.perform(get(BASE).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        var before=jdbc.queryForList("SELECT category,version FROM automation_rule_group ORDER BY category");
        var request=put(BASE).header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input()));
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(request).andExpect(status().isConflict());
        mvc.perform(get(BASE+"/history").header("Authorization","Bearer "+session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='response_order_changed'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT category,version FROM automation_rule_group ORDER BY category")).isEqualTo(before);
    }
    @Test void boundsStagesVersionAndAuthenticationAreServerEnforced()throws Exception{
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        for(int wait:List.of(4,301)){var body=input();body.put("max_wait_seconds",wait);save(body,400);}
        var stages=input();stages.put("prediction_enabled",false);stages.put("observed_enabled",false);save(stages,400);
        var stale=input();stale.put("expected_version",999);save(stale,409);
        assertThat(jdbc.queryForObject("SELECT version FROM response_order_policy",Long.class)).isEqualTo(1);
    }
    void save(Map<String,Object> body,int status)throws Exception{mvc.perform(put(BASE).header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(status));}
}
