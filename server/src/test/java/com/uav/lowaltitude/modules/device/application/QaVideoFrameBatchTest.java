package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.TargetVideoController.TargetVideoDto;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

class QaVideoFrameBatchTest {
    final String device=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),target=UUID.randomUUID().toString();
    final long now=10000;
    final AppClock clock=mock(AppClock.class);
    final EoEdgeRepository edges=mock(EoEdgeRepository.class);
    final DeviceAccessPolicy access=mock(DeviceAccessPolicy.class);
    final DeviceVideoService video=mock(DeviceVideoService.class);
    final TargetVideoService targets=mock(TargetVideoService.class);
    final EoTrackingPolicy policy=mock(EoTrackingPolicy.class);
    final VideoStreamRegistry streams;
    final VideoStreamRegistry.Stream stream;
    final VideoDetectionAssociationService associations;
    final QaDeviceVideoService service;
    final Map<String,Object> current;
    QaVideoFrameBatchTest() {
        when(clock.nowMillis()).thenReturn(now);
        var env=new MockEnvironment();env.setActiveProfiles("test");
        streams=new VideoStreamRegistry(env,clock,true);stream=streams.register(task,target,device,"replay");
        var binding=new Binding(device,"fusion-device","ops-source","source","edge","broker","camera","replay",true,now,1,"org","district");
        when(edges.binding(device,false)).thenReturn(binding);
        when(video.authorize(device)).thenReturn(Map.of("enabled",true,"simulated",true,"source_enabled",true,"source_id","ops-source","source_mode","replay"));
        current=Map.of("task_id",task,"target_id",target,"status","OPEN");when(edges.currentTaskByDevice(device)).thenReturn(current);
        var status=mock(TargetVideoDto.class);when(status.status()).thenReturn("TRACKING");when(status.deviceId()).thenReturn(device);when(status.taskId()).thenReturn(task);
        when(targets.tracking(target)).thenReturn(status);
        associations=spy(new VideoDetectionAssociationService(mock(org.springframework.jdbc.core.JdbcTemplate.class),edges,new ObjectMapper(),clock));
        doReturn(List.of(new VideoDetectionAssociationService.Association("observation",now-3000,"UAV",target,"link","{}"))).when(associations).current(stream);
        service=new QaDeviceVideoService(access,video,streams,edges,targets,associations,
                mock(com.uav.lowaltitude.platform.audit.AuditService.class),clock,mock(com.uav.lowaltitude.modules.target.application.TargetReadService.class),policy);
    }
    QaDeviceVideoService.BatchFrameInput frame(long at,long media,double width) {
        return new QaDeviceVideoService.BatchFrameInput(at,media,List.of(new QaDeviceVideoService.DetectionInput("box",0.1,0.1,width,0.2,"UAV",0.8,"observation")));
    }
    QaDeviceVideoService.BatchInput batch(List<QaDeviceVideoService.BatchFrameInput> frames) {return new QaDeviceVideoService.BatchInput(stream.streamId(),task,frames);}
    @Test void partialResultsKeepActualAssociationsAndRejectStaleDuplicateInvalidAndFutureIndividually() {
        var result=service.batch(device,batch(List.of(frame(now-5001,0,.2),frame(now-3000,10,.2),frame(now-3000,10,.2),
                frame(now-2000,20,1.2),frame(now-1000,30,.2),frame(now,40,.2),frame(now+1,50,.2))));
        assertThat(result.acceptedCount()).isEqualTo(3);assertThat(result.rejectedCount()).isEqualTo(4);
        assertThat(result.frames()).extracting(QaDeviceVideoService.FrameResult::accepted).containsExactly(false,true,false,false,true,true,false);
        assertThat(result.frames().get(1).detections().get(0).associationStatus()).isEqualTo("LINKED");
        assertThat(result.frames().get(4).detections().get(0).associationStatus()).isEqualTo("LINKED");
        assertThat(result.frames().get(5).detections().get(0).associationStatus()).isEqualTo("UNLINKED");
        assertThat(result.frames().get(0).errorCode()).isEqualTo("VIDEO_FRAME_STALE");
        assertThat(result.frames().get(2).errorCode()).isEqualTo("VIDEO_FRAME_NON_MONOTONIC");
        assertThat(result.frames().get(6).errorCode()).isEqualTo("VIDEO_FRAME_FUTURE");
        assertThat(streams.frames(stream)).hasSize(3);verify(associations,times(1)).current(stream);
    }
    @Test void midBatchTaskChangeRejectsRemainingFramesWithoutUndoingEarlierAcceptance() {
        when(edges.currentTaskByDevice(device)).thenReturn(current,current,current,Map.of("task_id",UUID.randomUUID().toString(),"target_id",target,"status","OPEN"));
        var result=service.batch(device,batch(List.of(frame(now-3000,10,.2),frame(now-2000,20,.2),frame(now-1000,30,.2))));
        assertThat(result.frames()).extracting(QaDeviceVideoService.FrameResult::accepted).containsExactly(true,false,false);
        assertThat(result.frames().get(1).errorCode()).isEqualTo("VIDEO_TASK_MISMATCH");
        assertThat(result.frames().get(2).errorCode()).isEqualTo("VIDEO_TASK_MISMATCH");
        assertThat(streams.frames(stream)).hasSize(1);
    }
    @Test void boundedBatchAndInitialSourceAndPermissionGuardsRemainWholeRequestErrors() {
        assertThatThrownBy(()->service.batch(device,batch(Collections.nCopies(31,frame(now,1,.2))))).isInstanceOf(ApiException.class);
        when(video.authorize(device)).thenReturn(Map.of("enabled",true,"simulated",false,"source_enabled",true,"source_id","ops-source","source_mode","replay"));
        assertThatThrownBy(()->service.batch(device,batch(List.of(frame(now,1,.2))))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("VIDEO_TASK_MISMATCH"));
        verify(associations,never()).current(stream);assertThat(streams.frames(stream)).isEmpty();
    }
    @Test void lineageOrAliasChangeRejectsRemainingFramesEvenIfTaskRemainsOpen() {
        when(policy.taskIdentityChanged(current)).thenReturn(false,true);
        var result=service.batch(device,batch(List.of(frame(now-3000,10,.2),frame(now-2000,20,.2),frame(now-1000,30,.2))));
        assertThat(result.frames()).extracting(QaDeviceVideoService.FrameResult::accepted).containsExactly(true,false,false);
        assertThat(result.frames().get(1).errorCode()).isEqualTo("VIDEO_TASK_MISMATCH");
        assertThat(streams.frames(stream)).hasSize(1);verify(associations,times(1)).current(stream);
    }
    @Test void permissionRevocationRejectsCurrentAndRemainingFramesExplicitly() {
        when(access.requireDevicesOperate()).thenReturn(null,null).thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"FORBIDDEN","权限已收回"));
        var result=service.batch(device,batch(List.of(frame(now-3000,10,.2),frame(now-2000,20,.2),frame(now-1000,30,.2))));
        assertThat(result.frames()).extracting(QaDeviceVideoService.FrameResult::accepted).containsExactly(true,false,false);
        assertThat(result.frames().get(1).errorCode()).isEqualTo("FORBIDDEN");
        assertThat(result.frames().get(2).errorCode()).isEqualTo("FORBIDDEN");
    }
}
