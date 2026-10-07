package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
class LocalPlanNumberApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    String token, route;

    @BeforeEach void setup() throws Exception {
        token = "Bearer " + json.readTree(mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("session_id").asText();
        route = jdbc.queryForObject("SELECT v.route_version_id FROM route_version v JOIN route r ON r.route_id=v.route_id "
                + "WHERE r.source_mode='mock' AND r.enabled=TRUE AND r.owner_org_id IS NOT NULL FETCH FIRST 1 ROWS ONLY", String.class);
    }

    @ParameterizedTest @ValueSource(strings = {"mock", "replay"})
    void shortNumberIsStoredSearchableAndStableOnRetry(String mode) throws Exception {
        var body = input(mode);
        var first = send(body, 200);
        String id = first.path("subject_id").asText();
        String number = first.path("result").path("plan_no").asText();
        assertThat(number).matches("SIM-[0-9]{6,}").hasSizeLessThanOrEqualTo(16);
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT plan_no FROM flight_plan WHERE plan_id=?", String.class, id)).isEqualTo(number);
        assertThat(send(body, 200)).isEqualTo(first);
        var secondBody = input(mode);
        secondBody.put("start_at", (long) secondBody.get("start_at") + 60000);
        secondBody.put("end_at", (long) secondBody.get("end_at") + 60000);
        var second = send(secondBody, 200);
        assertThat(second.path("result").path("plan_no").asText()).isNotEqualTo(number).matches("SIM-[0-9]{6,}");
        var detail = json.readTree(mvc.perform(get("/api/v1/flight-plans/" + id).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(detail.path("plan_no").asText()).isEqualTo(number);
        var search = json.readTree(mvc.perform(get("/api/v1/flight-plans").param("keyword", number).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("items");
        assertThat(search).hasSize(1);
        assertThat(search.get(0).path("plan_id").asText()).isEqualTo(id);
    }

    @Test void localInputStillRejectsLiveSource() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan", Long.class);
        send(input("live"), 400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan", Long.class)).isEqualTo(before);
    }

    private Map<String, Object> input(String mode) {
        long start = System.currentTimeMillis() + 300000;
        return new LinkedHashMap<>(Map.of("message_id", "number-" + UUID.randomUUID(), "route_version_id", route,
                "uav_sn", "SN-" + UUID.randomUUID(), "start_at", start, "end_at", start + 3600000, "source_mode", mode));
    }

    private JsonNode send(Object body, int expected) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/local-interface-simulator/plans").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
}
