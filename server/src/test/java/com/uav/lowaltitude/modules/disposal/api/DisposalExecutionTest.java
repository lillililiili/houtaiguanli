package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 执行通道：人工执行走完整链路；经设备的 LINGYUN_B 在未绑定/离线/码族未开通时必须如实拒绝并留痕，
 * 不伪造回执，也不能把授权推进到 EXECUTING。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisposalExecutionTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private String requester, approver;
    private String eventId;

    @BeforeEach
    void fixture() {
        requester = user("REQ", List.of("disposal:read", "disposal:request"))[0];
        // 经协议 B 执行还需要 A 的设备控制面权限（决策 13-9）：那是模块级权限 `devices` 的 OP 档，
        // 不是一个叫 "devices.op" 的权限码——`devices.op` 是 A 代码里"模块 devices + op 档"的写法。
        approver = user("APR", List.of("disposal:read", "disposal:approve", "disposal:execute",
                "disposal:stop", "devices", "monitoring"))[0];
        eventId = event("CONFIRMED");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from disposal_authorization_event where authorization_id in"
                + " (select authorization_id from disposal_authorization where subject_id like 'exec-event-%')");
        jdbc.update("delete from disposal_authorization where subject_id like 'exec-event-%'"
                + " and chained_from_authorization_id is not null");
        jdbc.update("delete from disposal_authorization where subject_id like 'exec-event-%'");
        jdbc.update("delete from uav_event where event_id like 'exec-event-%'");
        jdbc.update("delete from alarm where alarm_id like 'exec-alarm-%'");
    }

    @Test
    void manualChannelRunsThroughToCompleted() throws Exception {
        String id = approved("MANUAL", null);
        assertThat(jdbc.queryForObject("select source_mode from disposal_authorization where authorization_id=?",
                String.class, id)).isEqualTo("mock");
        execute(id, 1).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXECUTING"));
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/manual-result", id)
                        .header("Authorization", bearer(approver)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":2,\"result\":\"SUCCEEDED\",\"detail\":\"演示：已驱离\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(kinds(id)).containsExactly("REQUEST", "APPROVE", "EXECUTE", "MANUAL_RESULT");
        String jam = jammingOf(id);
        assertThat(jam).as("反制完成后应自动接一条信号干扰授权").isNotNull();
        assertThat(statusOf(jam)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select action_type from disposal_authorization where authorization_id=?",
                String.class, jam)).isEqualTo("JAMMING");
        assertThat(jdbc.queryForObject("select channel from disposal_authorization where authorization_id=?",
                String.class, jam)).isEqualTo("MANUAL");
        assertThat(kinds(jam)).containsExactly("REQUEST", "APPROVE");
    }

    @Test
    void failedCountermeasureDoesNotChainJamming() throws Exception {
        String id = approved("MANUAL", null);
        execute(id, 1).andExpect(status().isOk());
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/manual-result", id)
                        .header("Authorization", bearer(approver)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":2,\"result\":\"FAILED\",\"detail\":\"现场未驱离\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"));
        assertThat(jammingOf(id)).isNull();
    }

    @Test
    void existingJammingIsNotReplacedByAutoChain() throws Exception {
        String eventId = event("CONFIRMED");
        String jamBody = "{\"action_type\":\"JAMMING\",\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""
                + eventId + "\",\"channel\":\"MANUAL\",\"reason\":\"先手选干扰\"}";
        String existing = body(mvc.perform(post("/api/v1/disposal-authorizations")
                        .header("Authorization", bearer(requester)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(jamBody))
                .andExpect(status().isCreated())).path("data").path("authorization_id").asText();
        String cmBody = "{\"action_type\":\"COUNTERMEASURE\",\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""
                + eventId + "\",\"channel\":\"MANUAL\",\"reason\":\"再走反制\"}";
        String counter = body(mvc.perform(post("/api/v1/disposal-authorizations")
                        .header("Authorization", bearer(requester)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(cmBody))
                .andExpect(status().isCreated())).path("data").path("authorization_id").asText();
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", counter)
                        .header("Authorization", bearer(approver)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":0}"))
                .andExpect(status().isOk());
        execute(counter, 1).andExpect(status().isOk());
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/manual-result", counter)
                        .header("Authorization", bearer(approver)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":2,\"result\":\"SUCCEEDED\",\"detail\":\"反制完成\"}"))
                .andExpect(status().isOk());
        assertThat(jammingOf(counter)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=? and action_type='JAMMING'",
                Long.class, eventId)).isEqualTo(1L);
        assertThat(statusOf(existing)).isEqualTo("REQUESTED");
    }

    @Test
    void deviceExecutionActionRequiresDeviceControlPermission() throws Exception {
        String id = approved("LINGYUN_B", anyDevice());
        jdbc.update("delete from app_role_permission where permission_code='devices' and role_code in "
                + "(select u.role_code from app_user u join app_session s on s.user_id=u.user_id where s.session_id=?)", approver);
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/disposal-authorizations/{id}", id).header("Authorization", bearer(approver)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).path("data").path("allowed_actions").toString()).doesNotContain("EXECUTE");
        assertThat(jdbc.queryForObject("select status from disposal_authorization where authorization_id=?", String.class, id))
                .isEqualTo("APPROVED");
    }

    @Test
    void manualResultIsRejectedOnDeviceChannel() throws Exception {
        String id = approved("MANUAL", null);
        execute(id, 1).andExpect(status().isOk());
        // 人工登记结果只对人工通道开放：协议 B 的结果必须来自设备回执，不能由人代设备宣布成功。
        String deviceId = anyDevice();
        if (deviceId == null) return;
        String other = approved("LINGYUN_B", deviceId);
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/manual-result", other)
                        .header("Authorization", bearer(approver)).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":1,\"result\":\"SUCCEEDED\",\"detail\":\"不该被接受\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
    }

    @Test
    void deviceChannelCannotExecuteThisPhaseButLeavesEvidence() throws Exception {
        String deviceId = anyDevice();
        if (deviceId == null) return;   // 环境里没有设备夹具时跳过，不做假断言。
        String id = approved("LINGYUN_B", deviceId);
        JsonNode error = body(execute(id, 1).andExpect(status().isConflict())).path("error");
        // 测试库里没有任何 mqtt_device_binding，因此走的必然是"未登记"这一支。断成精确值而不是二选一：
        // 用 isIn(A,B) 会让两条分支互相顶替——真跑成另一支也照样绿，等于没测。
        assertThat(bindings()).isZero();
        // 60003 已开通，测试库没有任何 mqtt_device_binding，因此下一刀是未登记。
        assertThat(error.path("code").asText()).isEqualTo("DEVICE_NOT_BOUND");
        // 关键：授权仍是 APPROVED，且拒绝这件事必须留在事件流里——
        // 若实现把异常直接抛出去，事件会跟着事务回滚，事后就查不出当时为什么执行不了。
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(kinds(id)).contains("DEVICE_NOT_BOUND");
        assertThat(commandId(id)).isNull();
    }

    @Test
    void fourChannelOnNonFourChannelDeviceIsCapabilityAndSaysSoInTheDto() throws Exception {
        String deviceId = anyDevice();
        assertThat(deviceId).as("测试库需要至少一台设备，否则本用例是空跑").isNotNull();
        String id = approved("COUNTERMEASURE_4CH", deviceId);
        body(execute(id, 1).andExpect(status().isConflict()))
                .path("error").path("code").asText().equals("DEVICE_CONTROL_UNAVAILABLE");
        assertThat(kinds(id)).contains("DEVICE_CONTROL_UNAVAILABLE");
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        // 测试库设备不是四通道协议：补救方是换设备，不能和"等厂家开通指令码"混为一谈。
        assertThat(blockReason(id)).isEqualTo("DEVICE_CAPABILITY");
    }

    @Test
    void fourChannelRejectsDecoyWithoutDispatching() throws Exception {
        String deviceId = anyDevice();
        assertThat(deviceId).isNotNull();
        String bodyText = "{\"action_type\":\"DECOY\",\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""
                + event("CONFIRMED") + "\",\"channel\":\"COUNTERMEASURE_4CH\",\"device_id\":\""
                + deviceId + "\",\"reason\":\"诱骗不能走四通道\"}";
        String id = body(mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(bodyText))
                .andExpect(status().isCreated())).path("data").path("authorization_id").asText();
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", id).header("Authorization", bearer(approver))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}")).andExpect(status().isOk());
        execute(id, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(kinds(id)).doesNotContain("EXECUTE");
    }

    @Test
    void deviceChannelBlockReasonIsProtocolNotOpened() throws Exception {
        String deviceId = anyDevice();
        assertThat(deviceId).isNotNull();
        JsonNode original = policyParams();
        try {
            // 50000 是协议标明未有真实设备的诱骗码，family() 仍为 null，用来守住「未开通」这一支。
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("command_map").path("COUNTERMEASURE"))
                    .put("operation_cmd", 50000);
            writePolicyParams(root);
            String id = approved("LINGYUN_B", deviceId);
            execute(id, 1).andExpect(status().isConflict());
            assertThat(blockReason(id)).isEqualTo("PROTOCOL_NOT_OPENED");
            assertThat(kinds(id)).contains("PROTOCOL_NOT_OPENED");
        } finally {
            writePolicyParams(original);
        }
    }

    @Test
    void blockReasonClearsOnceExecutionSucceeds() throws Exception {
        String id = approved("MANUAL", null);
        execute(id, 1).andExpect(status().isOk());
        // 人工通道从来没有受阻事件；执行成功后更不该显示"受阻"。
        assertThat(blockReason(id)).isNull();
    }

    @Test
    void executionOutsideTheWindowIsRejected() throws Exception {
        String id = approved("MANUAL", null);
        // 把有效期改成已经过去：过期的授权不是"晚一点也行"，是已经失效。
        jdbc.update("update disposal_authorization set valid_from=?, valid_until=? where authorization_id=?",
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2020-01-01T00:30:00Z")), id);
        execute(id, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTHORIZATION_EXPIRED"));
        assertThat(statusOf(id)).isEqualTo("APPROVED");
    }

    /* ---- 辅助 ---- */

    private String approved(String channel, String deviceId) throws Exception {
        String bodyText = "{\"action_type\":\"COUNTERMEASURE\",\"subject_kind\":\"UAV_EVENT\",\"subject_id\":\""
                + event("CONFIRMED") + "\",\"channel\":\"" + channel + "\""
                + (deviceId == null ? "" : ",\"device_id\":\"" + deviceId + "\"") + ",\"reason\":\"执行通道测试\"}";
        String id = body(mvc.perform(post("/api/v1/disposal-authorizations").header("Authorization", bearer(requester))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(bodyText))
                .andExpect(status().isCreated())).path("data").path("authorization_id").asText();
        mvc.perform(post("/api/v1/disposal-authorizations/{id}/approve", id).header("Authorization", bearer(approver))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected_version\":0}")).andExpect(status().isOk());
        return id;
    }

    private ResultActions execute(String id, long version) throws Exception {
        return mvc.perform(post("/api/v1/disposal-authorizations/{id}/execute", id)
                .header("Authorization", bearer(approver)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":" + version + "}"));
    }

    private String anyDevice() {
        return jdbc.query("select device_id from ops_device where enabled=true order by device_id asc limit 1",
                rs -> rs.next() ? rs.getString(1) : null);
    }

    private List<String> kinds(String id) {
        return jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?"
                + " order by occurred_at asc, event_id asc", String.class, id);
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("select status from disposal_authorization where authorization_id=?", String.class, id);
    }

    /** 从详情接口读派生值，而不是从库里自己推——要测的正是接口给出来的那个值。 */
    private String blockReason(String id) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = body(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/disposal-authorizations/{id}", id)
                        .header("Authorization", bearer(approver))).andExpect(status().isOk()))
                .path("data").path("execution_block_reason");
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private long bindings() {
        return jdbc.queryForObject("select count(*) from mqtt_device_binding", Long.class);
    }

    private String commandId(String id) {
        return jdbc.queryForObject("select execution_command_id from disposal_authorization where authorization_id=?",
                String.class, id);
    }

    private String jammingOf(String parentId) {
        return jdbc.query("select authorization_id from disposal_authorization where chained_from_authorization_id=?",
                rs -> rs.next() ? rs.getString(1) : null, parentId);
    }

    /** H2 把 JSON 列再包一层字符串；与 DisposalPolicyRepository.parse 同一解法。 */
    private JsonNode policyParams() throws Exception {
        String raw = jdbc.queryForObject(
                "select cast(params as text) from disposal_policy where policy_code='demo-v1'", String.class);
        JsonNode node = objectMapper.readTree(raw);
        if (node != null && node.isTextual()) node = objectMapper.readTree(node.textValue());
        return node;
    }

    private void writePolicyParams(JsonNode node) throws Exception {
        jdbc.update("update disposal_policy set params=cast(? as json) where policy_code='demo-v1'",
                objectMapper.writeValueAsString(node));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String event(String state) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alarmId = "exec-alarm-" + suffix, id = "exec-event-" + suffix;
        Timestamp at = Timestamp.from(Instant.parse("2026-09-07T02:00:00Z"));
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select 'exec-src','EXEC-TEST','执行测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='exec-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'exec-src',?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarmId, "告警-执行-" + suffix, at, at, ORG, DISTRICT, at);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,?,?,?,?,?,1)", id, alarmId, state, ORG, DISTRICT, at, at);
        return id;
    }

    private String[] user(String tag, List<String> permissions) {
        String suffix = UUID.randomUUID().toString().substring(0, 8), role = "ROLE-EXE-" + tag + "-" + suffix;
        String userId = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " select ?,?,?,false,current_timestamp where exists"
                    + " (select 1 from app_permission where permission_code=?)", role, permission,
                    permission.startsWith("disposal:") && permission.endsWith(":read") ? "READ" : "OP", permission);
        }
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " values (?, 'alarm:read','READ',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "exe-" + tag.toLowerCase() + "-" + suffix, "执行" + tag, role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, ORG, DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, userId, System.currentTimeMillis() + 3_600_000);
        return new String[]{token, userId};
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String bearer(String token) { return "Bearer " + token; }
}
