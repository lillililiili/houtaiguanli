package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CommissionScopeApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    String token, user, inside, outside, insideTask, outsideTask, org, district;

    @BeforeEach void fixtures() throws Exception {
        token=mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        inside=jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no='DEV-MOCK-001'",String.class);
        outside=jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no='DEV-MOCK-002'",String.class);
        insideTask=create(inside); outsideTask=create(outside);
        org=jdbc.queryForObject("SELECT org_id FROM app_org WHERE enabled=TRUE ORDER BY org_id FETCH FIRST 1 ROWS ONLY",String.class);
        district=jdbc.queryForObject("SELECT district_id FROM app_district WHERE enabled=TRUE ORDER BY district_id FETCH FIRST 1 ROWS ONLY",String.class);
        jdbc.update("DELETE FROM device_business_scope WHERE ops_device_id IN (?,?)",inside,outside);
        jdbc.update("INSERT INTO device_business_scope VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",inside,org,district);
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        jdbc.update("INSERT INTO app_user_data_scope (user_id,org_id,district_id) VALUES (?,?,?)",user,org,district);
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE user_id=?",user);
    }
    String create(String device) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/commission-tasks").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"device_id\":\""+device+"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("commission_id").asText();
    }
    int insideCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM commission_task WHERE device_id=?",Integer.class,inside); }
    @Test void filtersListAndCountBeforePagination() throws Exception {
        var body=mapper.readTree(mvc.perform(get("/api/v1/commission-tasks?size=1").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(body.path("total").asInt()).isEqualTo(insideCount());
        assertThat(body.path("items").get(0).path("commission_id").asText()).isEqualTo(insideTask);
        mvc.perform(get("/api/v1/commission-tasks?device_id="+outside).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"","/events","/report"})
    void hidesAllOutOfScopeReads(String suffix) throws Exception {
        mvc.perform(get("/api/v1/commission-tasks/"+outsideTask+suffix).header("Authorization","Bearer "+token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMISSION_TASK_NOT_FOUND"));
    }
    @Test void refusesOutOfScopeCreationAndPreviousTaskLink() throws Exception {
        mvc.perform(post("/api/v1/commission-tasks").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\""+outside+"\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DEVICE_NOT_FOUND"));
        mvc.perform(post("/api/v1/commission-tasks").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_id\":\""+inside+"\",\"previous_task_id\":\""+outsideTask+"\"}"))
                .andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"connect","configuration","start","cancel"})
    void refusesOutOfScopeMutationsBeforeStateChecks(String action) throws Exception {
        var request="configuration".equals(action)?put("/api/v1/commission-tasks/"+outsideTask+"/"+action):post("/api/v1/commission-tasks/"+outsideTask+"/"+action);
        mvc.perform(request.header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"transport\":\"TCP\",\"host\":\"127.0.0.1\",\"port\":10007}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("COMMISSION_TASK_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT version FROM commission_task WHERE commission_id=?",Long.class,outsideTask)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE payload=?",Long.class,outsideTask)).isZero();
    }
    @Test void authorizedTupleWorksAndRevocationImmediatelyBlocks() throws Exception {
        mvc.perform(get("/api/v1/commission-tasks/"+insideTask).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/commission-tasks/"+insideTask+"/connect").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}" )).andExpect(status().isAccepted());
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        mvc.perform(get("/api/v1/commission-tasks/"+insideTask).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/commission-tasks/"+insideTask+"/cancel").header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}" )).andExpect(status().isNotFound());
    }
    @Test void disabledOrganizationCannotGrantScope() throws Exception {
        jdbc.update("UPDATE app_org SET enabled=FALSE WHERE org_id=?",org);
        mvc.perform(get("/api/v1/commission-tasks").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/commission-tasks/"+insideTask).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
    @Test void requiresExactTupleInsteadOfCombiningOrganizationsAndDistricts() throws Exception {
        String otherOrg=jdbc.queryForObject("SELECT org_id FROM app_org WHERE enabled=TRUE AND org_id<>? ORDER BY org_id FETCH FIRST 1 ROWS ONLY",String.class,org);
        String otherDistrict=jdbc.queryForObject("SELECT district_id FROM app_district WHERE enabled=TRUE AND district_id<>? ORDER BY district_id FETCH FIRST 1 ROWS ONLY",String.class,district);
        jdbc.update("INSERT INTO device_business_scope VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",outside,org,otherDistrict);
        jdbc.update("INSERT INTO app_user_data_scope (user_id,org_id,district_id) VALUES (?,?,?)",user,otherOrg,otherDistrict);
        mvc.perform(get("/api/v1/commission-tasks/"+outsideTask).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/commission-tasks").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(insideCount()));
    }
    @Test void retainsAuthorizedHistoryAfterLogicalDeviceDeletion() throws Exception {
        jdbc.update("UPDATE commission_task SET status='CANCELLED' WHERE commission_id=?",insideTask);
        jdbc.update("UPDATE ops_device SET enabled=FALSE,deleted_at=1 WHERE device_id=?",inside);
        mvc.perform(get("/api/v1/commission-tasks/"+insideTask).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/commission-tasks").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(insideCount()));
    }
}
