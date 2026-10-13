package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;

class EoFusionTaskContextAdapterTest {
    private final EoEdgeRepository edges=mock(EoEdgeRepository.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final EoTrackingPolicy policy=mock(EoTrackingPolicy.class);
    private final EoFusionTaskContextAdapter adapter=new EoFusionTaskContextAdapter(edges,jdbc,policy);
    private final Map<String,Object> open=Map.of("status","OPEN","ops_device_id","ops-a","target_id","target-a");
    @Test void consumeLocksTargetThenBindingAndTaskAndRereadsStatus() {
        when(edges.task("task-a")).thenReturn(open,Map.of("status","ENDED"));
        when(edges.binding("ops-a",true)).thenReturn(new Binding("ops-a","device-a","os","source-a","edge-a","broker","external","replay",true,100L,1,"org","district"));
        var context=adapter.lookupForFusion("task-a","source-a","device-a","replay","org","district",100,100);
        assertThat(context.exclusionReason()).isEqualTo("EO_TASK_NOT_OPEN");
        var order=inOrder(edges,jdbc);
        order.verify(edges).task("task-a");
        order.verify(jdbc).queryForList("SELECT target_id FROM target WHERE target_id=? FOR UPDATE","target-a");
        order.verify(edges).binding("ops-a",true);
        order.verify(jdbc).queryForList("SELECT task_id FROM eo_tracking_task WHERE task_id=? FOR UPDATE","task-a");
        order.verify(edges).task("task-a");
        verify(edges,never()).command(any());
    }
    @Test void readonlyLookupDoesNotAcquireMutationLocks() {
        when(edges.task("task-b")).thenReturn(Map.of("status","ENDING"));
        assertThat(adapter.lookup("task-b","source","device","live","org","district",100,100).accepted()).isFalse();
        verifyNoInteractions(jdbc);
        verify(edges,never()).binding(anyString(),eq(true));
    }
}
