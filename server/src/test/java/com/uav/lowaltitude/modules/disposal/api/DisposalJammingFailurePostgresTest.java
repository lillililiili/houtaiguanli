package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** No successor command is created; already-sent historical failures remain separately auditable. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class DisposalJammingFailurePostgresTest extends AutomationMqttFixture {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.mqtt.enabled", () -> true);
        registry.add("app.outbox.enabled", () -> true);
        registry.add("app.lingyun-control.command-timeout-millis", () -> 120000);
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
    @Autowired HandoffSubmissionService handoffs;
    @SpyBean NotificationDirectoryService recipients;
    @MockBean HandoffChannelPort channel;
    @SpyBean com.uav.lowaltitude.modules.disposal.application.DisposalJammingChain chain;

    @Test
    void completedCountermeasureNeverCreatesAnotherAuthorizationAfterObservationChanges() throws Exception {
        String actor = user("disposal:direct","disposal:read","devices","target:read");
        String parent = data(request("/api/v1/disposal-authorizations/direct-execute",actor,key(),body("isolated no successor regression"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        outbox.poll(); awaitWire(1);
        reply(commandOf(parent),0);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(statusOf(parent)).isEqualTo("COMPLETED"));
        String target = jdbc.queryForObject("select target_id from disposal_authorization where authorization_id=?",String.class,parent);
        jdbc.update("update target_latest_state set observed_at=? where target_id=?",
                java.sql.Timestamp.from(clock.now().minusSeconds(120)),target);
        chain.scheduleAfterComplete(parent);
        chain.retryWhileOn(parent);
        outbox.poll();
        assertThat(children(parent)).isEmpty();
        assertThat(frames).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?",Integer.class,binding.opsDeviceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?",Integer.class,eventId)).isZero();
    }

    @ParameterizedTest(name="R11 device negative response={0}")
    @ValueSource(strings={"QA simulated execution failure", "QA simulated explicit device rejection"})
    void historicalJammingNegativeReceiptStaysSeparateWithoutAutomaticHandoff(String replyText) throws Exception {
        String actor = user("disposal:direct", "disposal:read", "devices", "target:read", "handoff:create", "handoff:read", "evidence:read");
        String recipient = key();
        jdbc.update("update handoff_recipient set enabled=false where handoff_type='UAV_PUNISHMENT'");
        jdbc.update("insert into handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) values(?,'QA synthetic R11 recipient','UAV_PUNISHMENT',true,current_timestamp,current_timestamp)", recipient);
        doReturn(new RecipientSnapshot(recipient,"QA synthetic R11 recipient",null,null,null,null,null,
                "MOCK",null,null,1L,true,null,clock.nowMillis())).when(recipients).forHandoff(eq("UAV_PUNISHMENT"),eq(recipient));
        when(channel.simulated()).thenReturn(true);
        when(channel.deliver(any())).thenAnswer(call -> {
            var at = ((HandoffChannelPort.HandoffDispatch)call.getArgument(0)).at();
            return new DeliveryOutcome("SUBMITTED","PENDING",null,null,at,null,null);
        });
        String parent = data(request("/api/v1/disposal-authorizations/direct-execute",actor,key(),body("QA R11 explicit synthetic direct authority"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        outbox.poll(); awaitWire(1);
        assertThat(frames.get(0).path("data").path("operationCmd").asInt()).isEqualTo(60003);
        reply(commandOf(parent),0);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(statusOf(parent)).isEqualTo("COMPLETED");
        });
        assertThat(children(parent)).isEmpty();
        String child = historicalSentChild(parent);
        assertThat(frames).hasSize(1);
        publishNegative(commandOf(child),replyText);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(statusOf(child)).isEqualTo("FAILED"));
        assertThat(statusOf(parent)).isEqualTo("COMPLETED");
        // Protocol B maps every nonzero device result to FAILED; device rejection is not an approval REJECTED state.
        assertThat(jdbc.queryForObject("select result_detail from device_command where command_id=?",String.class,commandOf(child))).isEqualTo(replyText);
        JsonNode parentRead = read("/api/v1/disposal-authorizations/"+parent,actor);
        JsonNode childRead = read("/api/v1/disposal-authorizations/"+child,actor);
        assertThat(parentRead.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(childRead.path("status").asText()).isEqualTo("FAILED");
        assertThat(parentRead.path("subject_id").asText()).isEqualTo(eventId);
        assertThat(childRead.path("subject_id").asText()).isEqualTo(eventId);
        assertThat(read("/api/v1/uav-events/"+eventId,actor).path("state").asText()).isEqualTo("CONFIRMED");
        handoffs.automaticAfterJamming(eventId);
        handoffs.automaticAfterJamming(eventId);
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?",Integer.class,eventId)).isZero();
        verify(channel,never()).deliver(any());

        // An explicitly requested manual handoff is independent of successful device execution.
        long version = jdbc.queryForObject("select version from uav_event where event_id=?",Long.class,eventId);
        String handoff = data(request("/api/v1/handoffs",actor,key(),Map.of("source_kind","UAV_EVENT","source_id",eventId,
                "handoff_type","UAV_PUNISHMENT","recipient_id",recipient,"expected_version",version))
                .andExpect(status().isCreated())).path("handoff_id").asText();
        JsonNode frozen = read("/api/v1/handoffs/"+handoff,actor).path("material");
        var results = new LinkedHashMap<String,String>();
        for (JsonNode disposal : frozen.path("disposals")) results.put(disposal.path("authorization_id").asText(),disposal.path("status").asText());
        assertThat(results).containsEntry(parent,"COMPLETED").containsEntry(child,"FAILED").hasSize(2);
        String snapshot = jdbc.queryForObject("select CAST(snapshot AS VARCHAR) from handoff_material_snapshot where handoff_id=?",String.class,handoff);
        publishNegative(commandOf(child),replyText);
        outbox.poll(); handoffs.automaticAfterJamming(eventId);
        assertThat(jdbc.queryForObject("select CAST(snapshot AS VARCHAR) from handoff_material_snapshot where handoff_id=?",String.class,handoff)).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?",Integer.class,eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?",Integer.class,handoff)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from punishment_case where event_id=?",Integer.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from command_receipt where command_id=?",Integer.class,commandOf(child))).isEqualTo(1);
        verify(channel,times(1)).deliver(any());
        assertThat(frames).hasSize(1);
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("synthetic_fixture",true); evidence.put("event_id",eventId);
        evidence.put("parent",parentRead); evidence.put("child",childRead);
        evidence.put("negative_device_text",replyText); evidence.put("automatic_handoff_count_before_manual",0);
        evidence.put("manual_handoff_id",handoff); evidence.put("frozen_material",frozen); evidence.put("wire_frames",frames);
        evidence.put("receipts",jdbc.queryForList("select r.* from command_receipt r join device_command c on c.command_id=r.command_id where c.device_id=?",binding.opsDeviceId()));
        Path output = Path.of("target","supplemental-r11"); Files.createDirectories(output);
        Files.writeString(output.resolve(replyText.contains("rejection")?"device-rejection.json":"device-failure.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    /** Already-sent legacy fact in the isolated schema; no second device command is dispatched. */
    private String historicalSentChild(String parent) {
        String child=key(), command=key(), number="OLD-"+key();
        jdbc.update("insert into disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,"
                + "target_id,device_id,channel,reason,requested_by,requested_at,valid_from,valid_until,status,policy_version,"
                + "owner_org_id,district_id,source_mode,chained_from_authorization_id,authorization_mode,version,created_at,updated_at)"
                + " select ?,?,'JAMMING',subject_kind,subject_id,target_id,device_id,channel,'isolated historical child',requested_by,"
                + "requested_at,valid_from,valid_until,'APPROVED',policy_version,owner_org_id,district_id,source_mode,authorization_id,"
                + "authorization_mode,0,created_at,updated_at from disposal_authorization where authorization_id=?",
                child,"OLD-"+child.substring(0,12),parent);
        jdbc.update("insert into device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,"
                + "source_mode,simulated,deadline_at,created_at,updated_at,authorization_id,issued_at)"
                + " select ?,?,device_id,requested_by,command_type,'isolated already-sent command','SENT',source_mode,simulated,"
                + "?,?,?, ?,? from device_command where command_id=?",command,number,clock.nowMillis()+120000,
                clock.nowMillis(),clock.nowMillis(),child,clock.nowMillis(),commandOf(parent));
        jdbc.update("insert into lingyun_control_command(command_id,msg_no,operation_type,operation_cmd,params_json,authorization_id)"
                + " select ?,?,operation_type,60002,params_json,? from lingyun_control_command where command_id=?",
                command,number,child,commandOf(parent));
        jdbc.update("update disposal_authorization set status='EXECUTING',execution_command_id=? where authorization_id=?",command,child);
        return child;
    }

    private JsonNode read(String path,String token) throws Exception {
        return data(mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk()));
    }
    private void publishNegative(String command,String message) throws Exception {
        String number = jdbc.queryForObject("select command_no from device_command where command_id=?",String.class,command);
        String payload = json.writeValueAsString(Map.of("head",Map.of("msgNo",number,"deviceId",binding.externalDeviceId(),"time",clock.nowMillis()),
                "data",Map.of("code",1,"msg",message)));
        deviceClient.publish(binding.controlRespTopic(),payload.getBytes(StandardCharsets.UTF_8),1,false);
    }
}
