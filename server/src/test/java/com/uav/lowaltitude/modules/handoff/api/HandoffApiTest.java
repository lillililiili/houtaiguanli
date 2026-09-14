package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.audit.AuditService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HandoffApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @SpyBean AuditService audit;
    @SpyBean com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort channel;
    private String session;
    private String riskId;
    private String recipientId;

    @BeforeEach
    void fixture() {
        session = user(true, true, true, false, false);
        riskId = "risk-handoff-" + UUID.randomUUID().toString().substring(0, 8);
        insertNotifiableRisk(riskId, session);
        recipientId = "recipient-test-" + UUID.randomUUID().toString().substring(0, 8);
        insertRecipient(recipientId, "RISK_NOTICE", true);
    }

    @AfterEach
    void cleanup() {
        AuditService target = org.springframework.test.util.AopTestUtils.getTargetObject(audit);
        org.mockito.Mockito.reset(target);
        jdbc.update("delete from handoff_delivery where handoff_id in (select handoff_id from handoff where source_id like 'risk-handoff-%')");
        jdbc.update("delete from handoff_material_snapshot where handoff_id in (select handoff_id from handoff where source_id like 'risk-handoff-%')");
        jdbc.update("delete from handoff where source_id like 'risk-handoff-%'");
        jdbc.update("delete from flight_risk_verification where risk_id like 'risk-handoff-%'");
        jdbc.update("delete from flight_risk where risk_id like 'risk-handoff-%'");
        jdbc.update("delete from handoff_recipient where recipient_id like 'recipient-test-%'");
        jdbc.update("delete from audit_log where account like 'handoff-w-%'");
        jdbc.update("delete from idempotency_request where user_id in (select user_id from app_user where account like 'handoff-w-%')");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'handoff-w-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'handoff-w-%')");
        jdbc.update("delete from app_user where account like 'handoff-w-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-HANDOFF-W-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-HANDOFF-W-%'");
    }

    @Test
    void missingCreateOrSourceReadPermissionIsForbiddenBeforeAnyValidation() throws Exception {
        String noCreate = user(true, true, false, false, false);
        String noRiskRead = user(false, true, true, false, false);
        for (String denied : new String[]{noCreate, noRiskRead}) {
            mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(denied))
                    .contentType(MediaType.APPLICATION_JSON).content("{not-json")).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(denied))
                    .header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON)
                    .content(body("RISK", "missing", "RISK_NOTICE", "nobody", 0) .replace("}", ",\"delivery_status\":\"DELIVERED\"}")))
                    .andExpect(status().isForbidden());
        }
        String noHandoffRead = user(true, false, true, false, false);
        mvc.perform(get("/api/v1/handoffs?wat=1").header("Authorization", bearer(noHandoffRead))).andExpect(status().isForbidden());
        String neither = user(true, false, false, false, false);
        mvc.perform(get("/api/v1/handoff-recipients?handoff_type=BOGUS").header("Authorization", bearer(neither))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/handoffs/{id}", " ").header("Authorization", bearer(noHandoffRead))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/handoffs/{id}/deliveries?page=0", " ").header("Authorization", bearer(noHandoffRead))).andExpect(status().isForbidden());
        assertThat(handoffCount(riskId)).isZero();
    }

    @Test
    void recipientCatalogOpensToCreateOnlyRolesButNeverToRolesWithNeitherPermission() throws Exception {
        String createOnly = user(true, false, true, false, false);
        mvc.perform(get("/api/v1/handoff-recipients?handoff_type=RISK_NOTICE").header("Authorization", bearer(createOnly)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.recipient_id=='" + recipientId + "')].display_name").value("测试接收方"));
        // 鉴权仍先于 handoff_type 解析：有 create 权限时坏参数才轮到 400。
        mvc.perform(get("/api/v1/handoff-recipients?handoff_type=BOGUS").header("Authorization", bearer(createOnly))).andExpect(status().isBadRequest());
        String readOnly = user(true, true, false, false, false);
        mvc.perform(get("/api/v1/handoff-recipients?handoff_type=RISK_NOTICE").header("Authorization", bearer(readOnly))).andExpect(status().isOk());
        String neither = user(true, false, false, false, false);
        mvc.perform(get("/api/v1/handoff-recipients?handoff_type=RISK_NOTICE").header("Authorization", bearer(neither))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/handoff-recipients?handoff_type=BOGUS").header("Authorization", bearer(neither))).andExpect(status().isForbidden());
    }

    @Test
    void pendingVerificationAndExcludedRisksCannotBeHandedOff() throws Exception {
        String pending = "risk-handoff-pv-" + UUID.randomUUID().toString().substring(0, 6);
        String excluded = "risk-handoff-ex-" + UUID.randomUUID().toString().substring(0, 6);
        insertRisk(pending, "PENDING_VERIFICATION", 0, "seed-stage3-plan-legal", "seed-stage3-rv-legal", "seed-stage3-org", "seed-stage3-district");
        insertRisk(excluded, "EXCLUDED", 1, "seed-stage3-plan-legal", "seed-stage3-rv-legal", "seed-stage3-org", "seed-stage3-district");
        create(session, body("RISK", pending, "RISK_NOTICE", recipientId, 0), "pv-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
        create(session, body("RISK", excluded, "RISK_NOTICE", recipientId, 1), "ex-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
        assertThat(handoffCount(pending)).isZero();
        assertThat(handoffCount(excluded)).isZero();
    }

    @Test
    void uavPunishmentIsBlockedForEverySourceKind() throws Exception {
        create(session, body("RISK", riskId, "UAV_PUNISHMENT", recipientId, 1), "up-risk-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("HANDOFF_PREREQUISITE_UNAVAILABLE"));
        // 决策 14-21 修订：来源读权先于前提校验。session 没有 alarm:read，对**任意**事件 id 都只能拿到 403——
        // 前提校验用的 completedExists 不带范围过滤，若排在读权之前，只有 handoff:create 的人
        // 就能靠 409/403 的差异跨机构探测"那边有没有处置完成的事件"。
        create(session, body("UAV_EVENT", "event-any", "UAV_PUNISHMENT", recipientId, 0), "up-event-" + UUID.randomUUID())
                .andExpect(status().isForbidden());
        assertThat(handoffCount(riskId)).isZero();
    }

    @Test
    void emptyRecipientCatalogIsConflict() throws Exception {
        List<String> enabled = jdbc.queryForList("select recipient_id from handoff_recipient where enabled=true", String.class);
        jdbc.update("update handoff_recipient set enabled=false");
        try {
            create(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "empty-" + UUID.randomUUID())
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RECIPIENT_NOT_CONFIGURED"));
        } finally {
            for (String id : enabled) jdbc.update("update handoff_recipient set enabled=true where recipient_id=?", id);
        }
        assertThat(handoffCount(riskId)).isZero();
    }

    /**
     * 决策 18-14 / 18-16：风险通知不该再让值班员选接收方——上级就那一个，每次问一遍既慢又容易选错。
     * 不传接收方时依次找：标了默认的那个 → 该类型唯一的启用接收方 → 都没有才 400。
     * 三条用例分别钉这三档，缺任何一条这套回落规则都可能悄悄退化成另一种。
     */
    @Test
    void riskNoticeWithoutRecipientFallsBackToTheDefaultOne() throws Exception {
        String risk = "risk-handoff-dflt-" + UUID.randomUUID().toString().substring(0, 8);
        insertNotifiableRisk(risk, session);
        // 库里本来就有一个默认接收方；不先让开，命中哪一个就取决于 ID 排序，这条用例等于没钉住任何东西。
        List<String> defaults = riskNoticeDefaults();
        jdbc.update("update handoff_recipient set is_default=false where handoff_type='RISK_NOTICE'");
        jdbc.update("update handoff_recipient set is_default=true where recipient_id=?", recipientId);
        String withoutRecipient = "{\"source_kind\":\"RISK\",\"source_id\":\"" + risk
                + "\",\"handoff_type\":\"RISK_NOTICE\",\"expected_version\":1}";

        MvcResult result;
        try {
            result = create(session, withoutRecipient, "default-" + UUID.randomUUID())
                    .andExpect(status().isCreated()).andReturn();
        } finally {
            restoreRiskNoticeDefaults(defaults);
        }
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(body.path("recipient_id").asText()).isEqualTo(recipientId);
        // 本类固定跑"未接通"渠道：回执结果是上级给的，没投出去就不该有；空值字段照本接口惯例整条不出现。
        assertThat(body.has("receipt_result")).isFalse();
    }

    /** 决策 18-16：一个默认都没标，但该类型只有一个启用接收方——只有一个的时候没有可选的余地，不该再要求他抄一遍。 */
    @Test
    void riskNoticeWithoutRecipientUsesTheOnlyEnabledOneWhenNoneIsDefault() throws Exception {
        String risk = "risk-handoff-dflt-" + UUID.randomUUID().toString().substring(0, 8);
        insertNotifiableRisk(risk, session);
        List<String> defaults = riskNoticeDefaults();
        List<String> others = jdbc.queryForList("select recipient_id from handoff_recipient"
                + " where handoff_type='RISK_NOTICE' and enabled=true and recipient_id<>?", String.class, recipientId);
        jdbc.update("update handoff_recipient set is_default=false where handoff_type='RISK_NOTICE'");
        for (String id : others) jdbc.update("update handoff_recipient set enabled=false where recipient_id=?", id);
        String withoutRecipient = "{\"source_kind\":\"RISK\",\"source_id\":\"" + risk
                + "\",\"handoff_type\":\"RISK_NOTICE\",\"expected_version\":1}";
        MvcResult result;
        try {
            result = create(session, withoutRecipient, "sole-" + UUID.randomUUID())
                    .andExpect(status().isCreated()).andReturn();
        } finally {
            for (String id : others) jdbc.update("update handoff_recipient set enabled=true where recipient_id=?", id);
            restoreRiskNoticeDefaults(defaults);
        }
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("recipient_id").asText()).isEqualTo(recipientId);
    }

    /** 决策 18-16：有好几个启用接收方又没标默认，服务端替不了值班员决定发给谁——这时才 400。 */
    @Test
    void riskNoticeWithoutRecipientIsRejectedWhenSeveralAreEnabledAndNoneIsDefault() throws Exception {
        String risk = "risk-handoff-dflt-" + UUID.randomUUID().toString().substring(0, 8);
        insertNotifiableRisk(risk, session);
        // 自己再插一个，"有好几个"就不依赖库里恰好还剩几个接收方——那正是这条规则的分界点。
        insertRecipient("recipient-test-second-" + UUID.randomUUID().toString().substring(0, 6), "RISK_NOTICE", true);
        List<String> defaults = riskNoticeDefaults();
        jdbc.update("update handoff_recipient set is_default=false where handoff_type='RISK_NOTICE'");
        String withoutRecipient = "{\"source_kind\":\"RISK\",\"source_id\":\"" + risk
                + "\",\"handoff_type\":\"RISK_NOTICE\",\"expected_version\":1}";
        try {
            create(session, withoutRecipient, "nodefault-" + UUID.randomUUID())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("RECIPIENT_REQUIRED"));
        } finally {
            restoreRiskNoticeDefaults(defaults);
        }
    }

    private List<String> riskNoticeDefaults() {
        return jdbc.queryForList("select recipient_id from handoff_recipient"
                + " where handoff_type='RISK_NOTICE' and is_default=true", String.class);
    }

    private void restoreRiskNoticeDefaults(List<String> defaults) {
        for (String id : defaults) jdbc.update("update handoff_recipient set is_default=true where recipient_id=?", id);
    }

    @Test
    void unknownDisabledOrWrongTypeRecipientIsNotFound() throws Exception {
        String disabled = "recipient-test-off-" + UUID.randomUUID().toString().substring(0, 6);
        String wrongType = "recipient-test-uav-" + UUID.randomUUID().toString().substring(0, 6);
        insertRecipient(disabled, "RISK_NOTICE", false);
        insertRecipient(wrongType, "UAV_PUNISHMENT", true);
        for (String recipient : new String[]{"recipient-missing", disabled, wrongType}) {
            create(session, body("RISK", riskId, "RISK_NOTICE", recipient, 1), "rcpt-" + UUID.randomUUID())
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RECIPIENT_NOT_FOUND"));
        }
        assertThat(handoffCount(riskId)).isZero();
    }

    @Test
    void invisibleOrCrossScopeRiskIsNotFound() throws Exception {
        String cross = "risk-handoff-cross-" + UUID.randomUUID().toString().substring(0, 6);
        insertRisk(cross, "PENDING_NOTIFICATION", 1, "seed-stage3-plan-cross-scope", "seed-stage3-rv-cross-scope",
                "seed-stage3-other-org", "seed-stage3-other-district");
        create(session, body("RISK", cross, "RISK_NOTICE", recipientId, 1), "cross-" + UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        create(session, body("RISK", "risk-handoff-missing", "RISK_NOTICE", recipientId, 1), "missing-" + UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(handoffCount(cross)).isZero();
    }

    @Test
    void strictBodyRejectsSmuggledDeliveryFieldsAndMalformedJson() throws Exception {
        String valid = body("RISK", riskId, "RISK_NOTICE", recipientId, 1);
        for (String smuggled : new String[]{"\"delivery_status\":\"DELIVERED\"", "\"receipt_status\":\"ACKNOWLEDGED\"", "\"delivered_at\":1"}) {
            create(session, valid.replace("}", "," + smuggled + "}"), "smuggle-" + UUID.randomUUID())
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        }
        for (String malformed : new String[]{
                "{not-json",
                "{\"source_kind\":\"RISK\",\"source_kind\":\"RISK\",\"source_id\":\"" + riskId + "\",\"handoff_type\":\"RISK_NOTICE\",\"recipient_id\":\"" + recipientId + "\",\"expected_version\":1}",
                "{\"source_kind\":\"RISK\",\"source_id\":\"" + riskId + "\",\"handoff_type\":\"RISK_NOTICE\",\"recipient_id\":\"" + recipientId + "\"}",
                "{\"source_kind\":\"RISK\",\"source_id\":\"" + riskId + "\",\"handoff_type\":\"RISK_NOTICE\",\"recipient_id\":\"" + recipientId + "\",\"expected_version\":\"1\"}",
                "{\"source_kind\":1,\"source_id\":\"" + riskId + "\",\"handoff_type\":\"RISK_NOTICE\",\"recipient_id\":\"" + recipientId + "\",\"expected_version\":1}",
                valid + " {}"}) {
            create(session, malformed, "malformed-" + UUID.randomUUID())
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        create(session, body("DEVICE_INCIDENT", "incident-1", "RISK_NOTICE", recipientId, 1), "kind-" + UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_KIND"));
        // 同上（14-21 修订）：声明 UAV_EVENT 来源就要先有 alarm:read，组合是否成立轮不到无权限者知道。
        create(session, body("UAV_EVENT", "event-1", "RISK_NOTICE", recipientId, 1), "kind-mismatch-" + UUID.randomUUID())
                .andExpect(status().isForbidden());
        create(session, body("RISK", riskId, "MAGIC", recipientId, 1), "type-" + UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(handoffCount(riskId)).isZero();
    }

    @Test
    void staleVersionIsConflictWithoutRows() throws Exception {
        create(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 0), "stale-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(handoffCount(riskId)).isZero();
        assertThat(state(riskId)).isEqualTo("PENDING_NOTIFICATION");
    }

    @Test
    void acknowledgedDeliveryRecordsBothStagesAndPreservesSnapshotVersion() throws Exception {
        var at = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        org.mockito.Mockito.doReturn(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome(
                "DELIVERED", "ACKNOWLEDGED", null, null, at, at, at)).when(channel).deliver(org.mockito.ArgumentMatchers.any());
        String id = created(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "ack-" + UUID.randomUUID());
        assertThat(state(riskId)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, riskId)).isEqualTo(3);
        mvc.perform(get("/api/v1/handoffs/{id}", id).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.material.risk.state").value("PENDING_NOTIFICATION"))
                .andExpect(jsonPath("$.data.material.risk.version").value(1));
        create(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 3), "again-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("HANDOFF_ALREADY_EXISTS"));
        assertThat(handoffCount(riskId)).isOne();
        mvc.perform(get("/api/v1/risks/{id}", riskId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("ACKNOWLEDGED"));
        mvc.perform(get("/api/v1/risks?state=ACKNOWLEDGED").header("Authorization", bearer(session)))
                .andExpect(status().isOk());
    }

    @Test
    void confirmationAfterEarlierSubmissionAdvancesOnlyOnceAndNeverRegresses() throws Exception {
        created(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "submit-" + UUID.randomUUID());
        assertThat(state(riskId)).isEqualTo("NOTIFIED");
        var at = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        org.mockito.Mockito.doReturn(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome(
                "DELIVERED", "ACKNOWLEDGED", null, null, at, at, at)).when(channel).deliver(org.mockito.ArgumentMatchers.any());
        String second = "recipient-test-" + UUID.randomUUID().toString().substring(0, 8);
        insertRecipient(second, "RISK_NOTICE", true);
        created(session, body("RISK", riskId, "RISK_NOTICE", second, 2), "ack-later-" + UUID.randomUUID());
        assertThat(state(riskId)).isEqualTo("ACKNOWLEDGED");
        String third = "recipient-test-" + UUID.randomUUID().toString().substring(0, 8);
        insertRecipient(third, "RISK_NOTICE", true);
        org.mockito.Mockito.doReturn(com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome.notConnected())
                .when(channel).deliver(org.mockito.ArgumentMatchers.any());
        created(session, body("RISK", riskId, "RISK_NOTICE", third, 3), "third-" + UUID.randomUUID());
        assertThat(state(riskId)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, riskId)).isEqualTo(3L);
    }

    @Test
    void simulatedAcknowledgmentCannotCloseLiveRisk() throws Exception {
        jdbc.update("update flight_risk set source_id='rule-engine-space-risk-live',source_mode='live' where risk_id=?", riskId);
        var at = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        org.mockito.Mockito.doReturn(true).when(channel).simulated();
        org.mockito.Mockito.doReturn(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome(
                "DELIVERED", "ACKNOWLEDGED", null, null, at, at, at)).when(channel).deliver(org.mockito.ArgumentMatchers.any());
        created(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "live-mock-" + UUID.randomUUID());
        assertThat(state(riskId)).isEqualTo("NOTIFIED");
    }

    @Test
    void deliveredWithoutAcknowledgmentRemainsNotified() throws Exception {
        var at = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        org.mockito.Mockito.doReturn(new com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome(
                "DELIVERED", "PENDING", null, null, at, at, null)).when(channel).deliver(org.mockito.ArgumentMatchers.any());
        created(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "noack-" + UUID.randomUUID());
        assertThat(state(riskId)).isEqualTo("NOTIFIED");
    }

    @Test
    void submissionMarksNotifiedEvenWhenChannelIsUnavailable() throws Exception {
        MvcResult result = create(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "ok-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source_kind").value("RISK"))
                .andExpect(jsonPath("$.data.source_id").value(riskId))
                .andExpect(jsonPath("$.data.handoff_type").value("RISK_NOTICE"))
                .andExpect(jsonPath("$.data.recipient_id").value(recipientId))
                .andExpect(jsonPath("$.data.source_version").value(1))
                .andExpect(jsonPath("$.data.delivery_status").value("PENDING_DELIVERY"))
                .andExpect(jsonPath("$.data.receipt_status").value("NOT_EXPECTED"))
                .andExpect(jsonPath("$.data.blocked_reason").value("CHANNEL_NOT_CONNECTED"))
                .andExpect(jsonPath("$.data.created_at").isNumber())
                .andReturn();
        String handoffId = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("handoff_id").asText();
        assertThat(handoffId).isNotBlank();
        assertThat(state(riskId)).isEqualTo("NOTIFIED");
        assertThat(jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, riskId)).isEqualTo(2L);
        assertThat(handoffCount(riskId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from handoff_material_snapshot where handoff_id=? and schema_version=1", Long.class, handoffId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=? and attempt_no=1 and delivery_status='PENDING_DELIVERY' and receipt_status='NOT_EXPECTED' and blocked_reason='CHANNEL_NOT_CONNECTED' and submitted_at is null and delivered_at is null and acknowledged_at is null", Long.class, handoffId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from handoff where handoff_id=? and risk_id=? and event_id is null and owner_org_id='seed-stage3-org' and district_id='seed-stage3-district' and source_mode='mock'", Long.class, handoffId, riskId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where module_code='handoff' and action='handoff_created' and object_type='handoff' and object_id=? and result='SUCCESS'", Long.class, handoffId)).isEqualTo(1L);
    }

    @Test
    void secondUserWithDifferentKeyGetsAlreadyExistsAndOnlyOneHandoffRemains() throws Exception {
        String other = user(true, true, true, false, false);
        create(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "first-" + UUID.randomUUID()).andExpect(status().isCreated());
        create(other, body("RISK", riskId, "RISK_NOTICE", recipientId, 2), "second-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("HANDOFF_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.error.handoff_id").doesNotExist());
        assertThat(handoffCount(riskId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery d join handoff h on h.handoff_id=d.handoff_id where h.source_id=?", Long.class, riskId)).isEqualTo(1L);
    }

    @Test
    void sameKeyReplayIsRejectedWithoutSecondRecord() throws Exception {
        String key = "replay-" + UUID.randomUUID();
        create(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), key).andExpect(status().isCreated());
        create(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), key)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        assertThat(handoffCount(riskId)).isEqualTo(1L);
    }

    @Test
    void auditFailureRollsBackHandoffSnapshotDeliveryAndIdempotencyClaim() throws Exception {
        AuditService target = org.springframework.test.util.AopTestUtils.getTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(target).record(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("handoff"),
                org.mockito.ArgumentMatchers.eq("handoff_created"), org.mockito.ArgumentMatchers.eq("handoff"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("SUCCESS"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        create(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "audit-fail-" + UUID.randomUUID())
                .andExpect(status().is5xxServerError());
        org.mockito.Mockito.reset(target);
        assertThat(handoffCount(riskId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from handoff_material_snapshot s join handoff h on h.handoff_id=s.handoff_id where h.source_id=?", Long.class, riskId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery d join handoff h on h.handoff_id=d.handoff_id where h.source_id=?", Long.class, riskId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id=(select user_id from app_session where session_id=?)", Long.class, session)).isZero();
        assertThat(state(riskId)).isEqualTo("PENDING_NOTIFICATION");
    }

    @Test
    void listDetailAndDeliveriesShareScopeAndCount() throws Exception {
        String handoffId = created(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "list-" + UUID.randomUUID());
        mvc.perform(get("/api/v1/handoffs?source_kind=RISK&source_id={id}", riskId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].handoff_id").value(handoffId))
                .andExpect(jsonPath("$.data.items[0].delivery_status").value("PENDING_DELIVERY"))
                .andExpect(jsonPath("$.data.items[0].receipt_status").value("NOT_EXPECTED"))
                .andExpect(jsonPath("$.data.items[0].blocked_reason").value("CHANNEL_NOT_CONNECTED"))
                .andExpect(jsonPath("$.data.items[0].recipient_id").value(recipientId))
                .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(20));
        mvc.perform(get("/api/v1/handoffs?source_id={id}&delivery_status=DELIVERED", riskId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
        for (String query : new String[]{"page_size=1", "delivery_status=SENT", "created_from=5&created_to=5", "source_kind=RISK&source_kind=RISK", "wat=1", "source_mode=browser"}) {
            mvc.perform(get("/api/v1/handoffs?" + query).header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handoff_id").value(handoffId))
                .andExpect(jsonPath("$.data.material.schema_version").value(1))
                .andExpect(jsonPath("$.data.material.risk.risk_id").value(riskId))
                .andExpect(jsonPath("$.data.material.risk.state").value("PENDING_NOTIFICATION"))
                .andExpect(jsonPath("$.data.material.risk.version").value(1))
                .andExpect(jsonPath("$.data.material.verifications[0].conclusion").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.material.verifications[0].resulting_state").value("PENDING_NOTIFICATION"))
                .andExpect(jsonPath("$.data.material.files").doesNotExist())
                .andExpect(jsonPath("$.data.latest_delivery.attempt_no").value(1))
                .andExpect(jsonPath("$.data.latest_delivery.delivery_status").value("PENDING_DELIVERY"))
                .andExpect(jsonPath("$.data.latest_delivery.blocked_reason").value("CHANNEL_NOT_CONNECTED"));
        mvc.perform(get("/api/v1/handoffs/{id}/deliveries", handoffId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].attempt_no").value(1))
                .andExpect(jsonPath("$.data.items[0].delivery_status").value("PENDING_DELIVERY"));
        mvc.perform(get("/api/v1/handoffs/{id}/deliveries?page=1&page=2", handoffId).header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        String otherScope = user(true, true, true, false, true);
        mvc.perform(get("/api/v1/handoffs?source_id={id}", riskId).header("Authorization", bearer(otherScope)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(otherScope)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/handoffs/{id}/deliveries", handoffId).header("Authorization", bearer(otherScope)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/handoff-recipients?handoff_type=RISK_NOTICE").header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[?(@.recipient_id=='" + recipientId + "')].display_name").value("测试接收方"));
        mvc.perform(get("/api/v1/handoff-recipients?handoff_type=BOGUS").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/handoff-recipients?wat=1").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
    }

    @Test
    void snapshotDropsReferencesTheReaderCannotSeeNow() throws Exception {
        String creator = user(true, true, true, true, false);
        String handoffId = created(creator, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "snap-" + UUID.randomUUID());
        String stored = jdbc.queryForObject("select cast(snapshot as varchar) from handoff_material_snapshot where handoff_id=?", String.class, handoffId);
        JsonNode snapshot = objectMapper.readTree(stored);
        // H2 把 CAST(? AS JSON) 的字符串存成 JSON 文本；PostgreSQL 直接存对象，两种形态都要能验证。
        if (snapshot.isTextual()) snapshot = objectMapper.readTree(snapshot.textValue());
        // 落库的快照必须是带 schema_version 的 JSON 对象，而不是被包成纯文本的字符串。
        assertThat(snapshot.isObject()).isTrue();
        assertThat(snapshot.path("schema_version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select schema_version from handoff_material_snapshot where handoff_id=?", Integer.class, handoffId)).isEqualTo(1);
        assertThat(snapshot.path("references").path("plan_id").asText()).isEqualTo("seed-stage3-plan-legal");
        assertThat(snapshot.path("references").has("route_version_id")).isFalse();
        assertThat(snapshot.has("files")).isFalse();
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(creator)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.material.references.plan_id").value("seed-stage3-plan-legal"));
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.material.risk.risk_id").value(riskId))
                .andExpect(jsonPath("$.data.material.references.plan_id").doesNotExist())
                .andExpect(jsonPath("$.data.material.references.hidden_count").doesNotExist());
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(session)))
                .andExpect(jsonPath("$.data.availability.material").value("AVAILABLE"));
        String handoffOnly = user(false, true, false, true, false);
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(handoffOnly)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handoff_id").value(handoffId))
                .andExpect(jsonPath("$.data.material.schema_version").value(1))
                .andExpect(jsonPath("$.data.material.risk").doesNotExist())
                .andExpect(jsonPath("$.data.material.verifications").doesNotExist())
                .andExpect(jsonPath("$.data.material.references").doesNotExist())
                .andExpect(jsonPath("$.data.availability.material").value("FORBIDDEN"));
    }

    @Test
    void materialIsOmittedWhenSourceRiskLeavesTheReadersVisibleScope() throws Exception {
        String handoffId = created(session, body("RISK", riskId, "RISK_NOTICE", recipientId, 1), "scope-" + UUID.randomUUID());
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.material.risk.risk_id").value(riskId))
                .andExpect(jsonPath("$.data.availability.material").value("AVAILABLE"));
        // 源风险归属变化后，交接头仍按自身归属可见，但旧快照的风险材料与核实历史不得继续泄漏。
        jdbc.update("update flight_risk set district_id='seed-stage3-other-district' where risk_id=?", riskId);
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handoff_id").value(handoffId))
                .andExpect(jsonPath("$.data.latest_delivery.attempt_no").value(1))
                .andExpect(jsonPath("$.data.material.schema_version").value(1))
                .andExpect(jsonPath("$.data.material.risk").doesNotExist())
                .andExpect(jsonPath("$.data.material.verifications").doesNotExist())
                .andExpect(jsonPath("$.data.material.references").doesNotExist())
                .andExpect(jsonPath("$.data.material.hidden_count").doesNotExist())
                .andExpect(jsonPath("$.data.availability.material").value("SOURCE_NOT_VISIBLE"));
    }

    private ResultActions create(String token, String json, String key) throws Exception {
        return mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String created(String token, String json, String key) throws Exception {
        MvcResult result = create(token, json, key).andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("handoff_id").asText();
    }

    private static String body(String kind, String sourceId, String type, String recipient, long version) {
        return "{\"source_kind\":\"" + kind + "\",\"source_id\":\"" + sourceId + "\",\"handoff_type\":\"" + type
                + "\",\"recipient_id\":\"" + recipient + "\",\"expected_version\":" + version + "}";
    }

    private void insertNotifiableRisk(String id, String token) {
        insertRisk(id, "PENDING_NOTIFICATION", 1, "seed-stage3-plan-legal", "seed-stage3-rv-legal", "seed-stage3-org", "seed-stage3-district");
        String actor = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, token);
        jdbc.update("insert into flight_risk_verification (history_id,risk_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','PENDING_NOTIFICATION','CONFIRMED','人工复核后确认',?,?)",
                UUID.randomUUID().toString(), id, actor, Timestamp.from(Instant.now()));
    }

    private void insertRisk(String id, String state, long version, String plan, String route, String org, String district) {
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,'seed-stage3-source',?,?,?,'ROUTE_DEVIATION','HIGH',?,'ROUTE_DEVIATION','服务端保存依据',?,?,'UNKNOWN','mock',?,?,?,?,?)",
                id, "source-" + id, plan, route, state, at, at, org, district, at, at, version);
    }

    private void insertRecipient(String id, String type, boolean enabled) {
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update("insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values (?,?,?,?,?,?)",
                id, "测试接收方", type, enabled, at, at);
    }

    private String user(boolean riskRead, boolean handoffRead, boolean handoffCreate, boolean flightRead, boolean otherScope) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-HANDOFF-W-" + suffix;
        String user = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        if (riskRead) grant(role, "risk:read", "READ");
        if (handoffRead) grant(role, "handoff:read", "READ");
        if (handoffCreate) grant(role, "handoff:create", "OP");
        if (flightRead) grant(role, "flight:read", "READ");
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", user, "handoff-w-" + suffix, "交接提交", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", user,
                otherScope ? "seed-stage3-other-org" : "seed-stage3-org", otherScope ? "seed-stage3-other-district" : "seed-stage3-district");
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private void grant(String role, String permission, String level) {
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,?,false,current_timestamp)", role, permission, level);
    }

    private long handoffCount(String sourceId) { return jdbc.queryForObject("select count(*) from handoff where source_kind='RISK' and source_id=?", Long.class, sourceId); }
    private String state(String id) { return jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, id); }
    private static String bearer(String token) { return "Bearer " + token; }
}
