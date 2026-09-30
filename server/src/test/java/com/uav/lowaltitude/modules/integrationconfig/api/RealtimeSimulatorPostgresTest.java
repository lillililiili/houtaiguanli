package com.uav.lowaltitude.modules.integrationconfig.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

@ActiveProfiles(value={"test","postgres-test"},inheritProfiles=false)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class RealtimeSimulatorPostgresTest extends RealtimeSimulatorApiTest {
 private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
 @DynamicPropertySource static void database(DynamicPropertyRegistry p){DATABASE.springProperties(p);p.add("app.handoff.channel",()->"simulator");}
 @AfterAll static void close(){DATABASE.close();}
}
