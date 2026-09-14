package com.uav.lowaltitude.modules.assessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.LegalStatus;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RouteDistance;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks.EvaluationOutcome;
import com.uav.lowaltitude.platform.audit.AuditService;

/**
 * 复核接口：三种结论、SUPERSEDED 409、跨范围 404、缺权限 403 先于 400、幂等重放 409、审计失败回滚、allowed_actions、
 * 转告警、重算与手动评估。不使用测试级事务：审计失败回滚与写事务必须真正提交/回滚，夹具按前缀清理。
 * 引擎评估在 H2 上用桩 SpatialFactPort（无空域命中、在走廊内）；研判本身的决策表由 E1 的测试覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegalityReviewApiTest {
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 5, 2, 0, 0, 0, ZoneOffset.UTC);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired RuleEngineHooks hooks;
    @SpyBean AuditService audit;

    private String suffix, orgId, district, target, run, evaluation, session;

    @TestConfiguration
    static class Stubs {
        @Bean @Primary SpatialFactPort stubSpatialFacts() {
            return new SpatialFactPort() {
                @Override public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime asOf) { return List.of(); }
                @Override public RouteDistance distanceToRoute(TargetState state, String routeVersionId) { return new RouteDistance(routeVersionId, new BigDecimal("5"), new BigDecimal("50"), null); }
                @Override public boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime asOf) { return false; }
            };
        }
    }

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        orgId = "s7r-org-" + suffix; district = "s7r-dist-" + suffix; target = "s7r-target-" + suffix; run = "s7r-run-" + suffix; evaluation = "s7r-eval-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "S7R-" + suffix, "复核测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "S7R-" + suffix, "复核测试区域");
        target(target, "S7R-SN-" + suffix, now().minusSeconds(10));
        insertRun(run, "MANUAL", "DONE");
        insertEvaluation(evaluation, run, "ABNORMAL", "[\"ROUTE_DEVIATION\"]", "MEDIUM", new BigDecimal("40"));
        insertReview(evaluation, "PENDING_REVIEW", 0);
        session = user("ASSIGNED", "assessment:read", "assessment:revise", "assessment:evaluate", "assessment:escalate", "target:read", "alarm:read", "flight:read");
    }

    @AfterEach
    void cleanup() {
        AuditService spied = org.springframework.test.util.AopTestUtils.getTargetObject(audit);
        org.mockito.Mockito.reset(spied);
        jdbc.update("delete from audit_log where account like 's7r-%'");
        jdbc.update("delete from legality_review_history where evaluation_id in (select evaluation_id from rule_evaluation where owner_org_id=?)", orgId);
        jdbc.update("delete from alarm_merge_member where evaluation_id in (select evaluation_id from rule_evaluation where owner_org_id=?)", orgId);
        jdbc.update("delete from alarm_merge_group where owner_org_id=?", orgId);
        jdbc.update("delete from legality_review where owner_org_id=?", orgId);
        jdbc.update("delete from uav_event where owner_org_id=?", orgId);
        jdbc.update("update rule_evaluation set alarm_id=null where owner_org_id=?", orgId);
        jdbc.update("delete from alarm where owner_org_id=?", orgId);
        jdbc.update("delete from assessment_result where evaluation_id in (select evaluation_id from rule_evaluation where owner_org_id=?)", orgId);
        // 重算产生的新研判引用旧研判（supersedes_evaluation_id），必须先删引用方再删被引用方。
        jdbc.update("delete from rule_evaluation where owner_org_id=? and supersedes_evaluation_id is not null", orgId);
        jdbc.update("delete from rule_evaluation where owner_org_id=?", orgId);
        jdbc.update("delete from rule_evaluation where run_id in (select run_id from rule_run where triggered_by in (select user_id from app_user where account like 's7r-%'))");
        jdbc.update("delete from rule_run where run_id like 's7r-%' or triggered_by in (select user_id from app_user where account like 's7r-%')");
        jdbc.update("delete from track_point where track_id in (select track_id from track where target_id in (select target_id from target where owner_org_id=?))", orgId);
        jdbc.update("delete from track where target_id in (select target_id from target where owner_org_id=?)", orgId);
        jdbc.update("delete from target_latest_state where target_id in (select target_id from target where owner_org_id=?)", orgId);
        jdbc.update("delete from target_source_link where target_id in (select target_id from target where owner_org_id=?)", orgId);
        jdbc.update("delete from target where owner_org_id=?", orgId);
        jdbc.update("delete from idempotency_request where user_id in (select user_id from app_user where account like 's7r-%')");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 's7r-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 's7r-%')");
        jdbc.update("delete from app_user where account like 's7r-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-S7R-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-S7R-%'");
        jdbc.update("delete from app_district where district_id=?", district);
        jdbc.update("delete from app_org where org_id=?", orgId);
    }

    @Test
    void confirmRejectAndOverrideEachWriteOneHistoryAndOneSuccessAudit() throws Exception {
        revise(session, evaluation, "CONFIRM", null, "现场核对属实", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.review.manual_status").value("ABNORMAL"))
                .andExpect(jsonPath("$.data.review.version").value(1))
                .andExpect(jsonPath("$.data.allowed_actions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("REVIEW"))));
        assertThat(jdbc.queryForObject("select count(*) from legality_review_history where evaluation_id=? and conclusion='CONFIRM' and status_before='ABNORMAL' and status_after='ABNORMAL' and version=1", Long.class, evaluation)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where module_code='assessment' and action='legality_evaluation_revised' and object_id=? and result='SUCCESS'", Long.class, evaluation)).isEqualTo(1L);

        String rejected = "s7r-eval-rej-" + suffix;
        insertEvaluation(rejected, run, "ILLEGAL", "[\"NO_AUTHORIZATION\"]", "HIGH", new BigDecimal("80"));
        insertReview(rejected, "PENDING_REVIEW", 0);
        revise(session, rejected, "REJECT", null, "系统误判，目标为合法作业", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review.state").value("REJECTED"))
                .andExpect(jsonPath("$.data.review.manual_status").doesNotExist());
        assertThat(jdbc.queryForObject("select status_after from legality_review_history where evaluation_id=?", String.class, rejected)).isNull();

        String overridden = "s7r-eval-ovr-" + suffix;
        insertEvaluation(overridden, run, "UNDETERMINED", "[]", null, null);
        insertReview(overridden, "PENDING_REVIEW", 0);
        revise(session, overridden, "OVERRIDE", null, "缺人工结论", 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("OVERRIDE_STATUS_REQUIRED"));
        revise(session, overridden, "OVERRIDE", "ILLEGAL", "人工确认无授权飞行", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.review.state").value("OVERRIDDEN"))
                .andExpect(jsonPath("$.data.review.manual_status").value("ILLEGAL"));
        // 复核不改研判事实。
        assertThat(jdbc.queryForObject("select legal_status from rule_evaluation where evaluation_id=?", String.class, overridden)).isEqualTo("UNDETERMINED");
        // 已复核的研判不能再复核。
        revise(session, overridden, "CONFIRM", null, "重复复核", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void supersededReviewIsRejectedWith409() throws Exception {
        jdbc.update("update legality_review set review_state='SUPERSEDED',version=1 where evaluation_id=?", evaluation);
        revise(session, evaluation, "CONFIRM", null, "旧研判已被取代", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVALUATION_SUPERSEDED"));
        escalate(session, evaluation, "旧研判已被取代", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVALUATION_SUPERSEDED"));
    }

    @Test
    void crossScopeIsNotFoundAndMissingPermissionWinsOverBadBody() throws Exception {
        String stranger = user("ASSIGNED", "assessment:read", "assessment:revise");
        jdbc.update("update app_user_data_scope set org_id='seed-stage3-org',district_id='seed-stage3-district' where user_id=(select user_id from app_session where session_id=?)", stranger);
        revise(stranger, evaluation, "CONFIRM", null, "跨范围复核", 0).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("LEGALITY_EVALUATION_NOT_FOUND"));
        mvc.perform(get("/api/v1/legality-evaluations/" + evaluation).header("Authorization", bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/legality-evaluations").header("Authorization", bearer(stranger))).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));

        String readOnly = user("ASSIGNED", "assessment:read");
        mvc.perform(post("/api/v1/legality-evaluations/" + evaluation + "/revisions").header("Authorization", bearer(readOnly))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/legality-evaluations/" + evaluation + "/alarms").header("Authorization", bearer(readOnly))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"bogus\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/legality-evaluations?wat=1").header("Authorization", bearer(user("ASSIGNED", "target:read")))).andExpect(status().isForbidden());
        // 有权限后才轮到字段校验：未知/重复字段 UNKNOWN_FIELD，坏 JSON INVALID_REQUEST。
        mvc.perform(post("/api/v1/legality-evaluations/" + evaluation + "/revisions").header("Authorization", bearer(session))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conclusion\":\"CONFIRM\",\"note\":\"x\",\"expected_version\":0,\"extra\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        mvc.perform(post("/api/v1/legality-evaluations/" + evaluation + "/revisions").header("Authorization", bearer(session))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        revise(session, evaluation, "APPROVE", null, "非法结论", 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CONCLUSION"));
        mvc.perform(get("/api/v1/legality-evaluations?latest_only=maybe").header("Authorization", bearer(session))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select review_state||'/'||version from legality_review where evaluation_id=?", String.class, evaluation)).isEqualTo("PENDING_REVIEW/0");
    }

    @Test
    void sameKeyReplaysAndDifferentRequestOnSameKeyIsRejected() throws Exception {
        String key = "replay-" + UUID.randomUUID();
        revise(session, evaluation, "CONFIRM", null, "首次提交", 0, key).andExpect(status().isOk());
        revise(session, evaluation, "CONFIRM", null, "首次提交", 0, key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        revise(session, evaluation, "REJECT", null, "换了结论", 0, key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        // 新键但版本过期：409 VERSION_CONFLICT，不再写第二条历史。
        revise(session, evaluation, "REJECT", null, "版本过期", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from legality_review_history where evaluation_id=?", Long.class, evaluation)).isEqualTo(1L);
    }

    @Test
    void successAuditFailureRollsBackReviewAndHistory() throws Exception {
        AuditService spied = org.springframework.test.util.AopTestUtils.getTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(spied).record(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("assessment"), org.mockito.ArgumentMatchers.eq("legality_evaluation_revised"),
                org.mockito.ArgumentMatchers.eq("legality_evaluation"), org.mockito.ArgumentMatchers.eq(evaluation), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("SUCCESS"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        revise(session, evaluation, "CONFIRM", null, "审计失败必须让业务事务一起回滚", 0).andExpect(status().is5xxServerError());
        assertThat(jdbc.queryForObject("select review_state||'/'||version from legality_review where evaluation_id=?", String.class, evaluation)).isEqualTo("PENDING_REVIEW/0");
        assertThat(jdbc.queryForObject("select count(*) from legality_review_history where evaluation_id=?", Long.class, evaluation)).isZero();
        // 幂等占位随事务回滚：同键重试不能被误判为 replay。
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id=(select user_id from app_session where session_id=?)", Long.class, session)).isZero();
    }

    @Test
    void allowedActionsReflectStatePermissionsAndAlarmLinkage() throws Exception {
        mvc.perform(get("/api/v1/legality-evaluations/" + evaluation).header("Authorization", bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed_actions").value(org.hamcrest.Matchers.containsInAnyOrder("REVIEW", "RECOMPUTE", "ESCALATE")))
                .andExpect(jsonPath("$.data.target_id").value(target))
                .andExpect(jsonPath("$.data.review.state").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.hit_details").isArray())
                .andExpect(jsonPath("$.data.rule_set_code").value(LocalStage7RuleEngineSeeder.RULE_SET_CODE))
                .andExpect(jsonPath("$.data.param_status").value("DEMO"));
        String readOnly = user("ASSIGNED", "assessment:read");
        mvc.perform(get("/api/v1/legality-evaluations/" + evaluation).header("Authorization", bearer(readOnly))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed_actions").isEmpty())
                .andExpect(jsonPath("$.data.target_id").doesNotExist());
        // LEGAL 研判不能转告警；列表按 review_state 与 latest_only 过滤，且 total 与 items 同谓词。
        String legal = "s7r-eval-legal-" + suffix;
        insertEvaluation(legal, run, "LEGAL", "[]", null, null);
        insertReview(legal, "PENDING_REVIEW", 0);
        mvc.perform(get("/api/v1/legality-evaluations/" + legal).header("Authorization", bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed_actions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ESCALATE"))));
        mvc.perform(get("/api/v1/legality-evaluations?mode=ACTIVE&latest_only=true&owner_org_id=" + orgId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].evaluation_id").value(legal));
        mvc.perform(get("/api/v1/legality-evaluations?review_state=PENDING_REVIEW&legal_status=ABNORMAL&owner_org_id=" + orgId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].evaluation_id").value(evaluation));
        mvc.perform(get("/api/v1/legality-evaluations/" + evaluation + "/revisions").header("Authorization", bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void escalationCreatesAlarmAndEventOnceAndLinksHistory() throws Exception {
        MvcResult result = escalate(session, evaluation, "需要人工转告警核实", 0).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.alarm_id").isString()).andExpect(jsonPath("$.data.event_id").isString())
                .andExpect(jsonPath("$.data.evaluation.review.version").value(1))
                .andExpect(jsonPath("$.data.evaluation.allowed_actions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ESCALATE")))).andReturn();
        JsonNode data = json.readTree(result.getResponse().getContentAsString()).path("data");
        String alarmId = data.path("alarm_id").asText();
        assertThat(data.path("evaluation").path("alarm_id").asText()).isEqualTo(alarmId);
        assertThat(jdbc.queryForObject("select source_id||'|'||source_alarm_id||'|'||alarm_type from alarm where alarm_id=?", String.class, alarmId))
                .isEqualTo("rule-engine-legality-mock|manual:" + evaluation + "|RULE_LEGALITY");
        assertThat(jdbc.queryForObject("select state_code from uav_event where alarm_id=?", String.class, alarmId)).isEqualTo("PENDING_VERIFICATION");
        // 人工转告警：occurred_at 是研判的 as_of（业务时刻），received_at 是操作时刻（时钟 now），不能再拿观测时刻冒充接收时刻。
        assertThat(jdbc.queryForObject("select occurred_at from alarm where alarm_id=?", Timestamp.class, alarmId).toInstant()).isEqualTo(T0.toInstant());
        assertThat(jdbc.queryForObject("select received_at from alarm where alarm_id=?", Timestamp.class, alarmId).toInstant()).isAfter(now().minusMinutes(5).toInstant());
        assertThat(jdbc.queryForObject("select related_alarm_id from legality_review_history where evaluation_id=? and conclusion='ESCALATE'", String.class, evaluation)).isEqualTo(alarmId);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='legality_evaluation_escalated' and object_id=? and result='SUCCESS'", Long.class, evaluation)).isEqualTo(1L);
        // 研判行不被人工动作改写；复核状态保持待复核，只推进版本。
        assertThat(jdbc.queryForObject("select alarm_id from rule_evaluation where evaluation_id=?", String.class, evaluation)).isNull();
        assertThat(jdbc.queryForObject("select review_state||'/'||version from legality_review where evaluation_id=?", String.class, evaluation)).isEqualTo("PENDING_REVIEW/1");
        escalate(session, evaluation, "再转一次", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ALARM_ALREADY_LINKED"));
        assertThat(jdbc.queryForObject("select count(*) from alarm where target_id=?", Long.class, target)).isEqualTo(1L);
        String legal = "s7r-eval-legal-" + suffix;
        insertEvaluation(legal, run, "LEGAL", "[]", null, null);
        insertReview(legal, "PENDING_REVIEW", 0);
        escalate(session, legal, "合法研判转告警", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void recomputeSupersedesOldReviewAndCreatesPendingReviewForNewEvaluation() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/legality-evaluations/" + evaluation + "/recompute").header("Authorization", bearer(session))
                        .header("Idempotency-Key", "recompute-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"按当前规则集重新研判\",\"expected_version\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.supersedes_evaluation_id").value(evaluation))
                .andExpect(jsonPath("$.data.trigger_kind").value("RECOMPUTE"))
                .andExpect(jsonPath("$.data.review.state").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.review.version").value(0)).andReturn();
        String fresh = json.readTree(result.getResponse().getContentAsString()).path("data").path("evaluation_id").asText();
        assertThat(fresh).isNotEqualTo(evaluation);
        assertThat(jdbc.queryForObject("select review_state||'/'||version from legality_review where evaluation_id=?", String.class, evaluation)).isEqualTo("SUPERSEDED/1");
        assertThat(jdbc.queryForObject("select related_evaluation_id from legality_review_history where evaluation_id=? and conclusion='RECOMPUTE'", String.class, evaluation)).isEqualTo(fresh);
        assertThat(jdbc.queryForObject("select legal_status from rule_evaluation where evaluation_id=?", String.class, evaluation)).isEqualTo("ABNORMAL");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='legality_evaluation_recomputed' and object_id=? and result='SUCCESS'", Long.class, evaluation)).isEqualTo(1L);
        mvc.perform(get("/api/v1/legality-evaluations/" + evaluation).header("Authorization", bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.superseded_by_evaluation_id").value(fresh))
                .andExpect(jsonPath("$.data.allowed_actions").isEmpty());
        // 旧研判已被取代：再复核/再重算都 409。
        revise(session, evaluation, "CONFIRM", null, "取代后复核", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVALUATION_SUPERSEDED"));
        // 队列只看最新：旧研判不再出现。
        mvc.perform(get("/api/v1/legality-evaluations?latest_only=true&mode=ACTIVE&owner_org_id=" + orgId).header("Authorization", bearer(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].evaluation_id").value(fresh));
    }

    @Test
    void manualEvaluationCreatesRunAndEvaluationForVisibleTargetOnly() throws Exception {
        mvc.perform(post("/api/v1/legality-evaluations").header("Authorization", bearer(session))
                        .header("Idempotency-Key", "manual-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject_kind\":\"TARGET\",\"subject_id\":\"" + target + "\",\"mode\":\"ACTIVE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.run_id").isString())
                .andExpect(jsonPath("$.data.evaluation.trigger_kind").value("MANUAL"))
                .andExpect(jsonPath("$.data.evaluation.legal_status").value("ILLEGAL"))
                .andExpect(jsonPath("$.data.evaluation.review.state").value("PENDING_REVIEW"));
        // 没有计划的目标按 C03.no_plan_status 判非法并经 C06 生成一条告警。
        assertThat(jdbc.queryForObject("select count(*) from alarm where target_id=? and source_id='rule-engine-legality-mock'", Long.class, target)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='legality_evaluation_triggered' and result='SUCCESS' and user_id=(select user_id from app_session where session_id=?)", Long.class, session)).isEqualTo(1L);
        mvc.perform(post("/api/v1/legality-evaluations").header("Authorization", bearer(session))
                        .header("Idempotency-Key", "manual-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject_kind\":\"TARGET\",\"subject_id\":\"seed-stage7-target-cross-scope\",\"mode\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/legality-evaluations").header("Authorization", bearer(user("ASSIGNED", "assessment:read", "assessment:evaluate")))
                        .header("Idempotency-Key", "manual-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject_kind\":\"TARGET\",\"subject_id\":\"" + target + "\",\"mode\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void hookCreatesPendingReviewOnlyForApplicableActiveEvaluations() {
        // NOT_APPLICABLE（STALE/NO_STATE）没有可复核的结论：每个 tick 都会产生这类研判，建复核行只会淹没队列。
        String stale = "s7r-eval-stale-" + suffix, undetermined = "s7r-eval-undet-" + suffix, shadow = "s7r-eval-shadow-" + suffix;
        insertEvaluation(stale, run, "NOT_APPLICABLE", "[]", null, null);
        insertEvaluation(undetermined, run, "UNDETERMINED", "[]", null, null);
        insertEvaluation(shadow, run, "ILLEGAL", "[\"NO_AUTHORIZATION\"]", "HIGH", new BigDecimal("80"));
        assertThat(hooks.afterEvaluation(outcome(stale, RunMode.ACTIVE, LegalStatus.NOT_APPLICABLE)).alarmId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from legality_review where evaluation_id=?", Long.class, stale)).isZero();
        hooks.afterEvaluation(outcome(undetermined, RunMode.ACTIVE, LegalStatus.UNDETERMINED));
        assertThat(jdbc.queryForObject("select review_state from legality_review where evaluation_id=?", String.class, undetermined)).isEqualTo("PENDING_REVIEW");
        hooks.afterEvaluation(outcome(shadow, RunMode.SHADOW, LegalStatus.ILLEGAL));
        assertThat(jdbc.queryForObject("select count(*) from legality_review where evaluation_id=?", Long.class, shadow)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alarm where target_id=?", Long.class, target)).isZero();
    }

    private EvaluationOutcome outcome(String id, RunMode mode, LegalStatus status) {
        return new EvaluationOutcome(id, run, LocalStage7RuleEngineSeeder.RULE_SET_ID, LocalStage7RuleEngineSeeder.VERSION_1, mode, "MANUAL",
                new Subject(SubjectKind.TARGET, target, null, null, null), target, null, null, null, orgId, district, "mock", status,
                status == LegalStatus.NOT_APPLICABLE ? PlanMatchCode.NOT_APPLICABLE : PlanMatchCode.NONE, null, null, List.of(), List.of(),
                T0, T0, T0, now(), null, null, false, null);
    }

    // ---- helpers ----

    private org.springframework.test.web.servlet.ResultActions revise(String token, String id, String conclusion, String override, String note, long version) throws Exception {
        return revise(token, id, conclusion, override, note, version, "k-" + UUID.randomUUID());
    }

    private org.springframework.test.web.servlet.ResultActions revise(String token, String id, String conclusion, String override, String note, long version, String key) throws Exception {
        String body = "{\"conclusion\":\"" + conclusion + "\"" + (override == null ? "" : ",\"override_status\":\"" + override + "\"") + ",\"note\":\"" + note + "\",\"expected_version\":" + version + "}";
        return mvc.perform(write("/api/v1/legality-evaluations/" + id + "/revisions", token, key, body));
    }

    private org.springframework.test.web.servlet.ResultActions escalate(String token, String id, String note, long version) throws Exception {
        return mvc.perform(write("/api/v1/legality-evaluations/" + id + "/alarms", token, "esc-" + UUID.randomUUID(), "{\"note\":\"" + note + "\",\"expected_version\":" + version + "}"));
    }

    private static MockHttpServletRequestBuilder write(String path, String token, String key, String body) {
        return post(path).header("Authorization", bearer(token)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void target(String id, String sn, OffsetDateTime observed) {
        String link = "s7r-link-" + suffix, track = "s7r-track-" + suffix;
        Timestamp at = ts(observed.minusMinutes(5));
        jdbc.update("insert into target (target_id,target_no,object_type_code,uav_sn,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) values (?,?,'UAV',?,?,?,'mock',?,?,?,?,0)",
                id, "MB-S7R-" + suffix, sn, at, ts(observed), orgId, district, at, ts(observed));
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,'seed-stage7-source',?,?,?)", link, id, "s-" + suffix, "x-" + suffix, at);
        jdbc.update("insert into target_latest_state (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,classification_confidence,fusion_confidence,observed_at,received_at,created_at,updated_at,version) values (?,CAST('SRID=4326;POINT(118.61 37.41)' AS GEOMETRY),80,60,10,90,0.9,0.95,?,?,?,?,0)",
                id, ts(observed), ts(observed), at, ts(observed));
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at) values (?,?,?,?,?,?)", track, id, link, "tr-" + suffix, at, at);
        for (int i = 0; i < 5; i++) {
            Timestamp seen = ts(observed.minusSeconds((4 - i) * 5L));
            jdbc.update("insert into track_point (point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,height_agl_m,created_at) values (?,?,?,?,?,CAST('SRID=4326;POINT(118.61 37.41)' AS GEOMETRY),80,60,?)",
                    "s7r-pt-" + suffix + "-" + i, track, i, seen, seen, seen);
        }
    }

    private void insertRun(String id, String trigger, String status) {
        jdbc.update("insert into rule_run (run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,replay_dataset_code,triggered_by,as_of,started_at,finished_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,error_summary,source_mode,created_at) values (?,?,?,'ACTIVE',?,null,null,?,?,?,?,1,1,0,0,null,'mock',?)",
                id, LocalStage7RuleEngineSeeder.RULE_SET_ID, LocalStage7RuleEngineSeeder.VERSION_1, trigger, ts(T0), ts(T0), ts(T0.plusSeconds(1)), status, ts(T0));
    }

    private void insertEvaluation(String id, String runId, String legalStatus, String violations, String grade, BigDecimal score) {
        String hits = "[{\"rule_code\":\"C01\",\"rule_version_id\":\"seed-stage7-rule-C01\",\"result_code\":\"FAIL\",\"reason_code\":\"NO_PLAN_CANDIDATE\",\"facts\":{\"plan_match_code\":\"NONE\"},\"params\":[{\"key\":\"time_window_min\",\"value\":\"10\",\"status\":\"DEMO\"}],\"evidence\":[],\"message\":\"没有可匹配的飞行计划；参数为 DEMO 演示值\"}]";
        jdbc.update("insert into rule_evaluation (evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,track_id,plan_id,route_version_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,supersedes_evaluation_id,assessment_id,alarm_outcome,alarm_id,owner_org_id,district_id,source_mode,created_at) values (?,?,?,'ACTIVE','TARGET',?,null,null,null,?,?,?,'FRESH','NONE',?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST('[]' AS JSON),CAST('[{\"kind\":\"target\",\"id\":\"" + target + "\"}]' AS JSON),CAST('{}' AS JSON),null,null,null,null,?,?,'mock',?)",
                id, runId, LocalStage7RuleEngineSeeder.VERSION_1, target, ts(T0), ts(T0), ts(T0), legalStatus, score, grade, violations, hits, orgId, district, ts(T0));
    }

    private void insertReview(String id, String state, long version) {
        jdbc.update("insert into legality_review (evaluation_id,review_state,manual_status,version,owner_org_id,district_id,created_at,updated_at) values (?,?,null,?,?,?,?,?)",
                id, state, version, orgId, district, ts(T0), ts(T0));
    }

    /** ASSIGNED 用户配本测试的 (orgId, district) 授权元组；越权用例再把授权改到别的元组。 */
    private String user(String scope, String... permissions) {
        String tag = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-S7R-" + tag, userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,?,0,0,0,0)",
                userId, "s7r-" + tag, "复核测试员", role, scope);
        if ("ASSIGNED".equals(scope)) jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, orgId, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, userId, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC).withNano(0); }
    private static Timestamp ts(OffsetDateTime value) { return Timestamp.from(Instant.from(value)); }
    private static String bearer(String token) { return "Bearer " + token; }
}
