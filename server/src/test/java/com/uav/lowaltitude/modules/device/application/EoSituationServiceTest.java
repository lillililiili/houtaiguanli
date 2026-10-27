package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EoSituationServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1001,READY","1002,UNKNOWN","0,UNKNOWN"})
    void validationTimeFollowsEvidenceReadWithoutRelaxingFutureOrExpiry(long observed,String expected) {
        var devices=mock(DeviceService.class);
        var edges=mock(com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository.class);
        var clock=mock(com.uav.lowaltitude.platform.time.AppClock.class);
        var device=mock(DeviceService.DeviceSummary.class);
        String id=java.util.UUID.randomUUID().toString();
        when(device.deviceId()).thenReturn(id);when(device.sourceMode()).thenReturn("live");
        when(devices.list(any(),eq(1),eq(100),isNull())).thenReturn(new DeviceService.DevicePage(java.util.List.of(device),1,100,1));
        var time=new java.util.concurrent.atomic.AtomicLong(1000);
        when(clock.nowMillis()).thenAnswer(a->time.get());
        when(edges.cameraSnapshot(id)).thenAnswer(a->{
            // A new heartbeat commits after the request started but before its row is read.
            time.set(1001);
            return java.util.Map.of("camera_status_json","{\"platformObservedAt\":"+observed+",\"angleReference\":\"TRUE_NORTH\",\"angleUnit\":\"DEG\",\"calibrated\":true,\"panOrientAngle\":90,\"tiltOrientAngle\":0,\"hfov\":12,\"vfov\":8}");
        });
        var service=new EoSituationService(devices,edges,mock(com.uav.lowaltitude.modules.target.application.TargetReadService.class),
                mock(EoTrackingPolicy.class),json,clock,1000);
        var result=service.read(1,100);
        assertThat(result.checkedAt()).isEqualTo(1001);
        assertThat(result.items().get(0).status()).isEqualTo(expected);
        if("UNKNOWN".equals(expected))assertThat(result.items().get(0).azimuthDeg()).isNull();
        else assertThat(result.items().get(0).observedAt()).isLessThanOrEqualTo(result.checkedAt());
    }
    @Test void calibratedFreshTrueNorthDegreesAreRequired() throws Exception {
        var c=json.readTree("{\"platformObservedAt\":1000,\"angleReference\":\"TRUE_NORTH\",\"angleUnit\":\"DEG\",\"calibrated\":true,\"panOrientAngle\":359,\"tiltOrientAngle\":-20,\"hfov\":12,\"vfov\":8}");
        assertThat(EoSituationService.validate(c,1500,1000).reason()).isNull();
        assertThat(EoSituationService.validate(c,1500,1000).azimuth()).isEqualTo(359);
        assertThat(EoSituationService.validate(c,2500,1000).reason()).isEqualTo("CAMERA_REPORT_STALE");
        assertThat(EoSituationService.validate(c,999,1000).azimuth()).isNull();
        ((com.fasterxml.jackson.databind.node.ObjectNode)c).put("angleReference","MAGNETIC_NORTH");
        assertThat(EoSituationService.validate(c,1500,1000).reason()).isEqualTo("CAMERA_REFERENCE_UNKNOWN");
        ((com.fasterxml.jackson.databind.node.ObjectNode)c).put("angleReference","TRUE_NORTH").put("hfov",0);
        assertThat(EoSituationService.validate(c,1500,1000).reason()).isEqualTo("CAMERA_ANGLES_INVALID");
        assertThat(EoSituationService.validate(null,1500,1000).azimuth()).isNull();
    }
}
