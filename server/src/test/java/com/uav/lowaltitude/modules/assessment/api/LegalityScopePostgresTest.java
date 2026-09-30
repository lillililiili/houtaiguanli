package com.uav.lowaltitude.modules.assessment.api;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Scoped read-contract regression; each run owns a disposable migrated PostgreSQL schema. */
@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@SpringBootTest
@AutoConfigureMockMvc
class LegalityScopePostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    LegalityReviewApiTest fixture;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.password", () -> "changeme");
    }
    @BeforeEach void prepare() {
        fixture = new LegalityReviewApiTest();
        fixture.mvc = mvc; fixture.jdbc = jdbc; fixture.json = json;
        fixture.fixture();
    }
    @Test void objectTypeFilterKeepsOnlyConfirmedUavTargetsAndCountsSameScope() throws Exception {
        fixture.objectTypeFilterKeepsOnlyConfirmedUavTargetsAndCountsSameScope();
    }
    @ParameterizedTest
    @CsvSource({"LEGAL,NULL", "ILLEGAL,0", "ILLEGAL,99", "ILLEGAL,NULL"})
    void reliableConclusionsDoNotRequireReviewRegardlessOfScore(String conclusion, String score) throws Exception {
        fixture.reliableConclusionsDoNotRequireReviewRegardlessOfScore(conclusion, score);
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
}
