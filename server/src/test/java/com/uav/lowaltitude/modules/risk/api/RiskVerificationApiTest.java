package com.uav.lowaltitude.modules.risk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.uav.lowaltitude.platform.audit.AuditService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RiskVerificationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @SpyBean AuditService audit;
    private String session;
    private String riskId;

    @BeforeEach
    void fixture() {
        session = user(true, true);
        riskId = "risk-verify-" + UUID.randomUUID().toString().substring(0, 8);
        insertRisk(riskId, "PENDING_VERIFICATION", 0);
    }

    @AfterEach
    void cleanup() {
        AuditService target=org.springframework.test.util.AopTestUtils.getTargetObject(audit);
        org.mockito.Mockito.reset(target);
        jdbc.update("delete from audit_log where account like 'risk-w-%'");
        jdbc.update("delete from flight_risk_verification where risk_id like 'risk-verify-%' or risk_id like 'risk-other-%'");
        jdbc.update("delete from flight_risk where risk_id like 'risk-verify-%' or risk_id like 'risk-other-%'");
        jdbc.update("delete from idempotency_request where user_id in (select user_id from app_user where account like 'risk-w-%')");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'risk-w-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'risk-w-%')");
        jdbc.update("delete from app_user where account like 'risk-w-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-RISK-W-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-RISK-W-%'");
    }

    @Test
    void confirmedMovesOnlyToPendingNotificationAndWritesHistoryAndSuccessAudit() throws Exception {
        mvc.perform(get("/api/v1/risks/{id}",riskId).header("Authorization",bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed_actions[0]").value("VERIFY"));
        verify(riskId, "CONFIRMED", "人工复核轨迹与计划版本后确认风险", 0, "confirm-" + UUID.randomUUID())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PENDING_NOTIFICATION"))
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(state(riskId)).isEqualTo("PENDING_NOTIFICATION");
        assertThat(state(riskId)).isNotEqualTo("NOTIFIED");
        assertThat(jdbc.queryForObject("select count(*) from flight_risk_verification where risk_id=? and conclusion='CONFIRMED'", Long.class, riskId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where module_code='risk' and action='risk_verified' and object_id=? and result='SUCCESS'", Long.class, riskId)).isEqualTo(1L);
    }

    @Test
    void excludedMovesToExcludedAndTerminalStatesCannotBeReverified() throws Exception {
        verify(riskId, "EXCLUDED", "核对来源后确认该记录不属于当前飞行计划", 0, "exclude-" + UUID.randomUUID())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("EXCLUDED"));
        verify(riskId, "CONFIRMED", "终态不得再次变更为待通知", 1, "terminal-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void invalidConclusionNoteAndVersionAreRejectedWithoutMutation() throws Exception {
        verify(riskId, "NOTIFIED", "非法直接通知", 0, "bad-conclusion-" + UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CONCLUSION"));
        verify(riskId, "CONFIRMED", "   ", 0, "bad-note-" + UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(riskId, "CONFIRMED", "有效说明", -1, "bad-version-" + UUID.randomUUID())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(state(riskId)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where module_code='risk' and result='FAILURE' and action like 'POST /api/v1/risks/%'",Long.class)).isGreaterThan(0L);
    }

    @Test
    void authorizedWriteRejectsUnknownAndDuplicateJsonFields() throws Exception {
        mvc.perform(post("/api/v1/risks/{id}/verifications",riskId).header("Authorization",bearer(session))
                .header("Idempotency-Key","unknown-field-"+UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"CONFIRMED\",\"note\":\"有效说明\",\"expected_version\":0,\"state\":\"NOTIFIED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/risks/{id}/verifications",riskId).header("Authorization",bearer(session))
                .header("Idempotency-Key","malformed-"+UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/risks/{id}/verifications",riskId).header("Authorization",bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"CONFIRMED\",\"note\":\"有效说明\",\"expected_version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/v1/risks/{id}/verifications",riskId).header("Authorization",bearer(session))
                .header("Idempotency-Key","duplicate-field-"+UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"CONFIRMED\",\"conclusion\":\"EXCLUDED\",\"note\":\"有效说明\",\"expected_version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        for(String invalidBody:new String[]{
                "{\"conclusion\":\"CONFIRMED\",\"note\":\"缺少版本\"}",
                "{\"conclusion\":1,\"note\":\"错误类型\",\"expected_version\":0}",
                "{\"conclusion\":\"CONFIRMED\",\"note\":false,\"expected_version\":0}",
                "{\"conclusion\":\"CONFIRMED\",\"note\":\"错误版本类型\",\"expected_version\":\"0\"}",
                "{\"conclusion\":\"CONFIRMED\",\"note\":\"尾随 JSON\",\"expected_version\":0} {}"}){
            mvc.perform(post("/api/v1/risks/{id}/verifications",riskId).header("Authorization",bearer(session))
                    .header("Idempotency-Key","strict-body-"+UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                    .content(invalidBody)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void staleVersionAndSequentialReplayCannotAppendHistory() throws Exception {
        verify(riskId, "CONFIRMED", "第一次确认提交", 8, "stale-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        String key = "replay-" + UUID.randomUUID();
        verify(riskId, "CONFIRMED", "同一请求仅允许一次", 0, key).andExpect(status().isOk());
        verify(riskId, "CONFIRMED", "同一请求仅允许一次", 0, key)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        assertThat(jdbc.queryForObject("select count(*) from flight_risk_verification where risk_id=?", Long.class, riskId)).isEqualTo(1L);
    }

    @Test
    void reusedKeyForDifferentPayloadIsRejected() throws Exception {
        String other = "risk-other-" + UUID.randomUUID().toString().substring(0, 8);
        insertRisk(other, "PENDING_VERIFICATION", 0);
        String key = "shared-" + UUID.randomUUID();
        verify(riskId, "EXCLUDED", "排除第一条风险", 0, key).andExpect(status().isOk());
        verify(other, "CONFIRMED", "确认另一条风险", 0, key)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void delimiterBearingFieldsCannotAliasDifferentIdempotencyPayloads() throws Exception {
        assertDelimiterCollisionIsKeyReuse("risk-verify-a", "CONFIRMED", "b:CONFIRMED:c",
                "risk-verify-a:CONFIRMED:b", "CONFIRMED", "c");
        assertDelimiterCollisionIsKeyReuse("risk-verify-b", "EXCLUDED", "m:CONFIRMED:n",
                "risk-verify-b:EXCLUDED:m", "CONFIRMED", "n");
    }

    @Test
    void secondUserWithOldVersionGetsConflictAndNoSecondHistory() throws Exception {
        String otherSession = user(true, true);
        verify(riskId, "CONFIRMED", "第一位核验人确认", 0, "first-user-" + UUID.randomUUID()).andExpect(status().isOk());
        mvc.perform(post("/api/v1/risks/{id}/verifications", riskId).header("Authorization", bearer(otherSession))
                        .header("Idempotency-Key", "second-user-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conclusion\":\"EXCLUDED\",\"note\":\"第二位核验人持有旧版本\",\"expected_version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from flight_risk_verification where risk_id=?", Long.class, riskId)).isEqualTo(1L);
    }

    @Test
    void successAuditFailureRollsBackRiskAndHistory() throws Exception {
        AuditService target=org.springframework.test.util.AopTestUtils.getTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(target).record(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("risk"),
                org.mockito.ArgumentMatchers.eq("risk_verified"), org.mockito.ArgumentMatchers.eq("flight_risk"),
                org.mockito.ArgumentMatchers.eq(riskId), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("SUCCESS"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(riskId, "EXCLUDED", "审计失败必须让业务事务一起回滚", 0, "audit-fail-" + UUID.randomUUID())
                .andExpect(status().is5xxServerError());
        assertThat(state(riskId)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("select count(*) from flight_risk_verification where risk_id=?", Long.class, riskId)).isZero();
        org.mockito.Mockito.reset(target);
    }

    @Test
    void writeRequiresBothReadAndVerifyAndObjectScope() throws Exception {
        String readOnly = user(true, false);
        mvc.perform(post("/api/v1/risks/{id}/verifications", riskId).header("Authorization", bearer(readOnly))
                        .header("Idempotency-Key", "denied-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conclusion\":\"CONFIRMED\",\"note\":\"无写权限\",\"expected_version\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizationWinsOverBadPathBodyAndIdempotencyKey() throws Exception {
        String readOnly=user(true,false),verifyOnly=user(false,true);
        for(String denied:new String[]{readOnly,verifyOnly}){
            mvc.perform(post("/api/v1/risks/{id}/verifications"," ").header("Authorization",bearer(denied))
                    .contentType(MediaType.APPLICATION_JSON).content("{not-json")).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/risks/{id}/verifications",riskId).header("Authorization",bearer(denied))
                    .header("Idempotency-Key","x").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/risks/{id}/verifications",riskId).header("Authorization",bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content("{not-json")).andExpect(status().isBadRequest());
    }

    @Test
    void historyUsesServerPagingAndRejectsRepeatedParameters() throws Exception {
        verify(riskId, "EXCLUDED", "写入历史后读取", 0, "history-" + UUID.randomUUID()).andExpect(status().isOk());
        mvc.perform(get("/api/v1/risks/{id}/verifications?page=1&size=1", riskId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].conclusion").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.items[0].version").value(1))
                .andExpect(jsonPath("$.data.size").value(1)).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/risks/{id}/verifications?page=1&page=2", riskId).header("Authorization", bearer(session)))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions verify(String id, String conclusion, String note, long version, String key) throws Exception {
        return mvc.perform(post("/api/v1/risks/{id}/verifications", id).header("Authorization", bearer(session))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"" + conclusion + "\",\"note\":\"" + note + "\",\"expected_version\":" + version + "}"));
    }

    private void assertDelimiterCollisionIsKeyReuse(String firstId,String firstConclusion,String firstNote,
            String secondId,String secondConclusion,String secondNote) throws Exception {
        insertRisk(firstId,"PENDING_VERIFICATION",0);
        insertRisk(secondId,"PENDING_VERIFICATION",0);
        String key="delimiter-"+UUID.randomUUID();
        verify(firstId,firstConclusion,firstNote,0,key).andExpect(status().isOk());
        verify(secondId,secondConclusion,secondNote,0,key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    private void insertRisk(String id, String state, long version) {
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update("insert into flight_risk (risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,reason_code,reason_text,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal','ROUTE_DEVIATION','HIGH',?,'ROUTE_DEVIATION','服务端保存依据',?,'UNKNOWN','mock','seed-stage3-org','seed-stage3-district',?,?,?)",
                id, "source-" + id, state, at, at, at, version);
    }

    private String user(boolean read, boolean verify) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-RISK-W-" + suffix;
        String user = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        if (read) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'risk:read','READ',false,current_timestamp)", role);
        if (verify) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'risk:verify','OP',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", user, "risk-w-" + suffix, "风险核验", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,'seed-stage3-org','seed-stage3-district')", user);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private String state(String id) { return jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, id); }
    private static String bearer(String token) { return "Bearer " + token; }
}
