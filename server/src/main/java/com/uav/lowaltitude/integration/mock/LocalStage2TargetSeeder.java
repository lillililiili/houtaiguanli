package com.uav.lowaltitude.integration.mock;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!production & (local | test)")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(30)
public class LocalStage2TargetSeeder implements ApplicationRunner {

    private static final String SOURCE_ID = "seed-stage2-source-mock";
    private static final String DEVICE_ID = "seed-stage2-radar-001";
    private static final String OWNER_ORG_ID = stableId("demo-org:platform");
    private static final String DISTRICT_ID = stableId("demo-district:dongying");
    private static final Timestamp CREATED_AT = timestamp("2026-09-04T00:00:00Z");

    private static final List<TargetSeed> TARGETS = List.of(
            new TargetSeed(
                    "seed-target-uav-wgs84", "目标-0904-001", "UAV", "QUADCOPTER", "SN-0001",
                    "2026-09-04T00:58:00Z", "2026-09-04T01:03:00Z",
                    "seed-link-uav-001", "seed-session-uav", "external-uav-001"),
            new TargetSeed(
                    "seed-target-bird-wgs84", "目标-0904-002", "BIRD", "MIGRATORY_BIRD", null,
                    "2026-09-04T00:59:00Z", "2026-09-04T01:02:00Z",
                    "seed-link-bird-001", "seed-session-bird", "external-bird-001"),
            new TargetSeed(
                    "seed-target-no-location", "目标-0904-003", null, null, null,
                    "2026-09-04T01:01:00Z", "2026-09-04T01:01:00Z",
                    "seed-link-unknown-001", "seed-session-unknown", "external-unknown-001"));

    private final JdbcTemplate jdbc;

