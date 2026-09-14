package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.uav.lowaltitude.Application;

/**
 * production 必须压过 local：阶段 4 的告警/事件/风险演示夹具不允许因部署 profile 组合泄入生产。
 * 这里故意把 app.dev-seed.enabled 打开，证明仅靠 profile 门禁就足以阻止两个 seeder 注册和写表；
 * property 门禁由各 seeder 自己的测试覆盖。
 */
class ProductionStage4SeedIsolationTest {
    @Test void productionNeverRegistersStage4Seeders() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage4_seed_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=true", "--app.live-device.enabled=false",
                "--spring.main.banner-mode=off")) {
            assertThat(context.containsBean("localStage4AlarmSeeder")).isFalse();
            assertThat(context.containsBean("localStage4RiskSeeder")).isFalse();
            assertThat(context.getBeansOfType(LocalStage4AlarmSeeder.class)).isEmpty();
            assertThat(context.getBeansOfType(LocalStage4RiskSeeder.class)).isEmpty();
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("select count(*) from alarm where alarm_id like 'seed-stage4-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from uav_event where event_id like 'seed-stage4-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from integration_source where source_id like 'seed-stage4-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_id like 'seed-stage4-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from flight_risk where source_risk_id like 'STAGE4-SEED-%'", Integer.class)).isZero();
        }
    }
}
