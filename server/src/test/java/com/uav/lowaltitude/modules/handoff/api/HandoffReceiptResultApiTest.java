package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;

/**
 * 回执结果（是否已驱离）只有在真的投出去、上级真的回了话之后才存在，
 * 所以这里换成模拟上级渠道跑——{@link HandoffApiTest} 固定跑未接通渠道，证不了这一段。
 * 风险状态按现行口径：提交成功即已通知，渠道确认回执即已回执；驱离结果单独记在交接上。
 * 缺省模拟渠道只送到等待回执；带结果的回执用例在下面显式打桩，不依赖渠道默认立刻已回执。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.handoff.channel=mock")
class HandoffReceiptResultApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    // 真渠道对风险通知恒回"已驱离"，所以"未驱离"那条只能在这里换掉回执；其余用例仍走真渠道，
    // 否则就只是在证明"我造的假回执被原样读了回来"。
    @SpyBean HandoffChannelPort channel;
    private String session;
    private String riskId;
    private String recipientId;

    @BeforeEach
    void fixture() {
        session = user();
        riskId = "risk-receipt-" + UUID.randomUUID().toString().substring(0, 8);
        insertNotifiableRisk(riskId);
        recipientId = "recipient-receipt-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update("insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values (?,'测试接收方','RISK_NOTICE',true,?,?)",
                recipientId, at, at);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from handoff_delivery where handoff_id in (select handoff_id from handoff where source_id like 'risk-receipt-%')");
        jdbc.update("delete from handoff_material_snapshot where handoff_id in (select handoff_id from handoff where source_id like 'risk-receipt-%')");
        jdbc.update("delete from handoff where source_id like 'risk-receipt-%'");
        jdbc.update("delete from flight_risk_verification where risk_id like 'risk-receipt-%'");
        jdbc.update("delete from flight_risk where risk_id like 'risk-receipt-%'");
        jdbc.update("delete from handoff_recipient where recipient_id like 'recipient-receipt-%'");
        jdbc.update("delete from audit_log where account like 'handoff-rr-%'");
        jdbc.update("delete from idempotency_request where user_id in (select user_id from app_user where account like 'handoff-rr-%')");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'handoff-rr-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'handoff-rr-%')");
        jdbc.update("delete from app_user where account like 'handoff-rr-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-HANDOFF-RR-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-HANDOFF-RR-%'");
    }

    @Test
    void defaultMockRiskNoticeDeliversAndLeavesRiskNotified() throws Exception {
        String json = "{\"source_kind\":\"RISK\",\"source_id\":\"" + riskId + "\",\"handoff_type\":\"RISK_NOTICE\""
                + ",\"recipient_id\":\"" + recipientId + "\",\"expected_version\":1}";
        mvc.perform(post("/api/v1/handoffs").header("Authorization", "Bearer " + session)
                        .header("Idempotency-Key", "wait-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.delivery_status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.receipt_status").value("PENDING"))
                .andExpect(jsonPath("$.data.receipt_result").doesNotExist());
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, riskId))
                .isEqualTo("NOTIFIED");
    }

    @Test
    void riskNoticeCarriesTheReceiptResultBackAndKeepsIt() throws Exception {
        stubAcknowledged("DISPERSED");
        String json = "{\"source_kind\":\"RISK\",\"source_id\":\"" + riskId + "\",\"handoff_type\":\"RISK_NOTICE\""
                + ",\"recipient_id\":\"" + recipientId + "\",\"expected_version\":1}";
        MvcResult result = mvc.perform(post("/api/v1/handoffs").header("Authorization", "Bearer " + session)
                        .header("Idempotency-Key", "receipt-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receipt_status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.receipt_result").value("DISPERSED"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        String handoffId = body.path("handoff_id").asText();
        // 答复里有还不够：结果要留在交接上，事后翻记录才答得出"那条风险到底驱离了没有"。
        assertThat(jdbc.queryForObject("select receipt_result from handoff where handoff_id=?", String.class, handoffId))
                .isEqualTo("DISPERSED");
        mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", "Bearer " + session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipt_result").value("DISPERSED"));
        // 通报记录是列表，不是详情：详情有而列表没有，页面上那一列就永远空着。
        mvc.perform(get("/api/v1/handoffs?source_kind=RISK&source_id={id}", riskId).header("Authorization", "Bearer " + session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].receipt_result").value("DISPERSED"));
        // 渠道确认回执时提交事务里连续记已通知、已回执，刷新看到最终已回执。
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, riskId))
                .isEqualTo("ACKNOWLEDGED");
    }

    /** 未驱离只记在交接回执结果上；风险仍按渠道确认回执进入已回执。 */
    @Test
    void aNotDispersedReceiptRecordsTheResultButDoesNotBlockAcknowledgment() throws Exception {
        stubAcknowledged("NOT_DISPERSED");

        String json = "{\"source_kind\":\"RISK\",\"source_id\":\"" + riskId + "\",\"handoff_type\":\"RISK_NOTICE\""
                + ",\"recipient_id\":\"" + recipientId + "\",\"expected_version\":1}";
        mvc.perform(post("/api/v1/handoffs").header("Authorization", "Bearer " + session)
                        .header("Idempotency-Key", "notdispersed-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receipt_result").value("NOT_DISPERSED"));
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, riskId))
                .isEqualTo("ACKNOWLEDGED");
    }

    private void stubAcknowledged(String receiptResult) {
        var at = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        org.mockito.Mockito.doReturn(new DeliveryOutcome("DELIVERED", "ACKNOWLEDGED", receiptResult, null, at, at, at))
                .when(channel).deliver(org.mockito.ArgumentMatchers.any());
    }

    private void insertNotifiableRisk(String id) {
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal','ROUTE_DEVIATION','HIGH','PENDING_NOTIFICATION','ROUTE_DEVIATION','服务端保存依据',?,?,'UNKNOWN','mock','seed-stage3-org','seed-stage3-district',?,?,1)",
                id, "source-" + id, at, at, at, at);
        String actor = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, session);
        jdbc.update("insert into flight_risk_verification (history_id,risk_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) values (?,?,1,'PENDING_VERIFICATION','PENDING_NOTIFICATION','CONFIRMED','人工复核后确认',?,?)",
                UUID.randomUUID().toString(), id, actor, at);
    }

    private String user() {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-HANDOFF-RR-" + suffix;
        String user = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        grant(role, "risk:read", "READ");
        grant(role, "handoff:read", "READ");
        grant(role, "handoff:create", "OP");
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", user, "handoff-rr-" + suffix, "交接提交", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,'seed-stage3-org','seed-stage3-district')", user);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private void grant(String role, String permission, String level) {
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,?,false,current_timestamp)", role, permission, level);
    }
}
