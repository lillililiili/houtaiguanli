package com.uav.lowaltitude.modules.punishment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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

/** 裁量：区间校验、警告零金额、一案多版、确认即提请复核（决策 14-9 / 契约 v1.1）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PenaltyDiscretionTest {
    private static final List<String> ALL = List.of("punishment:read", "punishment:file", "punishment:decide",
            "punishment:review", "punishment:close", "handoff:read");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private PunishmentFixture fixture;
    private String officer;
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new PunishmentFixture(jdbc);
        officer = fixture.user("DIS", ALL, false)[0];
        caseId = body(mvc.perform(post("/api/v1/punishment-cases").header("Authorization", bearer(officer))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handoff_id\":\"" + fixture.punishmentHandoff() + "\",\"party_type\":\"PERSON\","
                                + "\"party_name\":\"张某\"}"))
                .andExpect(status().isCreated())).path("data").path("case_id").asText();
        mvc.perform(post("/api/v1/punishment-cases/{id}/assign", caseId).header("Authorization", bearer(officer))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"officer_id\":\"" + fixture.user("TGT", ALL, false)[1] + "\",\"expected_version\":0}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() { fixture.cleanup(); }

    @Test
    void fineBelowOrAboveTheRangeIsRejected() throws Exception {
        JsonNode rule = ruleByCode("PR-01");
        long min = rule.path("fine_min").asLong(), max = rule.path("fine_max").asLong();
        for (long outside : new long[]{min - 1, max + 1}) {
            draft("PR-01", "FINE", outside, 1).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("FINE_OUT_OF_RANGE"));
        }
        // 边界值本身合法：区间是闭区间，不能把两端也拒掉。
        draft("PR-01", "FINE", min, 1).andExpect(status().isOk());
    }

    @Test
    void warningCarriesNoFine() throws Exception {
        // PR-09 只允许警告；写着警告却带金额的裁量，落到文书上自相矛盾。
        draft("PR-09", "WARNING", 100, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        draft("PR-09", "WARNING", 0, 1).andExpect(status().isOk());
    }

    @Test
    void penaltyTypeMustBeSupportedByTheRule() throws Exception {
        // PR-09 档位只给警告，拿它开罚款就是越档。
        // 专码 PENALTY_TYPE_NOT_ALLOWED（决策 14-24）：与"金额不在区间"分开，前端才能说清是哪一种不合规。
        draft("PR-09", "FINE", 10000, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PENALTY_TYPE_NOT_ALLOWED"));
    }

    @Test
    void redraftingSupersedesTheOldDraft() throws Exception {
        draft("PR-01", "FINE", 30000, 1).andExpect(status().isOk());
        draft("PR-01", "FINE", 40000, 2).andExpect(status().isOk());
        // 一案同时只能有一份待确认裁量，否则"以哪一版为准"说不清。
        assertThat(count("status='DRAFT'")).isEqualTo(1);
        assertThat(count("status='SUPERSEDED'")).isEqualTo(1);
        assertThat(detail().path("current_discretion").path("fine_amount").asLong()).isEqualTo(40000);
        assertThat(detail().path("current_discretion").path("version_no").asInt()).isEqualTo(2);
    }

    @Test
    void confirmingMovesTheCaseIntoReviewWithoutASeparateSubmitStep() throws Exception {
        draft("PR-01", "FINE", 30000, 1).andExpect(status().isOk());
        String discretionId = detail().path("current_discretion").path("discretion_id").asText();
        mvc.perform(post("/api/v1/punishment-cases/{id}/discretions/{did}/confirm", caseId, discretionId)
                        .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"));
        // 契约 v1.1：确认裁量即提请复核，没有单独的"提交复核"动作。
        List<String> kinds = jdbc.queryForList("select event_kind from punishment_case_event where case_id=?"
                + " order by occurred_at asc, event_id asc", String.class, caseId);
        assertThat(kinds).contains("DISCRETION_CONFIRMED", "REVIEW_REQUESTED");
        assertThat(detail().path("allowed_actions").toString()).doesNotContain("SUBMIT_REVIEW");
    }

    @Test
    void draftingRequiresDecidePermission() throws Exception {
        String noDecide = fixture.user("NDE", List.of("punishment:read", "punishment:file", "handoff:read"), false)[0];
        mvc.perform(post("/api/v1/punishment-cases/{id}/discretions", caseId).header("Authorization", bearer(noDecide))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rule_code\":\"PR-01\",\"penalty_type\":\"FINE\",\"fine_amount\":30000,\"expected_version\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void draftingOnlyWhileInvestigating() throws Exception {
        draft("PR-01", "FINE", 30000, 1).andExpect(status().isOk());
        String discretionId = detail().path("current_discretion").path("discretion_id").asText();
        mvc.perform(post("/api/v1/punishment-cases/{id}/discretions/{did}/confirm", caseId, discretionId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":2}")).andExpect(status().isOk());
        // 进入复核后不能再改裁量：复核看的必须是提交给它的那一版。
        draft("PR-01", "FINE", 50000, 3).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    private ResultActions draft(String ruleCode, String penaltyType, long fine, long version) throws Exception {
        return mvc.perform(post("/api/v1/punishment-cases/{id}/discretions", caseId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_code\":\"" + ruleCode + "\",\"penalty_type\":\"" + penaltyType + "\","
                        + "\"fine_amount\":" + fine + ",\"basis_text\":\"演示裁量\",\"expected_version\":" + version + "}"));
    }

    private JsonNode ruleByCode(String code) throws Exception {
        JsonNode rules = body(mvc.perform(get("/api/v1/penalty-rules").header("Authorization", bearer(officer)))
                .andExpect(status().isOk())).path("data");
        for (JsonNode rule : rules) if (code.equals(rule.path("rule_code").asText())) return rule;
        throw new IllegalStateException("rule not seeded: " + code);
    }

    private JsonNode detail() throws Exception {
        return body(mvc.perform(get("/api/v1/punishment-cases/{id}", caseId).header("Authorization", bearer(officer)))
                .andExpect(status().isOk())).path("data");
    }

    private long count(String predicate) {
        return jdbc.queryForObject("select count(*) from penalty_discretion where case_id=? and " + predicate,
                Long.class, caseId);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
