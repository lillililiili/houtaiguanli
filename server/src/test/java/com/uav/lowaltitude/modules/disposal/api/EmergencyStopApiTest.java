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
    @Autowired com.uav.lowaltitude.modules.disposal.application.DisposalCommandGuard commandGuard;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.EoDispatchRepository eoDispatch;
    @Autowired com.uav.lowaltitude.modules.alarm.infrastructure.NoCounterRepository noCounter;
    private static final String UNSETTLED_COMMAND = "设备指令仍在途或结果不明确，请先核查实际设备状态";
    String requester, operator, backendOperator, eventId;

    @BeforeEach
    void fixture() {
        requester = user("disposal:read", "disposal:request");
        operator = user("disposal:read", "disposal:approve", "disposal:execute", "disposal:stop", "devices", "monitoring");
        backendOperator = com.uav.lowaltitude.testsupport.BackendAccountFixture.session(jdbc, "ASSIGNED", ORG, DISTRICT);
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
        // 2026-10-14 起急停后不再登记现场停机核查：概览不再提供登记动作，旧客户端调用的接口仍然兼容。
        assertThat(latest.path("devices").get(0).path("allowed_actions").toString()).doesNotContain("MANUAL_CONFIRM");
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
        assertThat(acknowledged.path("detail").asText()).isEqualTo("已收到全关确认");
        assertThat(acknowledged.path("allowed_actions").toString()).doesNotContain("MANUAL_CONFIRM");
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
    void sharedDeviceStopIsNeverBlockedByAnotherEventAndTellsTheOtherRecord() throws Exception {
        // ZT-42：急停永远要能停下本事件实际在跑的设备；同设备上别的事件只记一条“已下发全关”，不被替人停掉。
        String device = fourChannel(), firstEvent = eventId;
        String firstAuthorization = authorization("COUNTERMEASURE", true);
        eventId = event();
        String secondEvent = eventId, secondAuthorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id in (?,?)",
                device, firstAuthorization, secondAuthorization);
        eventId = firstEvent;
        JsonNode overview = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator)).andExpect(status().isOk()));
        assertThat(overview.path("allowed_actions").toString()).contains("EMERGENCY_STOP");
        assertThat(overview.path("block_reason").textValue()).isNull();
        String response = stop(operator, key()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("SHARED_DEVICE_SCOPE_BLOCKED").doesNotContain(secondEvent);
        assertThat(statusOf(firstAuthorization)).isEqualTo("STOPPED");
        assertThat(statusOf(secondAuthorization)).isEqualTo("EXECUTING");
        String allOff = jdbc.queryForObject("select command_id from device_command where device_id=?", String.class, device);
        assertThat(jdbc.queryForObject("select mask from countermeasure_4ch_command where command_id=?", Integer.class, allOff)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from disposal_emergency_stop where event_id=?", Long.class, secondEvent)).isZero();
        var notes = jdbc.queryForList("select event_kind,note,cast(snapshot as varchar) as snapshot from disposal_authorization_event"
                + " where authorization_id=? and event_kind in ('DEVICE_ALL_OFF_ISSUED','STOP')", secondAuthorization);
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).get("event_kind")).isEqualTo("DEVICE_ALL_OFF_ISSUED");
        assertThat(notes.get(0).get("note").toString()).contains("另一起处置按了急停").doesNotContain(firstEvent);
        assertThat(notes.get(0).get("snapshot").toString()).contains(allOff);
        // 另一事件自己的急停照样可用。
        eventId = secondEvent;
        stop(operator, key()).andExpect(status().isOk());
        assertThat(statusOf(secondAuthorization)).isEqualTo("STOPPED");
    }

    @Test
    void otherEventApprovalOnStoppedDeviceStartsOnceAllOffFinishesWithoutSiteCheck() throws Exception {
        // ZT-42 原场景：A、B 两起事件都批了同一台反制设备，A 在执行。A 急停后，B 的批准不被撤销。
        // 2026-10-14 起急停后不再登记现场停机核查：全关还在排队时设备忙，全关结束后 B 直接可以执行，不用任何人登记。
        String device = fourChannel(), firstEvent = eventId;
        String operatorId = jdbc.queryForObject("select user_id from app_session where session_id=?", String.class, operator);
        String running = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, running);
        eventId = event();
        String secondEvent = eventId;
        String waiting = data(request("/api/v1/disposal-authorizations", requester, key(), Map.of("action_type", "COUNTERMEASURE",
                "subject_kind", "UAV_EVENT", "subject_id", secondEvent, "channel", "COUNTERMEASURE_4CH",
                "device_id", device, "reason", "同一设备上的第二起事件"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        request("/api/v1/disposal-authorizations/" + waiting + "/approve", operator, key(), Map.of("expected_version", 0))
                .andExpect(status().isOk());
        eventId = firstEvent;

        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        assertThat(statusOf(running)).isEqualTo("STOPPED");
        assertThat(latest.path("devices").findValuesAsText("device_id")).containsExactly(device);
        JsonNode task = latest.path("devices").get(0);
        assertThat(task.path("stop_status").asText()).isEqualTo("QUEUED");
        assertThat(task.path("allowed_actions").toString()).doesNotContain("MANUAL_CONFIRM");
        String allOff = task.path("command_id").asText();
        assertThat(jdbc.queryForObject("select mask from countermeasure_4ch_command where command_id=?", Integer.class, allOff)).isZero();

        assertThat(statusOf(waiting)).isEqualTo("APPROVED");
        var notes = jdbc.queryForList("select actor_id,note from disposal_authorization_event"
                + " where authorization_id=? and event_kind='DEVICE_ALL_OFF_ISSUED'", waiting);
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).get("actor_id")).isEqualTo(operatorId);
        assertThat(notes.get(0).get("note").toString()).contains("另一起处置按了急停").doesNotContain("核查");
        // 排队中的启动、续链和手动执行都不再因为这次急停被拦。
        assertThat(commandGuard.mayStart(waiting, operatorId, device)).isTrue();

        // 全关还在排队：设备忙，这次不下发、授权仍是已批准；不是急停核查拦的。
        long version = jdbc.queryForObject("select version from disposal_authorization where authorization_id=?", Long.class, waiting);
        String busy = request("/api/v1/disposal-authorizations/" + waiting + "/execute", operator, key(),
                Map.of("expected_version", version)).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(busy).contains("DEVICE_BUSY").doesNotContain("EMERGENCY_STOP_UNCONFIRMED");
        assertThat(statusOf(waiting)).isEqualTo("APPROVED");

        // Trusted device receipt fixture: the controller acknowledged the all-off.
        jdbc.update("update device_command set status='SUCCEEDED' where command_id=?", allOff);
        JsonNode acknowledged = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())).path("latest_stop").path("devices").get(0);
        assertThat(acknowledged.path("stop_status").asText()).isEqualTo("CONTROLLER_ALL_OFF_ACK");
        assertThat(acknowledged.path("allowed_actions").toString()).doesNotContain("MANUAL_CONFIRM");
        version = jdbc.queryForObject("select version from disposal_authorization where authorization_id=?", Long.class, waiting);
        request("/api/v1/disposal-authorizations/" + waiting + "/execute", operator, key(), Map.of("expected_version", version))
                .andExpect(status().isOk());
        assertThat(statusOf(waiting)).isEqualTo("EXECUTING");
        assertThat(jdbc.queryForObject("select count(*) from disposal_emergency_stop_device where stop_id=? and confirmed_at is not null",
                Long.class, latest.path("stop_id").asText())).isZero();
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
        bindStartingDevice(authorization, device);
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
        request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", backendOperator, key(),
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"FAULT,上报故障", "OFFLINE,当前离线", "DISABLED,已停用",
            "ABNORMAL,工作异常", "UNKNOWN,还没有上报状态"})
    void directOnUnusableDeviceIsRefusedBeforeAnyAuthorizationExists(String state, String said) throws Exception {
        // BUG-03 / ZT-18：坏的、离线的、停用的、状态不明的设备在发起时就拒绝，不再先落一条执行不了却占着名额的授权。
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read");
        String device = fourChannel();
        switch (state) {
            case "FAULT" -> jdbc.update("update ops_device_state set health_code='BAD',has_alarm=true where device_id=?", device);
            case "OFFLINE", "ABNORMAL" -> jdbc.update("update ops_device_state set connectivity=? where device_id=?", state, device);
            case "DISABLED" -> jdbc.update("update ops_device set enabled=false where device_id=?", device);
            case "UNKNOWN" -> jdbc.update("delete from ops_device_state where device_id=?", device);
            default -> throw new IllegalArgumentException(state);
        }
        JsonNode error = json.readTree(request("/api/v1/disposal-authorizations/direct-execute", actor, key(),
                Map.of("subject_kind", "UAV_EVENT", "subject_id", eventId, "action_type", "COUNTERMEASURE",
                        "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "不可用设备申请时拒绝"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("DEVICE_UNAVAILABLE");
        assertThat(error.path("message").asText()).contains(said).contains("请换一台");
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?", Integer.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Integer.class, device)).isZero();
    }

    /** 申请时设备正常、批准后设备出状况：执行如实受阻，授权仍是已批准。 */
    String blockedAfterApproval(String state) throws Exception {
        String device = fourChannel();
        String authorization = data(request("/api/v1/disposal-authorizations", requester, key(), Map.of("action_type", "COUNTERMEASURE",
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "channel", "COUNTERMEASURE_4CH",
                "device_id", device, "reason", "批准后设备出状况"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        request("/api/v1/disposal-authorizations/" + authorization + "/approve", operator, key(), Map.of("expected_version", 0))
                .andExpect(status().isOk());
        if ("FAULT".equals(state)) jdbc.update("update ops_device_state set health_code='BAD',has_alarm=true where device_id=?", device);
        else jdbc.update("update ops_device_state set connectivity='OFFLINE' where device_id=?", device);
        request("/api/v1/disposal-authorizations/" + authorization + "/execute", operator, key(), Map.of("expected_version", 1))
                .andExpect(status().isConflict());
        return authorization;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"FAULT,DEVICE_FAULT", "OFFLINE,DEVICE_OFFLINE"})
    void deviceThatGoesBadAfterApprovalBlocksExecutionAndTheApprovalCanBeWithdrawn(String state, String reason) throws Exception {
        String authorization = blockedAfterApproval(state);
        assertThat(statusOf(authorization)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForList("select event_kind from disposal_authorization_event where authorization_id=?", String.class, authorization))
                .contains("REQUEST", "APPROVE", reason).doesNotContain("EXECUTE");
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=(select device_id from disposal_authorization"
                + " where authorization_id=?)", Integer.class, authorization)).isZero();
        JsonNode detail = data(mvc.perform(get("/api/v1/disposal-authorizations/" + authorization)
                .header("Authorization", "Bearer " + requester)).andExpect(status().isOk()));
        assertThat(detail.path("execution_block_reason").asText()).isEqualTo(reason);
        assertThat(detail.path("allowed_actions").toString()).contains("CANCEL");
        // BUG-03：受阻的已批准授权不必等过期——申请人撤销后，同一事件马上可以换一台设备重新申请。
        request("/api/v1/disposal-authorizations/" + authorization + "/cancel", requester, key(),
                Map.of("expected_version", detail.path("version").asLong(), "note", "设备出状况，改用其他设备"))
                .andExpect(status().isOk());
        assertThat(statusOf(authorization)).isEqualTo("CANCELLED");
        request("/api/v1/disposal-authorizations", requester, key(), Map.of("action_type", "COUNTERMEASURE",
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "channel", "COUNTERMEASURE_4CH",
                "device_id", fourChannel(), "reason", "换一台设备重新申请")).andExpect(status().isCreated());
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
                "handoff:create", "handoff:read", "evidence:read", "evidence:ingest");
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
                .param("subject_kind", "EVENT").param("subject_id", eventId)
                .header("Authorization", "Bearer " + actor).header("Idempotency-Key", key())).andExpect(status().isCreated()))
                .path("evidence_id").asText();
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
        request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", backendOperator, key(),
                Map.of("authorization_id", authorization, "action", "SET_MASK", "mask", 15, "reason", "急停后不应重新启动"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?", Long.class, device)).isEqualTo(before);
        jdbc.update("update disposal_authorization set status='COMPLETED' where authorization_id=?", authorization);
        request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", backendOperator, key(),
                Map.of("authorization_id", authorization, "action", "SET_MASK", "mask", 15, "reason", "迟到完成状态也不能绕过急停"))
                .andExpect(status().isConflict());
        String allOff = directFourChannel(device, authorization, 0);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, allOff)).isEqualTo("QUEUED");
        request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", backendOperator, key(),
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
        if (mask != 0) bindStartingDevice(authorization, device);
        return data(request("/api/v1/devices/" + device + "/commands/countermeasure-4ch", mask == 0 ? backendOperator : operator, key(),
                Map.of("authorization_id", authorization, "action", "SET_MASK", "mask", mask, "reason", "直接设备命令回归测试"))
                .andExpect(status().isAccepted())).path("command_id").asText();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,15})
    void sharedFrontendDeviceOperationReturnsItsOwnReceiptWithoutBackendAccess(int mask) throws Exception {
        String device=fourChannel(), authorization=authorization("COUNTERMEASURE",true), requestKey=key();
        bindStartingDevice(authorization, device);
        var body=Map.of("authorization_id",authorization,"action","SET_MASK","mask",mask,"reason","共享设备提交回执");
        String command=data(request("/api/v1/devices/"+device+"/commands/countermeasure-4ch",operator,requestKey,body)
                .andExpect(status().isAccepted())).path("command_id").asText();
        assertThat(command).isNotBlank();
        assertThat(data(request("/api/v1/devices/"+device+"/commands/countermeasure-4ch",operator,requestKey,body)
                .andExpect(status().isAccepted())).path("command_id").asText()).isEqualTo(command);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Integer.class,device)).isEqualTo(1);
        mvc.perform(get("/api/v1/device-commands/"+command).header("Authorization","Bearer "+operator))
                .andExpect(status().isForbidden());
        request("/api/v1/devices/"+device+"/commands/countermeasure-4ch",requester,key(),body).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Integer.class,device)).isEqualTo(1);
    }

    @Test
    void backendDevicePermissionCannotBorrowReviewedAuthorizationToStart() throws Exception {
        String device=fourChannel(), authorization=authorization("COUNTERMEASURE",true);
        bindStartingDevice(authorization, device);
        request("/api/v1/devices/"+device+"/commands/countermeasure-4ch",backendOperator,key(),
                Map.of("authorization_id",authorization,"action","SET_MASK","mask",15,"reason","后台身份不隐含业务执行权"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Integer.class,device)).isZero();
    }

    /** Adapt the isolated historical execution fixture to the actual device before testing a new start. */
    private void bindStartingDevice(String authorization, String device) {
        jdbc.update("UPDATE disposal_authorization SET device_id=?,channel='COUNTERMEASURE_4CH' WHERE authorization_id=?",
                device, authorization);
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
    void sameEventCanRequestAgainRightAfterStopWithoutSiteCheck() throws Exception {
        authorization("COUNTERMEASURE", true);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        JsonNode task = latest.path("devices").get(0);
        assertThat(task.path("stop_status").asText()).isEqualTo("UNSUPPORTED");
        assertThat(task.path("device_name").asText()).isEqualTo("人工处置（未绑定设备）");
        assertThat(task.path("detail").asText()).isEqualTo("该处置不支持远程停止，请到现场关停设备");
        assertThat(task.path("allowed_actions").toString()).doesNotContain("MANUAL_CONFIRM");
        String again = data(request("/api/v1/disposal-authorizations", requester, key(), Map.of("action_type", "COUNTERMEASURE",
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "channel", "COUNTERMEASURE_4CH",
                "device_id", "estop-fixture-device", "reason", "再次处置"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        request("/api/v1/disposal-authorizations/" + again + "/approve", operator, key(), Map.of("expected_version", 0))
                .andExpect(status().isOk());
        assertThat(statusOf(again)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from disposal_emergency_stop_device where stop_id=? and confirmed_at is null",
                Long.class, latest.path("stop_id").asText())).isEqualTo(1);
    }

    @Test
    void legacyManualConfirmationIsOptionalButStillCancelsQueuedAllOffAndStaysReadable() throws Exception {
        String device = fourChannel();
        String authorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, authorization);
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        String command = jdbc.queryForObject("select command_id from device_command where device_id=?", String.class, device);
        // 不用任何人登记，同一事件马上可以再申请、再批准。
        assertThat(statusOf(authorization("COUNTERMEASURE", false))).isEqualTo("APPROVED");
        // 旧客户端仍可调用登记接口：照旧取消排队中的全关，并留下只读记录。
        String confirmPath = path() + "/" + latest.path("stop_id").asText() + "/devices/" + device + "/manual-confirm";
        JsonNode confirmed = data(request(confirmPath, operator, key(), Map.of("note", "现场已经断电核实停机"))
                .andExpect(status().isOk())).path("latest_stop");
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?", String.class, command)).isEqualTo("CANCELLED");
        request(confirmPath, operator, key(), Map.of("note", "重复登记")).andExpect(status().isConflict());
        JsonNode reread = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())).path("latest_stop");
        for (JsonNode stop : java.util.List.of(confirmed, reread)) {
            JsonNode task = stop.path("devices").get(0);
            assertThat(task.path("stop_status").asText()).isEqualTo("MANUALLY_CONFIRMED");
            assertThat(task.path("confirmation_note").asText()).isEqualTo("现场已经断电核实停机");
            assertThat(task.path("confirmed_by_name").asText()).isEqualTo("急停测试操作员");
            assertThat(task.path("confirmed_at").isNumber()).isTrue();
            assertThat(task.path("allowed_actions").toString()).doesNotContain("MANUAL_CONFIRM", "RETRY");
            assertThat(stop.path("events").findValuesAsText("kind")).contains("STOP", "MANUAL_CONFIRM");
        }
    }

    @Test
    void oldStopRecordsKeepTheirTextButShowCurrentInstructions() throws Exception {
        authorization("COUNTERMEASURE", true);
        String stop = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop").path("stop_id").asText();
        // 2026-10-14 之前写入的记录：库里的原文不改，读出时换成现在的说法。
        jdbc.update("update disposal_emergency_stop_device set device_name='人工处置现场核查',detail='该处置不支持远程停止，请登记现场核查'"
                + " where stop_id=?", stop);
        JsonNode task = data(mvc.perform(get(path()).header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())).path("latest_stop").path("devices").get(0);
        assertThat(task.path("device_name").asText()).isEqualTo("人工处置（未绑定设备）");
        assertThat(task.path("detail").asText()).isEqualTo("该处置不支持远程停止，请到现场关停设备");
        assertThat(jdbc.queryForMap("select device_name,detail from disposal_emergency_stop_device where stop_id=?", stop))
                .containsEntry("device_name", "人工处置现场核查").containsEntry("detail", "该处置不支持远程停止，请登记现场核查");
    }

    @Test
    void unconfirmedStopNoLongerKeepsTheDeviceBusy() throws Exception {
        String device = fourChannel();
        jdbc.update("update ops_device_state set connectivity='OFFLINE' where device_id=?", device);
        String authorization = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, authorization);
        JsonNode task = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop").path("devices").get(0);
        assertThat(task.path("stop_status").asText()).isEqualTo("OFFLINE");
        assertThat(task.path("confirmed_at").isNumber()).isFalse();
        long now = System.currentTimeMillis();
        assertThat(deviceRepository.hasActiveWork(device)).isFalse();
        assertThat(deviceRepository.hasCatalogBlockingWork(device)).isFalse();
        assertThat(deviceRepository.hasRecoveryBlockingWork(device, now - 60_000, now)).isFalse();
        jdbc.update("update ops_device_state set connectivity='ONLINE' where device_id=?", device);
        String next = data(request("/api/v1/disposal-authorizations", requester, key(), Map.of("action_type", "COUNTERMEASURE",
                "subject_kind", "UAV_EVENT", "subject_id", eventId, "channel", "COUNTERMEASURE_4CH",
                "device_id", device, "reason", "急停后马上再反制"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        request("/api/v1/disposal-authorizations/" + next + "/approve", operator, key(), Map.of("expected_version", 0))
                .andExpect(status().isOk());
        request("/api/v1/disposal-authorizations/" + next + "/execute", operator, key(), Map.of("expected_version", 1))
                .andExpect(status().isOk());
        assertThat(statusOf(next)).isEqualTo("EXECUTING");
    }

    @Test
    void stopWithoutSiteCheckNoLongerProtectsEoObservation() throws Exception {
        String target = jdbc.queryForObject("select a.target_id from alarm a join uav_event e on e.alarm_id=a.alarm_id where e.event_id=?",
                String.class, eventId);
        authorization("COUNTERMEASURE", true);
        JsonNode task = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop").path("devices").get(0);
        assertThat(task.path("stop_status").asText()).isEqualTo("UNSUPPORTED");
        assertThat(task.path("source_mode").asText()).isEqualTo("mock");
        assertThat(eoDispatch.protectedTarget(target)).isFalse();
        // 真正在做的设备处置仍然保护它的观察任务。
        authorization("COUNTERMEASURE", false);
        assertThat(eoDispatch.protectedTarget(target)).isTrue();
    }

    @Test
    void timedOutLaunchBlocksEndingTheDisposalUntilThatDeviceIsEmergencyStopped() throws Exception {
        String device = fourChannel(), firstEvent = eventId;
        String failed = authorization("COUNTERMEASURE", true);
        String launch = launch(device, failed);
        jdbc.update("update device_command set status='TIMED_OUT' where command_id=?", launch);
        jdbc.update("update disposal_authorization set status='FAILED' where authorization_id=?", failed);
        // 没有急停：结果不明的启动照旧挡住结束处置。
        assertThat(noCounter.operationBlock(firstEvent)).isEqualTo(UNSETTLED_COMMAND);
        // 之后这台设备按过急停（这里是同设备上的另一起事件）：全关作用于整台设备，不用登记就以急停为准。
        eventId = event();
        String other = authorization("COUNTERMEASURE", true);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH' where authorization_id=?", device, other);
        JsonNode task = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop").path("devices").get(0);
        assertThat(task.path("stop_status").asText()).isEqualTo("QUEUED");
        assertThat(task.path("confirmed_at").isNumber()).isFalse();
        long launchedAt = jdbc.queryForObject("select created_at from device_command where command_id=?", Long.class, launch);
        long stoppedAt = jdbc.queryForObject("select requested_at from disposal_emergency_stop where event_id=?", Long.class, eventId);
        assertThat(stoppedAt).isGreaterThanOrEqualTo(launchedAt);
        assertThat(noCounter.operationBlock(firstEvent)).isEmpty();
        assertThat(noCounter.operationBlock(eventId)).isEmpty();
    }

    @Test
    void emergencyStopSettlesItsOwnTimedOutCommandsButNotLaterLaunches() throws Exception {
        String device = fourChannel(), authorization = authorization("COUNTERMEASURE", true);
        String launch = launch(device, authorization);
        jdbc.update("update device_command set status='TIMED_OUT' where command_id=?", launch);
        // 回执同步还没把授权改成失败时按了急停。
        JsonNode latest = data(stop(operator, key()).andExpect(status().isOk())).path("latest_stop");
        String allOff = latest.path("devices").get(0).path("command_id").asText();
        assertThat(noCounter.operationBlock(eventId)).isEmpty();
        // 急停自己的全关超时也不会把结束处置卡住（不再有现场登记能解开它）。
        jdbc.update("update device_command set status='TIMED_OUT' where command_id=?", allOff);
        assertThat(noCounter.operationBlock(eventId)).isEmpty();
        // 急停之后另起的启动不在这次急停范围内：结果不明时照旧不能结束。
        String later = authorization("COUNTERMEASURE", false);
        String laterLaunch = directFourChannel(device, later, 15);
        long stoppedAt = jdbc.queryForObject("select requested_at from disposal_emergency_stop where stop_id=?", Long.class,
                latest.path("stop_id").asText());
        jdbc.update("update device_command set status='TIMED_OUT',created_at=? where command_id=?", stoppedAt + 1, laterLaunch);
        jdbc.update("update disposal_authorization set status='FAILED' where authorization_id=?", later);
        assertThat(noCounter.operationBlock(eventId)).isEqualTo(UNSETTLED_COMMAND);
    }

    /** 处置启动指令（四通道 mask 15），授权随之指到这台设备。 */
    private String launch(String device, String authorization) throws Exception {
        String command = directFourChannel(device, authorization, 15);
        jdbc.update("update disposal_authorization set device_id=?,channel='COUNTERMEASURE_4CH',execution_command_id=? where authorization_id=?",
                device, command, authorization);
        return command;
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
