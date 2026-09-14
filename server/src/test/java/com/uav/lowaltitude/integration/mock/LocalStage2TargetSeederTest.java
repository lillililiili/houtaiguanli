package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.Application;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class LocalStage2TargetSeederTest {

    private static final String SEED_BEAN_NAME = "localStage2TargetSeeder";
    private static final List<String> TARGET_IDS = List.of(
            "seed-target-bird-wgs84",
            "seed-target-no-location",
            "seed-target-uav-wgs84");

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void enabledSeedCreatesDeterministicTargetAndTrackFixtures() {
        assertThat(seedTargetIds(jdbc)).containsExactlyElementsOf(TARGET_IDS);
        assertThat(jdbc.queryForList("""
                select target_id || ':' || target_no || ':' || coalesce(object_type_code, '') || ':' || source_mode
                from target
                where target_id like 'seed-target-%'
                order by target_id
                """, String.class)).containsExactly(
                        "seed-target-bird-wgs84:目标-0904-002:BIRD:mock",
                        "seed-target-no-location:目标-0904-003::mock",
                        "seed-target-uav-wgs84:目标-0904-001:UAV:mock");
        assertThat(jdbc.queryForObject("""
                select count(*) from target
                where target_id like 'seed-target-%'
                  and owner_org_id is not null and district_id is not null
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from target_latest_state
                where target_id like 'seed-target-%' and location is not null
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from target_latest_state
                where target_id = 'seed-target-no-location'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from target_source_link
                where target_id like 'seed-target-%'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from track
                where target_id like 'seed-target-%'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from track
                where target_id = 'seed-target-no-location'
                  and track_id = 'seed-track-no-location-001'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from track_point
                where track_id in ('seed-track-uav-001', 'seed-track-bird-001', 'seed-track-no-location-001')
                  and location is not null
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                select last_seen_at from target where target_id = 'seed-target-uav-wgs84'
                """, Timestamp.class)).isEqualTo(Timestamp.from(Instant.parse("2026-09-04T01:03:00Z")));
    }

    @Test
    void rerunningSeedDoesNotDuplicateFixtures() throws Exception {
        assertThat(context.containsBean(SEED_BEAN_NAME)).isTrue();
        if (!context.containsBean(SEED_BEAN_NAME)) return;

        ApplicationRunner seeder = context.getBean(SEED_BEAN_NAME, ApplicationRunner.class);
        ApplicationArguments args = new DefaultApplicationArguments(new String[0]);
        seeder.run(args);
        seeder.run(args);

        assertThat(seedTargetIds(jdbc)).containsExactlyElementsOf(TARGET_IDS);
        assertThat(jdbc.queryForObject("""
                select count(*) from track_point
                where track_id in ('seed-track-uav-001', 'seed-track-bird-001', 'seed-track-no-location-001')
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                select count(*) from target_latest_state
                where target_id = 'seed-target-no-location'
                """, Integer.class)).isZero();
    }

    @Test
    void disabledSeedCreatesNoFixtureBeanOrRows() {
        String database = "stage2_target_seed_disabled_" + UUID.randomUUID();
        try (ConfigurableApplicationContext disabled = start(database, "test", false)) {
            assertThat(disabled.containsBean(SEED_BEAN_NAME)).isFalse();
            assertThat(seedTargetIds(disabled.getBean(JdbcTemplate.class))).isEmpty();
        }
    }

    @Test
    void productionProfileCannotEnableTargetFixtures() {
        String database = "stage2_target_seed_production_" + UUID.randomUUID();
        try (ConfigurableApplicationContext production = start(database, "production", true)) {
            assertThat(production.containsBean(SEED_BEAN_NAME)).isFalse();
            assertThat(seedTargetIds(production.getBean(JdbcTemplate.class))).isEmpty();
        }
    }

    @Test
    void productionProfileWinsWhenLocalIsAlsoActive() {
        String database = "stage2_target_seed_production_local_" + UUID.randomUUID();
        try (ConfigurableApplicationContext production = start(database, "production,local", true)) {
            assertThat(production.containsBean(SEED_BEAN_NAME)).isFalse();
            assertThat(seedTargetIds(production.getBean(JdbcTemplate.class))).isEmpty();
        }
    }

    private static ConfigurableApplicationContext start(String database, String profiles, boolean enabled) {
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.profiles.active=" + profiles,
                        "--spring.datasource.url=jdbc:h2:mem:" + database
                                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.flyway.locations=classpath:db/migration",
                        "--app.dev-seed.enabled=" + enabled,
                        "--app.dev-seed.password=Stage2TargetSeed-9!",
                        "--app.live-device.enabled=false");
    }

    private static List<String> seedTargetIds(JdbcTemplate template) {
        return template.queryForList("""
                select target_id from target
                where target_id like 'seed-target-%'
                order by target_id
                """, String.class);
    }
}
