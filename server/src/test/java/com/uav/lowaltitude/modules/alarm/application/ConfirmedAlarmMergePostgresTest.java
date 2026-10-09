package com.uav.lowaltitude.modules.alarm.application;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

@SpringBootTest
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Transactional
class ConfirmedAlarmMergePostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @Autowired AlarmMergePolicy policy;
    @Autowired JdbcTemplate jdbc;
    private AlarmMergePolicyTest fixture;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.password", () -> "changeme");
    }
    @BeforeEach void prepare() {
        fixture=new AlarmMergePolicyTest(); fixture.policy=policy; fixture.jdbc=jdbc; fixture.fixture();
    }
    @Test void lastViolationAndUnknownStopGuard() { fixture.confirmedCloseWindowStartsAtLastViolationAndUnknownDoesNotProveItStopped(); }
    @Test void actualFalsePositiveVerificationStartsUpgradeWindow() { fixture.confirmedFalsePositiveUpgradeWindowUsesActualVerificationTime(); }
    @ParameterizedTest @CsvSource({"-1,MERGED", "0,MERGED", "1,CREATED"})
    void confirmedFiveMinuteBoundary(long offsetMillis,String expected) { fixture.confirmedDedupWindowIncludesExactlyFiveMinutes(offsetMillis,expected); }
    @ParameterizedTest @CsvSource({"-121,SUFFICIENT,0", "-120,SUFFICIENT,1", "0,SUFFICIENT,1", "1,SUFFICIENT,0", "0,INSUFFICIENT,0"})
    void currentObservedLegalEvidenceIsRequired(long observedOffset,String assurance,int expected) { fixture.confirmedCloseRequiresFreshObservedEvidenceAndAssurance(observedOffset,assurance,expected); }
    @Test void missingObservationAndStaleAssessmentMomentCannotCloseEpisode() { fixture.confirmedCloseRejectsMissingObservedTimeAndAnOutdatedAssessmentMoment(); }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
}
