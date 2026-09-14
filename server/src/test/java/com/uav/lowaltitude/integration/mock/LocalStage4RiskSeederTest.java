package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.uav.lowaltitude.Application;

@SpringBootTest(properties = "app.dev-seed.enabled=true")
@ActiveProfiles("test")
class LocalStage4RiskSeederTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalStage4RiskSeeder seeder;
    @Autowired ApplicationArguments arguments;

    @Test
    void seedIsIdempotentAndRestartNeverResetsProcessedState() throws Exception {
        long before = count();
        String processed = jdbc.queryForObject("select risk_id from flight_risk where state_code<>'PENDING_VERIFICATION' order by risk_id fetch first 1 row only", String.class);
        String state = jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, processed);
        long version = jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, processed);
        seeder.run(arguments);
        assertThat(count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?", String.class, processed)).isEqualTo(state);
        assertThat(jdbc.queryForObject("select version from flight_risk where risk_id=?", Long.class, processed)).isEqualTo(version);
    }

    @Test
    void seedsUnknownHeightAndExactScopeCounterexampleWithoutNotifiedState() {
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_id='seed-stage4-risk-unknown-height' and observed_altitude_m is null and height_relation='UNKNOWN'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where owner_org_id='seed-stage3-other-org' and district_id='seed-stage3-other-district'", Long.class)).isGreaterThan(0L);
        // 阶段 4 夹具不得写已通知；阶段 5 交接种子会把 seed-stage5-% 推到已通知，不能用全库计数。
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_id like 'seed-stage4-%' and state_code='NOTIFIED'", Long.class)).isZero();
    }

    @Test
    void profileAndPropertyGateExcludeProductionEvenWhenLocalIsAlsoActive() {
        Profile profile = LocalStage4RiskSeeder.class.getAnnotation(Profile.class);
        ConditionalOnProperty property = LocalStage4RiskSeeder.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(profile).isNotNull();
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches(name -> java.util.Set.of("production", "local").contains(name))).isFalse();
        assertThat(expression.matches(name -> java.util.Set.of("production").contains(name))).isFalse();
        assertThat(expression.matches(name -> java.util.Set.of("test").contains(name))).isTrue();
        assertThat(property.havingValue()).isEqualTo("true");
    }

    @Test
    void actualIsolatedContextsDoNotSeedWhenProductionOrPropertyGateBlocks() {
        assertIsolatedSeedAbsent(true,"production");
        assertIsolatedSeedAbsent(true,"production","local");
        assertIsolatedSeedAbsent(false,"test");
    }

    private void assertIsolatedSeedAbsent(boolean enabled,String... profiles) {
        String database="risk-gate-"+java.util.UUID.randomUUID();
        String[] args={
                "--spring.datasource.url=jdbc:h2:mem:"+database+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.driver-class-name=org.h2.Driver","--spring.datasource.username=sa","--spring.datasource.password=",
                "--spring.flyway.locations=classpath:db/migration","--app.dev-seed.enabled="+enabled,"--app.live-device.enabled=false",
                "--spring.main.banner-mode=off"};
        try(ConfigurableApplicationContext context=new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).profiles(profiles).run(args)) {
            assertThat(context.getBeansOfType(LocalStage4RiskSeeder.class)).isEmpty();
            JdbcTemplate isolated=context.getBean(JdbcTemplate.class);
            assertThat(isolated.queryForObject("select count(*) from flight_risk",Long.class)).isZero();
        }
    }

    private long count() { return jdbc.queryForObject("select count(*) from flight_risk where source_id='seed-stage3-source' and source_risk_id like 'STAGE4-SEED-%'", Long.class); }
}
