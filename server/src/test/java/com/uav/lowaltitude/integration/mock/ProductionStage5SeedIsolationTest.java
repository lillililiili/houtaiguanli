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
 * production 必须压过 local：阶段 5 的设备归属映射与交接演示夹具不允许因部署 profile 组合泄入生产。
 * 故意打开 app.dev-seed.enabled，证明仅靠 profile 门禁就足以阻止两个 seeder 注册和写表；
 * 生产也不得由启动器自动插入任何交接接收方。
 */
class ProductionStage5SeedIsolationTest {
    @Test void productionNeverRegistersStage5Seeders() { assertIsolated("production"); }
    @Test void productionAlsoWinsOverLocalProfile() { assertIsolated("production,local"); }

    private static void assertIsolated(String profiles) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
                "--spring.profiles.active=" + profiles,
                // 命令行参数优先级高于 application-local.yml，production,local 组合也只会连到这个隔离 H2。
                "--spring.datasource.url=jdbc:h2:mem:stage5_seed_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=true", "--app.live-device.enabled=false",
                "--spring.main.banner-mode=off")) {
            assertThat(context.containsBean("localStage5DeviceScopeSeeder")).isFalse();
            assertThat(context.containsBean("localStage5HandoffSeeder")).isFalse();
            assertThat(context.getBeansOfType(LocalStage5DeviceScopeSeeder.class)).isEmpty();
            assertThat(context.getBeansOfType(LocalStage5HandoffSeeder.class)).isEmpty();
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            // 设备映射只能来自受控迁移/配置导入：迁移本身不插入任何行，seeder 未注册时表必须为空。
            assertThat(jdbc.queryForObject("select count(*) from device_business_scope", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_org where org_id like 'seed-stage5-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from app_district where district_id like 'seed-stage5-%'", Integer.class)).isZero();
            // 生产不自动插入接收方：既无 seed 前缀行，也没有任何接收方。
            assertThat(jdbc.queryForObject("select count(*) from handoff_recipient where recipient_id like 'seed-stage5-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff_recipient", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff where handoff_id like 'seed-stage5-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff_material_snapshot where handoff_id like 'seed-stage5-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where delivery_id like 'seed-stage5-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_id like 'seed-stage5-%'", Integer.class)).isZero();
        }
    }
}
