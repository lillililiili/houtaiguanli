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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 处罚交接材料包 v2（决策 14-1…14-4）。
 *
 * 这一套的重点不是"能不能提交成功"，而是**快照里到底冻结了什么**：
 * 处罚要求"事实清楚、证据充分"，四段缺任何一段，事后都无法凭这份材料说明当时依据了什么。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HandoffPunishmentMaterialsApiTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private String submitter;
    private String eventId;
    private String recipientId;

    @BeforeEach
    void fixture() {
        submitter = user("SUB", List.of("handoff:create", "handoff:read", "alarm:read", "evidence:read"));
        eventId = confirmedEventWithCompletedDisposal();
        recipientId = "pm-recipient-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into handoff_recipient (recipient_id,display_name,handoff_type,enabled,created_at,updated_at)"
                + " values (?,?,'UAV_PUNISHMENT',true,current_timestamp,current_timestamp)", recipientId, "演示处罚接收方");
    }

    @AfterEach
    void cleanup() {
        // 自己造的会话/范围/授权也清掉：共享 H2 上下文里后跑的用例会把这些授权数成"产品授权"（14-34）。
        // 用户与角色行不删：审计以 FK 引用用户；空授权对任何断言都是惰性的。
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where role_code like 'ROLE-PM-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where role_code like 'ROLE-PM-%')");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-PM-%'");
        jdbc.update("delete from handoff_delivery where handoff_id in (select handoff_id from handoff where source_id like 'pm-event-%')");
        jdbc.update("delete from handoff_material_snapshot where handoff_id in (select handoff_id from handoff where source_id like 'pm-event-%')");
        jdbc.update("delete from handoff where source_id like 'pm-event-%'");
        jdbc.update("delete from handoff_recipient where recipient_id like 'pm-recipient-%'");
        jdbc.update("delete from evidence_link where subject_id like 'pm-event-%'");
        jdbc.update("delete from evidence_file where evidence_id like 'pm-evi-%'");
        jdbc.update("delete from disposal_authorization_event where authorization_id in"
                + " (select authorization_id from disposal_authorization where subject_id like 'pm-event-%')");
        jdbc.update("delete from disposal_authorization where subject_id like 'pm-event-%'"
                + " and chained_from_authorization_id is not null");
        jdbc.update("delete from disposal_authorization where subject_id like 'pm-event-%'");
        jdbc.update("delete from uav_event_verification where event_id like 'pm-event-%'");
        jdbc.update("delete from uav_event where event_id like 'pm-event-%'");
        jdbc.update("delete from alarm where alarm_id like 'pm-alarm-%'");
    }

    /* ---- 正面 ---- */

    @Test
    void snapshotFreezesAllFourSections() throws Exception {
        String handoffId = body(submit(submitter, eventId).andExpect(status().isCreated()))
                .path("data").path("handoff_id").asText();
        JsonNode material = detail(handoffId, submitter).path("material");
        assertThat(material.path("schema_version").asInt()).isEqualTo(2);
        // 事件：处罚认定"何时何地发生了什么"的依据。
        assertThat(material.path("event").path("event_id").asText()).isEqualTo(eventId);
        assertThat(material.path("event").path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(material.path("event").path("source_alarm_id").asText()).isNotBlank();
        // 核实历史：谁在什么时候认定它属实。
        assertThat(material.path("verifications")).isNotEmpty();
        // 处置授权：证明确实处置过——这是处罚交接的前提，也是卷宗里最容易被追问的一段。
        assertThat(material.path("disposals")).isNotEmpty();
        assertThat(material.path("disposals").get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(material.path("disposals").get(0).path("authorization_no").asText()).isNotBlank();
        // 证据引用。
        assertThat(material.path("evidence")).isNotEmpty();
        assertThat(material.path("evidence").get(0).path("evidence_no").asText()).isNotBlank();
    }

    @Test
    void snapshotIsFrozenAndDoesNotFollowLaterChanges() throws Exception {
        String handoffId = body(submit(submitter, eventId).andExpect(status().isCreated()))
                .path("data").path("handoff_id").asText();
        // 提交之后再加一条证据：快照是"提交那一刻的事实"，不能随源变（决策 14-2）。
        evidence(eventId, "pm-evi-late-" + UUID.randomUUID().toString().substring(0, 6), "后补证据");
        JsonNode material = detail(handoffId, submitter).path("material");
        assertThat(material.path("evidence")).hasSize(1);
    }

    /* ---- 权限与状态 ---- */

    @Test
    void submittingWithoutAlarmReadIsForbidden() throws Exception {
        // 处罚交接读的是事件，不再无条件要求 risk:read（决策 14-3）。缺 alarm:read 一律 403，先于任何解析。
        String noAlarm = user("NOA", List.of("handoff:create", "handoff:read"));
        submit(noAlarm, eventId).andExpect(status().isForbidden());
    }

    @Test
    void withoutAlarmReadEveryEventIdIsForbiddenAlike() throws Exception {
        // 决策 14-21 修订：读权先于前提校验。前提用的 completedExists 不带范围过滤，
        // 若排在读权之前，只有 handoff:create 的人就能靠 409/403 的差异跨机构探测
        // "那边有没有处置完成的事件"。三个 id 三种真实情况，对无权限者必须**一模一样**都是 403。
        String noAlarm = user("NA2", List.of("handoff:create", "handoff:read"));
        String withDisposal = eventId;                       // 存在且有已完成授权
        String withoutDisposal = confirmedEvent();           // 存在但没有已完成授权
        String nonExistent = "pm-event-does-not-exist";      // 根本不存在
        for (String probe : List.of(withDisposal, withoutDisposal, nonExistent)) {
            mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(noAlarm))
                            .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"source_kind\":\"UAV_EVENT\",\"source_id\":\"" + probe + "\","
                                    + "\"handoff_type\":\"UAV_PUNISHMENT\",\"recipient_id\":\"" + recipientId
                                    + "\",\"expected_version\":1}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void withAlarmReadTheKindTypeMismatchStillAnswersFourHundred() throws Exception {
        // 14-21 修订把无 alarm:read 的情形改成 403 之后，阶段 5 起 (UAV_EVENT, RISK_NOTICE) 恒 400 INVALID_KIND
        // 这条答复就没人看守了——原来盯着它的用例现在先吃 403。这里用一个**有** alarm:read 的账号补上：
        // 权限够了之后，组合本身讲不通仍然是 400，不是 403 也不是 409。
        mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(submitter))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source_kind\":\"UAV_EVENT\",\"source_id\":\"" + eventId + "\","
                                + "\"handoff_type\":\"RISK_NOTICE\",\"recipient_id\":\"" + recipientId
                                + "\",\"expected_version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_KIND"));
    }

    @Test
    void unconfirmedEventCannotBeHandedOver() throws Exception {
        String pending = confirmedEventWithCompletedDisposal();
        jdbc.update("update uav_event set state_code='PENDING_VERIFICATION' where event_id=?", pending);
        submit(submitter, pending).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void eventWithoutCompletedDisposalIsStillBlocked() throws Exception {
        String noDisposal = confirmedEvent();
        // 阶段 13 的前提没有被材料包 v2 取消：没处置过就不能移送处罚。
        submit(submitter, noDisposal).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("HANDOFF_PREREQUISITE_UNAVAILABLE"));
    }

    @Test
    void staleExpectedVersionIsConflict() throws Exception {
        mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(submitter))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content(bodyFor(eventId, 99)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    /**
     * 决策 18-14 只放开了风险通知的接收方缺省。移送给谁是案件的一部分，
     * 处罚交接漏填接收方必须当场拒绝——不能顺手替值班员挑一个。
     */
    @Test
    void punishmentHandoffStillRequiresAnExplicitRecipient() throws Exception {
        // 把这个接收方标成默认：不这样，缺省逻辑即便对处罚也生效，也会因为"查不到默认"而恰好 400，测试就白钉了。
        jdbc.update("update handoff_recipient set is_default=true where recipient_id=?", recipientId);
        long version = jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId);
        String withoutRecipient = "{\"source_kind\":\"UAV_EVENT\",\"source_id\":\"" + eventId
                + "\",\"handoff_type\":\"UAV_PUNISHMENT\",\"expected_version\":" + version + "}";
        mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(submitter))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(withoutRecipient))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RECIPIENT_REQUIRED"));
    }

    @Test
    void secondHandoffForSameEventAndRecipientIsRejected() throws Exception {
        submit(submitter, eventId).andExpect(status().isCreated());
        submit(submitter, eventId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("HANDOFF_ALREADY_EXISTS"));
    }

    /* ---- 证据段的可用性（决策 14-4）---- */

    @Test
    void evidenceSectionIsOmittedWhenSubmitterLacksEvidenceRead() throws Exception {
        String noEvidence = user("NOE", List.of("handoff:create", "handoff:read", "alarm:read"));
        String handoffId = body(submit(noEvidence, eventId).andExpect(status().isCreated()))
                .path("data").path("handoff_id").asText();
        JsonNode detail = detail(handoffId, noEvidence);
        // 整段省略并标明，不留空数组冒充"本案没有证据"——那是两件完全不同的事。
        assertThat(detail.path("material").has("evidence")).isFalse();
        assertThat(detail.path("material").path("evidence_omitted").asBoolean()).isTrue();
        assertThat(detail.path("availability").path("evidence").asText()).isEqualTo("OMITTED_AT_SUBMISSION");
    }

    @Test
    void readerWithoutEvidenceReadSeesForbiddenRatherThanTheSection() throws Exception {
        String handoffId = body(submit(submitter, eventId).andExpect(status().isCreated()))
                .path("data").path("handoff_id").asText();
        String reader = user("RDR", List.of("handoff:read", "alarm:read"));
        JsonNode detail = detail(handoffId, reader);
        assertThat(detail.path("material").has("evidence")).isFalse();
        assertThat(detail.path("availability").path("evidence").asText()).isEqualTo("FORBIDDEN");
    }

    /* ---- 读侧可用性 ---- */

    @Test
    void readerWithoutAlarmReadGetsForbiddenMaterial() throws Exception {
        String handoffId = body(submit(submitter, eventId).andExpect(status().isCreated()))
                .path("data").path("handoff_id").asText();
        String reader = user("NAR", List.of("handoff:read"));
        JsonNode detail = detail(handoffId, reader);
        assertThat(detail.path("availability").path("material").asText()).isEqualTo("FORBIDDEN");
        assertThat(detail.path("material").has("event")).isFalse();
    }

    @Test
    void readerOutsideScopeGetsSourceNotVisible() throws Exception {
        String handoffId = body(submit(submitter, eventId).andExpect(status().isCreated()))
                .path("data").path("handoff_id").asText();
        String outsider = user("OUT", List.of("handoff:read", "alarm:read"), true);
        // 交接本身可能因归属可见，但事件已不在读者范围时材料整体省略——不能靠材料反推事件内容。
        JsonNode detail = detail(handoffId, outsider);
        if (detail.isMissingNode() || detail.isNull()) return;   // 交接本身不可见时接口 404，也是可接受的结果
        assertThat(detail.path("availability").path("material").asText()).isEqualTo("SOURCE_NOT_VISIBLE");
    }

    /* ---- 辅助 ---- */

    private ResultActions submit(String token, String event) throws Exception {
        long version = jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, event);
        return mvc.perform(post("/api/v1/handoffs").header("Authorization", bearer(token))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(bodyFor(event, version)));
    }

    private String bodyFor(String event, long version) {
        return "{\"source_kind\":\"UAV_EVENT\",\"source_id\":\"" + event + "\",\"handoff_type\":\"UAV_PUNISHMENT\","
                + "\"recipient_id\":\"" + recipientId + "\",\"expected_version\":" + version + "}";
    }

    private JsonNode detail(String handoffId, String token) throws Exception {
        return body(mvc.perform(get("/api/v1/handoffs/{id}", handoffId).header("Authorization", bearer(token)))).path("data");
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    /** 已核实事件 + 一条已完成处置授权 + 一份证据：处罚交接的最小完整前提。 */
    private String confirmedEventWithCompletedDisposal() {
        String id = confirmedEvent();
        disposal(id, "COMPLETED");
        evidence(id, "pm-evi-" + UUID.randomUUID().toString().substring(0, 8), "现场照片");
        return id;
    }

    private String confirmedEvent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alarmId = "pm-alarm-" + suffix, id = "pm-event-" + suffix;
        Timestamp at = Timestamp.from(Instant.parse("2026-09-08T02:00:00Z"));
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select 'pm-src','PM-TEST','处罚材料测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='pm-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'pm-src',?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarmId, "告警-处罚-" + suffix, at, at, ORG, DISTRICT, at);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'CONFIRMED',?,?,?,?,1)", id, alarmId, ORG, DISTRICT, at, at);
        jdbc.update("insert into uav_event_verification (history_id,event_id,version,previous_state,resulting_state,"
                + "conclusion,note,actor_id,created_at) select ?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','演示核实',"
                + "(select user_id from app_user where account='admin1'),?", UUID.randomUUID().toString(), id, at);
        return id;
    }

    private void disposal(String eventId, String status) {
        Timestamp at = Timestamp.from(Instant.parse("2026-09-08T02:10:00Z"));
        String admin = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        jdbc.update("insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "target_id,device_id,channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,"
                + "status,result_code,result_detail,policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at)"
                + " values (?,?,'COUNTERMEASURE','UAV_EVENT',?,null,null,'MANUAL','演示处置',?,?,?,?,?,?,?,'MANUAL_SUCCEEDED',"
                + "'演示：人工反制完成','demo-v1',?,?,'mock',1,?,?)",
                UUID.randomUUID().toString(), "AUTH-20260908-" + (8000 + (int) (Math.random() * 999)), eventId,
                admin, at, admin, at, at, Timestamp.from(Instant.parse("2026-09-08T02:40:00Z")), status,
                ORG, DISTRICT, at, at);
    }

    private void evidence(String eventId, String evidenceId, String name) {
        Timestamp at = Timestamp.from(Instant.parse("2026-09-08T02:05:00Z"));
        jdbc.update("insert into evidence_file (evidence_id,evidence_no,kind_code,original_name,content_type,storage_backend,"
                + "object_key,size_bytes,sha256,captured_at,stored_at,status,source_mode,owner_org_id,district_id,created_at,updated_at)"
                + " values (?,?,'SCENE_PHOTO',?,'image/jpeg','local',?,1024,?,?,?,'AVAILABLE','mock',?,?,?,?)",
                evidenceId, "证据-" + evidenceId.substring(7), name, "obj/" + evidenceId,
                "a".repeat(64), at, at, ORG, DISTRICT, at, at);
        jdbc.update("insert into evidence_link (link_id,evidence_id,subject_kind,subject_id,event_id,created_at)"
                + " values (?,?,'EVENT',?,?,?)", UUID.randomUUID().toString(), evidenceId, eventId, eventId, at);
    }

    private String user(String tag, List<String> permissions) { return user(tag, permissions, false); }

    private String user(String tag, List<String> permissions, boolean otherScope) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-PM-" + tag + "-" + suffix;
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,?,false,current_timestamp)", role, permission,
                    permission.endsWith(":read") ? "READ" : "OP");
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "pm-" + tag.toLowerCase() + "-" + suffix, "处罚" + tag, role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId,
                otherScope ? "seed-stage3-other-org" : ORG, otherScope ? "seed-stage3-other-district" : DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, userId, System.currentTimeMillis() + 3_600_000);
        return token;
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
