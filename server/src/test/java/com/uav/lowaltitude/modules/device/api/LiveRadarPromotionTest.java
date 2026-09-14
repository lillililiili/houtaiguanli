package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackBatch;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackItem;
import com.uav.lowaltitude.modules.device.application.LiveRadarFrameIngestService;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceObservationPort;
import com.uav.lowaltitude.modules.fusion.application.NoopSourceObservationPort;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LiveRadarPromotionTest {

    @Autowired LiveRadarFrameIngestService ingest;
    @Autowired SourceObservationPort port;
    @Autowired JdbcTemplate jdbc;

    @Test
    void defaultOffWritesOpsInboxAndTracksButZeroLiveRadarEnvelopes() {
        assertThat(port).isInstanceOf(NoopSourceObservationPort.class);
        Fixture device = fixture();
        long tracksBefore = trackPoints(device.opsDeviceId(), "p4a-off");
        TrackBatch batch = batch(1000L, "7", item("p4a-off"));

        assertThat(ingest.ingestTrack(device.opsSourceId(), device.opsDeviceId(), device.deviceNo(),
                device.sourceCode(), batch, new byte[] { 1, 2, 3 }, 1_700_000_000_000L)).isTrue();
        assertThat(ingest.ingestTrack(device.opsSourceId(), device.opsDeviceId(), device.deviceNo(),
                device.sourceCode(), batch, new byte[] { 1, 2, 3 }, 1_700_000_000_001L)).isFalse();

        assertThat(count("inbox_message WHERE source=?", "live-device:" + device.opsDeviceId())).isEqualTo(1L);
        assertThat(count("inbox_message WHERE source=?", "live-radar:" + device.sourceCode())).isZero();
        assertThat(trackPoints(device.opsDeviceId(), "p4a-off")).isEqualTo(tracksBefore + 1);
    }

    @Test
    void emptyTrackBatchStillDoesNotPromoteWhenFlagIsOff() {
        Fixture device = fixture();
        TrackBatch empty = new TrackBatch(2000L, "1", 0L, BigDecimal.ZERO, BigDecimal.ONE, 0, 0, List.of());
        assertThat(ingest.ingestTrack(device.opsSourceId(), device.opsDeviceId(), device.deviceNo(),
                device.sourceCode(), empty, new byte[] { 9 }, 1_700_000_000_100L)).isTrue();
        assertThat(count("inbox_message WHERE source=?", "live-radar:" + device.sourceCode())).isZero();
        assertThat(count("inbox_message WHERE source=?", "live-device:" + device.opsDeviceId())).isEqualTo(1L);
    }

    private Fixture fixture() {
        String deviceId = jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no='DEV-MOCK-001'", String.class);
        String sourceId = jdbc.queryForObject("SELECT source_id FROM ops_device WHERE device_id=?", String.class, deviceId);
        return new Fixture(sourceId, deviceId, "DEV-MOCK-001", "RAD-P4A-OFF-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private long trackPoints(String deviceId, String externalTrackId) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ops_track_point p JOIN ops_track t ON t.track_id=p.track_id
                WHERE t.device_id=? AND t.external_track_id=?
                """, Long.class, deviceId, externalTrackId);
        return value == null ? 0 : value;
    }

    private long count(String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + where, Long.class, args);
    }

    private static TrackBatch batch(long boot, String frameId, TrackItem item) {
        return new TrackBatch(boot, frameId, System.currentTimeMillis(), BigDecimal.ZERO, BigDecimal.ONE, 1, 0, List.of(item));
    }

    private static TrackItem item(String externalId) {
        return new TrackItem(externalId, new BigDecimal("1.25"), new BigDecimal("-2.50"), new BigDecimal("3.75"),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("12.34"),
                new BigDecimal("0.50"), new BigDecimal("0.500001"), 3, "UAV", false);
    }

    private record Fixture(String opsSourceId, String opsDeviceId, String deviceNo, String sourceCode) { }
}
