package com.uav.lowaltitude.modules.flight.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.DeviceService.*;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.platform.time.AppClock;

class FlightDevicePreflightTest {
    final Instant now=Instant.parse("2026-09-15T01:30:00Z");
    DeviceService devices=mock(DeviceService.class);
    FlightReadRepository plans=mock(FlightReadRepository.class);
    SpatialFactPort spatial=mock(SpatialFactPort.class);
    FlightReadRepository.PlanRow plan=mock(FlightReadRepository.PlanRow.class);
    FlightDeviceCheckService service;
    @BeforeEach void setup() {
        service=new FlightDeviceCheckService(devices,plans,mock(AccessControlService.class),spatial,
            new AppClock(Clock.fixed(now,ZoneOffset.UTC)),BigDecimal.valueOf(5000),new MockEnvironment());
        when(plans.findPlan(eq("future-plan"),any())).thenReturn(plan);
        when(plan.sourceMode()).thenReturn("mock");when(plan.statusCode()).thenReturn("PENDING");
        when(plan.routeVersionId()).thenReturn("route");
        when(plan.startAt()).thenReturn(now.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        when(plan.endAt()).thenReturn(now.plusSeconds(7200).atOffset(ZoneOffset.UTC));
        when(plans.findRouteVersion(eq("route"),any())).thenReturn(mock(FlightReadRepository.RouteVersionRow.class));
        var device=mock(DeviceSummary.class);when(device.deviceId()).thenReturn("sensor");
        when(device.sourceMode()).thenReturn("mock");when(device.deviceTypeCode()).thenReturn("radar");
        when(device.enabled()).thenReturn(true);
        when(devices.list(any(),anyInt(),anyInt(),anyString())).thenReturn(new DevicePage(List.of(device),1,100,1));
        var detail=mock(DeviceDetail.class);when(detail.coordinateSystem()).thenReturn("WGS-84");
        when(detail.longitude()).thenReturn(BigDecimal.valueOf(118));when(detail.latitude()).thenReturn(BigDecimal.valueOf(37));
        when(devices.detail("sensor")).thenReturn(detail);
        when(spatial.distanceToRoute(any(),eq("route"))).thenReturn(new RouteDistance("route",BigDecimal.TEN,BigDecimal.TEN,null));
        when(devices.incidents(anyString(),any(),any(),anyInt(),anyInt())).thenReturn(new IncidentPage(List.of(),1,100,0));
        state("ONLINE","GOOD");
    }
    void state(String connectivity,String health) {
        long at=now.minusSeconds(5).toEpochMilli();
        when(devices.state("sensor")).thenReturn(new DeviceState("sensor",connectivity,"1",false,health,at,at,at,null,List.of(),true));
    }
    @Test void futurePlanChecksCurrentNormalDeviceWithoutTakeoffConclusion() {
        var result=service.read("future-plan");
        assertThat(result.rows()).hasSize(1);assertThat(result.complete()).isTrue();
        assertThat(result.conclusion()).isEqualTo("PREFLIGHT_DEVICE_NORMAL");
        assertThat(result.message()).doesNotContain("未按计划起飞");
    }
    @Test void futurePlanShowsCurrentFaultAndIgnoresClosedPastIncident() {
        state("ONLINE","BAD");
        when(devices.incidents(anyString(),any(),any(),anyInt(),anyInt())).thenReturn(new IncidentPage(List.of(
            new Incident("old","old","sensor","sensor","雷达","FAULT","HIGH","CLOSED",now.minusSeconds(900).toEpochMilli(),"旧故障",now.minusSeconds(600).toEpochMilli(),null,true,null)),1,100,1));
        var result=service.read("future-plan");
        assertThat(result.conclusion()).isEqualTo("PREFLIGHT_DEVICE_ABNORMAL");
        assertThat(result.rows().get(0).abnormal()).isTrue();
        assertThat(result.rows().get(0).incidents()).isEmpty();
    }
    @Test void futurePlanWithUnknownStateCannotBeNormal() {
        state("UNKNOWN","UNKNOWN");
        assertThat(service.read("future-plan").conclusion()).isEqualTo("CHECK_INCOMPLETE");
    }
    @Test void startedPlanKeepsExistingTakeoffCheck() {
        when(plan.startAt()).thenReturn(now.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        assertThat(service.read("future-plan").conclusion()).isEqualTo("SUSPECTED_NOT_TAKEN_OFF");
    }
}
