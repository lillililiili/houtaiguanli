package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import com.uav.lowaltitude.Application;

/** production 必须压过 local：演示飞行计划及研判不允许因部署 profile 组合泄入生产。 */
class ProductionStage3SeedIsolationTest {
    @Test void productionNeverRegistersStage3Seeder() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }
    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=" + profiles,
                "--spring.datasource.url=jdbc:h2:mem:stage3_seed_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=true", "--app.live-device.enabled=false")) {
            assertThat(context.containsBean("localStage3PlanningSeeder")).isFalse();
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("select count(*) from flight_plan where plan_id like 'seed-stage3-%'", Integer.class)).isZero();
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("select count(*) from assessment_result where assessment_id like 'seed-stage3-%'", Integer.class)).isZero();
        }
    }
}
