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
 * 有效生产配置不注册演示种子、不写样本，迁移目录和显式启用的正式能力继续验证。
 * 非法模拟开关必须拒绝启动，由 ProductionDevSeedIsolationTest 与 SimulationPolicyTest 单独覆盖。
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
                "--spring.flyway.locations=classpath:db/migration", "--app.dev-seed.enabled=false", "--app.bootstrap-admin.enabled=false", "--app.live-device.enabled=false",
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
            assertThat(jdbc.queryForObject("select count(*) from handoff_recipient where recipient_id<>'fixed-superior-recipient'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff where handoff_id like 'seed-stage5-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff_material_snapshot where handoff_id like 'seed-stage5-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where delivery_id like 'seed-stage5-%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from flight_risk where risk_id like 'seed-stage5-%'", Integer.class)).isZero();
        }
    }
}
