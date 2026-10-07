package com.uav.lowaltitude.modules.device.application;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.*;
import com.uav.lowaltitude.modules.identity.infrastructure.UserMapper;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.platform.time.AppClock;

class EoTrackingCommandSafetyTest {
    private final EoEdgeRepository edges=mock(EoEdgeRepository.class);
    private final EoTrackingRepository tracking=mock(EoTrackingRepository.class);
    private final EoTrackingPolicy policy=mock(EoTrackingPolicy.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<MqttSessionSupervisor> provider=mock(ObjectProvider.class);
    private final MqttSessionSupervisor mqtt=mock(MqttSessionSupervisor.class);
    private final long now=100000;
    private final Binding binding=new Binding("device","d","os","s","edge","broker","external","replay",true,now,0,"org","district");
    private final EoEdgeCommandService service=new EoEdgeCommandService(edges,provider,new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),new ObjectMapper(),new MockEnvironment(),policy,tracking,mock(UserMapper.class),mock(AccessService.class),mock(org.springframework.transaction.PlatformTransactionManager.class));
    @Test void sentCommandIsNeverRepublishedEvenAfterRestart() {
        when(edges.command("c")).thenReturn(command("SENT","EO_BEGIN_TRACK"));
        service.dispatch(EoEdgeCommandService.TOPIC_BEGIN,"c");
        verifyNoInteractions(provider,mqtt);
    }
    @Test void beginTimeoutKeepsDeviceOccupiedWhenItMayHaveExecuted() {
        when(edges.command("c")).thenReturn(command("SENT","EO_BEGIN_TRACK"));
        when(edges.updateCommand(eq("c"),eq("SENT"),eq("TIMED_OUT"),anyLong(),anyString(),anyString())).thenReturn(1);
        when(edges.openTask("device")).thenReturn(Map.of("task_id","t","status","OPEN"));
        service.timeout("c","timeout");
        verify(edges,never()).updateTask(anyString(),anyString(),anyString(),any(),anyLong());
    }
    @Test void queuedExpiredBeginCanBeReleasedWithoutGuessingExecution() {
        when(edges.command("c")).thenReturn(command("QUEUED","EO_BEGIN_TRACK"));
        when(edges.updateCommand(eq("c"),eq("QUEUED"),eq("TIMED_OUT"),anyLong(),anyString(),anyString())).thenReturn(1);
        when(edges.openTask("device")).thenReturn(Map.of("task_id","t","status","OPEN"));
        service.timeout("c","timeout");
        verify(edges).updateTask("t","OPEN","FAILED",null,now);
    }
    @Test void pauseOrStaleObservationCancelsQueuedAutomaticBegin() {
        when(edges.command("c")).thenReturn(command("QUEUED","EO_BEGIN_TRACK"));
        when(edges.binding("device",true)).thenReturn(binding);
        when(edges.taskByBegin("c")).thenReturn(Map.of("task_id","t","target_id","target","origin","AUTO","status","OPEN"));
        when(tracking.snapshot("target")).thenReturn(Map.of("source_mode","replay","owner_org_id","org","district_id","district"));
        when(policy.deviceReady(binding)).thenReturn(true);
        when(policy.enabledFor(anyMap())).thenReturn(true);
        when(tracking.paused("target")).thenReturn(true);
        service.dispatch(EoEdgeCommandService.TOPIC_BEGIN,"c");
        verify(edges).updateCommand("c","QUEUED","CANCELLED",now,"TRACK_ELIGIBILITY_CHANGED","下发前资格已失效");
        verifyNoInteractions(provider,mqtt);
    }
    @Test void publishFailureCommitsUnknownAndNeverFreesTask() {
        when(edges.command("c")).thenReturn(command("QUEUED","EO_END_TRACK"),command("SENT","EO_END_TRACK"));
        when(edges.updateCommand("c","QUEUED","SENT",now,null,null)).thenReturn(1);
        when(edges.binding("device",true)).thenReturn(binding);
        when(edges.openTask("device")).thenReturn(Map.of("task_id","t","status","ENDING","end_command_id","c"));
        when(provider.getIfAvailable()).thenReturn(mqtt);
        doThrow(new IllegalStateException("connection lost after send")).when(mqtt).publish(anyString(),anyString(),any());
        service.dispatch(EoEdgeCommandService.TOPIC_END,"c");
        verify(edges).updateCommand("c","SENT","TIMED_OUT",now,"PUBLISH_RESULT_UNKNOWN","发送结果未知，需要设备回执核查");
        verify(edges,never()).updateTask(anyString(),anyString(),anyString(),any(),anyLong());
    }
    @Test void repeatedEndReturnsOriginalCommand() {
        when(edges.openTask("device")).thenReturn(Map.of("task_id","t","status","ENDING","end_command_id","end"));
        assertThat(service.enqueue(binding,EoEdgeCommandService.END,EoEdgeCommandService.TOPIC_END,"pause")).isEqualTo("end");
        verify(edges,never()).addOutbox(anyString(),anyString(),anyString(),anyLong());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"TIMED_OUT","FAILED","CANCELLED","UNKNOWN"})
    void explicitStopCanRetryUnconfirmedEndWithoutFreeingDevice(String status) {
        String task=UUID.randomUUID().toString(), previous=UUID.randomUUID().toString();
        when(edges.openTask("device")).thenReturn(Map.of("task_id",task,"status","ENDING","end_command_id",previous));
        when(edges.command(previous)).thenReturn(command(status,"EO_END_TRACK"));
        String next=service.enqueue(binding,EoEdgeCommandService.END,EoEdgeCommandService.TOPIC_END,"operator retry","operator");
        assertThat(next).isNotEqualTo(previous);
        verify(edges).updateTask(task,"ENDING","ENDING",next,now);
        verify(edges).addOutbox(anyString(),eq(EoEdgeCommandService.TOPIC_END),eq(next),eq(now));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"QUEUED","SENT","ACCEPTED","SUCCEEDED"})
    void explicitStopNeverDuplicatesPendingOrCompletedCommand(String status) {
        when(edges.openTask("device")).thenReturn(Map.of("task_id","t","status","ENDING","end_command_id","end"));
        when(edges.command("end")).thenReturn(command(status,"EO_END_TRACK"));
        assertThat(service.enqueue(binding,EoEdgeCommandService.END,EoEdgeCommandService.TOPIC_END,"retry","operator")).isEqualTo("end");
        verify(edges,never()).addOutbox(anyString(),anyString(),anyString(),anyLong());
    }
    @Test void automaticStopNeverRetriesTimedOutCommand() {
        when(edges.openTask("device")).thenReturn(Map.of("task_id","t","status","ENDING","end_command_id","end"));
        when(edges.command("end")).thenReturn(command("TIMED_OUT","EO_END_TRACK"));
        assertThat(service.enqueue(binding,EoEdgeCommandService.END,EoEdgeCommandService.TOPIC_END,"auto stop")).isEqualTo("end");
        verify(edges,never()).addOutbox(anyString(),anyString(),anyString(),anyLong());
    }
    private Map<String,Object> command(String status,String type) {return Map.of("command_id","c","status",status,"device_id","device","command_type",type,"deadline_at",now+10000,"source_mode","replay","simulated",true);}
}
