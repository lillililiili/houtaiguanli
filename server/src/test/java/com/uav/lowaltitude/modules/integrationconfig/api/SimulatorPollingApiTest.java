package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="app.notifications.transport=simulator")
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class SimulatorPollingApiTest {
    private static final String BASE="/api/v1/local-interface-simulator";
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired com.uav.lowaltitude.modules.integrationconfig.application.RealtimeNotificationTransport transport;
    private String login() throws Exception {
        return "Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
    @Test void lightPollingReturnsOnlyReceiverMessagesAndDoesNotRenewTheLease() throws Exception {
        String token=login();
        jdbc.update("UPDATE app_user SET role_code='ROLE-BACKEND' WHERE account='admin1'");
        mvc.perform(get(BASE+"/session").header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.available").value(true)).andExpect(jsonPath("$.data.routes").doesNotExist());
        mvc.perform(get(BASE+"/receiver-context").header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.receiver_messages").isEmpty());
        mvc.perform(post(BASE+"/bindings").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"source_kind\":\"NOTIFICATION_CHANNEL\",\"source_id\":\"receiver\",\"enabled\":true}"))
            .andExpect(status().isOk());
        String subject=UUID.randomUUID().toString();
        transport.submit("RISK_NOTICE",subject,subject,json.createObjectNode());
        Long expiry=jdbc.queryForObject("SELECT expires_at FROM simulator_notification_receiver WHERE receiver_id='receiver'",Long.class);
        mvc.perform(get(BASE+"/receiver-context").header("Authorization",token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.receiver_messages[0].subject_id").value(subject))
            .andExpect(jsonPath("$.data.routes").doesNotExist()).andExpect(jsonPath("$.data.plans").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT expires_at FROM simulator_notification_receiver WHERE receiver_id='receiver'",Long.class)).isEqualTo(expiry);
    }
    @Test void pollingRetainsAuthenticationBackendIdentityAndScopeGuards() throws Exception {
        for(String path:new String[]{"/session","/receiver-context"})mvc.perform(get(BASE+path)).andExpect(status().isUnauthorized());
        String token=login();
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE account='admin1'");
        mvc.perform(get(BASE+"/session").header("Authorization",token)).andExpect(status().isOk());
        mvc.perform(get(BASE+"/receiver-context").header("Authorization",token)).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        for(String path:new String[]{"/session","/receiver-context"})mvc.perform(get(BASE+path).header("Authorization",token)).andExpect(status().isForbidden());
        String frontendRole="POLL-"+UUID.randomUUID().toString().substring(0,20);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,'Polling frontend fixture','',FALSE,TRUE,0,0,0,FALSE)",frontendRole);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'interfaces','OP',TRUE)",frontendRole);
        jdbc.update("UPDATE app_user SET scope_mode='ALL',role_code=? WHERE account='admin1'",frontendRole);
        for(String path:new String[]{"/session","/receiver-context"})mvc.perform(get(BASE+path).header("Authorization",token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BACKEND_ACCESS_DENIED"));
    }
    @Test void revokedSessionStopsBothPollingEndpoints() throws Exception {
        String token=login();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization",token)).andExpect(status().isOk());
        for(String path:new String[]{"/session","/receiver-context"})mvc.perform(get(BASE+path).header("Authorization",token)).andExpect(status().isUnauthorized());
    }
}
