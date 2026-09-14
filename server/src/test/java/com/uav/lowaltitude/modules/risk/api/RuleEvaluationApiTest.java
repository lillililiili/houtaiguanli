package com.uav.lowaltitude.modules.risk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 手动触发 C04/C05 评估。H2 没有 PostGIS：这里断言的是"如实报告后端不可用"，而不是让它假装算出了结果。
 * 本类不加 @Transactional：评估在 REQUIRES_NEW 事务里落运行记录，需要真实提交才能回读。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleEvaluationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private SpaceRiskFixture fixture;
    private String suffix, org, district, evaluator, readerOnly;

    @BeforeEach
    void seed() {
        fixture = new SpaceRiskFixture(jdbc);
        suffix = SpaceRiskFixture.id().substring(0, 8);
        org = SpaceRiskFixture.id(); district = SpaceRiskFixture.id();
        fixture.org(org, "ORG-EV-" + suffix); fixture.district(district, "DIST-EV-" + suffix);
        evaluator = fixture.session(fixture.role("EV-" + suffix, "risk:read", "risk:evaluate"), org, district, "ASSIGNED");
        readerOnly = fixture.session(fixture.role("EVR-" + suffix, "risk:read"), org, district, "ASSIGNED");
    }

    @Test
    void evaluationNeedsRiskEvaluatePermissionBeforeBodyIsParsed() throws Exception {
        mvc.perform(trigger(readerOnly, "{\"bogus\":1}", "key-" + SpaceRiskFixture.id()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(trigger(evaluator, "{\"rule_code\":\"C04\",\"window_from\":1,\"window_to\":2,\"extra\":1}", "key-" + SpaceRiskFixture.id()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        mvc.perform(trigger(evaluator, "{\"rule_code\":\"C99\",\"window_from\":1,\"window_to\":2}", "key-" + SpaceRiskFixture.id()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(trigger(evaluator, "{\"rule_code\":\"C04\",\"window_from\":2,\"window_to\":1}", "key-" + SpaceRiskFixture.id()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"));
        // 幂等键缺失同样在鉴权之后拒绝。
        mvc.perform(post("/api/v1/rule-evaluations").header("Authorization", "Bearer " + evaluator)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rule_code\":\"C04\",\"window_from\":1,\"window_to\":2}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void onH2EvaluationIsAcceptedAndReportedUnavailableWithARunRow() throws Exception {
        long from = SpaceRiskFixture.T0.toInstant().toEpochMilli(), to = SpaceRiskFixture.T0.plusMinutes(30).toInstant().toEpochMilli();
        // 演示种子已经写过两条空间风险：断言"本次评估没有新增"，而不是"库里一条都没有"。
        long risksBefore = jdbc.queryForObject("select count(*) from flight_risk where source_id like 'rule-engine-space-risk-%'", Long.class);
        String key = "eval-" + SpaceRiskFixture.id();
        String body = "{\"rule_code\":\"C04\",\"window_from\":" + from + ",\"window_to\":" + to + "}";
        JsonNode accepted = accepted(trigger(evaluator, body, key));
        String runId = accepted.path("run_id").asText();
        // 202 + UNAVAILABLE：请求已受理并留痕，但没有 PostGIS 就不产生任何风险，也不返回 500。
        assertThat(accepted.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(accepted.path("message").asText()).isEqualTo("SPATIAL_BACKEND_UNAVAILABLE");
        assertThat(accepted.path("risks_created").asInt()).isZero();
        assertThat(accepted.path("targets_seen").asInt()).isZero();
        assertThat(jdbc.queryForObject("select status from rule_evaluation_run where run_id=?", String.class, runId)).isEqualTo("UNAVAILABLE");
        assertThat(jdbc.queryForObject("select trigger_kind from rule_evaluation_run where run_id=?", String.class, runId)).isEqualTo("MANUAL");
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation_run where run_id=? and finished_at is not null", Long.class, runId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='rule_evaluation_triggered' and object_id=? and result='SUCCESS'", Long.class, runId)).isEqualTo(1L);
        // 评估不写风险：H2 上这次评估没有造出任何新的 SPACE_OBJECT 风险。
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where source_id like 'rule-engine-space-risk-%'", Long.class)).isEqualTo(risksBefore);

        // 同键同请求重放：409，且不产生第二条运行记录。
        mvc.perform(trigger(evaluator, body, key)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation_run where actor_id=(select user_id from app_session where session_id=?)", Long.class, evaluator)).isEqualTo(1L);

        JsonNode runs = data("/api/v1/rule-evaluations?rule_code=C04&size=100", evaluator);
        assertThat(runs.path("total").asLong()).isPositive();
        cleanup(runId);
    }

    @Test
    void runListRejectsUnknownFiltersAndNeedsRiskRead() throws Exception {
        mvc.perform(get("/api/v1/rule-evaluations?rule_code=NOPE").header("Authorization", "Bearer " + evaluator))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        String denied = fixture.session(fixture.role("EVD-" + suffix), org, district, "ASSIGNED");
        mvc.perform(get("/api/v1/rule-evaluations").header("Authorization", "Bearer " + denied))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    /** 本类不在测试事务里，运行记录会真实提交：清掉自己造的行，避免污染同库的其他用例。 */
    private void cleanup(String runId) {
        jdbc.update("delete from audit_log where object_id=?", runId);
        jdbc.update("delete from rule_evaluation_run where run_id=?", runId);
    }

    private JsonNode accepted(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private JsonNode data(String path, String session) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private static MockHttpServletRequestBuilder trigger(String session, String body, String key) {
        return post("/api/v1/rule-evaluations").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
