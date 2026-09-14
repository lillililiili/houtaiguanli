package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackBatch;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.TrackItem;
import com.uav.lowaltitude.modules.device.application.LiveRadarFrameIngestService;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceObservationPort;
import com.uav.lowaltitude.modules.fusion.application.LiveRadarSourceObservationPort;
import com.uav.lowaltitude.modules.fusion.ingest.LiveRadarFrameMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.fusion.live-promotion.enabled=true",
        "app.fusion.enabled=false",
        "app.fusion.replay.run-on-start=false"
})
@Transactional
class LiveRadarPromotionEnabledTest {

    @Autowired LiveRadarFrameIngestService ingest;
    @Autowired SourceObservationPort port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private static final java.sql.Timestamp T0 = java.sql.Timestamp.from(java.time.Instant.parse("2026-09-07T12:00:00Z"));

    @Test
    void flagOnWritesOneLiveRadarEnvelopeUsingSourceCodeAndSkipsDuplicate() throws Exception {
        assertThat(port).isInstanceOf(LiveRadarSourceObservationPort.class);
        Fixture device = fixture(true);
        long observationsBefore = count("source_observation");
        long targetsBefore = count("target");
        TrackBatch batch = batch(1000L, "7", item("p4a-on"));

        assertThat(ingest.ingestTrack(device.opsSourceId(), device.opsDeviceId(), device.deviceNo(),
                device.sourceCode(), batch, new byte[] { 1 }, 1_700_000_000_000L)).isTrue();
        assertThat(ingest.ingestTrack(device.opsSourceId(), device.opsDeviceId(), device.deviceNo(),
                device.sourceCode(), batch, new byte[] { 1 }, 1_700_000_000_001L)).isFalse();

        assertThat(count("inbox_message WHERE source=?", "live-device:" + device.opsDeviceId())).isEqualTo(1L);
        assertThat(count("inbox_message WHERE source=?", "live-radar:" + device.sourceCode())).isEqualTo(1L);
        assertThat(count("inbox_message WHERE source=?", "live-radar:" + device.opsDeviceId())).isZero();

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source,source_msg_id,source_id,status,CAST(payload AS VARCHAR) AS payload_text
                FROM inbox_message WHERE source=?
                """, "live-radar:" + device.sourceCode());
        assertThat(row).containsEntry("source", "live-radar:" + device.sourceCode())
                .containsEntry("source_msg_id", "1000:7")
                .containsEntry("source_id", device.standardSourceId())
                .containsEntry("status", "RECEIVED");
        JsonNode payload = readPayload((String) row.get("payload_text"));
        assertThat(payload.path("device_id").asText()).isEqualTo(device.sourceCode());
        assertThat(payload.path("items").isArray()).isTrue();
        assertThat(payload.path("items").get(0).path("z_m").decimalValue()).isEqualByComparingTo("3.75");
        assertThat(payload.path("items").get(0).path("classification").asText()).isEqualTo("UAV");
        assertThat(payload.path("items").get(0).path("rcs_m2").decimalValue()).isEqualByComparingTo("0.500001");
        JsonNode longitude = payload.path("items").get(0).path("longitude");
        JsonNode latitude = payload.path("items").get(0).path("latitude");
        assertThat(longitude.isMissingNode() || longitude.isNull()).isTrue();
        assertThat(latitude.isMissingNode() || latitude.isNull()).isTrue();

        InboxRow inbox = new InboxRow("id", "live-radar:" + device.sourceCode(), "1000:7",
                device.standardSourceId(), 1_700_000_000_000L, payload.toString());
        assertThat(new LiveRadarFrameMapper(mapper).map(inbox).items()).hasSize(1);

        assertThat(count("source_observation")).isEqualTo(observationsBefore);
        assertThat(count("target")).isEqualTo(targetsBefore);
    }

    @Test
    void emptyItemsStillWritesOneEnvelope() {
        Fixture device = fixture(true);
        TrackBatch empty = new TrackBatch(2000L, "3", 0L, BigDecimal.ZERO, BigDecimal.ONE, 0, 0, List.of());
        assertThat(ingest.ingestTrack(device.opsSourceId(), device.opsDeviceId(), device.deviceNo(),
                device.sourceCode(), empty, new byte[] { 2 }, 1_700_000_000_200L)).isTrue();
        assertThat(count("inbox_message WHERE source=? AND source_msg_id=?",
                "live-radar:" + device.sourceCode(), "2000:3")).isEqualTo(1L);
        String text = jdbc.queryForObject("SELECT CAST(payload AS VARCHAR) FROM inbox_message WHERE source=? AND source_msg_id=?",
                String.class, "live-radar:" + device.sourceCode(), "2000:3");
        assertThat(readPayload(text).path("items").isArray()).isTrue();
        assertThat(readPayload(text).path("items")).isEmpty();
    }

    @Test
    void unregisteredStandardSourceRejectsEnvelopeButOpsStillWrites() {
        Fixture unknown = fixture(false);
        long opsBefore = count("inbox_message WHERE source=?", "live-device:" + unknown.opsDeviceId());
        TrackBatch batch = batch(3000L, "1", item("p4a-reject"));
        assertThat(ingest.ingestTrack(unknown.opsSourceId(), unknown.opsDeviceId(), unknown.deviceNo(),
                unknown.sourceCode(), batch, new byte[] { 3 }, 1_700_000_000_300L)).isTrue();
        assertThat(count("inbox_message WHERE source=?", "live-device:" + unknown.opsDeviceId())).isEqualTo(opsBefore + 1);
        assertThat(count("inbox_message WHERE source=?", "live-radar:" + unknown.sourceCode())).isZero();
        assertThat(count("integration_source WHERE source_code=?", unknown.sourceCode())).isZero();
    }

    @Test
    void disabledStandardSourceRejectsEnvelopeButOpsStillWrites() {
        Fixture disabled = fixture(true);
        jdbc.update("UPDATE integration_source SET enabled=FALSE WHERE source_id=?", disabled.standardSourceId());
        long opsBefore = count("inbox_message WHERE source=?", "live-device:" + disabled.opsDeviceId());
        TrackBatch second = batch(4000L, "2", item("p4a-disabled"));
        assertThat(ingest.ingestTrack(disabled.opsSourceId(), disabled.opsDeviceId(), disabled.deviceNo(),
                disabled.sourceCode(), second, new byte[] { 4 }, 1_700_000_000_400L)).isTrue();
        assertThat(count("inbox_message WHERE source=?", "live-device:" + disabled.opsDeviceId())).isEqualTo(opsBefore + 1);
        assertThat(count("inbox_message WHERE source=?", "live-radar:" + disabled.sourceCode())).isZero();
    }

    private Fixture fixture(boolean registerStandard) {
        String deviceId = jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no='DEV-MOCK-001'", String.class);
        String opsSourceId = jdbc.queryForObject("SELECT source_id FROM ops_device WHERE device_id=?", String.class, deviceId);
        String sourceCode = "RAD-P4A-ON-" + UUID.randomUUID().toString().substring(0, 8);
        String standardId = null;
        if (registerStandard) {
            standardId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO integration_source (source_id,source_code,source_type,name,source_mode,enabled,
                        credential_ref,created_at,updated_at,version)
                    VALUES (?,?,'RADAR',?,'live',TRUE,NULL,?,?,0)
                    """, standardId, sourceCode, "P4-A " + sourceCode, T0, T0);
        }
        return new Fixture(opsSourceId, deviceId, "DEV-MOCK-001", sourceCode, standardId);
    }

    private long count(String where, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + where, Long.class, args);
    }

    private JsonNode readPayload(String text) {
        try {
            JsonNode root = mapper.readTree(text);
            if (root != null && root.isTextual()) root = mapper.readTree(root.textValue());
            return root;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static TrackBatch batch(long boot, String frameId, TrackItem item) {
        return new TrackBatch(boot, frameId, System.currentTimeMillis(), BigDecimal.ZERO, BigDecimal.ONE, 1, 0, List.of(item));
    }

    private static TrackItem item(String externalId) {
        return new TrackItem(externalId, new BigDecimal("1.25"), new BigDecimal("-2.50"), new BigDecimal("3.75"),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("12.34"),
                new BigDecimal("0.50"), new BigDecimal("0.500001"), 3, "UAV", false);
    }

    private record Fixture(String opsSourceId, String opsDeviceId, String deviceNo, String sourceCode, String standardSourceId) { }
}
