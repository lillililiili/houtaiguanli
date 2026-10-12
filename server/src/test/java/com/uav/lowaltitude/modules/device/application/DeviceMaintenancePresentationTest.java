package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.Recovery;
import org.junit.jupiter.api.Test;

class DeviceMaintenancePresentationTest {
    @Test void livePassWaitsForPersistedReadinessWithoutGrantingPrematureCompletion() {
        var pass=new Recovery("PASS","设备已恢复",1);
        assertThat(DeviceMaintenancePresentation.next("PROCESSING",pass)).contains("核验通过","更新办理状态");
        assertThat(DeviceMaintenancePresentation.primary("PROCESSING",pass,true)).isNull();
        assertThat(DeviceMaintenancePresentation.primary("READY_TO_COMPLETE",pass,true)).isEqualTo("CONFIRM_COMPLETE");
        assertThat(DeviceMaintenancePresentation.primary("READY_TO_COMPLETE",pass,false)).isNull();
    }
    @Test void expiredEvidenceRevokesCompletionAndGivesTheCurrentBlocker() {
        var stale=new Recovery("UNKNOWN","健康上报已过期，等待设备重新上报",1);
        assertThat(DeviceMaintenancePresentation.primary("READY_TO_COMPLETE",stale,true)).isNull();
        assertThat(DeviceMaintenancePresentation.next("READY_TO_COMPLETE",stale)).contains("健康上报已过期");
    }
}
