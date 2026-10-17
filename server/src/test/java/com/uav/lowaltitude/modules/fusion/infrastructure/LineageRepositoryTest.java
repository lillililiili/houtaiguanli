package com.uav.lowaltitude.modules.fusion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LineageRepositoryTest {
    @Test
    void h2KeepsAllFourRelationsAndStablePagination() {
        check(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""), false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
    void postgresMatchesExactArrayMembersAndSources() {
        String url = System.getenv("POSTGRES_TEST_URL");
        String user = System.getenv("POSTGRES_TEST_USER");
        String password = System.getenv("POSTGRES_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThat(admin.queryForObject("SELECT current_database()", String.class))
                .matches("^stage456_verify_[a-z0-9_]+$");
        String schema = "lineage_read_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            check(new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password), true);
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void check(DriverManagerDataSource source, boolean exactArrayMembership) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE target_lineage (
                  lineage_id VARCHAR(36) PRIMARY KEY, op VARCHAR(16) NOT NULL,
                  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                  survivor_target_id VARCHAR(36), origin_target_id VARCHAR(36),
                  member_target_ids JSONB NOT NULL, source_target_ids JSONB NOT NULL,
                  basis JSONB NOT NULL, algo_version VARCHAR(32) NOT NULL,
                  config_version VARCHAR(32) NOT NULL, operator_kind VARCHAR(8) NOT NULL,
                  operator_id VARCHAR(36), note VARCHAR(1000), snapshots JSONB NOT NULL,
                  created_at TIMESTAMP WITH TIME ZONE NOT NULL)
                """);
        var repository = new LineageRepository(jdbc);
        // Different IDs and times share the same table, so queries must not mix neighboring targets.
        for (int sample = 0; sample < 2; sample++) {
            String target = UUID.randomUUID().toString();
            String unrelated = UUID.randomUUID().toString();
            OffsetDateTime at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusDays(sample + 1);
            String survivor = insert(repository, at, target, null, "[]", "[]");
            String origin = insert(repository, at.plusSeconds(1), null, target, "[]", "[]");
            String member = insert(repository, at.plusSeconds(2), null, null, array(target), "[]");
            String sourceOnly = insert(repository, at.plusSeconds(3), null, null, "[]", array(target));
            // A row matching every relation still appears once. Ties use lineage_id for pagination.
            String repeated = insert(repository, at.plusSeconds(3), target, target, array(target), array(target));
            insert(repository, at.plusSeconds(4), unrelated, unrelated, array(unrelated), array(unrelated));
            if (exactArrayMembership) {
                insert(repository, at.plusSeconds(5), null, null, array("prefix-" + target), array(target + "-suffix"));
            }
            List<String> tail = List.of(sourceOnly, repeated).stream().sorted(Comparator.naturalOrder()).toList();
            List<String> expected = List.of(survivor, origin, member, tail.get(0), tail.get(1));
            assertThat(repository.countLineage(target)).isEqualTo(5);
            assertThat(repository.listLineage(target, 0, 20)).extracting(LineageRepository.LineageRow::lineageId)
                    .containsExactlyElementsOf(expected);
            assertThat(repository.listLineage(target, 2, 2)).extracting(LineageRepository.LineageRow::lineageId)
                    .containsExactlyElementsOf(expected.subList(2, 4));
            assertThat(repository.lastLineage(target).lineageId()).isEqualTo(expected.get(4));
        }
        String missing = UUID.randomUUID().toString();
        assertThat(repository.countLineage(missing)).isZero();
        assertThat(repository.listLineage(missing, 0, 20)).isEmpty();
        assertThat(repository.lastLineage(missing)).isNull();
    }

    private String insert(LineageRepository repository, OffsetDateTime at, String survivor, String origin,
            String members, String sources) {
        String id = UUID.randomUUID().toString();
        repository.insertLineage(new LineageRepository.LineageInsert(id, "MERGE", at, survivor, origin,
                members, sources, "{}", "test-algo", "test-config", "SYSTEM", null, null, "{}", at));
        return id;
    }

    private String array(String id) {
        // Test values are generated UUIDs or their alphanumeric prefixes/suffixes.
        return "[\"" + id + "\"]";
    }
}
