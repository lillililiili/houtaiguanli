package com.uav.lowaltitude.modules.fusion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper;
import com.uav.lowaltitude.modules.fusion.ingest.InboxSourceRouter;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/** A real Flyway upgrade in a disposable schema; never connects to a business database. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stage456_verify_[a-z0-9_]+")
class FusionPendingInboxIndexPostgresTest {
    private static final String INDEX = "idx_fusion_inbox_pending_received_at";
    private static final List<String> PREFIXES = List.of("replay:", "lingyun:", "eo-edge:", "sim-normalized:", "live-radar:");

    @Test
    void pendingIndexPreservesWatermarkBoundariesAndSupportsTheUnchangedPreparedQuery() throws Exception {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stage456_verify_[a-z0-9_]+"))
            throw new IllegalStateException("An isolated stage456_verify_ database is required");
        String schema = "fusion_pending_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url,
                System.getenv("POSTGRES_TEST_USER"), System.getenv("POSTGRES_TEST_PASSWORD"))) {
            var dataSource = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).matches("stage456_verify_[a-z0-9_]+");
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                jdbc.execute("SET search_path TO " + schema + ",public");
                migrate(dataSource, schema, "202610160002");
                String replay = source(jdbc, "replay");
                String live = source(jdbc, "live");
                long base = System.currentTimeMillis();

                // Retained terminal history must not enter the watermark or be rewritten by this migration.
                jdbc.update("INSERT INTO inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload,status,processed_at)"
                        + " SELECT md5(?||n::text),'replay:history',n::text,?-n,?,CAST('{}' AS jsonb),'DONE',?"
                        + " FROM generate_series(1,32) n", UUID.randomUUID().toString(), base, replay, base);
                row(jdbc, replay, "replay:failed", "FAILED", 0, base + 1, "{}", null);
                row(jdbc, null, "replay:missing-source", "RECEIVED", 0, base + 2, "{}", null);
                row(jdbc, replay, "replay:missing-payload", "RECEIVED", 0, base + 3, null, null);
                row(jdbc, replay, "live-device:ops-only", "RECEIVED", 0, base + 4, "{}", null);
                row(jdbc, replay, "replay-other:prefix-lookalike", "RECEIVED", 0, base + 5, "{}", null);
                row(jdbc, live, "live-radar:switched", "RECEIVED", 0, base + 50, "{}", null);
                row(jdbc, replay, "replay:at-limit", "RECEIVED", 5, base + 100, "{}", null);
                row(jdbc, replay, "replay:below-limit", "RECEIVED", 4, base + 200, "{}", null);
                row(jdbc, replay, "replay:in-flight", "PROCESSING", 7, base + 300, "{}", base + 60_000);
                row(jdbc, live, "eo-edge:expired-lease", "PROCESSING", 9, base + 350, "{}", base - 1);
                row(jdbc, replay, "sim-normalized:bound", "RECEIVED", 0, base + 500, "{}", null);
                row(jdbc, replay, "lingyun:simulated", "RECEIVED", 0, base + 550, "{}", null);
                row(jdbc, live, "lingyun:physical", "RECEIVED", 0, base + 600, "{}", null);

                Map<String, Long> before = boundaries(jdbc, base);
                String rowsBefore = rowDigest(jdbc);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname=?",
                        Integer.class, schema, INDEX)).isZero();

                migrate(dataSource, schema, "202610160003");

                assertThat(boundaries(jdbc, base)).isEqualTo(before);
                assertThat(rowDigest(jdbc)).as("Index migration must not change any inbox row").isEqualTo(rowsBefore);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='202610160003' AND success",
                        Integer.class)).isEqualTo(1);
                String definition = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname=?",
                        String.class, schema, INDEX);
                assertThat(definition).contains("(received_at)", "RECEIVED", "PROCESSING", "source_id IS NOT NULL", "payload IS NOT NULL")
                        .doesNotContain("fusion_attempts", "lease_until", "source LIKE");

                // ANALYZE is confined to this disposable schema, never the running acceptance database.
                jdbc.execute("ANALYZE inbox_message");
                jdbc.execute("SET enable_seqscan=off");
                jdbc.execute("SET plan_cache_mode=force_generic_plan");
                // Same SQL predicates as oldestPendingReceivedAt(), including the dynamic attempt limit/prefixes.
                jdbc.execute("PREPARE pending_watermark(integer,text,text,text,text) AS SELECT MIN(received_at) FROM inbox_message"
                        + " WHERE (status='PROCESSING' OR (status='RECEIVED' AND fusion_attempts<$1))"
                        + " AND (source LIKE $2 OR source LIKE $3 OR source LIKE $4 OR source LIKE $5)"
                        + " AND source_id IS NOT NULL AND payload IS NOT NULL");
                String execute = "EXECUTE pending_watermark(5,'replay:%','lingyun:%','eo-edge:%','sim-normalized:%')";
                assertThat(jdbc.queryForObject(execute, Long.class)).isEqualTo(base + 200);
                String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + execute, String.class));
                assertThat(plan).as("Index availability only; forced index planning is not a latency benchmark: %s", plan).contains(INDEX);
                System.out.println("Fusion pending watermark index availability (EXPLAIN only, enable_seqscan=off):\n" + plan);
                jdbc.execute("DEALLOCATE pending_watermark");
            } finally {
                jdbc.execute("SET search_path TO public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void migrate(SingleConnectionDataSource dataSource, String schema, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).createSchemas(false)
                .cleanDisabled(true).locations("classpath:db/migration", "classpath:db/postgresql")
                .target(target);
        com.uav.lowaltitude.platform.database.MigrationLineage.configure(configuration);
        configuration.load().migrate();
    }

    private static Map<String, Long> boundaries(JdbcTemplate jdbc, long base) {
        Map<String, Long> actual = new LinkedHashMap<>();
        actual.put("received-below-limit", repository(jdbc, true, false, PREFIXES).oldestPendingReceivedAt(5));
        actual.put("processing-attempts-not-excluded", repository(jdbc, true, false, PREFIXES).oldestPendingReceivedAt(4));
        actual.put("changed-attempt-limit", repository(jdbc, true, false, PREFIXES).oldestPendingReceivedAt(6));
        actual.put("expired-processing-still-protects", repository(jdbc, true, false, List.of("eo-edge:")).oldestPendingReceivedAt(1));
        actual.put("normalized-prefix", repository(jdbc, true, false, List.of("sim-normalized:")).oldestPendingReceivedAt(5));
        actual.put("qa-lingyun", repository(jdbc, true, false, List.of("lingyun:")).oldestPendingReceivedAt(5));
        actual.put("formal-source-mode", repository(jdbc, false, false, List.of("lingyun:")).oldestPendingReceivedAt(5));
        actual.put("radar-enabled", repository(jdbc, true, true, PREFIXES).oldestPendingReceivedAt(5));
        actual.put("no-processable-prefix", repository(jdbc, true, false, List.of("unregistered:")).oldestPendingReceivedAt(5));
        assertThat(actual).containsEntry("received-below-limit", base + 200)
                .containsEntry("processing-attempts-not-excluded", base + 300)
                .containsEntry("changed-attempt-limit", base + 100)
                .containsEntry("expired-processing-still-protects", base + 350)
                .containsEntry("normalized-prefix", base + 500)
                .containsEntry("qa-lingyun", base + 550)
                .containsEntry("formal-source-mode", base + 600)
                .containsEntry("radar-enabled", base + 50)
                .containsEntry("no-processable-prefix", null);
        return actual;
    }

    private static FusionInboxRepository repository(JdbcTemplate jdbc, boolean simulation, boolean radar, List<String> prefixes) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(simulation ? "test" : "prod");
        FusionProperties properties = new FusionProperties();
        properties.getLivePromotion().setEnabled(radar);
        List<FrameMapper> mappers = prefixes.stream().<FrameMapper>map(prefix -> new FrameMapper() {
            @Override public String prefix() { return prefix; }
            @Override public Frame map(FusionInboxRepository.InboxRow inbox) { throw new UnsupportedOperationException("Read-only watermark test"); }
        }).toList();
        return new FusionInboxRepository(jdbc, new InboxSourceRouter(mappers), properties, new SimulationPolicy(environment));
    }

    private static String source(JdbcTemplate jdbc, String mode) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO integration_source (source_id,source_code,name,enabled,source_mode,source_type,created_at,updated_at,version)"
                + " VALUES (?,?,?,true,?,'RADAR',current_timestamp,current_timestamp,0)", id, "PENDING-" + id, "Pending watermark index fixture", mode);
        return id;
    }

    private static void row(JdbcTemplate jdbc, String sourceId, String source, String status, int attempts,
            long received, String payload, Long leaseUntil) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload,status,processed_at,fusion_attempts,lease_token,lease_until)"
                + " VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?)", id, source, id, received, sourceId, payload, status,
                List.of("DONE", "FAILED").contains(status) ? received + 1 : null, attempts,
                leaseUntil == null ? null : UUID.randomUUID().toString(), leaseUntil);
    }

    private static String rowDigest(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT md5(string_agg(to_jsonb(m)::text,'' ORDER BY inbox_id)) FROM inbox_message m", String.class);
    }
}
