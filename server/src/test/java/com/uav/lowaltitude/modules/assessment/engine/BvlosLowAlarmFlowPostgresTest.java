package com.uav.lowaltitude.modules.assessment.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/**
 * 同 {@link BvlosLowAlarmFlowTest} 的三个端到端用例，跑在隔离的 PostgreSQL/PostGIS schema 上（Flyway 全量迁移 + 种子，
 * 已发布参数受只增触发器保护）：飞手/目标坐标经 ST_X/ST_Y 读回、JSONB 落库、告警与事件建行都走真实数据库。
 * 父类的嵌套桩配置不会被子类自动发现，这里显式导入。
 */
@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import({DeviceMonitoringPostgresFixture.NoScheduledJobs.class, BvlosLowAlarmFlowTest.Stubs.class})
class BvlosLowAlarmFlowPostgresTest extends BvlosLowAlarmFlowTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.password", () -> "changeme");
    }

    @AfterAll static void closeDatabase() { DATABASE.close(); }
}
