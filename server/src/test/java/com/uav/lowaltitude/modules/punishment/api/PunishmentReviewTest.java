package com.uav.lowaltitude.modules.punishment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.punishment.domain.DecisionDocumentRenderer;

/** 复核与决定书：复核人≠承办人、结论决定去向、文书只能基于冻结裁量、结案要有文书。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PunishmentReviewTest {
    private static final List<String> ALL = List.of("punishment:read", "punishment:file", "punishment:decide",
            "punishment:review", "punishment:close", "handoff:read");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private PunishmentFixture fixture;
    private String officer, reviewer;
    private String officerId;
    private String caseId;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new PunishmentFixture(jdbc);
        String[] off = fixture.user("OFF", ALL, false);
        officer = off[0]; officerId = off[1];
        reviewer = fixture.user("REV", ALL, false)[0];
        caseId = underReviewCase();
    }

    @AfterEach
    void tearDown() { fixture.cleanup(); }

    @Test
    void officerMayNotReviewTheirOwnCase() throws Exception {
        // 复核的意义就在于换一双眼睛（决策 14-11）。
        review(officer, "UPHELD", null, 3).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REVIEW_SELF_NOT_ALLOWED"));
    }

    @Test
    void upheldDecidesTheCase() throws Exception {
        review(reviewer, "UPHELD", null, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECIDED"));
        assertThat(kinds()).contains("REVIEW_CONCLUDED");
    }

    @Test
    void insufficientSendsItBackAndAttachesMissingLeads() throws Exception {
        review(reviewer, "INSUFFICIENT",
                "[{\"kind\":\"EVIDENCE\",\"description\":\"缺现场照片\"},{\"kind\":\"PARTY_IDENTITY\",\"description\":\"当事人身份未认定\"}]",
                3).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INVESTIGATING"));
        JsonNode detail = detail();
        assertThat(detail.path("open_leads")).hasSize(2);
        // 退回时已确认的裁量必须作废：否则下一份决定书会基于一份已经被否掉的裁量——最难发现的错。
        assertThat(jdbc.queryForObject("select count(*) from penalty_discretion where case_id=? and status='CONFIRMED'",
                Long.class, caseId)).isZero();
    }

    @Test
    void revisedAlsoReturnsToInvestigating() throws Exception {
        // 决策 14-19 ③：REVISED 与 INSUFFICIENT 都回调查，前者要求重做裁量。
        review(reviewer, "REVISED", null, 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVESTIGATING"));
    }

    @Test
    void missingLeadNeedsBothKindAndDescription() throws Exception {
        review(reviewer, "INSUFFICIENT", "[{\"kind\":\"EVIDENCE\"}]", 3)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        review(reviewer, "INSUFFICIENT", "[{\"kind\":\"NOT_A_KIND\",\"description\":\"x\"}]", 3)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void malformedLeadIsFourHundredEvenWhenTheVersionIsAlsoWrong() throws Exception {
        // 决策 14-33：请求体的结构校验排在版本/状态判定之前。若顺序反了，这里会先吃 409 VERSION_CONFLICT，
        // 把调用者引去查"是不是并发冲突"，而真正错的是他刚发过来的那段 JSON。
        review(reviewer, "INSUFFICIENT", "[{\"kind\":\"EVIDENCE\"}]", 99)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void upheldMayNotCarryMissingLeads() throws Exception {
        // 维持原结论就意味着没有要补的。一份写着"维持"却挂着待补线索的复核意见自相矛盾；
        // 真让它过去更糟：案件进了 DECIDED，线索却挂在上面没人处理。
        review(reviewer, "UPHELD", "[{\"kind\":\"EVIDENCE\",\"description\":\"缺现场照片\"}]", 3)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        // 被拒之后案件不该有任何变化：既没进 DECIDED，也没挂上线索。
        assertThat(jdbc.queryForObject("select status from punishment_case where case_id=?", String.class, caseId))
                .isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from punishment_case_lead where case_id=?", Long.class, caseId))
                .isZero();
    }

    @Test
    void reviewRequiresReviewPermission() throws Exception {
        String noReview = fixture.user("NRV", List.of("punishment:read", "punishment:file", "handoff:read"), false)[0];
        review(noReview, "UPHELD", null, 3).andExpect(status().isForbidden());
    }

    /* ---- 决定书 ---- */

    @Test
    void documentCannotBeIssuedBeforeTheCaseIsDecided() throws Exception {
        issue(3).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void issuedDocumentCarriesWatermarkHashAndDownloads() throws Exception {
        review(reviewer, "UPHELD", null, 3).andExpect(status().isOk());
        JsonNode document = body(issue(4).andExpect(status().isCreated())).path("data");
        assertThat(document.path("document_no").asText()).matches("CASE-\\d{8}-\\d{4,}-DEC-\\d{2,}");
        assertThat(document.path("rendered_sha256").asText()).hasSize(64);
        assertThat(document.path("template_version").asText()).isEqualTo(DecisionDocumentRenderer.TEMPLATE_VERSION);
        String documentId = document.path("document_id").asText();

        MvcResult download = mvc.perform(get("/api/v1/decision-documents/{id}/content", documentId)
                        .header("Authorization", bearer(officer)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"" + document.path("document_no").asText() + ".txt\""))
                .andReturn();
        String text = download.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text.lines().findFirst().orElseThrow()).isEqualTo(DecisionDocumentRenderer.WATERMARK);
        // 下载的正文与落库的哈希必须对得上：这正是"事后能证明当时出具的是哪一份"的依据。
        assertThat(DecisionDocumentRenderer.sha256(text)).isEqualTo(document.path("rendered_sha256").asText());
        // 不补条款号（决策 14-20）。
        assertThat(text).contains("条款号待法制岗核定");
    }

    @Test
    void revokedDocumentKeepsItsReasonAndStopsCountingForClosing() throws Exception {
        review(reviewer, "UPHELD", null, 3).andExpect(status().isOk());
        String documentId = body(issue(4).andExpect(status().isCreated())).path("data").path("document_id").asText();
        JsonNode revoked = body(mvc.perform(post("/api/v1/decision-documents/{id}/revoke", documentId)
                        .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"金额计算有误\",\"expected_version\":0}"))
                .andExpect(status().isOk())).path("data");
        assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.path("revoke_reason").asText()).isEqualTo("金额计算有误");
        // 作废之后就不再是"有效文书"，结案条件重新不满足（决策 14-14）。
        mvc.perform(post("/api/v1/punishment-cases/{id}/close", caseId).header("Authorization", bearer(officer))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DECISION_DOCUMENT_REQUIRED"));
    }

    @Test
    void revokingTwiceSaysInvalidTransitionNotVersionConflict() throws Exception {
        review(reviewer, "UPHELD", null, 3).andExpect(status().isOk());
        String documentId = body(issue(4).andExpect(status().isCreated())).path("data").path("document_id").asText();
        revoke(documentId, 0).andExpect(status().isOk());
        // 决策 14-27 ②：重复作废是状态问题不是并发问题。答 VERSION_CONFLICT 会让人以为重试就能成功。
        revoke(documentId, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void revokingRequiresDecidePermission() throws Exception {
        review(reviewer, "UPHELD", null, 3).andExpect(status().isOk());
        String documentId = body(issue(4).andExpect(status().isCreated())).path("data").path("document_id").asText();
        String noDecide = fixture.user("NDE", List.of("punishment:read", "punishment:file", "handoff:read"), false)[0];
        mvc.perform(post("/api/v1/decision-documents/{id}/revoke", documentId)
                        .header("Authorization", bearer(noDecide)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"无权限\",\"expected_version\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void issuedDocumentRecordsTheOperatorNameNotTheAccount() throws Exception {
        review(reviewer, "UPHELD", null, 3).andExpect(status().isOk());
        JsonNode document = body(issue(4).andExpect(status().isCreated())).path("data");
        // 决策 14-27 ①：卷宗与文书上的"出具人"要是姓名，不是登录名。
        String account = jdbc.queryForObject("select account from app_user where user_id=?", String.class,
                jdbc.queryForObject("select issued_by from penalty_decision_document where document_id=?",
                        String.class, document.path("document_id").asText()));
        String name = jdbc.queryForObject("select name from app_user where account=?", String.class, account);
        assertThat(document.path("issued_by_name").asText()).isEqualTo(name).isNotEqualTo(account);
        assertThat(document.path("fields").path("issued_by_name").asText()).isEqualTo(name);
    }

    private ResultActions revoke(String documentId, long version) throws Exception {
        return mvc.perform(post("/api/v1/decision-documents/{id}/revoke", documentId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"金额计算有误\",\"expected_version\":" + version + "}"));
    }

    @Test
    void closingSucceedsOnceAnIssuedDocumentExists() throws Exception {
        review(reviewer, "UPHELD", null, 3).andExpect(status().isOk());
        issue(4).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/punishment-cases/{id}/close", caseId).header("Authorization", bearer(officer))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"已送达\",\"expected_version\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(kinds()).contains("DOCUMENT_ISSUED", "CLOSE");
    }

    /* ---- 辅助 ---- */

    private String underReviewCase() throws Exception {
        String id = body(mvc.perform(post("/api/v1/punishment-cases").header("Authorization", bearer(officer))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handoff_id\":\"" + fixture.punishmentHandoff() + "\",\"party_type\":\"PERSON\","
                                + "\"party_name\":\"张某\"}"))
                .andExpect(status().isCreated())).path("data").path("case_id").asText();
        mvc.perform(post("/api/v1/punishment-cases/{id}/assign", id).header("Authorization", bearer(officer))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"officer_id\":\"" + officerId + "\",\"expected_version\":0}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/punishment-cases/{id}/discretions", id).header("Authorization", bearer(officer))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rule_code\":\"PR-01\",\"penalty_type\":\"FINE\",\"fine_amount\":30000,"
                        + "\"basis_text\":\"演示裁量\",\"expected_version\":1}")).andExpect(status().isOk());
        String discretionId = jdbc.queryForObject(
                "select discretion_id from penalty_discretion where case_id=? and status='DRAFT'", String.class, id);
        mvc.perform(post("/api/v1/punishment-cases/{id}/discretions/{did}/confirm", id, discretionId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":2}")).andExpect(status().isOk());
        return id;
    }

    private ResultActions review(String token, String conclusion, String missingLeads, long version) throws Exception {
        String body = "{\"conclusion\":\"" + conclusion + "\",\"note\":\"演示复核意见\""
                + (missingLeads == null ? "" : ",\"missing_leads\":" + missingLeads)
                + ",\"expected_version\":" + version + "}";
        return mvc.perform(post("/api/v1/punishment-cases/{id}/reviews", caseId).header("Authorization", bearer(token))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions issue(long version) throws Exception {
        return mvc.perform(post("/api/v1/punishment-cases/{id}/decision-documents", caseId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":" + version + "}"));
    }

    private JsonNode detail() throws Exception {
        return body(mvc.perform(get("/api/v1/punishment-cases/{id}", caseId).header("Authorization", bearer(officer)))
                .andExpect(status().isOk())).path("data");
    }

    private List<String> kinds() {
        return jdbc.queryForList("select event_kind from punishment_case_event where case_id=?"
                + " order by occurred_at asc, event_id asc", String.class, caseId);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
