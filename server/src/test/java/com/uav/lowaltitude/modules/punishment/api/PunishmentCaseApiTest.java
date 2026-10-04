package com.uav.lowaltitude.modules.punishment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 处罚案件只保留查询。立案、指派、线索、裁量、复核、决定书和结案入口不再写入。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PunishmentCaseApiTest {
    private static final List<String> ALL = List.of("punishment:read", "punishment:file", "punishment:decide",
            "punishment:review", "punishment:close", "handoff:read");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private PunishmentFixture fixture;
    private String officer, reader;

    @BeforeEach
    void setUp() {
        fixture = new PunishmentFixture(jdbc);
        officer = fixture.user("OFF", ALL, false)[0];
        reader = fixture.user("RDR", List.of("punishment:read"), false)[0];
    }

    @AfterEach
    void tearDown() { fixture.cleanup(); }

    @Test
    void caseWritesAreClosedAndExistingReadsStayAvailable() throws Exception {
        int cases = count("punishment_case");
        int events = count("punishment_case_event");
        int reviews = count("punishment_review");
        int discretions = count("penalty_discretion");
        int documents = count("penalty_decision_document");

        submit("/api/v1/punishment-cases", officer, "{\"handoff_id\":\"missing\",\"party_type\":\"PERSON\",\"party_name\":\"张某\"}")
                .andExpect(status().isMethodNotAllowed());
        submit("/api/v1/punishment-cases/missing-case/assign", officer, "{\"officer_id\":\"whoever\",\"expected_version\":0}")
                .andExpect(status().isNotFound());
        submit("/api/v1/punishment-cases/missing-case/leads", officer, "{\"kind\":\"FACT\",\"description\":\"不再补录\",\"expected_version\":0}")
                .andExpect(status().isNotFound());
        submit("/api/v1/punishment-cases/missing-case/leads/missing-lead/resolve", officer, "{\"note\":\"\",\"expected_version\":0}")
                .andExpect(status().isNotFound());
        submit("/api/v1/punishment-cases/missing-case/reviews", officer, "{\"conclusion\":\"UPHELD\",\"expected_version\":0}")
                .andExpect(status().isNotFound());
        submit("/api/v1/punishment-cases/missing-case/discretions", officer,
                "{\"rule_code\":\"PR-01\",\"penalty_type\":\"FINE\",\"fine_amount\":1,\"basis_text\":\"已关闭\",\"expected_version\":0}")
                .andExpect(status().isNotFound());
        submit("/api/v1/punishment-cases/missing-case/discretions/missing-discretion/confirm", officer, "{\"expected_version\":0}")
                .andExpect(status().isNotFound());
        submit("/api/v1/punishment-cases/missing-case/decision-documents", officer, "{\"expected_version\":0}")
                .andExpect(status().isMethodNotAllowed());
        submit("/api/v1/decision-documents/missing-document/revoke", officer, "{\"reason\":\"不再作废\",\"expected_version\":0}")
                .andExpect(status().isNotFound());
        submit("/api/v1/punishment-cases/missing-case/close", officer, "{\"expected_version\":0}")
                .andExpect(status().isNotFound());
        submit("/api/v1/punishment-cases/missing-case/withdraw", officer, "{\"reason\":\"不再撤案\",\"expected_version\":0}")
                .andExpect(status().isNotFound());

        assertThat(count("punishment_case")).isEqualTo(cases);
        assertThat(count("punishment_case_event")).isEqualTo(events);
        assertThat(count("punishment_review")).isEqualTo(reviews);
        assertThat(count("penalty_discretion")).isEqualTo(discretions);
        assertThat(count("penalty_decision_document")).isEqualTo(documents);

        mvc.perform(get("/api/v1/punishment-cases").header("Authorization", bearer(reader)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/punishment-cases/{id}", "missing-case").header("Authorization", bearer(reader)))
                .andExpect(status().isNotFound());
    }

    @Test
    void penaltyRulesAreAllDemo() throws Exception {
        JsonNode data = body(mvc.perform(get("/api/v1/penalty-rules").header("Authorization", bearer(reader)))
                .andExpect(status().isOk())).path("data");
        assertThat(data).hasSize(10);
        for (JsonNode rule : data) {
            assertThat(rule.path("schema_status").asText()).isEqualTo("DEMO");
            assertThat(rule.path("legal_basis").asText()).contains("条款号待法制岗核定");
        }
    }

    private ResultActions submit(String path, String token, String content) throws Exception {
        return mvc.perform(post(path).header("Authorization", bearer(token))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
