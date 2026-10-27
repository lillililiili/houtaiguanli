package com.uav.lowaltitude.modules.device.domain;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;

class EoTrackingContinuityTest {
    @ParameterizedTest @CsvSource({"live,false","replay,true"})
    void newerLossInvalidatesOldReportAndOnlyNewTrackingReacquires(String mode,boolean simulated) {
        long now=100000;
        String target=UUID.randomUUID().toString(),device=UUID.randomUUID().toString(),command=UUID.randomUUID().toString();
        var identity=new EoTrackingContinuity.Target(target,mode,"org","area","UAV","STABLE");
        var binding=new Binding(device,"fusion-device","ops-source","source","edge","broker","camera",mode,true,now,1,"org","area");
        var task=new HashMap<String,Object>(Map.of("target_id",target,"ops_device_id",device,"status","OPEN",
                "begin_command_id",command,"created_at",now-5000,"last_report_observed_at",now-1000,"last_report_at",now-1000));
        var receipt=Map.<String,Object>of("command_id",command,"command_type","EO_BEGIN_TRACK","device_id",device,
                "source_mode",mode,"simulated",simulated,"status","SUCCEEDED","issued_at",now-4000);
        assertThat(EoTrackingContinuity.block(identity,task,binding,receipt,now,15000,30000)).isNull();
        task.put("last_failure_observed_at",now-500);
        assertThat(EoTrackingContinuity.block(identity,task,binding,receipt,now,15000,30000)).isEqualTo("EO_REPORT_STALE");
        task.put("last_report_observed_at",now);task.put("last_report_at",now);
        assertThat(EoTrackingContinuity.block(identity,task,binding,receipt,now,15000,30000)).isNull();
    }
}
