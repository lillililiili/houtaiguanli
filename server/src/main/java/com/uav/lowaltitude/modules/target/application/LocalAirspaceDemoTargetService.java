package com.uav.lowaltitude.modules.target.application;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.device.application.EoManualTrackService;
import com.uav.lowaltitude.modules.device.application.EoManualTrackService.EoTrackingTask;
import com.uav.lowaltitude.integration.mock.LocalAirspaceRiskDemoSeeder;
import com.uav.lowaltitude.modules.target.infrastructure.LocalAirspaceDemoTargetRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** Local, explicitly simulated monitor samples. No flight_risk or risk workflow is created. */
@Service
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
public class LocalAirspaceDemoTargetService {
    // Same fixed sample catalog and seven frames as airspaceMonitorDemo.js.
    private record Sample(String key, String name, boolean bird, double lon, double lat,
                          double east, double north, double dx, double dy, double height, double dh) { }
    private static final List<Sample> SAMPLES = List.of(
        new Sample("dock-birds", "机巢东侧鸟群", true, 118.78, 37.58, 320, 100, -30, -8, 86, 5),
        new Sample("dock-balloon", "机巢北侧气球", false, 118.78, 37.58, -150, 250, -10, 15, 160, 0),
        new Sample("facility-birds", "设施西侧鸟群", true, 118.79, 37.568, -680, 150, 65, -10, 74, -4),
        new Sample("facility-balloon", "设施南侧气球", false, 118.79, 37.568, 250, -480, 0, 0, 110, 7),
        new Sample("route-birds", "航线东侧鸟群", true, 118.762, 37.554, 720, 1900, -85, 18, 120, 0),
        new Sample("route-balloon", "航线西侧气球", false, 118.762, 37.554, -200, 3100, -40, -15, 230, -6),
        new Sample("airspace-birds", "空域西侧鸟群", true, 118.8, 37.574, -120, 850, 40, 0, 95, 2),
        new Sample("airspace-balloon", "空域内气球", false, 118.8, 37.574, 260, 350, 10, 5, 180, 3));

    private final LocalAirspaceDemoTargetRepository repository;
    private final DeviceAccessPolicy devices;
    private final AccessControlService access;
    private final TargetReadService targets;
    private final EoManualTrackService tracking;
    private final AppClock clock;

    public LocalAirspaceDemoTargetService(LocalAirspaceDemoTargetRepository repository, DeviceAccessPolicy devices, AccessControlService access,
            TargetReadService targets, EoManualTrackService tracking, AppClock clock) {
        this.repository = repository; this.devices = devices; this.access = access; this.targets = targets;
        this.tracking = tracking; this.clock = clock;
    }

    @Transactional
    public PreparedTarget prepare(String targetId, Integer frame) {
        prepareFrame(targetId, frame);
        return new PreparedTarget(targetId);
    }

    @Transactional
    public EoTrackingTask begin(String targetId, Integer frame, String idempotencyKey) {
        // Keep the selected frame and command bootstrap in one transaction under the target lock.
        prepareFrame(targetId, frame);
        return tracking.begin(targetId, null, "模拟监测目标详情人工发起光电追踪", idempotencyKey);
    }

    private void prepareFrame(String targetId, Integer frame) {
        devices.requireDevicesOperate();
        access.require(PermissionCode.TARGET_READ);
        if (frame == null || frame < 0 || frame > 6)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "模拟帧须为 0 至 6");
        Sample sample = SAMPLES.stream().filter(s -> ("airspace-demo-" + s.key()).equals(targetId)).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEMO_TARGET_NOT_FOUND", "模拟目标不存在"));
        var scope = LocalAirspaceRiskDemoSeeder.CAMERAS.get(1);
        Timestamp now = new Timestamp(clock.nowMillis());
        repository.createAndLock(targetId, sample.name(), sample.bird(), scope.org(), scope.district(), now);
        // Uses the same target read permission and organization/district filtering as regular targets.
        // A denied read rolls back a first-time insertion as well.
        var target = targets.target(targetId);
        if (!"mock".equals(target.sourceMode()) || !scope.org().equals(target.ownerOrgId())
                || !scope.district().equals(target.districtId()))
            throw new ApiException(HttpStatus.CONFLICT, "DEMO_TARGET_CONFLICT", "目标不属于本地模拟样例");
        if (tracking.current(targetId) != null) return;
        double lon = sample.lon() + (sample.east() + sample.dx() * frame) / (111320 * Math.cos(Math.toRadians(sample.lat())));
        double lat = sample.lat() + (sample.north() + sample.dy() * frame) / 111320;
        double height = sample.height() + sample.dh() * frame;
        double speed = Math.hypot(sample.dx(), sample.dy()) / 10;
        Double heading = speed == 0 ? null : (Math.toDegrees(Math.atan2(sample.dx(), sample.dy())) + 360) % 360;
        repository.recordFrame(targetId, lon, lat, height, speed, heading, now);
    }

    public record PreparedTarget(String targetId) { }
}
