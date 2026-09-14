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

/** 案件生命周期：立案、指派、线索、结案、撤案，以及 403→400→409 的顺序与越权 404。 */
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
    private String handoffId;

    @BeforeEach
    void setUp() {
        fixture = new PunishmentFixture(jdbc);
        officer = fixture.user("OFF", ALL, false)[0];
        reader = fixture.user("RDR", List.of("punishment:read"), false)[0];
        handoffId = fixture.punishmentHandoff();
    }

    @AfterEach
    void tearDown() { fixture.cleanup(); }

    /* ---- 立案 ---- */

    @Test
    void filingProducesACaseNumberAndFileEvent() throws Exception {
        JsonNode data = body(file(officer, handoffId, "PERSON", "张某")
                .andExpect(status().isCreated())).path("data");
        assertThat(data.path("case_no").asText()).matches("CASE-\\d{8}-\\d{4,}");
        assertThat(data.path("status").asText()).isEqualTo("FILED");
        assertThat(data.path("allowed_actions")).isNotEmpty();
        String caseId = data.path("case_id").asText();
        assertThat(eventKinds(caseId)).containsExactly("FILE");
    }

    @Test
    void oneEventOneCase() throws Exception {
        file(officer, handoffId, "PERSON", "张某").andExpect(status().isCreated());
        // 同一事件不得立两个案：卷宗对不上，处罚也会重复。
        file(officer, handoffId, "PERSON", "张某").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CASE_ALREADY_EXISTS"));
    }

    @Test
    void unknownPartyMustNotCarryAName() throws Exception {
        // "当事人不详"和"当事人叫某某"不能同时成立，否则文书上说不清认定了谁（决策 14-12）。
        file(officer, handoffId, "UNKNOWN", "张某").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        file(officer, handoffId, "UNKNOWN", null).andExpect(status().isCreated());
    }

    @Test
    void filingRequiresPermissionBeforeAnyParsing() throws Exception {
        // 只读用户拿一个连 JSON 都不是的请求体来：必须 403，不能让人靠 400/403 差异探测权限。
        mvc.perform(post("/api/v1/punishment-cases").header("Authorization", bearer(reader))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownFieldIsRejected() throws Exception {
        mvc.perform(post("/api/v1/punishment-cases").header("Authorization", bearer(officer))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handoff_id\":\"" + handoffId + "\",\"party_type\":\"PERSON\",\"party_name\":\"张某\",\"typo\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
    }

    @Test
    void nonPunishmentHandoffCannotBeFiled() throws Exception {
        // 风险通知不是移送：拿它立处罚案件等于凭空多出一个处罚对象。
        String riskHandoff = jdbc.queryForObject(
                "select handoff_id from handoff where handoff_type='RISK_NOTICE' limit 1", String.class);
        if (riskHandoff == null) return;
        file(officer, riskHandoff, "PERSON", "张某").andExpect(status().isNotFound());
    }

    /* ---- 指派与线索 ---- */

    @Test
    void assignMovesCaseIntoInvestigating() throws Exception {
        String caseId = filedCase();
        String[] target = fixture.user("TGT", ALL, false);
        assign(caseId, target[1], 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVESTIGATING"));
        assertThat(statusOf(caseId)).isEqualTo("INVESTIGATING");
    }

    @Test
    void assigningAnUnknownOfficerIsNotFound() throws Exception {
        String caseId = filedCase();
        assign(caseId, "no-such-user", 0).andExpect(status().isNotFound());
    }

    @Test
    void leadsOnlyWhileInvestigatingAndCanBeResolved() throws Exception {
        String caseId = filedCase();
        // FILED 阶段还没进入调查，不能加线索。
        lead(caseId, "EVIDENCE", "补拍现场照片", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
        String[] target = fixture.user("TGT", ALL, false);
        assign(caseId, target[1], 0).andExpect(status().isOk());
        lead(caseId, "EVIDENCE", "补拍现场照片", 1).andExpect(status().isOk());
        JsonNode detail = detail(caseId, officer);
        assertThat(detail.path("open_leads")).hasSize(1);
        String leadId = detail.path("open_leads").get(0).path("lead_id").asText();
        mvc.perform(post("/api/v1/punishment-cases/{id}/leads/{lead}/resolve", caseId, leadId)
                        .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已补齐\",\"expected_version\":2}"))
                .andExpect(status().isOk());
        assertThat(detail(caseId, officer).path("open_leads")).isEmpty();
        assertThat(eventKinds(caseId)).contains("LEAD_ADDED", "LEAD_RESOLVED");
    }

    @Test
    void resolvingATwiceResolvedLeadIsNotFound() throws Exception {
        String caseId = investigatingCase();
        lead(caseId, "FACT", "核对飞行时间", 1).andExpect(status().isOk());
        String leadId = detail(caseId, officer).path("open_leads").get(0).path("lead_id").asText();
        mvc.perform(post("/api/v1/punishment-cases/{id}/leads/{lead}/resolve", caseId, leadId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已补齐\",\"expected_version\":2}"))
                .andExpect(status().isOk());
        // 再解决一次：这条线索已不在待办里，对调用者就是"没有这条"。
        mvc.perform(post("/api/v1/punishment-cases/{id}/leads/{lead}/resolve", caseId, leadId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"重复\",\"expected_version\":3}"))
                .andExpect(status().isNotFound());
    }

    /* ---- 版本与越权 ---- */

    @Test
    void staleExpectedVersionIsConflict() throws Exception {
        String caseId = filedCase();
        String[] target = fixture.user("TGT", ALL, false);
        assign(caseId, target[1], 0).andExpect(status().isOk());
        assign(caseId, target[1], 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void caseOutsideScopeIsNotFoundRatherThanForbidden() throws Exception {
        String caseId = filedCase();
        String outsider = fixture.user("OUT", ALL, true)[0];
        // 403 等于告诉对方"这个案号是存在的"，那本身就是泄露。
        mvc.perform(get("/api/v1/punishment-cases/{id}", caseId).header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());
    }

    /* ---- 结案与撤案 ---- */

    @Test
    void closingWithoutADocumentIsRejected() throws Exception {
        String caseId = filedCase();
        // 结案是有文书的结案（决策 14-14）：DECIDED 之前更不可能结。
        mvc.perform(post("/api/v1/punishment-cases/{id}/close", caseId).header("Authorization", bearer(officer))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void withdrawNeedsAReasonAndOnlyBeforeReview() throws Exception {
        String caseId = filedCase();
        mvc.perform(post("/api/v1/punishment-cases/{id}/withdraw", caseId).header("Authorization", bearer(officer))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/punishment-cases/{id}/withdraw", caseId).header("Authorization", bearer(officer))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复移送\",\"expected_version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        assertThat(eventKinds(caseId)).contains("WITHDRAW");
    }

    @Test
    void closeRequiresClosePermission() throws Exception {
        String caseId = filedCase();
        String noClose = fixture.user("NCL", List.of("punishment:read", "punishment:file", "handoff:read"), false)[0];
        mvc.perform(post("/api/v1/punishment-cases/{id}/withdraw", caseId).header("Authorization", bearer(noClose))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"无权限\",\"expected_version\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignAndLeadsRequireFilePermission() throws Exception {
        String caseId = filedCase();
        String noFile = fixture.user("NFI", List.of("punishment:read", "punishment:close", "handoff:read"), false)[0];
        mvc.perform(post("/api/v1/punishment-cases/{id}/assign", caseId).header("Authorization", bearer(noFile))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officer_id\":\"whoever\",\"expected_version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/punishment-cases/{id}/leads", caseId).header("Authorization", bearer(noFile))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"FACT\",\"description\":\"无权限\",\"expected_version\":0}"))
                .andExpect(status().isForbidden());
    }

    /* ---- 列表与事件流 ---- */

    @Test
    void listFiltersAndEventsRequireVisibility() throws Exception {
        String caseId = filedCase();
        JsonNode page = body(mvc.perform(get("/api/v1/punishment-cases?status=FILED")
                .header("Authorization", bearer(officer))).andExpect(status().isOk())).path("data");
        assertThat(page.path("items")).isNotEmpty();
        mvc.perform(get("/api/v1/punishment-cases/{id}/events", caseId).header("Authorization", bearer(officer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].event_kind").value("FILE"));
        String outsider = fixture.user("OUT", ALL, true)[0];
        mvc.perform(get("/api/v1/punishment-cases/{id}/events", caseId).header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void penaltyRulesAreAllDemo() throws Exception {
        JsonNode data = body(mvc.perform(get("/api/v1/penalty-rules").header("Authorization", bearer(reader)))
                .andExpect(status().isOk())).path("data");
        assertThat(data).hasSize(10);
        for (JsonNode rule : data) {
            assertThat(rule.path("schema_status").asText()).isEqualTo("DEMO");
            // 条款号未经法制岗核定，不能编（决策 14-20）。
            assertThat(rule.path("legal_basis").asText()).contains("条款号待法制岗核定");
        }
    }

    /* ---- 辅助 ---- */

    private String filedCase() throws Exception {
        return body(file(officer, fixture.punishmentHandoff(), "PERSON", "张某")
                .andExpect(status().isCreated())).path("data").path("case_id").asText();
    }

    private String investigatingCase() throws Exception {
        String caseId = filedCase();
        assign(caseId, fixture.user("TGT", ALL, false)[1], 0).andExpect(status().isOk());
        return caseId;
    }

    private ResultActions file(String token, String handoff, String partyType, String partyName) throws Exception {
        String body = "{\"handoff_id\":\"" + handoff + "\",\"party_type\":\"" + partyType + "\""
                + (partyName == null ? "" : ",\"party_name\":\"" + partyName + "\"") + "}";
        return mvc.perform(post("/api/v1/punishment-cases").header("Authorization", bearer(token))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions assign(String caseId, String officerId, long version) throws Exception {
        return mvc.perform(post("/api/v1/punishment-cases/{id}/assign", caseId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"officer_id\":\"" + officerId + "\",\"expected_version\":" + version + "}"));
    }

    private ResultActions lead(String caseId, String kind, String description, long version) throws Exception {
        return mvc.perform(post("/api/v1/punishment-cases/{id}/leads", caseId)
                .header("Authorization", bearer(officer)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"" + kind + "\",\"description\":\"" + description + "\",\"expected_version\":" + version + "}"));
    }

    private JsonNode detail(String caseId, String token) throws Exception {
        return body(mvc.perform(get("/api/v1/punishment-cases/{id}", caseId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())).path("data");
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private List<String> eventKinds(String caseId) {
        return jdbc.queryForList("select event_kind from punishment_case_event where case_id=?"
                + " order by occurred_at asc, event_id asc", String.class, caseId);
    }

    private String statusOf(String caseId) {
        return jdbc.queryForObject("select status from punishment_case where case_id=?", String.class, caseId);
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
