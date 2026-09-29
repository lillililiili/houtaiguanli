package com.uav.lowaltitude.modules.device.api;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMonitoringEventRepository;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*")
class DeviceMonitoringEventPostgresTest extends DeviceMonitoringEventTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();

    @BeforeAll static void migrate() { DATABASE.initialize(); }
    @AfterAll static void cleanup() { DATABASE.close(); }

    @Override @BeforeEach void setup() {
        var source = DATABASE.dataSource();
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        events = new DeviceMonitoringEventRepository(jdbc);
        id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ops_device(device_id,device_no,name,device_type_code,device_type_name,channel,
                    enabled,source_mode,simulated,version,created_at,updated_at)
                VALUES (?,?,?,'radar','雷达','PG测试',TRUE,'replay',TRUE,0,0,0)
                """, id, id, "监测事件隔离测试");
        jdbc.update("""
                INSERT INTO ops_device_state(device_id,connectivity,work_state_code,has_alarm,health_code,
                    received_at,simulated,version)
                VALUES (?,'UNKNOWN',NULL,FALSE,'UNKNOWN',0,TRUE,0)
                """, id);
    }
}
