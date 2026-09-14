package com.uav.lowaltitude.modules.device.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.uav.lowaltitude.modules.device.infrastructure.ProtocolDataRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class SensingTargetService {

    private final DeviceAccessPolicy access;
    private final ProtocolDataRepository repository;
    private final AppClock clock;

    public SensingTargetService(DeviceAccessPolicy access, ProtocolDataRepository repository, AppClock clock) {
        this.access = access;
        this.repository = repository;
        this.clock = clock;
    }

    public TargetPage list(String deviceId, Boolean active, Integer classification, Long updatedAfter, int page, int size) {
        access.requireMonitoringRead();
        if (classification != null && (classification < 0 || classification > 5))
            throw bad("radar_classification 必须为 0–5");
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, size));
        List<Target> items = repository.targets(blank(deviceId), active, classification, updatedAfter,
                (safePage - 1) * safeSize, safeSize).stream().map(this::target).toList();
        return new TargetPage(items, safePage, safeSize,
                repository.countTargets(blank(deviceId), active, classification, updatedAfter));
    }

    public TargetTrack track(String id, Long from, Long to, int limit) {
        access.requireMonitoringRead();
        if (repository.findTarget(id) == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", "雷达航迹目标不存在");
        long safeTo = to == null ? clock.nowMillis() : to;
        long safeFrom = from == null ? safeTo - 3_600_000L : from;
        if (safeFrom > safeTo || safeTo - safeFrom > 86_400_000L)
            throw bad("航迹查询时间窗必须在 24 小时内");
        int safeLimit = Math.min(500, Math.max(1, limit));
        List<TrackPoint> points = repository.trackPoints(id, safeFrom, safeTo, safeLimit).stream()
                .map(this::point).sorted((a, b) -> Long.compare(a.receivedAt(), b.receivedAt())).toList();
        return new TargetTrack(id, safeFrom, safeTo, points);
    }

    private Target target(Map<String, Object> r) {
        return new Target(text(r, "target_id"), text(r, "target_no"), text(r, "primary_device_id"),
                text(r, "device_no"), text(r, "device_name"), text(r, "external_track_id"),
                longValue(r, "radar_boot_micros"), intValue(r, "radar_classification"), text(r, "category_code"),
                bool(r, "active"), decimal(r, "raw_x_m"), decimal(r, "raw_y_m"), decimal(r, "raw_z_m"),
                decimal(r, "velocity_x_mps"), decimal(r, "velocity_y_mps"), decimal(r, "velocity_z_mps"),
                decimal(r, "snr_db"), decimal(r, "rcs_legacy_m2"), decimal(r, "rcs_high_resolution_m2"),
                bool(r, "selected"), decimal(r, "longitude_deg"), decimal(r, "latitude_deg"), bool(r, "derived"),
                text(r, "frame_id"), longValue(r, "observed_at"), longValue(r, "received_at"),
                longValue(r, "first_seen_at"), longValue(r, "last_seen_at"));
    }

    private TrackPoint point(Map<String, Object> r) {
        return new TrackPoint(text(r, "track_point_id"), text(r, "frame_id"), longValue(r, "observed_at"),
                longValue(r, "received_at"), decimal(r, "raw_x_m"), decimal(r, "raw_y_m"), decimal(r, "raw_z_m"),
                decimal(r, "velocity_x_mps"), decimal(r, "velocity_y_mps"), decimal(r, "velocity_z_mps"),
                decimal(r, "snr_db"), decimal(r, "longitude_deg"), decimal(r, "latitude_deg"), bool(r, "derived"));
    }

    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    private static String blank(String v) { return v == null || v.trim().isEmpty() ? null : v.trim(); }
    private static String text(Map<String, Object> r, String key) { Object v = r.get(key); return v == null ? null : String.valueOf(v); }
    private static Long longValue(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof Number n ? n.longValue() : null; }
    private static Integer intValue(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof Number n ? n.intValue() : null; }
    private static boolean bool(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(String.valueOf(v)); }
    private static BigDecimal decimal(Map<String, Object> r, String key) { Object v = r.get(key); return v instanceof BigDecimal b ? b : v instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null; }

    public record TargetPage(List<Target> items, int page, int size, long total) { }
    public record Target(String targetId, String targetNo, String deviceId, String deviceNo, String deviceName,
                         String externalTrackId, Long radarBootMicros, Integer radarClassification, String categoryCode,
                         boolean active, BigDecimal rawXM, BigDecimal rawYM, BigDecimal rawZM,
                         BigDecimal velocityXMps, BigDecimal velocityYMps, BigDecimal velocityZMps,
                         BigDecimal snrDb, BigDecimal rcsLegacyM2, BigDecimal rcsHighResolutionM2,
                         boolean selected, BigDecimal longitudeDeg, BigDecimal latitudeDeg, boolean derived,
                         String frameId, Long observedAt, Long receivedAt, Long firstSeenAt, Long lastSeenAt) { }
    public record TrackPoint(String trackPointId, String frameId, Long observedAt, Long receivedAt,
                             BigDecimal rawXM, BigDecimal rawYM, BigDecimal rawZM,
                             BigDecimal velocityXMps, BigDecimal velocityYMps, BigDecimal velocityZMps,
                             BigDecimal snrDb, BigDecimal longitudeDeg, BigDecimal latitudeDeg, boolean derived) { }
    public record TargetTrack(String targetId, long from, long to, List<TrackPoint> points) { }
}
