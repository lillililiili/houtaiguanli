package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.platform.audit.AuditService;

/**
 * 核实 API 覆盖状态、乐观版本和幂等边界；反制不属于本写接口的成功语义。
 * 不使用测试级事务：回滚证明和双线程竞争都要求业务事务真正提交或真正回滚，
 * 夹具数据由 {@link #cleanup()} 按依赖顺序删除。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UavEventVerificationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @SpyBean AuditService audit;
    @SpyBean UavEventRepository events;
    private String sessionId;
    private String userId;
    private String eventId;
    private String role;
    private final List<String> roles = new ArrayList<>(), users = new ArrayList<>(), orgs = new ArrayList<>(),
            districts = new ArrayList<>(), sources = new ArrayList<>(), alarms = new ArrayList<>(), eventIds = new ArrayList<>();

    @BeforeEach
    void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = UUID.randomUUID().toString(), district = UUID.randomUUID().toString();
        String source = UUID.randomUUID().toString(), alarm = UUID.randomUUID().toString();
        // 事件 ID 使用 36 位 UUID：迁移 023 已加宽 audit_log.action/object_id，失败审计必须在真实长度的路径下留痕。
        eventId = UUID.randomUUID().toString();
        role = "ROLE-VERIFY-" + suffix;
        roles.add(role);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", role, role);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'alarm:read','READ',false,current_timestamp),(?,'alarm:verify','READ',false,current_timestamp)", role, role);
        userId = UUID.randomUUID().toString();
        sessionId = session(userId, role, "verify-" + suffix);
        org(org, "ORG-" + suffix); district(district, "DIST-" + suffix); source(source, "SRC-" + suffix);
        alarm(alarm, source, "AL-" + suffix, org, district, Instant.parse("2026-09-05T12:00:00Z"), Instant.parse("2026-09-05T12:01:00Z"));
        event(eventId, alarm, org, district);
    }

    @AfterEach
    void cleanup() {
        Mockito.reset(AopTestUtils.<AuditService>getTargetObject(audit));
        Mockito.reset(AopTestUtils.<UavEventRepository>getTargetObject(events));
        for (String user : users) {
            jdbc.update("delete from audit_log where user_id=?", user);
            jdbc.update("delete from idempotency_request where user_id=?", user);
            jdbc.update("delete from app_session where user_id=?", user);
        }
        for (String id : eventIds) jdbc.update("delete from uav_event_verification where event_id=?", id);
        for (String id : eventIds) jdbc.update("delete from uav_event where event_id=?", id);
        for (String id : alarms) jdbc.update("delete from alarm where alarm_id=?", id);
        for (String id : sources) jdbc.update("delete from integration_source where source_id=?", id);
        for (String user : users) jdbc.update("delete from app_user where user_id=?", user);
        for (String id : districts) jdbc.update("delete from app_district where district_id=?", id);
        for (String id : orgs) jdbc.update("delete from app_org where org_id=?", id);
        for (String code : roles) { jdbc.update("delete from app_role_permission where role_code=?", code); jdbc.update("delete from app_role where role_code=?", code); }
    }

    @Test
    void confirmedCreatesAppendOnlyHistoryAndIncrementsVersion() throws Exception {
        mvc.perform(verify("CONFIRMED", "已核对目标轨迹", 0, "verify-key-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.allowed_actions").isEmpty());
        mvc.perform(get("/api/v1/uav-events/" + eventId + "/verifications").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].previous_state").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.data.items[0].resulting_state").value("CONFIRMED"));
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=? and version=1", Long.class, eventId)).isEqualTo(1);
    }

    @Test
    void evidenceRequiredIsRejectedAndTerminalStateCannotBeReopened() throws Exception {
        mvc.perform(verify("EVIDENCE_REQUIRED", "需要补充可信证据", 0, "verify-key-2"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CONCLUSION"));
        mvc.perform(verify("FALSE_POSITIVE", "现场确认为误报", 0, "verify-key-3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("FALSE_POSITIVE"));
        mvc.perform(verify("CONFIRMED", "终态不能重开", 1, "verify-key-4"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void staleVersionAndSameKeyReplayAreRejectedWithoutSecondHistory() throws Exception {
        mvc.perform(verify("CONFIRMED", "第一次提交", 0, "verify-key-5"))
                .andExpect(status().isOk());
        mvc.perform(verify("CONFIRMED", "第一次提交", 0, "verify-key-5"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        mvc.perform(verify("CONFIRMED", "不同稳定请求", 0, "verify-key-5"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(verify("FALSE_POSITIVE", "旧版本提交", 0, "verify-key-6"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?", Long.class, eventId)).isEqualTo(1);
    }

    @Test
    void delimiterContainingFieldsCannotTurnDifferentRequestsIntoReplay() throws Exception {
        String first = "a-" + UUID.randomUUID().toString().substring(0, 8), second = first + ":CONFIRMED:b";
        createVisiblePendingEvent(first); createVisiblePendingEvent(second);
        // 旧的冒号拼接会将 a/"b:CONFIRMED:c" 与 a:CONFIRMED:b/"c" 误拼为同一串。
        mvc.perform(verify(first, "CONFIRMED", "b:CONFIRMED:c", 0, "verify-key-delimiter"))
                .andExpect(status().isOk());
        mvc.perform(verify(second, "CONFIRMED", "c", 0, "verify-key-delimiter"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void validatesNoteVersionConclusionAndIdempotencyKey() throws Exception {
        mvc.perform(verify("MAYBE", "说明", 0, "verify-key-7")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CONCLUSION"));
        mvc.perform(verify("CONFIRMED", "   ", 0, "verify-key-8")).andExpect(status().isBadRequest());
        mvc.perform(verify("CONFIRMED", "版本无效", -1, "verify-key-9")).andExpect(status().isBadRequest());
        mvc.perform(verify("CONFIRMED", "键无效", 0, "short")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void eitherRequiredPermissionPrecedesPathBodyAndReplayDisclosure() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='alarm:read'", role);
        mvc.perform(post("/api/v1/uav-events/not-a-valid-id/verifications").header("Authorization", "Bearer " + sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{bad-json"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/uav-events/not-a-valid-id/verifications").header("Authorization", "Bearer " + sessionId)
                        .header("Idempotency-Key", "verify-key-10").contentType(MediaType.APPLICATION_JSON).content("{bad-json"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'alarm:read','READ',false,current_timestamp)", role);
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='alarm:verify'", role);
        mvc.perform(post("/api/v1/uav-events/not-a-valid-id/verifications").header("Authorization", "Bearer " + sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{bad-json"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void authorizedCallerSeesHeaderAndJsonValidationOnlyAfterPermissionsPass() throws Exception {
        mvc.perform(post("/api/v1/uav-events/" + eventId + "/verifications").header("Authorization", "Bearer " + sessionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"conclusion\":\"CONFIRMED\",\"note\":\"缺少幂等键\",\"expected_version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/v1/uav-events/" + eventId + "/verifications").header("Authorization", "Bearer " + sessionId)
                        .header("Idempotency-Key", "verify-key-12").contentType(MediaType.APPLICATION_JSON).content("{bad-json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/uav-events/" + eventId + "/verifications").header("Authorization", "Bearer " + sessionId)
                        .header("Idempotency-Key", "verify-key-13").contentType(MediaType.APPLICATION_JSON).content("{\"conclusion\":\"CONFIRMED\",\"note\":\"说明\",\"expected_version\":0,\"extra\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/uav-events/" + eventId + "/verifications").header("Authorization", "Bearer " + sessionId)
                        .header("Idempotency-Key", "verify-key-14").contentType(MediaType.APPLICATION_JSON).content("{\"conclusion\":\"CONFIRMED\",\"conclusion\":\"FALSE_POSITIVE\",\"note\":\"说明\",\"expected_version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void historyPaginationOverflowIsRejectedAsValidationError() throws Exception {
        mvc.perform(get("/api/v1/uav-events/" + eventId + "/verifications?page=2147483647&size=100").header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void readOnlyUserSeesEventButNeverReceivesVerifyAllowedAction() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='alarm:verify'", role);
        mvc.perform(get("/api/v1/uav-events/" + eventId).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.data.allowed_actions").isEmpty());
    }

    @Test
    void successAuditFailureRollsBackStateHistoryIdempotencyClaimAndSuccessAudit() throws Exception {
        AuditService target = AopTestUtils.getTargetObject(audit);
        Mockito.doThrow(new IllegalStateException("audit unavailable")).when(target).record(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.eq("alarm"), ArgumentMatchers.eq("uav_event_verified"), ArgumentMatchers.eq("uav_event"),
                ArgumentMatchers.eq(eventId), ArgumentMatchers.anyString(), ArgumentMatchers.eq("SUCCESS"),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        String key = "audit-fail-" + UUID.randomUUID();
        mvc.perform(verify("CONFIRMED", "成功审计写失败必须整体回滚", 0, key))
                .andExpect(status().is5xxServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        // 事件状态/版本、历史、幂等占位与成功审计必须同事务回滚；失败审计由异常路径在事务外单独落一条。
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?", String.class, eventId)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?", Long.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id=?", Long.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where user_id=? and action='uav_event_verified' and result='SUCCESS'", Long.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where user_id=? and result='FAILURE' and action=?", Long.class, userId,
                "POST /api/v1/uav-events/" + eventId + "/verifications")).isEqualTo(1L);
        Mockito.reset(target);
        // 幂等占位已回滚：同键同请求再次提交按正常写入处理，而不是 IDEMPOTENCY_REPLAY。
        mvc.perform(verify("CONFIRMED", "成功审计写失败必须整体回滚", 0, key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("CONFIRMED")).andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where user_id=? and action='uav_event_verified' and result='SUCCESS'", Long.class, userId)).isEqualTo(1L);
    }

    @Test
    void twoUsersRacingWithSameExpectedVersionProduceExactlyOneHistory() throws Exception {
        String otherRole = "ROLE-VERIFY-" + UUID.randomUUID().toString().substring(0, 8);
        roles.add(otherRole);
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?, '',false,true,0,0,0,false)", otherRole, otherRole);
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'alarm:read','READ',false,current_timestamp),(?,'alarm:verify','READ',false,current_timestamp)", otherRole, otherRole);
        String otherSession = session(UUID.randomUUID().toString(), otherRole, "verify-other-" + UUID.randomUUID().toString().substring(0, 8));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return mvc.perform(verify(sessionId, eventId, "CONFIRMED", "第一位核实人确认", 0, "race-a-" + UUID.randomUUID())).andReturn(); });
            Future<MvcResult> second = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return mvc.perform(verify(otherSession, eventId, "FALSE_POSITIVE", "第二位核实人判误报", 0, "race-b-" + UUID.randomUUID())).andReturn(); });
            List<MvcResult> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).sorted().toList();
            assertThat(statuses).containsExactly(200, 409);
            MvcResult rejected = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
            JsonNode body = json.readTree(rejected.getResponse().getContentAsString());
            assertThat(body.path("error").path("code").asText()).isEqualTo("VERSION_CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='uav_event_verified' and object_id=? and result='SUCCESS'", Long.class, eventId)).isEqualTo(1L);
    }

    @Test
    void conditionalUpdateStillRejectsStaleRowEvenWhenVersionCheckSawOldSnapshot() throws Exception {
        mvc.perform(verify("CONFIRMED", "第一位核实人确认", 0, "stale-first-" + UUID.randomUUID())).andExpect(status().isOk());
        UavEventRepository target = AopTestUtils.getTargetObject(events);
        AtomicBoolean staleOnce = new AtomicBoolean(true);
        // 模拟第二位用户锁定前读到旧快照（version 0）：内存版本检查通过，只能靠 UPDATE ... WHERE version=? 拦截。
        Mockito.doAnswer(invocation -> {
            EventRow real = (EventRow) invocation.callRealMethod();
            if (real == null || !staleOnce.getAndSet(false)) return real;
            return new EventRow(real.eventId(), real.alarmId(), real.targetId(), "PENDING_VERIFICATION", real.ownerOrgId(), real.districtId(), real.createdAt(), real.updatedAt(), 0L, real.sourceMode());
        }).when(target).lock(ArgumentMatchers.eq(eventId), ArgumentMatchers.any());
        mvc.perform(verify("FALSE_POSITIVE", "第二位核实人持旧版本", 0, "stale-second-" + UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(staleOnce.get()).isFalse();
        assertThat(jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?", String.class, eventId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?", Long.class, eventId)).isEqualTo(1L);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verify(String conclusion, String note, long version, String key) {
        return verify(eventId, conclusion, note, version, key);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verify(String id, String conclusion, String note, long version, String key) {
        return verify(sessionId, id, conclusion, note, version, key);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verify(String session, String id, String conclusion, String note, long version, String key) {
        return post("/api/v1/uav-events/" + id + "/verifications").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conclusion\":\"" + conclusion + "\",\"note\":\"" + note + "\",\"expected_version\":" + version + "}");
    }

    private String session(String user, String roleCode, String account) {
        String token = UUID.randomUUID().toString();
        users.add(user);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ALL',0,0,0,0)", user, account, "核实员", roleCode);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, user, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private void org(String id, String code) { orgs.add(id); jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", id, code, "机构"); }
    private void district(String id, String code) { districts.add(id); jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", id, code, "区域"); }
    private void source(String id, String code) { sources.add(id); jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) values (?,?,?,true,'mock',current_timestamp,current_timestamp,0)", id, code, "来源"); }
    private void alarm(String id, String source, String sourceAlarmId, String org, String district, Instant occurredAt, Instant receivedAt) {
        alarms.add(id);
        jdbc.update("insert into alarm (alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values (?,?,?,'UAV','HIGH',?,?,'mock',?,?,current_timestamp)", id, source, sourceAlarmId, occurredAt, receivedAt, org, district);
    }
    private void event(String id, String alarm, String org, String district) {
        eventIds.add(id);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'PENDING_VERIFICATION',?,?,current_timestamp,current_timestamp,0)", id, alarm, org, district);
    }

    private void createVisiblePendingEvent(String id) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String org = UUID.randomUUID().toString(), district = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(), alarm = UUID.randomUUID().toString();
        org(org, "ORG-C-" + suffix); district(district, "DIST-C-" + suffix); source(source, "SRC-C-" + suffix);
        alarm(alarm, source, "AL-C-" + suffix, org, district, null, Instant.now());
        event(id, alarm, org, district);
    }
}
