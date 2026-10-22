package com.uav.lowaltitude.testsupport;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.uav.lowaltitude.integration.mock.LocalStage8FusionReplaySeeder;
import com.uav.lowaltitude.integration.mock.LocalStage8FusionReplaySeeder.SourceSeed;
import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator;
import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator.ProtocolDataset;
import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator.ProtocolRecord;
import com.uav.lowaltitude.integration.replay.FusionReplayReader;
import com.uav.lowaltitude.modules.fusion.application.FusionPipeline;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;

/**
 * Explicitly imported, isolated test fixture. The mathematical synthetic scenes
 * have known WGS-84 origins. Copies receive new dataset/source/message identities;
 * frozen datasets and arbitrary external reports are never modified or relabelled.
 */
public final class Wgs84FusionTestFixture {
    public static final String DATASET_V2 = "test-wgs84-fusion-scenes-20261020";
    public static final String DATASET_S16 = "test-wgs84-merge-scenes-20261020";
    public static final String ORG = id("wgs84-test-org");
    public static final String DISTRICT = id("wgs84-test-district");
    public static final String RADAR = sourceCode(FusionReplayDatasetGenerator.RADAR);
    public static final String TDOA = sourceCode(FusionReplayDatasetGenerator.TDOA);
    public static final String EO = sourceCode(FusionReplayDatasetGenerator.EO);
    public static final String RADAR_SOURCE = "wgs84-seed-stage8-source-radar";
    public static final String TDOA_SOURCE = "wgs84-seed-stage8-source-tdoa";
    public static final String EO_SOURCE = "wgs84-seed-stage8-source-eo";
    public static final List<SourceSeed> SOURCES = LocalStage8FusionReplaySeeder.SOURCES.stream()
            .map(s -> new SourceSeed(sourceCode(s.sourceCode()), s.sourceType(), "WGS84 test " + s.sourceType(),
                    "wgs84-" + s.sourceId(), "wgs84-" + s.deviceId(), "WGS84-" + s.deviceNo())).toList();
    public static final Map<String, String> INBOX_SOURCES;
    static {
        Map<String, String> sources = new LinkedHashMap<>();
        FusionReplayDatasetGenerator.INBOX_SOURCES.forEach((code, source) -> sources.put(sourceCode(code), inboxSource(source)));
        INBOX_SOURCES = Map.copyOf(sources);
    }
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-09-05T00:00:00Z"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FusionInboxRepository inbox;
    private final FusionPipeline pipeline;
    private final FusionReplayDatasetGenerator generator;

