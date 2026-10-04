package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.disposal.application.DisposalJammingChain;
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

/** Event emergency stop is immediate, durable and distinct from confirmed physical shutdown. */
@SpringBootTest(properties = {"app.outbox.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:emergency_stop;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmergencyStopApiTest {
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DisposalJammingChain chain;
    @Autowired com.uav.lowaltitude.modules.disposal.application.DisposalReceiptSync receiptSync;
    @Autowired com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository disposalRepository;
    @Autowired com.uav.lowaltitude.modules.device.application.Countermeasure4ChControlService fourChannelControl;
    String requester, operator, eventId;

    @BeforeEach
    void fixture() {
        requester = user("disposal:read", "disposal:request");
        operator = user("disposal:read", "disposal:approve", "disposal:execute", "disposal:stop", "devices", "monitoring");
        eventId = event();
    }

    @Test
    void eventWithoutActiveCountermeasureHasReadableOverview() throws Exception {
        JsonNode overview = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator)).andExpect(status().isOk()));
        assertThat(overview.path("event_id").asText()).isEqualTo(eventId);
        assertThat(overview.path("applicable").asBoolean()).isFalse();
        assertThat(overview.path("requires_device_stop").isBoolean()).isTrue();
        assertThat(overview.path("requires_device_stop").asBoolean()).isFalse();
        assertThat(overview.path("allowed_actions").toString()).doesNotContain("EMERGENCY_STOP");
        stop(operator, key()).andExpect(status().isConflict());
    }

    @Test
    void immediateStopNeedsNoReasonAndSameKeyDoesNotStopTwice() throws Exception {
        String counter = authorization("COUNTERMEASURE", true);
        String jam = authorization("JAMMING", true);
        String idempotencyKey = key();
        JsonNode first = data(stop(operator, idempotencyKey).andExpect(status().isOk()));
        JsonNode latest = first.path("latest_stop");
        assertThat(latest.path("stop_id").asText()).isNotBlank();
        assertThat(latest.path("reason_pending").asBoolean()).isTrue();
        assertThat(latest.path("requested_by_name").asText()).isEqualTo("急停测试操作员");
        assertThat(latest.path("devices").size()).isEqualTo(2);
        assertThat(latest.path("devices").findValuesAsText("stop_status")).containsOnly("UNSUPPORTED");
        assertThat(statusOf(counter)).isEqualTo("STOPPED");
        assertThat(statusOf(jam)).isEqualTo("STOPPED");
        JsonNode replay = data(stop(operator, idempotencyKey).andExpect(status().isOk()));
        assertThat(replay.path("latest_stop").path("stop_id")).isEqualTo(latest.path("stop_id"));
        assertThat(replay.path("latest_stop").path("events").size()).isEqualTo(latest.path("events").size());
        assertThat(replay.path("latest_stop").path("devices").size()).isEqualTo(2);
    }

    @Test
    void reasonAndManualConfirmationAreSeparateAuditedFollowUps() throws Exception {
        authorization("COUNTERMEASURE", true);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        String stopPath = path() + "/" + latest.path("stop_id").asText();
        request(stopPath + "/notes", operator, key(), Map.of("note", "   ")).andExpect(status().isBadRequest());
        String noteKey = key();
        JsonNode noted = data(request(stopPath + "/notes", operator, noteKey, Map.of("note", "现场人员进入反制范围"))
                .andExpect(status().isOk())).path("latest_stop");
        assertThat(noted.path("reason_pending").asBoolean()).isFalse();
        assertThat(noted.path("note").asText()).isEqualTo("现场人员进入反制范围");
        JsonNode replay = data(request(stopPath + "/notes", operator, noteKey, Map.of("note", "现场人员进入反制范围"))
                .andExpect(status().isOk())).path("latest_stop");
        assertThat(replay.path("events").size()).isEqualTo(noted.path("events").size());
        String device = latest.path("devices").get(0).path("device_id").asText();
        request(stopPath + "/devices/" + device + "/manual-confirm", operator, key(), Map.of("note", ""))
                .andExpect(status().isBadRequest());
        JsonNode confirmed = data(request(stopPath + "/devices/" + device + "/manual-confirm", operator, key(),
                Map.of("note", "现场核验设备已经断电" )).andExpect(status().isOk())).path("latest_stop");
        assertThat(confirmed.path("devices").get(0).path("stop_status").asText()).isEqualTo("MANUALLY_CONFIRMED");
        assertThat(confirmed.path("events").size()).isGreaterThan(noted.path("events").size());
    }

    @Test
    void completedParentIsPreservedAndItsApprovedJammingChildIsSuppressed() throws Exception {
        String parent = authorization("COUNTERMEASURE", true);
        JsonNode rejected = json.readTree(request("/api/v1/disposal-authorizations/" + parent + "/manual-result", operator, key(),
                Map.of("expected_version", 2, "result", "SUCCEEDED", "detail", "反制完成"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
        assertThat(rejected.path("error").path("code").asText()).isEqualTo("MANUAL_CHANNEL_RETIRED");
        assertThat(statusOf(parent)).isEqualTo("EXECUTING");
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where chained_from_authorization_id=?", Long.class, parent)).isZero();
        JsonNode overview = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator)).andExpect(status().isOk()));
        assertThat(overview.path("requires_device_stop").isBoolean()).isTrue();
        assertThat(overview.path("requires_device_stop").asBoolean()).isTrue();
        stop(operator, key()).andExpect(status().isOk());
        assertThat(statusOf(parent)).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where chained_from_authorization_id=?", Long.class, parent)).isZero();
    }

    @Test
    void stopRejectsMissingPermissionAndOutOfScopeWithoutChangingAuthorization() throws Exception {
        String counter = authorization("COUNTERMEASURE", true);
        String reader = user("disposal:read");
        stop(reader, key()).andExpect(status().isForbidden());
        String outside = user("disposal:read", "disposal:stop");
        jdbc.update("delete from app_user_data_scope where user_id=(select user_id from app_session where session_id=?)", outside);
        mvc.perform(get(path()).header("Authorization", "Bearer " + outside)).andExpect(status().isForbidden());
        stop(outside, key()).andExpect(status().isForbidden());
        assertThat(statusOf(counter)).isEqualTo("EXECUTING");
    }

    @Test
    void dedicatedStopPermissionCanStopWithoutDisposalReadOrDeviceOperationPermission() throws Exception {
        String authorization = authorization("COUNTERMEASURE", true);
        String stopper = user("disposal:stop");
        JsonNode latest = data(stop(stopper, key()).andExpect(status().isOk())).path("latest_stop");
        assertThat(latest.path("stop_id").asText()).isNotBlank();
        assertThat(statusOf(authorization)).isEqualTo("STOPPED");
    }

    @Test
    void followUpsCheckScopeAndStopOwnership() throws Exception {
        authorization("COUNTERMEASURE", true);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        String stopPath = path() + "/" + latest.path("stop_id").asText();
        String reader = user("disposal:read");
        request(stopPath + "/notes", reader, key(), Map.of("note", "未授权补记")).andExpect(status().isForbidden());
        String device = latest.path("devices").get(0).path("device_id").asText();
        request(stopPath + "/devices/" + device + "/manual-confirm", reader, key(), Map.of("note", "未授权确认"))
                .andExpect(status().isForbidden());
        eventId = event();
        request(path() + "/" + latest.path("stop_id").asText() + "/notes", operator, key(), Map.of("note", "跨事件补记"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fourChannelAllOffAcknowledgmentNeverClaimsPhysicalShutdown() throws Exception {
        String device = fourChannel();
        String authorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, authorization);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        JsonNode stoppedDevice = latest.path("devices").get(0);
        assertThat(stoppedDevice.path("device_id").asText()).isEqualTo(device);
        assertThat(stoppedDevice.path("stop_status").asText()).isEqualTo("QUEUED");
        String command = jdbc.queryForObject("select command_id from device_command where device_id=?", String.class, device);
        assertThat(jdbc.queryForObject("select mask from countermeasure_4ch_command where command_id=?", Integer.class, command)).isZero();
        // Trusted device receipt fixture: the REST caller has no endpoint to fabricate this success.
        jdbc.update("update device_command set status='SUCCEEDED' where command_id=?", command);
        JsonNode acknowledged = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())).path("latest_stop").path("devices").get(0);
        assertThat(acknowledged.path("stop_status").asText()).isEqualTo("CONTROLLER_ALL_OFF_ACK");
        assertThat(acknowledged.path("stop_status").asText()).isNotEqualTo("MANUALLY_CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Long.class, device)).isEqualTo(1);
    }

    @Test
    void twoFourChannelDevicesReceiveDistinctAllOffCommands() throws Exception {
        String firstDevice = fourChannel(), secondDevice = fourChannel();
        String counter = authorization("COUNTERMEASURE", true), jamming = authorization("JAMMING", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", firstDevice, counter);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", secondDevice, jamming);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        assertThat(latest.path("devices").size()).isEqualTo(2);
        assertThat(latest.path("devices").findValuesAsText("stop_status")).containsOnly("QUEUED");
        assertThat(latest.path("devices").findValuesAsText("command_id")).hasSize(2).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id in (?,?)", Long.class, firstDevice, secondDevice)).isEqualTo(2);
    }

    @Test
    void sharedDeviceWithAnotherActiveEventBlocksAllOffWithoutChangingEitherEvent() throws Exception {
        String device = fourChannel(), firstEvent = eventId;
        String firstAuthorization = authorization("COUNTERMEASURE", true);
        eventId = event();
        String secondEvent = eventId, secondAuthorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id in (?,?)",
                device, firstAuthorization, secondAuthorization);
        eventId = firstEvent;
        String response = stop(operator, key()).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("SHARED_DEVICE_SCOPE_BLOCKED").doesNotContain(secondEvent);
        assertThat(statusOf(firstAuthorization)).isEqualTo("EXECUTING");
        assertThat(statusOf(secondAuthorization)).isEqualTo("EXECUTING");
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Long.class, device)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from disposal_emergency_stop where event_id in (?,?)", Long.class, firstEvent, secondEvent)).isZero();
    }

    @Test
    void oldCompletedDeviceOutsideCurrentChainIsNeitherStoppedNorUsedToBlockCurrentStop() throws Exception {
        String historicalDevice = fourChannel(), currentDevice = fourChannel(), currentEvent = eventId;
        String historical = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set status='COMPLETED',device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?",
                historicalDevice, historical);
        String current = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", currentDevice, current);
        eventId = event();
        String otherEventAuthorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", historicalDevice, otherEventAuthorization);
        eventId = currentEvent;
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        assertThat(latest.path("devices").findValuesAsText("device_id")).containsExactly(currentDevice);
        assertThat(statusOf(historical)).isEqualTo("COMPLETED");
        assertThat(statusOf(otherEventAuthorization)).isEqualTo("EXECUTING");
        assertThat(jdbc.queryForObject("select count(*) from disposal_emergency_stop_authorization where authorization_id=?", Long.class, historical)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Long.class, historicalDevice)).isZero();
    }

    @Test
    void emergencyStopCancelsSentStartCommandSoLateDeliveryCannotRestartDevice() throws Exception {
        String device = fourChannel(), authorization = authorization("COUNTERMEASURE", true);
        String command = data(request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", operator, key(),
                Map.of("authorization_id", authorization, "action", "SET_MASK", "mask", 15, "reason", "原始反制下发"))
                .andExpect(status().isAccepted())).path("command_id").asText();
        jdbc.update("update device_command set status='SENT' where command_id=?", command);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH',execution_command_id=? where authorization_id=?",
                device, command, authorization);
        stop(operator, key()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("CANCELLED");
        fourChannelControl.dispatch(command);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("CANCELLED");
    }

    @Test
    void reviewedAuthorizationExpiringInQueueCancelsStartButAllowsAllOff() throws Exception {
        String device = fourChannel(), authorization = authorization("COUNTERMEASURE", true);
        String command = directFourChannel(device, authorization, 15);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("QUEUED");
        Instant now = Instant.now();
        jdbc.update("update disposal_authorization set valid_from=?,valid_until=? where authorization_id=?",
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(1)), authorization);
        fourChannelControl.dispatch(command);
        fourChannelControl.dispatch(command);
        var stopped = jdbc.queryForMap("select status,result_code,issued_at from device_command where command_id=?", command);
        assertThat(stopped.get("status")).isEqualTo("CANCELLED");
        assertThat(stopped.get("result_code")).isEqualTo("AUTHORIZATION_STOPPED");
        assertThat(stopped.get("issued_at")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from command_receipt where command_id=?", Integer.class, command)).isZero();
        String allOff = directFourChannel(device, authorization, 0);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, allOff)).isEqualTo("QUEUED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DIRECT", "DEVICES", "USER", "ROLE", "SCOPE", "EXPIRED", "OBSERVATION"})
    void directQueuedStartRechecksChangedEligibilityWithoutSending(String changed) throws Exception {
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        String device = fourChannel();
        JsonNode created = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(),
                Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "隔离队列资格变化测试"))
                .andExpect(status().isCreated()));
        String authorization = created.path("authorization_id").asText();
        String command = jdbc.queryForObject("select execution_command_id from disposal_authorization where authorization_id=?", String.class, authorization);
        assertThat(command).isNotBlank();
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("QUEUED");
        String userId = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, actor);
        String role = jdbc.queryForObject("select role_code from app_user where user_id=?", String.class, userId);
        switch (changed) {
            case "DIRECT", "DEVICES" -> jdbc.update("update app_role_permission set permission_level='NONE' where role_code=? and permission_code=?",
                    role, "DIRECT".equals(changed) ? "disposal:direct" : "devices");
            case "USER" -> jdbc.update("update app_user set status='DISABLED' where user_id=?", userId);
            case "ROLE" -> jdbc.update("update app_role set enabled=false where role_code=?", role);
            case "SCOPE" -> jdbc.update("delete from app_user_data_scope where user_id=?", userId);
            case "EXPIRED" -> jdbc.update("update disposal_authorization set valid_from=?,valid_until=? where authorization_id=?",
                    Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().minusSeconds(1)), authorization);
            case "OBSERVATION" -> jdbc.update("update target_latest_state set observed_at=? where target_id=(select a.target_id from alarm a join uav_event e on e.alarm_id=a.alarm_id where e.event_id=?)",
                    Timestamp.from(Instant.now().minusSeconds(disposalRepository.freshSeconds().longValue() + 1)), eventId);
            default -> throw new IllegalArgumentException(changed);
        }
        fourChannelControl.dispatch(command);
        fourChannelControl.dispatch(command);
        var cancelled = jdbc.queryForMap("select status,result_code,issued_at from device_command where command_id=?", command);
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.get("result_code")).isEqualTo("AUTHORIZATION_STOPPED");
        assertThat(cancelled.get("issued_at")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from command_receipt where command_id=?", Integer.class, command)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from countermeasure_4ch_command where authorization_id=?", Integer.class, authorization)).isEqualTo(1);
    }

    @Test
    void knownDeviceFaultBlocksNewStartButRetainsAllOffPath() throws Exception {
        String device = fourChannel(), authorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update ops_device_state set health_code='BAD',has_alarm=true where device_id=?", device);
        request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", operator, key(),
                Map.of("authorization_id", authorization, "action", "SET_MASK", "mask", 15, "reason", "故障设备不得启动"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, device)).isZero();
        assertThat(directFourChannel(device, authorization, 0)).isNotBlank();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DISABLED", "OFFLINE", "FAULT"})
    void queuedStartRechecksDeviceAvailability(String changed) throws Exception {
        String device = fourChannel(), authorization = authorization("COUNTERMEASURE", true);
        String command = directFourChannel(device, authorization, 15);
        if ("DISABLED".equals(changed)) jdbc.update("update ops_device set enabled=false where device_id=?", device);
        else if ("OFFLINE".equals(changed)) jdbc.update("update ops_device_state set connectivity='OFFLINE' where device_id=?", device);
        else jdbc.update("update ops_device_state set health_code='BAD',has_alarm=true where device_id=?", device);
        fourChannelControl.dispatch(command);
        fourChannelControl.dispatch(command);
        var cancelled = jdbc.queryForMap("select status,result_code,issued_at from device_command where command_id=?", command);
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.get("result_code")).isEqualTo("DEVICE_NOT_OPERABLE");
        assertThat(cancelled.get("issued_at")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from command_receipt where command_id=?", Integer.class, command)).isZero();
    }

    @Test void directFaultBlockCommitsAuthorizationWithoutExecutionOrApproval() throws Exception {
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        String device = fourChannel();
        jdbc.update("update ops_device_state set health_code='BAD',has_alarm=true where device_id=?", device);
        JsonNode result = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(),
                Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "故障设备处置受阻测试"))
                .andExpect(status().isCreated()));
        String authorization = result.path("authorization_id").asText();
        assertThat(result.path("status").asText()).isEqualTo("APPROVED");
        assertThat(result.path("execution_block_reason").asText()).isEqualTo("DEVICE_FAULT");
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, device)).isZero();
        assertThat(jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?", String.class, authorization))
                .contains("DIRECT_AUTHORIZE","DEVICE_FAULT").doesNotContain("EXECUTE","APPROVE");
        assertThat(jdbc.queryForObject("select approved_by from disposal_authorization where authorization_id=?", String.class, authorization)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"QUEUED", "SENT", "ACCEPTED"})
    void differentEventsCannotQueueStartsOnTheSameBusyDevice(String pendingState) throws Exception {
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        String device = fourChannel();
        var first = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(),
                Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "设备占用首个事件"))
                .andExpect(status().isCreated()));
        assertThat(first.path("status").asText()).isEqualTo("EXECUTING");
        jdbc.update("update device_command set status=? where device_id=?", pendingState, device);
        String secondEvent = event();
        var second = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(),
                Map.of("subject_kind", "UAV_EVENT", "subject_id", secondEvent, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "设备占用第二事件"))
                .andExpect(status().isCreated()));
        assertThat(second.path("status").asText()).isEqualTo("APPROVED");
        assertThat(second.path("execution_block_reason").asText()).isEqualTo("DEVICE_BUSY");
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, device)).isEqualTo(1);
        String allOff = directFourChannel(device, first.path("authorization_id").asText(), 0);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, allOff)).isEqualTo("QUEUED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OFFLINE", "DISABLED"})
    void unavailableDevicePreservesBlockedAuthorizationWithoutCommand(String state) throws Exception {
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        String device = fourChannel();
        if ("OFFLINE".equals(state)) jdbc.update("update ops_device_state set connectivity='OFFLINE' where device_id=?", device);
        else jdbc.update("update ops_device set enabled=false where device_id=?", device);
        var result = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(),
                Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "离线或停用设备阻断测试"))
                .andExpect(status().isCreated()));
        assertThat(result.path("status").asText()).isEqualTo("APPROVED");
        assertThat(result.path("execution_block_reason").asText()).isEqualTo("DEVICE_OFFLINE");
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, device)).isZero();
        assertThat(jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?",
                String.class, result.path("authorization_id").asText())).contains("DIRECT_AUTHORIZE", "DEVICE_OFFLINE")
                .doesNotContain("EXECUTE", "APPROVE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "REQUEST,STALE", "REQUEST,LEGAL", "REQUEST,UNKNOWN", "REQUEST,INSUFFICIENT", "REQUEST,OTHER_EVENT",
            "DIRECT,STALE", "DIRECT,LEGAL", "DIRECT,UNKNOWN", "DIRECT,INSUFFICIENT", "DIRECT,OTHER_EVENT",
            "EXECUTE,STALE", "EXECUTE,LEGAL", "EXECUTE,UNKNOWN", "EXECUTE,INSUFFICIENT", "EXECUTE,OTHER_EVENT"})
    void everyCounterEntryRejectsInvalidCurrentEvidence(String entry, String condition) throws Exception {
        String actor = user("disposal:direct", "disposal:request", "disposal:read", "devices", "target:read");
        String device = fourChannel(), authorization = null;
        var body = Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "当前依据三入口矩阵");
        if ("EXECUTE".equals(entry)) {
            authorization = data(request("/api/v1/disposal-authorizations", actor, key(), body)
                    .andExpect(status().isCreated())).path("authorization_id").asText();
            request("/api/v1/disposal-authorizations/" + authorization + "/approve", operator, key(),
                    Map.of("expected_version", 0)).andExpect(status().isOk());
        }
        String alarm = jdbc.queryForObject("select alarm_id from uav_event where event_id=?", String.class, eventId);
        String target = jdbc.queryForObject("select target_id from alarm where alarm_id=?", String.class, alarm);
        switch (condition) {
            case "STALE" -> jdbc.update("update target_latest_state set observed_at=? where target_id=?",
                    Timestamp.from(Instant.now().minusSeconds(disposalRepository.freshSeconds() + 1L)), target);
            case "LEGAL", "UNKNOWN", "INSUFFICIENT", "OTHER_EVENT" -> {
                String previous = jdbc.queryForObject("select evaluation_id from rule_evaluation where target_id=? order by evaluated_at desc,evaluation_id desc fetch first 1 rows only", String.class, target);
                var original = jdbc.queryForMap("select * from rule_evaluation where evaluation_id=?", previous);
                String next = UUID.randomUUID().toString();
                String linkedAlarm = "OTHER_EVENT".equals(condition)
                        ? jdbc.queryForObject("select alarm_id from uav_event where event_id=?", String.class, event()) : alarm;
                String legal = "LEGAL".equals(condition) ? "LEGAL" : "UNKNOWN".equals(condition) ? "UNDETERMINED" : "ILLEGAL";
                String assurance = "INSUFFICIENT".equals(condition) ? "INSUFFICIENT" : "SUFFICIENT";
                long previousMillis = jdbc.queryForObject("select evaluated_at from rule_evaluation where evaluation_id=?", Timestamp.class, previous).getTime();
                Timestamp nextEvaluationTime = new Timestamp(Math.max(System.currentTimeMillis(), previousMillis + 1));
                // 研判历史只追加；新事实覆盖当前资格，不能修改旧研判规避 PostgreSQL 防篡改约束。
                jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons,supersedes_evaluation_id) "
                        + "select ?,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,?,freshness_code,plan_match_code,?,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,?,owner_org_id,district_id,source_mode,created_at,?,decision_algorithm_version,decision_assurance_reasons,evaluation_id from rule_evaluation where evaluation_id=?",
                        next, nextEvaluationTime, legal, linkedAlarm, assurance, previous);
                assertThat(jdbc.queryForObject("select evaluation_id from rule_evaluation where target_id=? order by evaluated_at desc,evaluation_id desc fetch first 1 rows only", String.class, target)).isEqualTo(next);
                assertThat(jdbc.queryForMap("select * from rule_evaluation where evaluation_id=?", previous)).usingRecursiveComparison().isEqualTo(original);
            }
            default -> throw new IllegalArgumentException(condition);
        }
        ResultActions rejected;
        if ("EXECUTE".equals(entry)) rejected = request("/api/v1/disposal-authorizations/" + authorization + "/execute",
                operator, key(), Map.of("expected_version", 1));
        else rejected = request("/api/v1/disposal-authorizations" + ("DIRECT".equals(entry) ? "/direct-execute" : ""),
                actor, key(), body);
        var response = json.readTree(rejected.andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
        assertThat(response.path("error").path("code").asText()).isEqualTo("ADVISORY_COUNTER_BLOCKED");
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, device)).isZero();
        if (authorization == null) {
            assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Integer.class, eventId)).isZero();
        } else {
            assertThat(statusOf(authorization)).isEqualTo("APPROVED");
            assertThat(jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?", String.class, authorization))
                    .contains("REQUEST", "APPROVE").doesNotContain("EXECUTE", "MANUAL_RESULT");
        }
    }

    @Test void directHandoffKeepsInitiatorAndFrozenEvidenceAfterLaterUpload() throws Exception {
        String actor = user("disposal:direct", "disposal:read", "disposal:stop", "devices",
                "handoff:create", "handoff:read", "evidence:read", "evidence:ingest", "evidence:link");
        String actorId = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, actor);
        jdbc.update("update app_user set name='直接发起人甲' where user_id=?", actorId);
        String device = fourChannel();
        var direct = data(request("/api/v1/disposal-authorizations/direct-execute", actor, key(), Map.of(
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "隔离移送快照验证"))
                .andExpect(status().isCreated()));
        String authorization = direct.path("authorization_id").asText();
        var stopped = data(request("/api/v1/disposal-authorizations/" + authorization + "/stop", actor, key(),
                Map.of("expected_version", direct.path("version").asLong(), "note", "隔离测试停止授权，不代表设备回执成功"))
                .andExpect(status().isOk()));
        assertThat(stopped.path("status").asText()).isEqualTo("STOPPED");
        assertThat(jdbc.queryForObject("select requested_by from disposal_authorization where authorization_id=?", String.class, authorization)).isEqualTo(actorId);
        assertThat(jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?", String.class, authorization))
                .contains("DIRECT_AUTHORIZE", "EXECUTE", "STOP").doesNotContain("APPROVE", "COMPLETE");
        String firstEvidence = uploadHandoffLog(actor, "before.txt", stopped.toString());
        String recipient = key();
        jdbc.update("insert into handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values (?,?,'UAV_PUNISHMENT',true,current_timestamp,current_timestamp)", recipient, "隔离测试接收方");
        long version = jdbc.queryForObject("select version from uav_event where event_id=?", Long.class, eventId);
        String handoff = data(request("/api/v1/handoffs", actor, key(), Map.of("source_kind", "UAV_EVENT", "source_id", eventId,
                "handoff_type", "UAV_PUNISHMENT", "recipient_id", recipient, "expected_version", version))
                .andExpect(status().isCreated())).path("handoff_id").asText();
        String rawBefore = jdbc.queryForObject("select cast(snapshot as varchar) from handoff_material_snapshot where handoff_id=?", String.class, handoff);
        var before = data(mvc.perform(get("/api/v1/handoffs/" + handoff).header("Authorization", "Bearer " + actor)).andExpect(status().isOk())).path("material");
        var disposal = before.path("disposals").get(0);
        assertThat(disposal.path("authorization_id").asText()).isEqualTo(authorization);
        assertThat(disposal.path("authorization_mode").asText()).isEqualTo("DIRECT");
        assertThat(disposal.path("requested_by_name").asText()).isEqualTo("直接发起人甲");
        assertThat(disposal.hasNonNull("approved_by_name")).isFalse();
        assertThat(disposal.path("status").asText()).isEqualTo("STOPPED");
        assertThat(before.path("evidence").findValuesAsText("evidence_id")).containsExactly(firstEvidence);
        String laterEvidence = uploadHandoffLog(actor, "after.txt", "隔离测试后补日志：" + authorization);
        jdbc.update("update app_user set name='直接发起人甲（后改名）' where user_id=?", actorId);
        jdbc.update("update handoff_recipient set display_name='接收方（后改配置）' where recipient_id=?", recipient);
        var after = data(mvc.perform(get("/api/v1/handoffs/" + handoff).header("Authorization", "Bearer " + actor)).andExpect(status().isOk())).path("material");
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("select cast(snapshot as varchar) from handoff_material_snapshot where handoff_id=?", String.class, handoff)).isEqualTo(rawBefore);
        assertThat(jdbc.queryForList("select evidence_id from evidence_link where subject_kind='EVENT' and subject_id=?", String.class, eventId))
                .containsExactlyInAnyOrder(firstEvidence, laterEvidence);
        assertThat(after.path("evidence").findValuesAsText("evidence_id")).doesNotContain(laterEvidence);
    }

    private String uploadHandoffLog(String actor, String filename, String content) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", filename, "text/plain", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String id = data(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/evidence-files")
                .file(file).param("kind_code", "COMMAND_LOG").param("owner_org_id", ORG).param("district_id", DISTRICT)
                .header("Authorization", "Bearer " + actor).header("Idempotency-Key", key())).andExpect(status().isCreated()))
                .path("evidence_id").asText();
        request("/api/v1/evidence-files/" + id + "/links", actor, key(), Map.of("subject_kind", "EVENT", "subject_id", eventId))
                .andExpect(status().isCreated());
        return id;
    }

    @Test void standaloneDeviceCommandDoesNotCreateOrCrashDisposalSettlement() throws Exception {
        String device = fourChannel();
        String command = directFourChannel(device, "external-protocol-authorization", 0);
        jdbc.update("update device_command set status='SUCCEEDED',result_code='TEST_ALL_OFF' where command_id=?", command);
        long before = jdbc.queryForObject("select count(*) from disposal_authorization", Long.class);
        receiptSync.syncByCommand(command);
        receiptSync.syncByCommand(command);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("SUCCEEDED");
    }

    @Test
    void emergencyStopCancelsEveryLinkedStartCommandIncludingUnreferencedDirectCommands() throws Exception {
        String device = fourChannel(), authorization = authorization("COUNTERMEASURE", true);
        String first = directFourChannel(device, authorization, 15);
        String extra = directFourChannel(device, authorization, 15);
        jdbc.update("update device_command set status='SENT' where command_id=?", first);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH',execution_command_id=? where authorization_id=?",
                device, first, authorization);
        stop(operator, key()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, first)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, extra)).isEqualTo("CANCELLED");
        // Simulate a recovered stale queued record after stop: dispatch must recheck the durable barrier.
        jdbc.update("update device_command set status='QUEUED' where command_id=?", extra);
        fourChannelControl.dispatch(extra);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, extra)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from device_command d join countermeasure_4ch_command c on c.command_id=d.command_id"
                + " where d.device_id=? and c.mask=0 and d.status='QUEUED'", Long.class, device)).isEqualTo(1);
    }

    @Test
    void stoppedAuthorizationRejectsNewDirectStartButStillAllowsAllOff() throws Exception {
        String device = fourChannel(), authorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, authorization);
        stop(operator, key()).andExpect(status().isOk());
        long before = jdbc.queryForObject("select count(*) from device_command where device_id=?", Long.class, device);
        request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", operator, key(),
                Map.of("authorization_id", authorization, "action", "SET_MASK", "mask", 15, "reason", "急停后不应重新启动"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Long.class, device)).isEqualTo(before);
        jdbc.update("update disposal_authorization set status='COMPLETED' where authorization_id=?", authorization);
        request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", operator, key(),
                Map.of("authorization_id", authorization, "action", "SET_MASK", "mask", 15, "reason", "迟到完成状态也不能绕过急停"))
                .andExpect(status().isConflict());
        String allOff = directFourChannel(device, authorization, 0);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, allOff)).isEqualTo("QUEUED");
        request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", operator, key(),
                Map.of("authorization_id", authorization, "action", "CHANNEL_OFF", "channel", "900M", "reason", "关闭单通道仍然合法"))
                .andExpect(status().isAccepted());
    }

    @Test
    void emergencyStopCancelsExtraLinkedLingyunStartButPreservesLinkedStopCommand() throws Exception {
        String device = fourChannel(), authorization = authorization("COUNTERMEASURE", true);
        String first = lingyunCommand(device, authorization, 1), extra = lingyunCommand(device, authorization, 1);
        String shutdown = lingyunCommand(device, authorization, 0);
        jdbc.update("update device_command set status='ACCEPTED' where command_id=?", first);
        jdbc.update("update disposal_authorization set device_id=?,channel='LINGYUN_B',execution_command_id=? where authorization_id=?",
                device, first, authorization);
        stop(operator, key()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, first)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, extra)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, shutdown)).isEqualTo("QUEUED");
    }

    String lingyunCommand(String device, String authorization, int operationType) {
        String command = key();
        String user = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, operator);
        long now = System.currentTimeMillis();
        // Durable transport fixture only: cancellation must not require a live MQTT broker or receipt.
        new com.uav.lowaltitude.modules.device.infrastructure.LingyunControlRepository(jdbc).insert(
                command, "ESTOP-LY-" + command, device, user, "凌云关联命令取消回归", "live", true,
                now + 60000, now, operationType, 60003, "{}", authorization);
        return command;
    }

    String directFourChannel(String device, String authorization, int mask) throws Exception {
        return data(request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", operator, key(),
                Map.of("authorization_id", authorization, "action", "SET_MASK", "mask", mask, "reason", "直接设备命令回归测试"))
                .andExpect(status().isAccepted())).path("command_id").asText();
    }

    @Test
    void failedFourChannelStopAllowsExplicitRetryAndPreservesHistory() throws Exception {
        String device = fourChannel();
        String authorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, authorization);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        String retryPath = path() + "/" + latest.path("stop_id").asText() + "/devices/" + device + "/retry";
        request(retryPath, operator, key(), Map.of()).andExpect(status().isConflict());
        jdbc.update("update device_command set status='FAILED' where device_id=?", device);
        String retryKey = key();
        data(request(retryPath, operator, retryKey, Map.of()).andExpect(status().isOk()));
        request(retryPath, operator, retryKey, Map.of()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Long.class, device)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=? and status='FAILED'", Long.class, device)).isEqualTo(1);
    }

    @Test
    void offlineDeviceRemainsUnconfirmedAndCannotBeFalselyReportedAsStopped() throws Exception {
        String device = fourChannel();
        jdbc.update("update ops_device_state set connectivity='OFFLINE' where device_id=?", device);
        String authorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, authorization);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        assertThat(latest.path("devices").get(0).path("stop_status").asText()).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Long.class, device)).isZero();
        assertThat(statusOf(authorization)).isEqualTo("STOPPED");
    }

    @Test
    void lateCompletedParentCannotCreateNewJammingAfterEmergencyStop() throws Exception {
        String parent = authorization("COUNTERMEASURE", true);
        stop(operator, key()).andExpect(status().isOk());
        // Model a delayed completion callback: the durable emergency barrier must still suppress chaining.
        jdbc.update("update disposal_authorization set status='COMPLETED' where authorization_id=?", parent);
        chain.scheduleAfterComplete(parent);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where chained_from_authorization_id=?", Long.class, parent))
                .isZero();
    }

    @Test
    void initialRequestRequiresSessionAndIdempotencyKey() throws Exception {
        String authorization = authorization("COUNTERMEASURE", true);
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path()).header("Authorization", "Bearer " + operator)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(authorization)).isEqualTo("EXECUTING");
    }

    @Test
    void approvedOnlyStopCancelsExecutionWithoutInventingPhysicalConfirmationWork() throws Exception {
        requester = user("disposal:read", "disposal:request", "disposal:stop");
        String historical = authorization("COUNTERMEASURE", false);
        jdbc.update("update disposal_authorization set status='STOPPED' where authorization_id=?", historical);
        String authorization = authorization("COUNTERMEASURE", false);
        JsonNode overview = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator)).andExpect(status().isOk()));
        assertThat(overview.path("requires_device_stop").isBoolean()).isTrue();
        assertThat(overview.path("requires_device_stop").asBoolean()).isFalse();
        stop(operator, key()).andExpect(status().isConflict());
        assertThat(statusOf(authorization)).isEqualTo("APPROVED");
        JsonNode latest = data(stop(requester, key()).andExpect(status().isOk())).path("latest_stop");
        assertThat(statusOf(authorization)).isIn("CANCELLED", "STOPPED");
        for (JsonNode device : latest.path("devices")) {
            assertThat(device.path("stop_status").asText()).isEqualTo("NOT_REQUIRED");
            assertThat(device.path("allowed_actions").toString()).doesNotContain("MANUAL_CONFIRM", "RETRY");
        }
    }

    @Test
    void unresolvedPhysicalStopBlocksNewAuthorizationUntilManualConfirmation() throws Exception {
        authorization("COUNTERMEASURE", true);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        request("/api/v1/disposal-authorizations", requester, key(), Map.of("action_type", "COUNTERMEASURE",
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "channel", "COUNTERMEASURE_4CH",
                "device_id", "estop-fixture-device", "reason", "再次处置"))
                .andExpect(status().isConflict());
        String device = latest.path("devices").get(0).path("device_id").asText();
        request(path() + "/" + latest.path("stop_id").asText() + "/devices/" + device + "/manual-confirm",
                operator, key(), Map.of("note", "已核实所有现场动作停止")).andExpect(status().isOk());
        assertThat(statusOf(authorization("COUNTERMEASURE", false))).isEqualTo("APPROVED");
    }

    @Test
    void manualConfirmationCancelsQueuedAllOffBeforeAllowingNewExecution() throws Exception {
        String device = fourChannel();
        String authorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, authorization);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        String command = jdbc.queryForObject("select command_id from device_command where device_id=?", String.class, device);
        request(path() + "/" + latest.path("stop_id").asText() + "/devices/" + device + "/manual-confirm", operator,
                key(), Map.of("note", "现场已经断电核实停机")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("CANCELLED");
        assertThat(statusOf(authorization("COUNTERMEASURE", false))).isEqualTo("APPROVED");
    }

    @Test
    void differentAssignedScopeCannotReadOrStopThisEvent() throws Exception {
        String authorization = authorization("COUNTERMEASURE", true);
        String outsider = user("disposal:read", "disposal:stop");
        String org = key(), district = key();
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                org, "OTHER-" + org.substring(0, 8), "另一测试机构" + org.substring(0, 8));
        jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                district, "OTHER-" + district.substring(0, 8), "另一测试区域" + district.substring(0, 8));
        jdbc.update("update app_user_data_scope set org_id=?,district_id=? where user_id=(select user_id from app_session where session_id=?)",
                org, district, outsider);
        mvc.perform(get(path()).header("Authorization", "Bearer " + outsider)).andExpect(status().isNotFound());
        stop(outsider, key()).andExpect(status().isNotFound());
        assertThat(statusOf(authorization)).isEqualTo("EXECUTING");
    }

    String fourChannel() {
        long now = System.currentTimeMillis();
        String source = key(), device = key(), no = "ESTOP4-" + key().substring(0, 8);
        jdbc.update("insert into ops_integration_source (source_id,source_code,name,protocol_code,protocol_version,source_mode,"
                + "enabled,allowed_cidrs,simulated,version,created_at,updated_at) values (?,?,?,?,'2.0','live',true,'127.0.0.1/32',true,0,?,?)",
                source, no, "急停四通道夹具", com.uav.lowaltitude.integration.device.DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, now, now);
        jdbc.update("insert into ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,"
                + "channel,enabled,source_mode,simulated,version,created_at,updated_at)"
                + " values (?,?,?,?,?,'countermeasure','反制','反制直连',true,'live',true,0,?,?)", device, source, no, no, "急停四通道夹具", now, now);
        jdbc.update("insert into device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at) values (?,?,?,current_timestamp,current_timestamp)", device, ORG, DISTRICT);
        jdbc.update("insert into device_connection_profile (device_id,transport,host,port,timeout_millis,retry_count,version,updated_at)"
                + " values (?,'TCP','127.0.0.1',1,1000,0,0,?)", device, now);
        jdbc.update("insert into countermeasure_4ch_profile (device_id,device_address,wire_encoding,poll_interval_millis,version,updated_at)"
                + " values (?,1,'ASCII_HEX_SPACED',5000,0,?)", device, now);
        jdbc.update("insert into ops_device_state (device_id,connectivity,has_alarm,health_code,observed_at,received_at,simulated,version)"
                + " values (?,'ONLINE',false,'GOOD',?,?,true,0)", device, now, now);
        return device;
    }

    String authorization(String action, boolean execute) throws Exception {
        if (execute) return historicalExecuting(action);
        String id = data(request("/api/v1/disposal-authorizations", requester, key(), Map.of("action_type", action,
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "channel", "COUNTERMEASURE_4CH",
                "device_id", UUID.randomUUID().toString(), "reason", "急停回归测试"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        request("/api/v1/disposal-authorizations/" + id + "/approve", operator, key(), Map.of("expected_version", 0))
                .andExpect(status().isOk());
        return id;
    }

    /** 历史人工执行只作为急停夹具，不再调用已关闭的人工执行接口。 */
    private String historicalExecuting(String action) {
        String id = UUID.randomUUID().toString();
        String requesterId = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, requester);
        String operatorId = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, operator);
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp until = Timestamp.from(Instant.now().plusSeconds(600));
        jdbc.update("insert into disposal_authorization (authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "channel,reason,requested_by,requested_at,approved_by,approved_at,valid_from,valid_until,status,"
                + "policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at,authorization_mode)"
                + " values (?,?,?,'UAV_EVENT',?,'MANUAL','急停回归测试',?,?,?,?,?,?,'EXECUTING','demo-v1',?,?,'mock',2,?,?,'REVIEW')",
                id, "ESTOP-" + id.substring(0, 8), action, eventId, requesterId, now, operatorId, now, now, until,
                ORG, DISTRICT, now, now);
        return id;
    }

    String statusOf(String id) {
        return jdbc.queryForObject("select status from disposal_authorization where authorization_id=?", String.class, id);
    }

    ResultActions stop(String token, String key) throws Exception {
        return request(path(), token, key, Map.of());
    }

    ResultActions request(String path, String token, String key, Object body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    String path() { return "/api/v1/uav-events/" + eventId + "/emergency-stop"; }
    static String key() { return UUID.randomUUID().toString(); }

    String event() {
        String tag = key().substring(0, 8), alarm = "estop-alarm-" + tag, event = "estop-event-" + tag;
        Timestamp at = Timestamp.from(Instant.parse("2026-09-07T02:00:00Z"));
        jdbc.update("insert into integration_source (source_id,source_code,name,enabled,source_mode,created_at,updated_at,version)"
                + " select 'estop-src','ESTOP-TEST','急停测试来源',true,'mock',?,?,0"
                + " where not exists(select 1 from integration_source where source_id='estop-src')", at, at);
        jdbc.update("insert into alarm (alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,"
                + "received_at,source_mode,owner_org_id,district_id,created_at)"
                + " values (?,null,'estop-src',?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarm, tag, at, at, ORG, DISTRICT, at);
        jdbc.update("insert into uav_event (event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)"
                + " values (?,?,'CONFIRMED',?,?,?,?,1)", event, alarm, ORG, DISTRICT, at, at);
        CounterEvidenceFixture.seed(jdbc,event);
        return event;
    }

    String user(String... permissions) {
        String id = key(), role = "ESTOP-" + key().substring(0, 8), token = key();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values (?,?,'',false,true,0,0,0,false)", role, role);
        for (String permission : permissions) {
            jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values (?,?,'OP',false,current_timestamp)", role, permission);
        }
        jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " values (?,'alarm:read','READ',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,"
                + "permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                id, "estop-" + key().substring(0, 8), "急停测试操作员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", id, ORG, DISTRICT);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                token, id, System.currentTimeMillis() + 3_600_000L);
        return token;
    }
}
