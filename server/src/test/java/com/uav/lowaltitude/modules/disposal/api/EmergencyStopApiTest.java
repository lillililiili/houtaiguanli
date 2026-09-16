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
        request("/api/v1/disposal-authorizations/" + parent + "/manual-result", operator, key(),
                Map.of("expected_version", 2, "result", "SUCCEEDED", "detail", "反制完成" )).andExpect(status().isOk());
        String child = jdbc.queryForObject("select authorization_id from disposal_authorization where chained_from_authorization_id=?",
                String.class, parent);
        assertThat(statusOf(child)).isEqualTo("APPROVED");
        JsonNode overview = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator)).andExpect(status().isOk()));
        assertThat(overview.path("requires_device_stop").isBoolean()).isTrue();
        assertThat(overview.path("requires_device_stop").asBoolean()).isTrue();
        stop(operator, key()).andExpect(status().isOk());
        assertThat(statusOf(parent)).isEqualTo("COMPLETED");
        assertThat(statusOf(child)).isIn("STOPPED", "CANCELLED");
        request("/api/v1/disposal-authorizations/" + child + "/execute", operator, key(),
                Map.of("expected_version", 1)).andExpect(status().isConflict());
        chain.scheduleAfterComplete(parent);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where chained_from_authorization_id=?", Long.class, parent))
                .isEqualTo(1);
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
        String historical = authorization("COUNTERMEASURE", false);
        jdbc.update("update disposal_authorization set status='STOPPED' where authorization_id=?", historical);
        String authorization = authorization("COUNTERMEASURE", false);
        JsonNode overview = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator)).andExpect(status().isOk()));
        assertThat(overview.path("requires_device_stop").isBoolean()).isTrue();
        assertThat(overview.path("requires_device_stop").asBoolean()).isFalse();
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
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
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "channel", "MANUAL", "reason", "再次处置"))
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
        String id = data(request("/api/v1/disposal-authorizations", requester, key(), Map.of("action_type", action,
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "channel", "MANUAL", "reason", "急停回归测试"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        request("/api/v1/disposal-authorizations/" + id + "/approve", operator, key(), Map.of("expected_version", 0))
                .andExpect(status().isOk());
        if (execute) request("/api/v1/disposal-authorizations/" + id + "/execute", operator, key(), Map.of("expected_version", 1))
                .andExpect(status().isOk());
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
        jdbc.update("INSERT INTO uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,outcome,danger,note,urgent,simulated) VALUES(?,?,0,'OBSERVATION',0,?,'STILL_INSIDE','HIGH','测试现场确认持续逼近受保护区域，存在紧急危险，需要立即申请有效授权',TRUE,FALSE)",key(),event,jdbc.queryForObject("select user_id from app_session where session_id=?",String.class,requester));
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
