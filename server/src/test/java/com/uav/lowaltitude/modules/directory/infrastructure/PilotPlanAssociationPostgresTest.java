package com.uav.lowaltitude.modules.directory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Executes the production association SQL in a disposable schema, without application jobs or seed data. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class PilotPlanAssociationPostgresTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 7})
    void newestAssociationNeverTurnsAnEvaluationRefreshOrUnknownIntoMissingPilot(int daysAgo) {
        String url = System.getenv("POSTGRES_TEST_URL");
        String user = System.getenv("POSTGRES_TEST_USER"), password = System.getenv("POSTGRES_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThat(admin.queryForObject("SELECT current_database()", String.class)).matches("^stage456_verify_[a-z0-9_]+$");
        String schema = "pilot_plan_" + key().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password));
            jdbc.setQueryTimeout(5);
            tables(jdbc);
            var repository = new DirectoryRepository(jdbc, new ObjectMapper());
            String event = key(), alarm = key(), target = key(), org = key(), district = key();
            jdbc.update("INSERT INTO alarm VALUES (?,?,'replay',?,?)", alarm, target, org, district);
            jdbc.update("INSERT INTO uav_event VALUES (?,?,?,?)", event, alarm, org, district);
            assertUnknown(repository.currentPilotPlanAssociation(event));

            Instant at = Instant.now().minusSeconds(daysAgo * 86400L);
            String old = evaluation(jdbc, alarm, target, org, district, "replay", "NONE", null, at);
            assertThat(repository.currentPilotPlanAssociation(event).explicitlyNoPlan()).isTrue();
            assertThat(repository.latestEventEvaluation(event)).isEqualTo(old);
            String plan = plan(jdbc, org, district, "replay");
            String full = evaluation(jdbc, alarm, target, org, district, "replay", "FULL", plan, at.plusSeconds(1));
            // A latest valid association must take precedence over the historical no-plan evaluation.
            assertThat(repository.eventPlan(event, old)).isNull();
            var association = repository.currentPilotPlanAssociation(event);
            assertThat(association.evaluationId()).isEqualTo(full);
            assertThat(association.planId()).isEqualTo(plan);
            assertThat(association.explicitlyNoPlan()).isFalse();
            assertThat(repository.latestEventEvaluation(event)).isEqualTo(full);
            evaluation(jdbc, alarm, target, org, district, "replay", "PARTIAL", plan, at.plusSeconds(2));
            // Exact old race: both evaluations have a valid plan, yet the second read of the old ID is null.
            assertThat(repository.eventPlan(event, full)).isNull();
            assertThat(repository.currentPilotPlanAssociation(event).planId()).isEqualTo(plan);

            evaluation(jdbc, alarm, target, org, district, "replay", "FULL", key(), at.plusSeconds(3));
            assertUnknown(repository.currentPilotPlanAssociation(event));
            String foreignOrgPlan = plan(jdbc, key(), district, "replay");
            evaluation(jdbc, alarm, target, org, district, "replay", "FULL", foreignOrgPlan, at.plusSeconds(4));
            assertUnknown(repository.currentPilotPlanAssociation(event));
            String foreignDistrictPlan = plan(jdbc, org, key(), "replay");
            evaluation(jdbc, alarm, target, org, district, "replay", "FULL", foreignDistrictPlan, at.plusSeconds(5));
            assertUnknown(repository.currentPilotPlanAssociation(event));
            evaluation(jdbc, alarm, target, org, district, "replay", "UNDETERMINED", null, at.plusSeconds(6));
            assertUnknown(repository.currentPilotPlanAssociation(event));
            evaluation(jdbc, alarm, target, org, district, "replay", "NONE", plan, at.plusSeconds(7));
            assertUnknown(repository.currentPilotPlanAssociation(event));
            evaluation(jdbc, key(), target, org, district, "replay", "NONE", null, at.plusSeconds(8));
            assertUnknown(repository.currentPilotPlanAssociation(event));
            evaluation(jdbc, alarm, target, org, district, "live", "NONE", null, at.plusSeconds(9));
            assertUnknown(repository.currentPilotPlanAssociation(event));
            String latest = evaluation(jdbc, alarm, target, org, district, "replay", "NONE", null, at.plusSeconds(10));
            assertThat(repository.currentPilotPlanAssociation(event).evaluationId()).isEqualTo(latest);
            assertThat(repository.currentPilotPlanAssociation(event).explicitlyNoPlan()).isTrue();
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static void assertUnknown(DirectoryRepository.PilotPlanAssociation value) {
        if (value == null) return;
        assertThat(value.planId()).isNull();
        assertThat(value.explicitlyNoPlan()).isFalse();
    }

    private static void tables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR(36) PRIMARY KEY,alarm_id VARCHAR(36),owner_org_id VARCHAR(36),district_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE alarm(alarm_id VARCHAR(36) PRIMARY KEY,target_id VARCHAR(36),source_mode VARCHAR(16),owner_org_id VARCHAR(36),district_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE rule_evaluation(evaluation_id VARCHAR(36) PRIMARY KEY,target_id VARCHAR(36),alarm_id VARCHAR(36),owner_org_id VARCHAR(36),district_id VARCHAR(36),source_mode VARCHAR(16),mode VARCHAR(16),plan_match_code VARCHAR(32),plan_id VARCHAR(36),evaluated_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE flight_plan(plan_id VARCHAR(36) PRIMARY KEY,owner_org_id VARCHAR(36),district_id VARCHAR(36),source_mode VARCHAR(16))");
    }

    private static String evaluation(JdbcTemplate jdbc, String alarm, String target, String org, String district,
            String source, String match, String plan, Instant at) {
        String id = key();
        jdbc.update("INSERT INTO rule_evaluation VALUES (?,?,?,?,?,?,'ACTIVE',?,?,?)", id, target, alarm, org, district, source, match, plan, Timestamp.from(at));
        return id;
    }

    private static String plan(JdbcTemplate jdbc, String org, String district, String source) {
        String id = key();
        jdbc.update("INSERT INTO flight_plan VALUES (?,?,?,?)", id, org, district, source);
        return id;
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