    public LocalStage2TargetSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        insertSource();
        insertDevice();
        TARGETS.forEach(this::insertTargetAndLink);
        insertLatestState("seed-target-uav-wgs84", 118.6748, 37.4348, 126.50, 82.30, 18.250, 73.20,
                0.98200, 0.94100, "2026-09-04T01:03:00Z", "2026-09-04T01:03:08Z");
        insertLatestState("seed-target-bird-wgs84", 118.6922, 37.4482, 91.20, 46.10, 11.800, 214.60,
                0.91300, 0.87200, "2026-09-04T01:02:00Z", "2026-09-04T01:02:07Z");
        insertTracks();
    }

    private void insertSource() {
        jdbc.update("""
                insert into integration_source (
                    source_id, source_code, name, protocol_code, protocol_version, enabled,
                    source_mode, created_at, updated_at, version
                )
                select ?, 'STAGE2-MOCK-RADAR', '目标轨迹模拟雷达', 'RADAR_MOCK', '1.0', true,
                    'mock', ?, ?, 0
                where not exists (select 1 from integration_source where source_id = ?)
                """, SOURCE_ID, CREATED_AT, CREATED_AT, SOURCE_ID);
        jdbc.update("update integration_source set name = '目标轨迹模拟雷达' where source_id = ? and name <> '目标轨迹模拟雷达'", SOURCE_ID);
    }

    private void insertDevice() {
        jdbc.update("""
                insert into device (
                    device_id, source_id, external_device_id, device_no, name, device_type_code,
                    enabled, source_mode, owner_org_id, district_id, created_at, updated_at, version
                )
                select ?, ?, 'external-radar-seed-001', 'DEV-STAGE2-RADAR-001', '目标演示雷达', 'RADAR',
                    true, 'mock', ?, ?, ?, ?, 0
                where not exists (select 1 from device where device_id = ?)
                """, DEVICE_ID, SOURCE_ID, OWNER_ORG_ID, DISTRICT_ID, CREATED_AT, CREATED_AT, DEVICE_ID);
        jdbc.update("update device set name = '目标演示雷达' where device_id = ? and name <> '目标演示雷达'", DEVICE_ID);
    }

    private void insertTargetAndLink(TargetSeed seed) {
        jdbc.update("""
                insert into target (
                    target_id, target_no, object_type_code, subtype, uav_sn,
                    first_seen_at, last_seen_at, source_mode, owner_org_id, district_id,
                    created_at, updated_at, version
                )
                select ?, ?, ?, ?, ?, ?, ?, 'mock', ?, ?, ?, ?, 0
                where not exists (select 1 from target where target_id = ?)
                """, seed.targetId(), seed.targetNo(), seed.objectTypeCode(), seed.subtype(), seed.uavSn(),
                timestamp(seed.firstSeenAt()), timestamp(seed.lastSeenAt()), OWNER_ORG_ID, DISTRICT_ID,
                CREATED_AT, CREATED_AT, seed.targetId());
        // 业务编号改为中文口径后，已有开发库里的旧编号一并补齐；只改展示编号，不动 ID 与事实字段。
        jdbc.update("update target set target_no = ? where target_id = ? and target_no <> ?", seed.targetNo(), seed.targetId(), seed.targetNo());
        if (seed.uavSn() != null) jdbc.update("update target set uav_sn = ? where target_id = ? and uav_sn <> ?", seed.uavSn(), seed.targetId(), seed.uavSn());
        jdbc.update("""
                insert into target_source_link (
                    link_id, target_id, source_id, device_id, source_session_key,
                    external_target_id, protocol_version, created_at
                )
                select ?, ?, ?, ?, ?, ?, '1.0', ?
                where not exists (select 1 from target_source_link where link_id = ?)
                """, seed.linkId(), seed.targetId(), SOURCE_ID, DEVICE_ID, seed.sessionKey(),
                seed.externalTargetId(), CREATED_AT, seed.linkId());
    }

    private void insertLatestState(
            String targetId, double longitude, double latitude,
            double altitudeAmsl, double heightAgl, double speed, double heading,
            double classificationConfidence, double fusionConfidence,
            String observedAt, String receivedAt) {
        jdbc.update("""
                insert into target_latest_state (
                    target_id, location, altitude_amsl_m, height_agl_m, speed_mps, heading_deg,
                    classification_confidence, fusion_confidence, observed_at, received_at,
                    unknown_fields, created_at, updated_at, version
                )
                select ?, cast(? as geometry), ?, ?, ?, ?, ?, ?, ?, ?, '[]', ?, ?, 0
                where not exists (select 1 from target_latest_state where target_id = ?)
                """, targetId, point(longitude, latitude), altitudeAmsl, heightAgl, speed, heading,
                classificationConfidence, fusionConfidence, timestamp(observedAt), timestamp(receivedAt),
                CREATED_AT, CREATED_AT, targetId);
    }

    private void insertTracks() {
        insertTrack("seed-track-uav-001", "seed-target-uav-wgs84", "seed-link-uav-001",
                "external-track-uav-001", "2026-09-04T01:00:00Z");
        insertPoint("seed-point-uav-001", "seed-track-uav-001", 1, "2026-09-04T01:00:00Z",
                "2026-09-04T01:00:08Z", 118.6710, 37.4310, 119.20, 75.00);
        insertPoint("seed-point-uav-002", "seed-track-uav-001", 2, "2026-09-04T01:01:30Z",
                "2026-09-04T01:01:38Z", 118.6729, 37.4329, 123.10, 78.90);
        insertPoint("seed-point-uav-003", "seed-track-uav-001", 3, "2026-09-04T01:03:00Z",
                "2026-09-04T01:03:08Z", 118.6748, 37.4348, 126.50, 82.30);

        insertTrack("seed-track-bird-001", "seed-target-bird-wgs84", "seed-link-bird-001",
                "external-track-bird-001", "2026-09-04T00:59:00Z");
        insertPoint("seed-point-bird-001", "seed-track-bird-001", 1, "2026-09-04T00:59:00Z",
                "2026-09-04T00:59:07Z", 118.6880, 37.4440, 86.40, 41.30);
        insertPoint("seed-point-bird-002", "seed-track-bird-001", 2, "2026-09-04T01:00:30Z",
                "2026-09-04T01:00:37Z", 118.6901, 37.4461, 88.90, 43.80);
        insertPoint("seed-point-bird-003", "seed-track-bird-001", 3, "2026-09-04T01:02:00Z",
                "2026-09-04T01:02:07Z", 118.6922, 37.4482, 91.20, 46.10);

        insertTrack("seed-track-no-location-001", "seed-target-no-location", "seed-link-unknown-001",
                "external-track-no-location-001", "2026-09-04T01:00:00Z");
        insertPoint("seed-point-no-location-001", "seed-track-no-location-001", 1, "2026-09-04T01:00:00Z",
                "2026-09-04T01:00:06Z", 118.6530, 37.4210, 104.60, 60.20);
        insertPoint("seed-point-no-location-002", "seed-track-no-location-001", 2, "2026-09-04T01:01:00Z",
                "2026-09-04T01:01:06Z", 118.6555, 37.4235, 108.10, 63.70);
    }

    private void insertTrack(String trackId, String targetId, String linkId, String externalTrackId, String startedAt) {
        jdbc.update("""
                insert into track (track_id, target_id, link_id, external_track_id, started_at, created_at)
                select ?, ?, ?, ?, ?, ?
                where not exists (select 1 from track where track_id = ?)
                """, trackId, targetId, linkId, externalTrackId, timestamp(startedAt), CREATED_AT, trackId);
    }

    private void insertPoint(
            String pointId, String trackId, long sequence, String observedAt, String receivedAt,
            double longitude, double latitude, double altitudeAmsl, double heightAgl) {
        jdbc.update("""
                insert into track_point (
                    point_id, track_id, point_seq, observed_at, received_at, location,
                    altitude_amsl_m, height_agl_m, created_at
                )
                select ?, ?, ?, ?, ?, cast(? as geometry), ?, ?, ?
                where not exists (select 1 from track_point where point_id = ?)
                """, pointId, trackId, sequence, timestamp(observedAt), timestamp(receivedAt),
                point(longitude, latitude), altitudeAmsl, heightAgl, CREATED_AT, pointId);
    }

    private static String point(double longitude, double latitude) {
        return "SRID=4326;POINT(" + longitude + " " + latitude + ")";
    }

    private static Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value));
    }

    private static String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record TargetSeed(
            String targetId,
            String targetNo,
            String objectTypeCode,
            String subtype,
            String uavSn,
            String firstSeenAt,
            String lastSeenAt,
            String linkId,
            String sessionKey,
            String externalTargetId) {
    }
}
