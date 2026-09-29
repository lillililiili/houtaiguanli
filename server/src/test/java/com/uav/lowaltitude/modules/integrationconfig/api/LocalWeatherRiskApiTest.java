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

@SpringBootTest(properties="app.weather-risk.qa.enabled=true")
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class LocalWeatherRiskApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    String token, plan;
    static final String BASE="/api/v1/local-interface-simulator/weather-risks";

    @BeforeEach void setup() throws Exception {
        token="Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        String route=jdbc.queryForObject("select v.route_version_id from route_version v join route r on r.route_id=v.route_id where r.source_mode='mock' and r.enabled=true and r.owner_org_id is not null fetch first 1 rows only",String.class);
        long now=System.currentTimeMillis();
        var body=Map.of("message_id","wx-plan-"+UUID.randomUUID(),"route_version_id",route,"uav_sn","QA-WEATHER",
            "start_at",now+300000,"end_at",now+3900000);
        plan=json.readTree(mvc.perform(post("/api/v1/local-interface-simulator/plans").header("Authorization",token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data").path("subject_id").asText();
    }
    Map<String,Object> input(String id) {
        long now=System.currentTimeMillis();
        var body=new LinkedHashMap<String,Object>();
        body.put("message_id",id);body.put("plan_id",plan);
        body.put("reason_code","WEATHER_STRONG_WIND");body.put("severity","HIGH");
        body.put("reason_text","QA大风风险时效检查，非真实预警");
        body.put("published_at",now-60000);body.put("valid_from",now-30000);body.put("valid_to",now+300000);
        body.put("polygon",List.of(List.of(118.6,37.4),List.of(118.7,37.4),List.of(118.7,37.5),List.of(118.6,37.4)));
        body.put("wind_speed_mps",12);return body;
    }
    JsonNode send(Object body,int expected) throws Exception {
        var response=mvc.perform(post(BASE).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(body))).andExpect(status().is(expected)).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data");
    }
    @Test void createsScopedWeatherWithoutTargetAndReplaysIdentically() throws Exception {
        var body=input("weather-valid");var first=send(body,200);String id=first.path("risk_id").asText();
        assertThat(send(body,200)).isEqualTo(first);
        mvc.perform(get("/api/v1/risks/"+id).header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.risk_type").value("WEATHER")).andExpect(jsonPath("$.data.source_mode").value("mock"))
            .andExpect(jsonPath("$.data.target_id").doesNotExist()).andExpect(jsonPath("$.data.state").value("PENDING_VERIFICATION"));
        assertThat(jdbc.queryForObject("select count(*) from weather_risk_fact where risk_id=?",Long.class,id)).isEqualTo(1);
        body.put("severity","LOW");send(body,409);
        assertThat(jdbc.queryForObject("select severity from flight_risk where risk_id=?",String.class,id)).isEqualTo("HIGH");
    }
    @Test void expiredEvidenceBecomesUnknownWithoutChangingHistory() throws Exception {
        var body=input("weather-expired");body.put("valid_from",System.currentTimeMillis()-50000);body.put("valid_to",System.currentTimeMillis()-10000);
        String id=send(body,200).path("risk_id").asText();
        var current=json.readTree(mvc.perform(get("/api/v1/risks/current").param("plan_id",plan).param("size","100")
            .header("Authorization",token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var found=new ArrayList<JsonNode>();current.path("data").path("items").forEach(x->{if(id.equals(x.path("risk").path("risk_id").asText()))found.add(x);});
        assertThat(found).hasSize(1);assertThat(found.get(0).path("current_status").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?",String.class,id)).isEqualTo("PENDING_VERIFICATION");
    }
    @Test void rejectsInvalidWindowAndGeometryWithoutPartialRisk() throws Exception {
        long before=jdbc.queryForObject("select count(*) from flight_risk",Long.class);
        var time=input("bad-time");time.put("valid_to",time.get("valid_from"));send(time,400);
        var future=input("future-publication");future.put("published_at",System.currentTimeMillis()+60000);send(future,400);
        var open=input("open-ring");open.put("polygon",List.of(List.of(118d,37d),List.of(119d,37d),List.of(119d,38d),List.of(118d,38d)));send(open,400);
        var crossing=input("crossing-ring");crossing.put("polygon",List.of(List.of(118d,37d),List.of(119d,38d),List.of(119d,37d),List.of(118d,38d),List.of(118d,37d)));send(crossing,400);
        assertThat(jdbc.queryForObject("select count(*) from flight_risk",Long.class)).isEqualTo(before);
    }
    @Test void rejectsLivePlansAndMissingPlan() throws Exception {
        var body=input("live-plan");jdbc.update("update flight_plan set source_mode='live' where plan_id=?",plan);send(body,409);
        body=input("missing-plan");body.put("plan_id",UUID.randomUUID().toString());send(body,404);
    }
    @Test void requiresLoginAndInterfaceOperationPermission() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input("anonymous")))).andExpect(status().isUnauthorized());
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-WX-READ','QA只读','',FALSE,TRUE,0,0,0,FALSE)");
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-WX-READ','interfaces','READ',TRUE)");
        jdbc.update("UPDATE app_user SET role_code='ROLE-WX-READ' WHERE account='admin1'");send(input("read-only"),403);
    }
    @Test void retriesRecheckPlanVisibilityAndCannotModifyHiddenHistory() throws Exception {
        var body=input("visibility-retry");String id=send(body,200).path("risk_id").asText();
        jdbc.update("update app_user set scope_mode='NONE' where account='admin1'");
        send(body,403);
        assertThat(jdbc.queryForObject("select count(*) from weather_risk_fact where risk_id=?",Long.class,id)).isEqualTo(1);
    }
}
