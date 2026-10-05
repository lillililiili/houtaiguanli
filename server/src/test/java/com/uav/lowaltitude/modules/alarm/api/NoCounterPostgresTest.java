package com.uav.lowaltitude.modules.alarm.api;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class NoCounterPostgresTest extends NoCounterApiTest {
    static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){DATABASE.springProperties(p);p.add("app.advisory.auto-sms.enabled",()->true);p.add("app.advisory.auto-voice.enabled",()->true);}
    @AfterAll static void cleanup(){DATABASE.close();}
}
