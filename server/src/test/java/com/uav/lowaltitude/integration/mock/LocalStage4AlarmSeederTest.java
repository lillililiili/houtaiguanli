package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 固定演示数据只在 test/local 双门禁内存在，重跑不能覆盖人工状态。 */
@SpringBootTest(properties = "app.dev-seed.enabled=true")
@ActiveProfiles("test")
class LocalStage4AlarmSeederTest {
    @Autowired LocalStage4AlarmSeeder seeder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void isIdempotentKeepsSeparateAlarmsAndPreservesProcessedEvent() throws Exception {
        seeder.run(null);
        assertThat(jdbc.queryForObject("select count(*) from alarm where alarm_id like 'seed-stage4-alarm-%'", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from uav_event where alarm_id in ('seed-stage4-alarm-same-target-a','seed-stage4-alarm-same-target-b')", Long.class)).isEqualTo(2);
        jdbc.update("update uav_event set state_code='CONFIRMED',version=1 where event_id='seed-stage4-event-pending'");
        seeder.run(null);
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id='seed-stage4-event-pending'", String.class)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select version from uav_event where event_id='seed-stage4-event-pending'", Long.class)).isEqualTo(1L);
    }

    @Test
    void productionAndDisabledSeedContextsNeverRegisterOrInsertStageFourSamples() {
        assertSeedIsAbsent("stage4-production", new String[] { "production" }, true);
        assertSeedIsAbsent("stage4-production-local", new String[] { "production", "local" }, true);
        assertSeedIsAbsent("stage4-test-disabled", new String[] { "test" }, false);
    }

    private void assertSeedIsAbsent(String database, String[] profiles, boolean enabled) {
        String url = "jdbc:h2:mem:" + database + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(com.uav.lowaltitude.Application.class)
                .profiles(profiles)
                .properties("spring.main.web-application-type=none")
                // command-line property source 的优先级高于 application-local，确保 production+local 也使用隔离 H2。
                .run("--spring.datasource.url=" + url, "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa", "--spring.datasource.password=",
                        "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=" + enabled,
                        "--app.live-device.enabled=false")) {
            // profile 与 property 两道门禁均要实测，不能仅依赖 @Profile/@ConditionalOnProperty 注解推断。
            assertThat(context.getBeansOfType(LocalStage4AlarmSeeder.class)).isEmpty();
            JdbcTemplate isolated = context.getBean(JdbcTemplate.class);
            assertThat(isolated.queryForObject("select count(*) from alarm where alarm_id like 'seed-stage4-alarm-%'", Long.class)).isZero();
            assertThat(isolated.queryForObject("select count(*) from uav_event where event_id like 'seed-stage4-event-%'", Long.class)).isZero();
        }
    }
}
