package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoFusionObservationRepository;
import com.uav.lowaltitude.modules.fusion.application.EoFusionTaskContextPort;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigLoader;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.platform.time.AppClock;

class EoFusionObservationSummaryServiceTest {
    private final EoEdgeRepository edges=mock(EoEdgeRepository.class);
    private final EoFusionObservationRepository observations=mock(EoFusionObservationRepository.class);
    private final EoFusionTaskContextPort tasks=mock(EoFusionTaskContextPort.class);
    private final FusionConfigLoader config=mock(FusionConfigLoader.class);
    private final AppClock clock=mock(AppClock.class);
    private final long now=Instant.parse("2026-10-11T09:10:00Z").toEpochMilli();
    private final Map<String,Object> task=Map.of("status","OPEN","task_id","task-a","ops_device_id","ops-a","target_id","target-a");
    private final EoFusionObservationSummaryService service=new EoFusionObservationSummaryService(edges,observations,tasks,config,clock,new ObjectMapper());
    @BeforeEach void setup() {
        when(edges.binding("ops-a",false)).thenReturn(new Binding("ops-a","device-a","ops-source-a","source-a","edge-a","broker-a","external-a","replay",true,now,1,"org-a","district-a"));
        when(clock.nowMillis()).thenReturn(now);
        FusionParams params=mock(FusionParams.class);
        when(params.integer("identity","short_lost_after_ms")).thenReturn(3000);
        when(config.active()).thenReturn(params);
    }
    private void evidence(long time,String quality) {
        when(observations.latest("source-a","device-a","task-a")).thenReturn(new EoFusionObservationRepository.Evidence(Instant.ofEpochMilli(time),Instant.ofEpochMilli(time),quality));
    }
    @Test void noAiEvidenceIsNoneDespiteAnOpenTask() {
        assertThat(service.current(task).status()).isEqualTo("NONE");
        verifyNoInteractions(tasks);
    }
    @Test void unlinkedMissingPositionEvidenceIsRejectedRatherThanCurrent() {
        evidence(now,"{\"eo_task_id\":\"task-a\",\"eo_fusion_exclusion\":\"EO_POSITION_UNKNOWN\"}");
        assertThat(service.current(task).status()).isEqualTo("REJECTED");
        assertThat(service.current(task).message()).contains("尚无可用位置");
        verifyNoInteractions(tasks);
    }
    @Test void acceptedEvidenceExpiresWithoutInterpretingHeartbeatAsObservation() {
        evidence(now-3001,"{\"eo_task_id\":\"task-a\",\"eo_target_id\":\"target-a\"}");
        assertThat(service.current(task).status()).isEqualTo("EXPIRED");
        verifyNoInteractions(tasks);
    }
    @Test void futureObservationTimeRemainsUnknownRatherThanExpired() {
        evidence(now+1,"{\"eo_task_id\":\"task-a\",\"eo_target_id\":\"target-a\"}");
        assertThat(service.current(task).status()).isEqualTo("UNKNOWN");
        assertThat(service.current(task).message()).contains("时间待确认");
        verifyNoInteractions(tasks);
    }
    @Test void currentEvidenceRequiresCurrentTaskContext() {
        evidence(now,"{\"eo_task_id\":\"task-a\",\"eo_target_id\":\"target-a\"}");
        when(tasks.lookup("task-a","source-a","device-a","replay","org-a","district-a",now,now))
                .thenReturn(new EoFusionTaskContextPort.Context("target-a",null));
        assertThat(service.current(task).status()).isEqualTo("CURRENT");
        when(tasks.lookup("task-a","source-a","device-a","replay","org-a","district-a",now,now))
                .thenReturn(new EoFusionTaskContextPort.Context(null,"EO_TASK_TARGET_CHANGED"));
        assertThat(service.current(task).status()).isEqualTo("REJECTED");
    }
    @Test void historicalAndEndingTasksDoNotDisplayCurrentObservationSummary() {
        assertThat(service.current(Map.of("status","ENDED"))).isNull();
        assertThat(service.current(Map.of("status","ENDING"))).isNull();
        verifyNoInteractions(observations,tasks);
    }
}
