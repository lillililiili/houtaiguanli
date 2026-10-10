package com.uav.lowaltitude.modules.flight.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import com.uav.lowaltitude.modules.device.application.DeviceService;
import com.uav.lowaltitude.modules.device.application.DeviceService.*;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.flight.api.LocalPlanFilingDtos;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.platform.time.AppClock;

class FlightDevicePreflightTest {
    final Instant now=Instant.parse("2026-09-15T01:30:00Z");
    DeviceService devices=mock(DeviceService.class);
    FlightReadRepository plans=mock(FlightReadRepository.class);
    SpatialFactPort spatial=mock(SpatialFactPort.class);
    FlightReadRepository.PlanRow plan=mock(FlightReadRepository.PlanRow.class);
    DeviceSummary device;
    DeviceDetail detail;
    DeviceState observedState;
    List<Incident> incidents=List.of();
    FlightDeviceCheckService service;
    @BeforeEach void setup() {
        var environment=new MockEnvironment().withProperty("app.flight-device-check.simulator-device-bridge-enabled","true");
        environment.setActiveProfiles("local");
        service=new FlightDeviceCheckService(devices,plans,mock(AccessControlService.class),spatial,
            new AppClock(Clock.fixed(now,ZoneOffset.UTC)),environment);
        when(plans.findPlan(eq("future-plan"),any())).thenReturn(plan);
        when(plan.sourceMode()).thenReturn("mock");when(plan.statusCode()).thenReturn("PENDING");
        when(plan.routeVersionId()).thenReturn("route");
        when(plan.startAt()).thenReturn(now.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        when(plan.endAt()).thenReturn(now.plusSeconds(7200).atOffset(ZoneOffset.UTC));
        when(plans.findRouteVersion(eq("route"),any())).thenReturn(mock(FlightReadRepository.RouteVersionRow.class));
        device=mock(DeviceSummary.class);when(device.deviceId()).thenReturn("sensor");
        when(device.sourceMode()).thenReturn("mock");when(device.deviceTypeCode()).thenReturn("radar");
        when(device.enabled()).thenReturn(true);
        when(device.coverage()).thenReturn(new Coverage("CIRCLE","AVAILABLE",BigDecimal.valueOf(1000),null,null,null,null,"测试配置",null,1L));
        detail=mock(DeviceDetail.class);when(detail.coordinateSystem()).thenReturn("WGS-84");
        when(detail.longitude()).thenReturn(BigDecimal.valueOf(118));when(detail.latitude()).thenReturn(BigDecimal.valueOf(37));
        when(devices.inspectPlanDevices(any(),any(),eq(false))).thenAnswer(call -> List.of(
            new PlanInspectionDevice(device,detail.longitude(),detail.latitude(),detail.coordinateSystem(),observedState,incidents)));
        when(spatial.distanceToRoute(any(),eq("route"))).thenReturn(new RouteDistance("route",BigDecimal.TEN,BigDecimal.TEN,null));
        state("ONLINE","GOOD");
    }
    void state(String connectivity,String health) {
        long at=now.minusSeconds(5).toEpochMilli();
        observedState=new DeviceState("sensor",connectivity,"1",false,health,at,at,at,null,List.of(),true);
    }
    @Test void futurePlanChecksCurrentNormalDeviceWithoutTakeoffConclusion() {
        var result=service.read("future-plan");
        assertThat(result.rows()).hasSize(1);assertThat(result.complete()).isTrue();
        assertThat(result.conclusion()).isEqualTo("PREFLIGHT_DEVICE_NORMAL");
        assertThat(result.message()).doesNotContain("未按任务起飞");
    }
    @Test void futurePlanShowsCurrentFaultAndIgnoresClosedPastIncident() {
        state("ONLINE","BAD");
        incidents=List.of(new Incident("old","old","sensor","sensor","雷达","FAULT","HIGH","CLOSED",now.minusSeconds(900).toEpochMilli(),"旧故障",now.minusSeconds(600).toEpochMilli(),null,true,null));
        var result=service.read("future-plan");
        assertThat(result.conclusion()).isEqualTo("PREFLIGHT_DEVICE_ABNORMAL");
        assertThat(result.rows().get(0).abnormal()).isTrue();
        assertThat(result.rows().get(0).incidents()).isEmpty();
    }
    @Test void futurePlanWithUnknownStateCannotBeNormal() {
        state("UNKNOWN","UNKNOWN");
        assertThat(service.read("future-plan").conclusion()).isEqualTo("CHECK_INCOMPLETE");
    }
    @Test void lingyunWorkingStateDoesNotReplaceUnknownHealth() {
        var sensor=device;
        when(sensor.protocolCode()).thenReturn("LINGYUN_MQTT_V8_6");
        state("ONLINE","UNKNOWN");
        var result=service.read("future-plan");
        assertThat(result.rows().get(0).healthCode()).isEqualTo("UNKNOWN");
        assertThat(result.rows().get(0).complete()).isFalse();
        assertThat(result.conclusion()).isEqualTo("CHECK_INCOMPLETE");
        when(plan.startAt()).thenReturn(now.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        assertThat(service.read("future-plan").conclusion()).isEqualTo("CHECK_INCOMPLETE");
    }
    @Test void lingyunExplicitFaultStillCountsAsAbnormal() {
        var sensor=device;
        when(sensor.protocolCode()).thenReturn("LINGYUN_MQTT_V8_6");
        long at=now.minusSeconds(5).toEpochMilli();
        observedState=new DeviceState("sensor","ONLINE","2",false,"UNKNOWN",at,at,at,null,List.of(),true);
        var result=service.read("future-plan");
        assertThat(result.rows().get(0).abnormal()).isTrue();
        assertThat(result.rows().get(0).healthCode()).isEqualTo("BAD");
        assertThat(result.conclusion()).isEqualTo("PREFLIGHT_DEVICE_ABNORMAL");
    }
    @Test void localSimulatorMockPlanChecksNearbyReplayDeviceThroughExplicitBridge() {
        when(plan.sourceId()).thenReturn(LocalPlanFilingDtos.SIMULATOR_SOURCE_ID);
        when(device.sourceMode()).thenReturn("replay");
        when(device.simulated()).thenReturn(true);

        var result=service.read("future-plan");

        assertThat(result.rows()).hasSize(1);
        assertThat(result.mqttSimulation()).isTrue();
        assertThat(result.conclusion()).isEqualTo("PREFLIGHT_DEVICE_NORMAL");
    }
    @Test void livePlanChecksLiveDeviceWithoutUsingSimulatorBridge() {
        when(plan.sourceId()).thenReturn("real-flight-source");
        when(plan.sourceMode()).thenReturn("live");
        when(device.sourceMode()).thenReturn("live");
        when(device.simulated()).thenReturn(false);

        var result=service.read("future-plan");

        assertThat(result.rows()).hasSize(1);
        assertThat(result.mqttSimulation()).isFalse();
        assertThat(result.conclusion()).isEqualTo("PREFLIGHT_DEVICE_NORMAL");
    }
    @Test void startedPlanKeepsExistingTakeoffCheck() {
        when(plan.startAt()).thenReturn(now.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        assertThat(service.read("future-plan").conclusion()).isEqualTo("SUSPECTED_NOT_TAKEN_OFF");
    }
    @Test void replayPlanIgnoresDisabledHistoricalReplayDevices() {
        when(plan.sourceMode()).thenReturn("replay");
        var sensor=device;
        when(sensor.sourceMode()).thenReturn("replay");
        when(sensor.simulated()).thenReturn(false);
        when(sensor.enabled()).thenReturn(false);

        var result=service.read("future-plan");

        assertThat(result.rows()).isEmpty();
        assertThat(result.conclusion()).isEqualTo("CHECK_INCOMPLETE");
    }

    @ParameterizedTest
    @ValueSource(ints={200,1000,1001,3000,8000})
    void usesDeviceRadiusInsteadOfFixedNearbyDistance(int distance) {
        when(spatial.distanceToRoute(any(),eq("route"))).thenReturn(new RouteDistance("route",BigDecimal.valueOf(distance),BigDecimal.TEN,null));
        assertThat(service.read("future-plan").rows()).hasSize(distance<=1000?1:0);
    }

    @Test void longRangeDeviceBeyondFiveKilometresStillCoversRoute() {
        when(device.coverage()).thenReturn(new Coverage("CIRCLE","AVAILABLE",BigDecimal.valueOf(10000),null,null,null,null,"测试配置",null,1L));
        when(spatial.distanceToRoute(any(),eq("route"))).thenReturn(new RouteDistance("route",BigDecimal.valueOf(8000),BigDecimal.TEN,null));
        assertThat(service.read("future-plan").rows()).hasSize(1);
    }

    @Test void missingCoverageDoesNotFallBackToFiveKilometres() {
        when(device.coverage()).thenReturn(null);
        var result=service.read("future-plan");
        assertThat(result.rows()).isEmpty();
        assertThat(result.complete()).isFalse();
    }

    @Test void offlineDeviceStillBelongsToConfiguredCoverage() {
        when(device.coverage()).thenReturn(new Coverage("CIRCLE","UNAVAILABLE",BigDecimal.valueOf(1000),null,null,null,"设备非在线","测试配置",null,1L));
        state("OFFLINE","UNKNOWN");
        var result=service.read("future-plan");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).abnormal()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void sectorUsesWholeRouteIntersection(boolean intersects) {
        when(device.coverage()).thenReturn(new Coverage("SECTOR","AVAILABLE",null,BigDecimal.valueOf(1000),BigDecimal.ZERO,BigDecimal.valueOf(60),null,"测试配置",null,1L));
        when(plans.routeIntersectsScanSector(eq("route"),any(),any(),any(),any(),any())).thenReturn(intersects);
        assertThat(service.read("future-plan").rows()).hasSize(intersects?1:0);
        verify(plans).routeIntersectsScanSector("route",BigDecimal.valueOf(118),BigDecimal.valueOf(37),
            BigDecimal.valueOf(1000),BigDecimal.ZERO,BigDecimal.valueOf(60));
    }

    @ParameterizedTest
    @ValueSource(ints={0,-1})
    void invalidRadiusIsUnknownInsteadOfUnlimitedCoverage(int radius) {
        when(device.coverage()).thenReturn(new Coverage("CIRCLE","AVAILABLE",BigDecimal.valueOf(radius),null,null,null,null,"测试配置",null,1L));
        var result=service.read("future-plan");
        assertThat(result.rows()).isEmpty();
        assertThat(result.uncheckedCoverage()).isEqualTo(1);
        assertThat(result.selectionBasis()).isEqualTo("DEVICE_SCAN_COVERAGE");
    }

    @Test void incompleteSectorIsUnknown() {
        when(device.coverage()).thenReturn(new Coverage("SECTOR","AVAILABLE",null,BigDecimal.valueOf(1000),null,BigDecimal.valueOf(60),null,"测试配置",null,1L));
        assertThat(service.read("future-plan").uncheckedCoverage()).isEqualTo(1);
    }

    @Test void scheduledAndManualChecksShareCoverageSelection() {
        var current=devices.state("sensor");
        when(devices.inspectPlanDevices(any(),any(),eq(true))).thenReturn(List.of(
            new PlanInspectionDevice(device,BigDecimal.valueOf(118),BigDecimal.valueOf(37),"WGS-84",current,List.of())));
        assertThat(service.scheduled(plan).rows()).hasSize(1);
        when(spatial.distanceToRoute(any(),eq("route"))).thenReturn(new RouteDistance("route",BigDecimal.valueOf(1500),BigDecimal.TEN,null));
        assertThat(service.scheduled(plan).rows()).isEmpty();
        assertThat(service.read("future-plan").rows()).isEmpty();
    }

    @Test void livePlanCannotUseMockCoverage() {
        when(plan.sourceMode()).thenReturn("live");
        assertThat(service.read("future-plan").rows()).isEmpty();
        verify(devices,never()).detail(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings={"RADAR","EO","TDOA","FIVE_G_A","FUSION_BOX"})
    void confirmedDetectorTypesAreCheckedAndUnknownPositionIsCountedSeparately(String type) {
        when(device.deviceTypeCode()).thenReturn(type);
        assertThat(service.read("future-plan").rows()).hasSize(1);
        when(detail.longitude()).thenReturn(null);
        var unknown=service.read("future-plan");
        assertThat(unknown.rows()).isEmpty();
        assertThat(unknown.uncheckedLocations()).isEqualTo(1);
        assertThat(unknown.complete()).isFalse();
    }

    @Test void disabledLiveDetectorIsAbnormalWhileCountermeasureIsExcluded() {
        when(plan.sourceMode()).thenReturn("live");
        when(device.sourceMode()).thenReturn("live");
        when(device.enabled()).thenReturn(false);
        var checked=service.read("future-plan");
        assertThat(checked.rows()).hasSize(1);
        assertThat(checked.rows().get(0).abnormal()).isTrue();
        assertThat(checked.rows().get(0).connectivity()).isEqualTo("DISABLED");
        when(device.deviceTypeCode()).thenReturn("COUNTERMEASURE");
        assertThat(service.read("future-plan").rows()).isEmpty();
    }
}
