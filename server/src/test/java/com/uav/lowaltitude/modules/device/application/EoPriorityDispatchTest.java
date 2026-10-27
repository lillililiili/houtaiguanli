package com.uav.lowaltitude.modules.device.application;

import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.device.infrastructure.*;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;

class EoPriorityDispatchTest {
    final EoDispatchRepository queue=mock(EoDispatchRepository.class);
    final EoTrackingRepository tracking=mock(EoTrackingRepository.class);
    final EoEdgeRepository edges=mock(EoEdgeRepository.class);
    final EoTrackingPolicy policy=mock(EoTrackingPolicy.class);
    final EoEdgeCommandService commands=mock(EoEdgeCommandService.class);
    final EoDispatchService dispatch=new EoDispatchService(queue,tracking,edges,policy,commands,
            mock(DeviceAccessPolicy.class),mock(com.uav.lowaltitude.modules.target.application.TargetReadService.class),
            mock(com.uav.lowaltitude.modules.identity.infrastructure.UserMapper.class),mock(com.uav.lowaltitude.platform.audit.AuditService.class),
            mock(com.uav.lowaltitude.modules.identity.application.AccessService.class),mock(com.uav.lowaltitude.modules.identity.application.AccessControlService.class));
    EoPriorityDispatchTest() {
        when(policy.now()).thenReturn(2000L);when(policy.enabledFor(any())).thenReturn(true);
        when(policy.demand(anyString())).thenReturn(List.of(new EoTrackingPolicy.DemandReason("ALARM_VERIFY","待核实")));
        when(tracking.tryLock(anyString())).thenReturn(true);
        when(queue.devices(anyString(),anyString(),anyString(),anyLong(),anyLong())).thenReturn(List.of("camera-a","camera-b"));
        when(tracking.coverage(anyString())).thenReturn(Map.of("longitude",118d,"latitude",37d,"coordinate_system","WGS-84",
                "coverage_kind","CIRCLE","radius_m",2000));
    }
    Map<String,Object> request(String target) {
        var snapshot=Map.<String,Object>of("target_id",target,"owner_org_id","org","district_id","area","source_mode","replay",
                "position","POINT (118 37.001)","srid",4326);
        when(tracking.snapshot(target)).thenReturn(snapshot);
        return Map.of("request_id",UUID.randomUUID().toString(),"target_id",target,"origin","AUTO","status","WAITING");
    }
    @Test void priorityThenDurableFifoAssignsTwoDifferentCamerasAndLeavesThirdWaiting() {
        String low=UUID.randomUUID().toString(),high=UUID.randomUUID().toString(),equal=UUID.randomUUID().toString();
        var requests=List.of(request(low),request(high),request(equal));
        when(queue.waiting()).thenReturn(requests);
        when(queue.priority(eq(low),anyLong(),anyLong())).thenReturn(10);
        when(queue.priority(eq(high),anyLong(),anyLong())).thenReturn(50);
        when(queue.priority(eq(equal),anyLong(),anyLong())).thenReturn(50);
        Set<String> used=new HashSet<>();List<String> assigned=new ArrayList<>();
        when(edges.idleDeviceForMode(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong()))
                .thenAnswer(a->used.contains(a.getArgument(2))?null:binding(a.getArgument(2)));
        when(commands.enqueueBegin(any(),anyString(),anyString(),isNull(),anyString(),any(),isNull(),anyString()))
                .thenAnswer(a->{Binding b=a.getArgument(0);used.add(b.opsDeviceId());assigned.add(a.getArgument(2));return "command";});
        assertThat(dispatch.poll(10)).isEqualTo(2);
        assertThat(assigned).containsExactly(high,equal);
        assertThat(used).containsExactlyInAnyOrder("camera-a","camera-b");
    }
    @Test void equalPriorityAndManualTasksAreNeverAutomaticallyPreempted() {
        for(String origin:List.of("AUTO","MANUAL")) {
            String target=UUID.randomUUID().toString(),old=UUID.randomUUID().toString();
            var incoming=request(target);when(queue.waiting()).thenReturn(List.of(incoming));
            request(old);
            when(queue.priority(eq(target),anyLong(),anyLong())).thenReturn("MANUAL".equals(origin)?100:50);
            when(queue.priority(eq(old),anyLong(),anyLong())).thenReturn(50);
            when(edges.openTask(anyString())).thenAnswer(a->Map.of("task_id","old-task","target_id",old,"ops_device_id",a.getArgument(0),"origin",origin,"status","OPEN"));
            dispatch.poll(10);
        }
        verify(queue,never()).reserve(anyString(),anyString(),anyString(),anyLong());
        verify(commands,never()).enqueueBegin(any(),anyString(),anyString(),any(),anyString(),any(),any(),anyString());
    }
    @Test void missingOrOutsideCoverageNeverAllocatesDevice() {
        var incoming=request(UUID.randomUUID().toString());when(queue.waiting()).thenReturn(List.of(incoming));
        when(tracking.coverage("camera-a")).thenReturn(null);
        when(tracking.coverage("camera-b")).thenReturn(Map.of("longitude",119d,"latitude",38d,"coordinate_system","WGS-84","coverage_kind","CIRCLE","radius_m",2000));
        assertThat(dispatch.poll(10)).isZero();
        verify(edges,never()).idleDeviceForMode(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
    }
    @Test void higherPriorityStopsOrdinaryAutomaticTaskWithoutStartingBeforeReceipt() {
        String target=UUID.randomUUID().toString(),old=UUID.randomUUID().toString();
        var incoming=request(target);request(old);
        when(queue.waiting()).thenReturn(List.of(incoming));
        when(queue.priority(eq(target),anyLong(),anyLong())).thenReturn(100);
        when(queue.priority(eq(old),anyLong(),anyLong())).thenReturn(10);
        var occupied=Map.<String,Object>of("task_id","old-task","target_id",old,"ops_device_id","camera-a","origin","AUTO","status","OPEN");
        when(edges.openTask("camera-a")).thenReturn(occupied);
        when(edges.binding("camera-a",true)).thenReturn(binding("camera-a"));
        assertThat(dispatch.poll(10)).isEqualTo(1);
        verify(queue).reserve(incoming.get("request_id").toString(),"camera-a","old-task",2000);
        verify(commands).enqueue(any(),eq(EoEdgeCommandService.END),eq(EoEdgeCommandService.TOPIC_END),eq("EO_DISPATCH_SWITCH"),isNull());
        verify(commands,never()).enqueueBegin(any(),anyString(),anyString(),any(),anyString(),any(),any(),anyString());
    }
    private Binding binding(String device) {return new Binding(device,"fusion-"+device,"source","source","edge","broker",device,"replay",true,2000L,0,"org","area");}
}
