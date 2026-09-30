package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.uav.lowaltitude.Application;

/**
 * 有效生产配置不注册演示种子、不写样本，迁移目录和显式启用的正式能力继续验证。
 * 非法模拟开关必须拒绝启动，由 ProductionDevSeedIsolationTest 与 SimulationPolicyTest 单独覆盖。
 */
class ProductionReportingSeedIsolationTest {
    @Test void productionNeverRegistersReportingSeeder() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=" + profiles,
                "--spring.datasource.url=jdbc:h2:mem:reporting_seed_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=false", "--app.bootstrap-admin.enabled=false", "--app.live-device.enabled=false",
                "--app.rule-engine.enabled=false", "--app.fusion.enabled=false", "--spring.main.banner-mode=off")) {
            assertThat(context.containsBean("localReportingSeeder")).isFalse();
            List<String> reportingRunners = context.getBeansOfType(ApplicationRunner.class).keySet().stream()
                    .filter(name -> name.toLowerCase().contains("reporting")).toList();
            assertThat(reportingRunners).isEmpty();
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            // 统计事实表在生产启动后必须为空：数字只能来自真实业务表，不能来自演示种子。
            for (String table : List.of("report_airborne_target", "report_penalty_case")) {
                assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
            }
        }
    }
}
