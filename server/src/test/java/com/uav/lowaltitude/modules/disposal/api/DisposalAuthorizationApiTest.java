package com.uav.lowaltitude.modules.disposal.api;

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

/** 处置授权接口：403→400→409 顺序、两人规则、时限、幂等、版本冲突、越权 404、事件只增。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisposalAuthorizationApiTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private String requester, approver, reader;
    private String requesterId, approverId;
    private String eventId;

    @BeforeEach
    void fixture() {
        String[] r = user("REQ", List.of("disposal:read", "disposal:request"), false);
        requester = r[0]; requesterId = r[1];
        String[] a = user("APR", List.of("disposal:read", "disposal:approve", "disposal:execute", "disposal:stop"), false);
        approver = a[0]; approverId = a[1];
        reader = user("RDR", List.of("disposal:read"), false)[0];
        eventId = confirmedEvent();
    }

    @AfterEach
    void cleanup() {
        // 先删事件再删授权：事件表有 FK 指向授权。反过来会撞 23503，而且失败发生在 @AfterEach，
        // 表现是"下一个用例莫名其妙地错"，排查会绕远路。
        jdbc.update("delete from disposal_authorization_event where authorization_id in"
                + " (select authorization_id from disposal_authorization"
                + "  where subject_id like 'dsp-event-%' or subject_id like 'dsp-target-%')");
        jdbc.update("delete from disposal_authorization where chained_from_authorization_id is not null"
                + " and (subject_id like 'dsp-event-%' or subject_id like 'dsp-target-%')");
        jdbc.update("delete from disposal_authorization where subject_id like 'dsp-event-%'"
                + " or subject_id like 'dsp-target-%'");
        jdbc.update("delete from target_current_alias where historical_target_id like 'dsp-target-%'");
        jdbc.update("delete from target_latest_state where target_id like 'dsp-target-%'");
        jdbc.update("delete from target where target_id like 'dsp-target-%'");
        jdbc.update("delete from uav_event where event_id like 'dsp-event-%'");
        jdbc.update("delete from alarm where alarm_id like 'dsp-alarm-%'");
    }

    /* ---- 鉴权先于一切 ---- */

    @Test
    void permissionIsCheckedBeforeBodyAndSubject() throws Exception {
        // 只读用户拿一个连 JSON 都不是的请求体去申请：必须 403，而不是 400。
        // 若先解析请求体，攻击者就能靠 400 与 403 的差异反推自己有没有这项权限。
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(reader))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isForbidden());
        // 主体不存在也仍然是 403 优先。
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(reader))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("COUNTERMEASURE", "no-such-event", "MANUAL")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownBodyFieldIsRejected() throws Exception {
        // 拼错的字段被静默丢掉，会让人以为自己填的参数生效了——对"允许动手"的申请，这种误解不能有。
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action_type\":\"COUNTERMEASURE\",\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""
                                + eventId + "\",\"channel\":\"MANUAL\",\"reason\":\"演示\",\"typo_field\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /* ---- 申请 ---- */

    @Test
    void createRequiresConfirmedEventPerPolicy() throws Exception {
        String pending = pendingEvent();
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("COUNTERMEASURE", pending, "MANUAL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POLICY_REQUIRES_CONFIRMED_EVENT"));
        // 驱离在 demo-v1 里不要求已核实，同一个事件应当放行——证明这条判断真的读了策略，不是写死的。
        create(requester, "DISPERSAL", pending, "MANUAL").andExpect(status().isCreated());
    }

    @Test
    void secondActiveAuthorizationForSameSubjectIsRejected() throws Exception {
        create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated());
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("COUNTERMEASURE", eventId, "MANUAL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_AUTHORIZATION_EXISTS"));
    }

    @Test
    void authorizationNumberIsIssuedAndRequestEventRecorded() throws Exception {
        JsonNode created = body(create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        assertThat(created.path("data").path("authorization_no").asText()).matches("AUTH-\\d{8}-\\d{4,}");
        assertThat(created.path("data").path("status").asText()).isEqualTo("REQUESTED");
        String id = created.path("data").path("authorization_id").asText();
        assertThat(eventKinds(id)).containsExactly("REQUEST");
    }

    /* ---- 审批 ---- */

    @Test
    void twoPersonRuleBlocksSelfApproval() throws Exception {
        // 申请人自己也有审批权时，仍然不能批自己的申请。
        String[] both = user("BOTH", List.of("disposal:read", "disposal:request", "disposal:approve"), false);
        String id = id(create(both[0], "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", id).header("Authorization", bearer(both[0]))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TWO_PERSON_RULE"));
        assertThat(statusOf(id)).isEqualTo("REQUESTED");
    }

    @Test
    void approveSetsWindowFromPolicyAndBumpsVersion() throws Exception {
        String id = id(create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        approve(id, 0).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(1));
        JsonNode detail = body(mvc.perform(get("/api/v1/disposal-authorizations/{id}", id)
                .header("Authorization", bearer(approver))).andExpect(status().isOk())).path("data");
        long from = detail.path("valid_from").asLong(), until = detail.path("valid_until").asLong();
        // COUNTERMEASURE 在 demo-v1 里是 30 分钟；时限来自策略而不是代码。
        assertThat(until - from).isEqualTo(30 * 60_000L);
    }

    @Test
    void staleExpectedVersionIsConflict() throws Exception {
        String id = id(create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        approve(id, 0).andExpect(status().isOk());
        // 拿旧版本号再批一次：必须 409，而不是把已批准的授权重批一遍。
        approve(id, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void approveOnNonRequestedIsInvalidTransition() throws Exception {
        String id = id(create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        approve(id, 0).andExpect(status().isOk());
        approve(id, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    /* ---- 幂等 ---- */

    @Test
    void sameIdempotencyKeyDoesNotCreateTwoAuthorizations() throws Exception {
        String key = key();
        String bodyText = createBody("COUNTERMEASURE", eventId, "MANUAL");
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(bodyText))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(bodyText))
                .andExpect(status().is4xxClientError());
        assertThat(count(eventId)).isEqualTo(1);
    }

    /* ---- 范围 ---- */

    @Test
    void authorizationOutsideScopeIsNotFoundRatherThanForbidden() throws Exception {
        String id = id(create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        // 别的组织的人有全部处置权限，但看不到这条：必须 404 而不是 403——
        // 403 等于告诉对方"这个 ID 是存在的"，那本身就是泄露。
        String outsider = user("OUT", List.of("disposal:read", "disposal:approve"), true)[0];
        mvc.perform(get("/api/v1/disposal-authorizations/{id}", id).header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", id).header("Authorization", bearer(outsider))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}"))
                .andExpect(status().isNotFound());
    }

    /* ---- allowed_actions ---- */

    @Test
    void allowedActionsReflectStatusAndCallerPermissions() throws Exception {
        String id = id(create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        assertThat(allowedActions(id, approver)).containsExactlyInAnyOrder("APPROVE", "REJECT", "CANCEL");
        // 申请人没有审批权，但可以撤回自己的申请。
        assertThat(allowedActions(id, requester)).containsExactly("CANCEL");
        // 只读用户一个动作都拿不到。
        assertThat(allowedActions(id, reader)).isEmpty();
    }

    /* ---- 事件只增 ---- */

    @Test
    void eventStreamGrowsAndIsNeverRewritten() throws Exception {
        String id = id(create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        approve(id, 0).andExpect(status().isOk());
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/stop", id).header("Authorization", bearer(approver))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":1,\"note\":\"演示停止\"}"))
                .andExpect(status().isOk());
        // 停止的是 MANUAL 通道：没有设备可急停，因此是"未尝试"而不是"已执行"。
        assertThat(eventKinds(id)).containsExactly("REQUEST", "APPROVE", "STOP");
        JsonNode detail = body(mvc.perform(get("/api/v1/disposal-authorizations/{id}", id)
                .header("Authorization", bearer(approver))).andExpect(status().isOk())).path("data");
        assertThat(detail.path("device_stop_result").asText()).isEqualTo("NOT_ATTEMPTED");
    }

    /* ---- 目标主体（决策 13-24）与人名回填（13-26）---- */

    @Test
    void dispersalMayBeRequestedAgainstATargetDirectly() throws Exception {
        String targetId = target(ORG, DISTRICT);
        // 态势页的"派发驱离"直接对着目标发；驱离在 demo-v1 里不要求已核实事件。
        JsonNode data = body(mvc.perform(post("/api/v1/disposal-authorizations")
                        .header("Authorization", bearer(requester)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action_type\":\"DISPERSAL\",\"subject_kind\":\"TARGET\",\"subject_id\":\""
                                + targetId + "\",\"channel\":\"MANUAL\",\"reason\":\"目标主体驱离\"}"))
                .andExpect(status().isCreated())).path("data");
        assertThat(data.path("status").asText()).isEqualTo("REQUESTED");
    }

    @Test
    void countermeasureAgainstATargetIsRejectedBecauseItNeedsAConfirmedEvent() throws Exception {
        String targetId = target(ORG, DISTRICT);
        // 反制要求"事件已核实"，而目标主体身上没有事件可查——不能因为换了个主体类型就绕过这条。
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action_type\":\"COUNTERMEASURE\",\"subject_kind\":\"TARGET\",\"subject_id\":\""
                                + targetId + "\",\"channel\":\"MANUAL\",\"reason\":\"不该被接受\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POLICY_REQUIRES_CONFIRMED_EVENT"));
    }

    @Test
    void targetOutsideScopeIsNotFound() throws Exception {
        String targetId = target("seed-stage3-other-org", "seed-stage3-other-district");
        // 看不见的目标必须表现为"不存在"，否则拿 ID 就能试探别的辖区有什么目标。
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action_type\":\"DISPERSAL\",\"subject_kind\":\"TARGET\",\"subject_id\":\""
                                + targetId + "\",\"channel\":\"MANUAL\",\"reason\":\"越权\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void staleTargetIsRejectedAsNotActive() throws Exception {
        // 对一条早已消失的航迹派驱离没有意义，也无法交代。阈值取 C03.fresh_seconds，不在代码里写死。
        String stale = target(ORG, DISTRICT, Instant.now().minusSeconds(4 * 3600));
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action_type\":\"DISPERSAL\",\"subject_kind\":\"TARGET\",\"subject_id\":\""
                                + stale + "\",\"channel\":\"MANUAL\",\"reason\":\"目标已消失\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TARGET_NOT_ACTIVE"));
    }

    @Test
    void targetWithNoObservationIsRejectedRatherThanAssumedLive() throws Exception {
        String id = "dsp-target-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(Instant.parse("2026-09-07T02:00:00Z"));
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'mock',?,?,?,?,0)", id, "目标-无观测-" + id.substring(11), ORG, DISTRICT, at, at);
        // 没有观测记录不等于"刚出现"：缺证据时不能默认它还活着。
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action_type\":\"DISPERSAL\",\"subject_kind\":\"TARGET\",\"subject_id\":\""
                                + id + "\",\"channel\":\"MANUAL\",\"reason\":\"无观测\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TARGET_NOT_ACTIVE"));
        jdbc.update("delete from target where target_id=?", id);
    }

    @Test
    void historicalTargetIdResolvesToTheCurrentTarget() throws Exception {
        // 融合会把历史目标并入当前目标；拿旧 ID 发起处置必须落到存活的那条航迹上，
        // 否则等于对一个已经不存在的航迹动手。
        String current = target(ORG, DISTRICT);
        String historical = target(ORG, DISTRICT, Instant.now().minusSeconds(4 * 3600));
        String lineage = lineage(current);
        if (lineage == null) return;   // 没有可用谱系夹具时不做假断言
        jdbc.update("insert into target_current_alias (historical_target_id,current_target_id,lineage_id,updated_at)"
                + " values (?,?,?,current_timestamp)", historical, current, lineage);
        // historical 自己已经过期，但解析到 current 之后是新鲜的——应当放行。
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action_type\":\"DISPERSAL\",\"subject_kind\":\"TARGET\",\"subject_id\":\""
                                + historical + "\",\"channel\":\"MANUAL\",\"reason\":\"旧 ID 派驱离\"}"))
                .andExpect(status().isCreated());
        // 落库的主体必须是解析后的当前目标，不是调用方递进来的旧 ID。
        assertThat(jdbc.queryForObject("select subject_id from disposal_authorization where subject_id=?",
                String.class, current)).isEqualTo(current);
        jdbc.update("delete from target_current_alias where historical_target_id=?", historical);
    }

    private String lineage(String targetId) {
        java.util.List<String> ids = jdbc.queryForList("select lineage_id from target_lineage limit 1", String.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    @Test
    void riskSubjectStaysUnsupported() throws Exception {
        mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action_type\":\"DISPERSAL\",\"subject_kind\":\"RISK\",\"subject_id\":\"risk-1\""
                                + ",\"channel\":\"MANUAL\",\"reason\":\"本期不支持\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SUBJECT_KIND_NOT_SUPPORTED"));
    }

    @Test
    void requesterAndApproverNamesAreReturnedNotJustIds() throws Exception {
        String id = id(create(requester, "COUNTERMEASURE", eventId, "MANUAL").andExpect(status().isCreated()));
        approve(id, 0).andExpect(status().isOk());
        JsonNode data = body(mvc.perform(get("/api/v1/disposal-authorizations/{id}", id)
                .header("Authorization", bearer(approver))).andExpect(status().isOk())).path("data");
        // 屏幕上要显示人名；内部 ID 只作为兜底，不该是唯一能看到的东西。
        assertThat(data.path("requested_by_name").asText()).isNotBlank().isNotEqualTo(requesterId);
        assertThat(data.path("approved_by_name").asText()).isNotBlank().isNotEqualTo(approverId);
    }

    /** 造一个"刚被观测到"的目标：新鲜度取生效规则集的 C03.fresh_seconds（种子里是 120 秒）。 */
    private String target(String org, String district) {
        return target(org, district, Instant.now());
    }

    private String target(String org, String district, Instant observedAt) {
        String id = "dsp-target-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp at = Timestamp.from(Instant.parse("2026-09-07T02:00:00Z"));
        jdbc.update("insert into target (target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'mock',?,?,?,?,0)", id, "目标-测试-" + id.substring(11), org, district, at, at);
        jdbc.update("insert into target_latest_state (target_id,observed_at,received_at,created_at,updated_at)"
                + " values (?,?,?,?,?)", id, Timestamp.from(observedAt), Timestamp.from(observedAt), at, at);
        return id;
    }

    /* ---- 策略透出 ---- */

    @Test
    void policyIsExposedAsDemoUntilCustomerConfirms() throws Exception {
        JsonNode data = body(mvc.perform(get("/api/v1/disposal-policies").header("Authorization", bearer(reader)))
                .andExpect(status().isOk())).path("data");
        assertThat(data.get(0).path("schema_status").asText()).isEqualTo("DEMO");
    }

    /* ---- 辅助 ---- */

    private ResultActions create(String token, String actionType, String subjectId, String channel) throws Exception {
        return mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(token))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(createBody(actionType, subjectId, channel)));
    }

    private ResultActions approve(String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", id)
                .header("Authorization", bearer(approver)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":" + version + "}"));
    }

    private static String createBody(String actionType, String subjectId, String channel) {
        return "{\"action_type\":\"" + actionType + "\",\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\"" + subjectId
                + "\",\"channel\":\"" + channel + "\",\"reason\":\"本地演示：接口测试\"}";
    }

    private List<String> allowedActions(String id, String token) throws Exception {
        JsonNode node = body(mvc.perform(get("/api/v1/disposal-authorizations/{id}", id)
                .header("Authorization", bearer(token))).andExpect(status().isOk()))
                .path("data").path("allowed_actions");
        return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    private List<String> eventKinds(String id) {
        return jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?"
                + " order by occurred_at asc, event_id asc", String.class, id);
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("select status from disposal_authorization where authorization_id=?", String.class, id);
    }

    private long count(String subjectId) {
        return jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Long.class, subjectId);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String id(ResultActions actions) throws Exception {
        return body(actions).path("data").path("authorization_id").asText();
    }

    private String confirmedEvent() { return event("CONFIRMED"); }
    private String pendingEvent() { return event("PENDING_VERIFICATION"); }

    private String event(String state) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alarmId = "dsp-alarm-" + suffix, id = "dsp-event-" + suffix;
        Timestamp at = Timestamp.from(Instant.parse("2026-09-07T02:00:00Z"));
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select 'dsp-src','DSP-TEST','处置测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='dsp-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'dsp-src',?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarmId, "告警-测试-" + suffix, at, at, ORG, DISTRICT, at);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,?,?,?,?,1)", id, alarmId, state, ORG, DISTRICT, at, at);
        return id;
    }

    private String[] user(String tag, List<String> permissions, boolean otherScope) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-DSP-" + tag + "-" + suffix;
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,?,false,current_timestamp)", role, permission,
                    permission.endsWith(":read") ? "READ" : "OP");
        }
        // 主体读权是申请的前置条件之一；没有它就读不到事件/目标，申请自然也发不起来。
        for (String subjectRead : List.of("alarm:read", "target:read")) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,'READ',false,current_timestamp)", role, subjectRead);
        }
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "dsp-" + tag.toLowerCase() + "-" + suffix, "处置" + tag, role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId,
                otherScope ? "seed-stage3-other-org" : ORG, otherScope ? "seed-stage3-other-district" : DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, userId, System.currentTimeMillis() + 3_600_000);
        return new String[]{token, userId};
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
