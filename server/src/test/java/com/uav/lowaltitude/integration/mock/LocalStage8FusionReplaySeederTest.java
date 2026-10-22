package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator;
import com.uav.lowaltitude.integration.replay.FusionReplayRunner;
import com.uav.lowaltitude.modules.fusion.application.FusionPipeline;
import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.testsupport.Wgs84FusionTestFixture;

/** Current fixtures are explicit; historical payloads remain immutable and are not grandfathered into ingestion. */
@SpringBootTest(properties = {
        "app.dev-seed.enabled=true", "app.fusion.enabled=false", "app.fusion.replay.run-on-start=false",
        "spring.datasource.url=jdbc:h2:mem:stage8_seed_${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(Wgs84FusionTestFixture.Sources.class)
@Transactional
class LocalStage8FusionReplaySeederTest {
    private static final String DIRECT = "(source LIKE 'lingyun:%' OR source LIKE 'eo-edge:%' OR source LIKE 'live-radar:%')";
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalStage8FusionReplaySeeder legacySeeder;
    @Autowired FusionReplayRunner legacyRunner;
    @Autowired FusionReplayDatasetGenerator legacyGenerator;
    @Autowired FusionProperties properties;
    @Autowired FusionPipeline pipeline;
    @Autowired ApplicationArguments arguments;
    @Autowired Wgs84FusionTestFixture fixture;

    @Test void defaultTestProfileDoesNotAutomaticallyLoadFrozenReplay() {
        assertThat(properties.getReplay().isSeedEnabled()).isFalse();
        Map<String, Long> before = counts();
        legacySeeder.run(arguments);
        assertThat(counts()).isEqualTo(before);
        assertThat(count("inbox_message WHERE " + DIRECT)).isZero();
    }

    @Test void explicitlyImportedWgs84DatasetsProduceReplayFactsAndRemainIdempotent() {
        assertThat(fixture.loadV2()).isPositive();
        assertThat(fixture.loadStage16()).isPositive();
        assertThat(fixture.drain()).isPositive();
        Map<String, Long> before = counts();
        assertThat(fixture.loadV2()).isZero();
        assertThat(fixture.loadStage16()).isZero();
        assertThat(fixture.drain()).isZero();
        assertThat(counts()).isEqualTo(before);
        assertThat(count("inbox_message where " + DIRECT + " AND status<>'DONE'")).isZero();
        assertThat(count("source_observation")).isPositive();
        assertThat(count("target where unified=true")).isPositive();
        assertThat(count("source_observation where source_mode<>'replay'")).isZero();
        assertThat(count("target where unified=true and source_mode<>'replay'")).isZero();
        assertThat(count("track where layer='RAW' and link_id is null")).isZero();
        assertThat(count("track_point p join track t on t.track_id=p.track_id where t.layer='RAW' and p.point_kind<>'MEAS'")).isZero();
    }

    @Test void newDatasetsHaveIndependentIdentifiersAndDoNotMutateFrozenGeneratedContent() {
        String frozenBefore = legacyGenerator.generateV2().toString();
        var current = fixture.datasetV2();
        var merge = fixture.datasetStage16();
        assertThat(current.datasetId()).isNotEqualTo(legacyGenerator.generateV2().datasetId()).isNotEqualTo(merge.datasetId());
        Set<String> oldSources = Set.copyOf(FusionReplayDatasetGenerator.INBOX_SOURCES.values());
        assertThat(current.records()).allSatisfy(record -> {
            assertThat(oldSources).doesNotContain(record.inboxSource());
            assertThat(record.payload()).containsEntry("coordinate_system", "WGS84");
        });
        Set<String> firstKeys = current.records().stream().map(r -> r.inboxSource() + "#" + r.recordNo()).collect(java.util.stream.Collectors.toSet());
        assertThat(merge.records()).noneMatch(r -> firstKeys.contains(r.inboxSource() + "#" + r.recordNo()));
        assertThat(legacyGenerator.generateV2().toString()).isEqualTo(frozenBefore);
        assertThat(legacyGenerator.generateV2().records()).allSatisfy(r -> assertThat(r.payload()).doesNotContainKey("coordinate_system"));
    }

    @Test void eachCurrentDatasetChecksItsOwnMessagesRatherThanSharedPrefixCounts() {
        fixture.loadV2(); fixture.loadStage16();
        assertThat(fixture.alreadyLoaded(fixture.datasetV2())).isTrue();
        assertThat(fixture.alreadyLoaded(fixture.datasetStage16())).isTrue();
        var record = fixture.datasetV2().records().get(0);
        jdbc.update("UPDATE inbox_message SET source=? WHERE source=? AND source_msg_id=?",
                "archived:wgs84-test", record.inboxSource(), Long.toString(record.recordNo()));
        assertThat(fixture.alreadyLoaded(fixture.datasetV2())).isFalse();
        assertThat(fixture.alreadyLoaded(fixture.datasetStage16())).isTrue();
    }

    @Test void undeclaredFrozenRawIsRejectedWithoutChangingItsPayloadHashOrCreatingFacts() {
        fixture.registerLegacySources();
        legacyRunner.loadV2();
        var record = legacyGenerator.generateV2().records().stream()
                .filter(r -> r.inboxSource().startsWith("lingyun:radar:")).findFirst().orElseThrow();
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT inbox_id,source_id,payload_hash,CAST(payload AS VARCHAR) AS payload_text FROM inbox_message WHERE source=? AND source_msg_id=?",
                record.inboxSource(), Long.toString(record.recordNo()));
        String payload = fixture.payload(record);
        var row = new FusionInboxRepository.InboxRow((String) before.get("inbox_id"), record.inboxSource(),
                Long.toString(record.recordNo()), (String) before.get("source_id"), record.receivedAtMillis(), payload);
        long observations = count("source_observation");
        assertThatThrownBy(() -> pipeline.processFrame(row)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COORDINATE_SYSTEM_REQUIRED")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(count("source_observation")).isEqualTo(observations);
        legacyRunner.loadV2();
        assertThat(jdbc.queryForMap(
                "SELECT inbox_id,source_id,payload_hash,CAST(payload AS VARCHAR) AS payload_text FROM inbox_message WHERE source=? AND source_msg_id=?",
                record.inboxSource(), Long.toString(record.recordNo()))).isEqualTo(before);
    }

    @Test void frozenArchiveLoadedWithAnOlderHashIsNotRewrittenBySeeder() {
        fixture.registerLegacySources(); legacyRunner.loadV2(); legacyRunner.loadStage16();
        String oldHash = "0".repeat(64);
        jdbc.update("UPDATE inbox_message SET payload_hash=? WHERE inbox_id=(SELECT inbox_id FROM inbox_message WHERE "
                + DIRECT + " ORDER BY inbox_id LIMIT 1)", oldHash);
        Map<String, Long> before = counts();
        try {
            properties.getReplay().setSeedEnabled(true);
            legacySeeder.run(arguments);
            assertThat(counts()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE payload_hash=?", Long.class, oldHash)).isEqualTo(1L);
        } finally { properties.getReplay().setSeedEnabled(false); }
    }

    @Test void profileAndPropertyGatesExcludeProductionEvenWhenLocalIsAlsoActive() {
        Profile profile = LocalStage8FusionReplaySeeder.class.getAnnotation(Profile.class);
        ConditionalOnProperty property = LocalStage8FusionReplaySeeder.class.getAnnotation(ConditionalOnProperty.class);
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches(name -> Set.of("production", "local").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("production").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("test").contains(name))).isTrue();
        assertThat(expression.matches(name -> Set.of("local").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("local", "qa").contains(name))).isTrue();
        assertThat(property.havingValue()).isEqualTo("true");
        for (Class<?> type : List.of(FusionReplayRunner.class, com.uav.lowaltitude.integration.replay.ReplayAdapterPort.class)) {
            assertThat(Profiles.of(type.getAnnotation(Profile.class).value()).matches(name -> Set.of("production").contains(name))).isFalse();
        }
    }

    private Map<String, Long> counts() {
        return Map.of("sources", count("integration_source"), "devices", count("device"), "inbox", count("inbox_message"),
                "observations", count("source_observation"), "targets", count("target where unified=true"), "lineage", count("target_lineage"));
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
}