    public Wgs84FusionTestFixture(JdbcTemplate jdbc, ObjectMapper json, FusionInboxRepository inbox,
            FusionPipeline pipeline, FusionReplayDatasetGenerator generator) {
        this.jdbc = jdbc; this.json = json; this.inbox = inbox; this.pipeline = pipeline; this.generator = generator;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Sources {
        @Bean Wgs84FusionTestFixture wgs84FusionTestFixture(JdbcTemplate jdbc, ObjectMapper json,
                FusionInboxRepository inbox, FusionPipeline pipeline, FusionReplayDatasetGenerator generator) {
            return new Wgs84FusionTestFixture(jdbc, json, inbox, pipeline, generator);
        }
        @Bean @Order(74) ApplicationRunner wgs84TestSources(Wgs84FusionTestFixture fixture) {
            return args -> fixture.registerSources();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(Sources.class)
    public static class Replay {
        @Bean @Order(76) ApplicationRunner wgs84TestReplay(Wgs84FusionTestFixture fixture) {
            return args -> { fixture.loadV2(); fixture.loadStage16(); fixture.drain(); };
        }
    }

    public void registerSources() { register(ORG, DISTRICT, "WGS84-TEST", SOURCES); }
    /** Registers metadata only: frozen payloads are loaded separately for archive/hash/rejection tests. */
    public void registerLegacySources() {
        register(LocalStage8FusionReplaySeeder.ORG, LocalStage8FusionReplaySeeder.DISTRICT,
                "DEMO", LocalStage8FusionReplaySeeder.SOURCES);
    }
    private void register(String org, String district, String code, List<SourceSeed> sources) {
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version)"
                + " SELECT ?,?,?,TRUE,0,0,0 WHERE NOT EXISTS(SELECT 1 FROM app_org WHERE org_id=?)",
                org, code + "-PLATFORM", "Isolated fusion fixture", org);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version)"
                + " SELECT ?,?,?,TRUE,0,0,0 WHERE NOT EXISTS(SELECT 1 FROM app_district WHERE district_id=?)",
                district, code + "-DONGYING", "Isolated fusion fixture", district);
        for (SourceSeed source : sources) {
            jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,source_type,created_at,updated_at,version)"
                    + " SELECT ?,?,?,TRUE,'replay',?,?,?,0 WHERE NOT EXISTS(SELECT 1 FROM integration_source WHERE source_id=?)",
                    source.sourceId(), source.sourceCode(), source.name(), source.sourceType(), CREATED_AT, CREATED_AT, source.sourceId());
            jdbc.update("INSERT INTO device(device_id,source_id,external_device_id,device_no,name,device_type_code,enabled,source_mode,"
                    + "owner_org_id,district_id,created_at,updated_at,version)"
                    + " SELECT ?,?,?,?,?,?,TRUE,'replay',?,?,?,?,0 WHERE NOT EXISTS(SELECT 1 FROM device WHERE device_id=?)",
                    source.deviceId(), source.sourceId(), "external-" + source.sourceCode(), source.deviceNo(), source.name(),
                    source.sourceType(), org, district, CREATED_AT, CREATED_AT, source.deviceId());
        }
    }

    public ProtocolDataset datasetV2() { return copySynthetic(generator.generateV2(), DATASET_V2); }
    public ProtocolDataset datasetStage16() { return copySynthetic(generator.generateStage16(), DATASET_S16); }
    private ProtocolDataset copySynthetic(ProtocolDataset original, String datasetId) {
        List<ProtocolRecord> records = new ArrayList<>();
        for (ProtocolRecord record : original.records()) {
            Map<String, Object> payload = copyMessage(record.payload());
            payload.put("coordinate_system", "WGS84");
            records.add(new ProtocolRecord(record.recordNo(), sourceCode(record.sourceCode()), inboxSource(record.inboxSource()),
                    record.observedAtMillis(), record.receivedAtMillis(), Map.copyOf(payload), record.scenario()));
        }
        var truth = original.groundTruth().stream().map(t -> new FusionReplayDatasetGenerator.GroundTruth(
                t.scenario(), t.recordNo(), t.trueTargetKey(), sourceCode(t.sourceCode()), t.externalTargetId(), t.observedAtMillis())).toList();
        return new ProtocolDataset(datasetId, List.copyOf(records), truth);
    }
    private static Map<String, Object> copyMessage(Map<String, Object> original) {
        Map<String, Object> copy = new LinkedHashMap<>();
        original.forEach((key, value) -> copy.put(key,
                ("deviceId".equals(key) || "edgeId".equals(key)) && value instanceof String ? "WGS84-" + value : copyValue(value)));
        return copy;
    }
    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) return copyMessage((Map<String, Object>) map);
        if (value instanceof List<?> list) return list.stream().map(Wgs84FusionTestFixture::copyValue).toList();
        return value;
    }
    public int loadV2() { return load(datasetV2()); }
    public int loadStage16() { return load(datasetStage16()); }
    private int load(ProtocolDataset dataset) {
        registerSources();
        int inserted = 0;
        for (ProtocolRecord record : dataset.records()) {
            String sourceId = SOURCES.stream().filter(s -> s.sourceCode().equals(record.sourceCode())).findFirst().orElseThrow().sourceId();
            String payload = payload(record);
            if (inbox.insertEnvelope(record.inboxSource(), Long.toString(record.recordNo()), record.receivedAtMillis(),
                    sourceId, FusionReplayReader.sha256(payload), payload)) inserted++;
        }
        return inserted;
    }
    public String payload(ProtocolRecord record) {
        try {
            return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(new java.util.TreeMap<>(record.payload()));
        } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    public boolean alreadyLoaded(ProtocolDataset dataset) {
        return dataset.records().stream().allMatch(record -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_message WHERE source=? AND source_msg_id=?", Long.class,
                record.inboxSource(), Long.toString(record.recordNo())) == 1);
    }
    public int drain() {
        int processed = 0;
        for (int round = 0; round < 1000; round++) {
            var rows = inbox.claim(System.currentTimeMillis(), 50, 30_000L);
            if (rows.isEmpty()) return processed;
            for (var row : rows) {
                try {
                    pipeline.processFrame(row); inbox.done(row.inboxId(), System.currentTimeMillis()); processed++;
                } catch (RuntimeException ex) {
                    inbox.fail(row.inboxId(), System.currentTimeMillis(), String.valueOf(ex.getMessage())); throw ex;
                }
            }
        }
        throw new IllegalStateException("WGS84 test fixture did not finish draining");
    }
    private static String sourceCode(String code) { return code + "-wgs84-test"; }
    private static String inboxSource(String source) {
        int split = source.lastIndexOf(':');
        return source.substring(0, split + 1) + "WGS84-" + source.substring(split + 1);
    }
    private static String id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
}
