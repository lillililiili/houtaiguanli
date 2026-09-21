package com.uav.lowaltitude.modules.device.api;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
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
class WeatherSensorApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Test void registrationPersistsInExistingCatalogWithoutFakeObservationOrActivation() throws Exception {
        String token=login();String body=body(token);
        String id=create(token,body);
        mvc.perform(get("/api/v1/devices/"+id).header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.device.device_type_code").value("weather_sensor"))
            .andExpect(jsonPath("$.data.device.enabled").value(false)).andExpect(jsonPath("$.data.device.simulated").value(false))
            .andExpect(jsonPath("$.data.device.connectivity").value("UNKNOWN"));
        mvc.perform(patch("/api/v1/devices/"+id+"/enabled").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":0,\"enabled\":true,\"reason\":\"测试启用\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("PROTOCOL_UNSUPPORTED"));
        assertThat(jdbc.queryForObject("SELECT enabled FROM ops_device WHERE device_id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_state WHERE device_id=?",Long.class,id)).isZero();
        mvc.perform(post("/api/v1/weather-sensors").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());
    }
    @Test void updatesUseVersionAndPreserveIdentity() throws Exception {
        String token=login();String body=body(token);String id=create(token,body);
        var input=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(body);
        input.put("version",0);input.put("name","已修改天气传感器");
        mvc.perform(put("/api/v1/weather-sensors/"+id).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(input.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(put("/api/v1/weather-sensors/"+id).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(input.toString()))
            .andExpect(status().isConflict());
        input.put("version",1);input.put("device_no","changed");
        mvc.perform(put("/api/v1/weather-sensors/"+id).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(input.toString()))
            .andExpect(status().isBadRequest());
    }
    @Test void noActionPermissionCannotRegisterAndAssignedUserCannotSeeOtherScope() throws Exception {
        String token=login();String body=body(token);String id=create(token,body);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-SENSOR-READ','设备只读','',FALSE,TRUE,0,0,0,FALSE)");
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-SENSOR-READ','devices','READ',TRUE)");
        jdbc.update("UPDATE app_user SET role_code='ROLE-SENSOR-READ',scope_mode='ASSIGNED' WHERE account='admin1'");
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=(SELECT user_id FROM app_user WHERE account='admin1')");
        mvc.perform(post("/api/v1/weather-sensors").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/weather-sensors/"+id).header("Authorization",token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices/"+id).header("Authorization",token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices?type_code=weather_sensor").header("Authorization",token)).andExpect(jsonPath("$.data.total").value(0));
    }
    private String body(String token) throws Exception {
        var result=mvc.perform(get("/api/v1/mqtt-brokers/scopes").header("Authorization",token)).andExpect(status().isOk()).andReturn();
        var scope=mapper.readTree(result.getResponse().getContentAsString()).path("data").get(0);
        return mapper.writeValueAsString(java.util.Map.of("device_no","WX-"+UUID.randomUUID(),"name","测试天气传感器",
            "owner_org_id",scope.path("org_id").asText(),"district_id",scope.path("district_id").asText()));
    }
    private String create(String token,String body) throws Exception {
        var result=mvc.perform(post("/api/v1/weather-sensors").header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();return mapper.readTree(result.getResponse().getContentAsString()).path("data").path("device_id").asText();
    }
    private String login() throws Exception {
        var result=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
            .andExpect(status().isOk()).andReturn();return "Bearer "+mapper.readTree(result.getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
}
