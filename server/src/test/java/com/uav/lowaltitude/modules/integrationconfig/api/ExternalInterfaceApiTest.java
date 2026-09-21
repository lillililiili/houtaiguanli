package com.uav.lowaltitude.modules.integrationconfig.api;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class ExternalInterfaceApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private static final String URL="/api/v1/external-interface-configs/WEATHER_FORECAST";
    @Test void draftPersistsButNeverEnablesOrReportsConnected() throws Exception {
        String token=login();
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header("Authorization",token)).andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED"));
        String body="{\"version\":0,\"name\":\"气象服务\",\"endpoint\":\"https://weather.example.test/forecast\",\"credential_ref\":\"env:WEATHER_KEY\",\"interval_minutes\":15}";
        mvc.perform(put(URL).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
            .andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.status").value("AWAITING_ADAPTER"));
        mvc.perform(get(URL).header("Authorization",token)).andExpect(jsonPath("$.data.name").value("气象服务"));
        mvc.perform(put(URL).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT version FROM external_interface_config WHERE kind='WEATHER_FORECAST'",Long.class)).isEqualTo(1);
    }
    @Test void rejectsSecretsInvalidEndpointIntervalsAndWrongInterfaceFields() throws Exception {
        String token=login();
        for(String fragment: new String[]{"\"credential_ref\":\"actual-secret\"","\"endpoint\":\"https://user:password@example.test\"",
            "\"endpoint\":\"https://example.test?key=secret\"","\"interval_minutes\":0","\"direction\":\"PUSH\""}) {
            mvc.perform(put(URL).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"test\","+fragment+"}"))
                .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT version FROM external_interface_config WHERE kind='WEATHER_FORECAST'",Long.class)).isZero();
    }
    @Test void readOnlyCannotSaveAndForecastStillChecksPlanPermission() throws Exception {
        String token=login();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-WX-READ','天气配置只读','',FALSE,TRUE,0,0,0,FALSE)");
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-WX-READ','interfaces','READ',TRUE)");
        jdbc.update("UPDATE app_user SET role_code='ROLE-WX-READ' WHERE account='admin1'");
        mvc.perform(get(URL).header("Authorization",token)).andExpect(status().isOk());
        mvc.perform(put(URL).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"test\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/flight-plans/missing/weather-forecast").header("Authorization",token)).andExpect(status().isForbidden());
    }
    @Test void missingPlanIsNotTreatedAsEmptyWeather() throws Exception {
        mvc.perform(get("/api/v1/flight-plans/missing/weather-forecast").header("Authorization",login()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("FLIGHT_PLAN_NOT_FOUND"));
    }
    @Test void mockModeIsExplicitAndCanBeDisabledWithoutFallback() throws Exception {
        String token=login();
        mvc.perform(put(URL).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":0,\"name\":\"天气演示\",\"source_mode\":\"mock\",\"area_name\":\"东营市\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.source_mode").value("mock"))
            .andExpect(jsonPath("$.data.status").value("SIMULATED")).andExpect(jsonPath("$.data.enabled").value(true));
        mvc.perform(put(URL).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":1,\"name\":\"天气服务\",\"source_mode\":\"live\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.status").value("AWAITING_ADAPTER"));
    }
    @Test void mockRequiresAreaAndDoesNotApplyToPlanInput() throws Exception {
        String token=login();
        for (String body : new String[]{
            "{\"version\":0,\"name\":\"test\",\"source_mode\":\"random\"}",
            "{\"version\":0,\"name\":\"test\",\"source_mode\":\"mock\"}"}) {
            mvc.perform(put(URL).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/external-interface-configs/FLIGHT_PLAN").header("Authorization",token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"test\",\"source_mode\":\"mock\"}"))
            .andExpect(status().isBadRequest());
    }
    private String login() throws Exception {
        var result=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk()).andReturn();
        JsonNode json=mapper.readTree(result.getResponse().getContentAsString());return "Bearer "+json.path("data").path("session_id").asText();
    }
}
