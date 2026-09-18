package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.fasterxml.jackson.databind.*;
import com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import com.uav.lowaltitude.platform.time.AppClock;

@SpringBootTest(properties={"app.handoff.channel=none","app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class DeviceMaintenanceNoticeApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @SpyBean FlightDeviceCheckService checks; @SpyBean AppClock clock;
    @MockBean HandoffChannelPort channel;
    String session,org,device,setting,taskId;
    final String plan="seed-stage3-plan-legal";
    long now;
    final Set<String> createdTaskIds=new HashSet<>();
    @BeforeEach void fixture()throws Exception {
        now=System.currentTimeMillis();doReturn(Instant.ofEpochMilli(now)).when(clock).now();
        session=data(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).path("session_id").asText();
        org=jdbc.queryForObject("SELECT org_id FROM app_org WHERE org_code='ORG-DEV'",String.class);
        device=jdbc.queryForObject("SELECT device_id FROM ops_device WHERE deleted_at IS NULL ORDER BY device_id FETCH FIRST 1 ROW ONLY",String.class);
        observe(true,now);
        when(channel.simulated()).thenReturn(true);
        when(channel.deliver(any())).thenAnswer(call->{var at=((HandoffChannelPort.HandoffDispatch)call.getArgument(0)).at();return new DeliveryOutcome("DELIVERED","PENDING",null,null,at,at,null);});
        setting=data(write(post("/api/v1/notification-settings"),Map.of("purpose","DEVICE_MAINTENANCE","recipient_org_id",org,"channel_type","MOCK","enabled",true),UUID.randomUUID().toString())).path("setting_id").asText();
    }
    @AfterEach void cleanCommittedFixture(){
        if(TransactionSynchronizationManager.isActualTransactionActive())return;
        for(String id:createdTaskIds){jdbc.update("DELETE FROM ops_device_maintenance_notice_attempt WHERE task_id=?",id);jdbc.update("DELETE FROM ops_device_maintenance_submission WHERE task_id=?",id);jdbc.update("DELETE FROM ops_device_maintenance_task WHERE task_id=?",id);}
        if(setting!=null)jdbc.update("DELETE FROM notification_setting WHERE setting_id=?",setting);
    }
    @Test void appendRetainsFirstDeliveryAndChangedRecipientSnapshotWhileLatestFailureIsSeparate()throws Exception {
        JsonNode first=create();advance();
        jdbc.update("UPDATE app_org SET name='变更后的运维单位' WHERE org_id=?",org);
        doReturn(new DeliveryOutcome("FAILED","NOT_EXPECTED",null,"模拟渠道返回失败",null,null,null)).when(channel).deliver(any());
        JsonNode second=resend(1,"人工再次通知",UUID.randomUUID().toString());
        assertThat(second.path("notification_delivery_status").asText()).isEqualTo("FAILED");
        assertThat(second.path("notification_attempts")).hasSize(2);
        assertThat(second.path("notification_attempts").get(1)).isEqualTo(first.path("notification_attempts").get(0));
        assertThat(second.path("notification_attempts").get(0).path("recipient_snapshot").path("org_name").asText()).isEqualTo("变更后的运维单位");
        assertThat(second.path("notification_attempts").get(1).path("delivery_status").asText()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT notification_delivery_status FROM ops_device_maintenance_task WHERE task_id=?",String.class,taskId)).isEqualTo("DELIVERED");
        JsonNode read=data(auth(get("/api/v1/flight-plans/"+plan+"/device-maintenance-tasks").param("device_id",device))).path("items").get(0);
        assertThat(read.path("notification_attempts")).isEqualTo(second.path("notification_attempts"));
    }
    @Test void cooldownExpectedAttemptAndIdempotencyPreventRepeatedDispatch()throws Exception {
        create();String key=UUID.randomUUID().toString();
        mvc.perform(resendRequest(1,null,key)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_RESEND_BLOCKED"));
        advance();JsonNode sent=resend(1,null,key);JsonNode replay=resend(1,null,key);
        assertThat(sent.path("latest_notification_attempt_no").asInt()).isEqualTo(2);
        assertThat(replay.path("reused").asBoolean()).isTrue();
        mvc.perform(resendRequest(1,null,UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_NOTICE_CHANGED"));
        mvc.perform(resendRequest(1,"更换原因",key)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        verify(channel,times(2)).deliver(any());
    }
    @Test void unknownAndSubmittedResultsBlockBlindResend()throws Exception {
        doThrow(new IllegalStateException("unknown")).when(channel).deliver(any());
        JsonNode first=create();assertThat(first.path("notification_attempts").get(0).path("outcome_state").asText()).isEqualTo("UNKNOWN");
        advance();mvc.perform(resendRequest(1,null,UUID.randomUUID().toString())).andExpect(status().isConflict());
        verify(channel,times(1)).deliver(any());
        jdbc.update("UPDATE ops_device_maintenance_notice_attempt SET outcome_state='SUBMITTED',delivery_status='SUBMITTED' WHERE task_id=?",taskId);
        mvc.perform(resendRequest(1,null,UUID.randomUUID().toString())).andExpect(status().isConflict());
    }
    @Test void handledTaskCannotSendAgain()throws Exception {
        create();advance();
        data(write(post("/api/v1/device-maintenance-tasks/"+taskId+"/handling"),Map.of("expected_version",1,"note","已核对设备异常，继续现场处理"),UUID.randomUUID().toString()));
        mvc.perform(resendRequest(1,null,UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_RESEND_BLOCKED"));
        verify(channel,times(1)).deliver(any());
    }
    @Test void currentDeviceScopeAbnormalityAndFreshnessAreRechecked()throws Exception {
        create();advance();observe(false,now);
        mvc.perform(resendRequest(1,null,UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEVICE_NOT_ABNORMAL"));
        observe(true,now-301000,"ONLINE");
        mvc.perform(resendRequest(1,null,UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_OBSERVATION_STALE"));
        doReturn(new FlightDeviceCheckService.Check(plan,"CHECK_INCOMPLETE","没有附近设备",now,BigDecimal.TEN,false,1,List.of(),false)).when(checks).read(plan);
        mvc.perform(resendRequest(1,null,UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEVICE_NOT_NEAR_PLAN"));
        verify(channel,times(1)).deliver(any());
    }
    @Test void unconfiguredAndLiveChannelsNeverDispatchAndKeepExplicitNotSentAttempts()throws Exception {
        when(channel.simulated()).thenReturn(false);JsonNode first=create();
        assertThat(first.path("notification_attempts").get(0).path("outcome_state").asText()).isEqualTo("NOT_SENT");
        advance();JsonNode second=resend(1,"发送失败后重试",UUID.randomUUID().toString());
        assertThat(second.path("notification_attempts")).hasSize(2);verify(channel,never()).deliver(any());
        when(channel.simulated()).thenReturn(true);advance();
        jdbc.update("UPDATE flight_plan SET source_mode='live' WHERE plan_id=?",plan);
        JsonNode live=resend(2,null,UUID.randomUUID().toString());
        assertThat(live.path("notification_attempts").get(0).path("outcome_state").asText()).isEqualTo("NOT_SENT");
        verify(channel,never()).deliver(any());
    }
    @Test void revokedPermissionAndAnonymousCallerCannotResend()throws Exception {
        create();advance();
        jdbc.update("DELETE FROM app_role_permission WHERE role_code='ROLE-ADMIN' AND permission_code='handoff:create'");sqlSession.clearCache();
        mvc.perform(resendRequest(1,null,UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/device-maintenance-tasks/"+taskId+"/notifications/resend").contentType(MediaType.APPLICATION_JSON).content("{\"expected_attempt_no\":1}")).andExpect(status().isUnauthorized());
        verify(channel,times(1)).deliver(any());
    }
    @Test void currentOfflineFailureCanBeRemindedWithoutNewHeartbeat()throws Exception {
        create();advance();observe(true,now-3600000,"OFFLINE");
        JsonNode sent=resend(1,null,UUID.randomUUID().toString());assertThat(sent.path("latest_notification_attempt_no").asInt()).isEqualTo(2);
        verify(channel,times(2)).deliver(any());
    }
    @Test void originalCreateStillReusesTaskWithoutAppendingNotice()throws Exception {
        JsonNode first=create();JsonNode repeat=create();assertThat(repeat.path("task_id")).isEqualTo(first.path("task_id"));
        assertThat(repeat.path("notification_attempts")).hasSize(1);verify(channel,times(1)).deliver(any());
    }
    JsonNode create()throws Exception {JsonNode task=data(write(post("/api/v1/flight-plans/"+plan+"/device-maintenance-tasks"),Map.of("device_id",device,"notification_setting_id",setting),UUID.randomUUID().toString()));taskId=task.path("task_id").asText();createdTaskIds.add(taskId);return task;}
    JsonNode resend(int expected,String reason,String key)throws Exception{return data(resendRequest(expected,reason,key));}
    MockHttpServletRequestBuilder resendRequest(int expected,String reason,String key)throws Exception {var body=new HashMap<String,Object>();body.put("expected_attempt_no",expected);if(reason!=null)body.put("reason",reason);return write(post("/api/v1/device-maintenance-tasks/"+taskId+"/notifications/resend"),body,key);}
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request){return request.header("Authorization","Bearer "+session);}
    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request,Object body,String key)throws Exception{return auth(request).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));}
    JsonNode data(MockHttpServletRequestBuilder request)throws Exception {return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    void advance(){now+=61000;doReturn(Instant.ofEpochMilli(now)).when(clock).now();observe(true,now);}
    void observe(boolean abnormal,long observed){observe(abnormal,observed,abnormal?"OFFLINE":"ONLINE");}
    void observe(boolean abnormal,long observed,String connectivity){var row=new FlightDeviceCheckService.DeviceRow(device,"测试设备",true,BigDecimal.ONE,connectivity,abnormal?"BAD":"GOOD",observed,observed,abnormal,true,List.of());doReturn(new FlightDeviceCheckService.Check(plan,"AUTO_DEVICE_ABNORMAL","设备检查",now,BigDecimal.TEN,true,0,List.of(row),false)).when(checks).read(plan);}
}
