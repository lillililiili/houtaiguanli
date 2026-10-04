package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.disposal.api.CounterEvidenceFixture;

/** Opt-in loopback browser acceptance fixture; never starts external transports or touches business schemas. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.address=127.0.0.1")
@AutoConfigureMockMvc
@ActiveProfiles({"test","postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
@EnabledIfSystemProperty(named="qa.no-counter.browser",matches="true")
class NoCounterBrowserFixtureTest {
    static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){DATABASE.springProperties(p);}
    @AfterAll static void close(){DATABASE.close();}
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @LocalServerPort int port;
    @Test void serve()throws Exception {
        var fixture=new UavAdvisoryApiTest();fixture.jdbc=jdbc;fixture.json=json;fixture.mvc=mvc;fixture.fixture();
        jdbc.update("update app_role_permission set menu_enabled=true where role_code=?",fixture.role);
        for(String permission:java.util.List.of("alarms","punishment","target:read","evidence:read","handoff:read"))
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) values(?,?,'READ',true,current_timestamp)",fixture.role,permission);
        CounterEvidenceFixture.seed(jdbc,fixture.eventId);
        var blocked=new UavAdvisoryApiTest();blocked.jdbc=jdbc;blocked.json=json;blocked.mvc=mvc;blocked.fixture();
        assertThat(jdbc.queryForObject("select current_database()",String.class)).matches("stage456_verify_[a-z0-9_]+");
        Path directory=Path.of("target","no-counter-browser").toAbsolutePath();Files.createDirectories(directory);
        String run=java.util.UUID.randomUUID().toString();Path stop=directory.resolve(run+".stop"),risk=directory.resolve(run+".risk"),ack=directory.resolve(run+".risk-ack");
        json.writeValue(directory.resolve("metadata.json").toFile(),Map.of("base_url","http://127.0.0.1:"+port,"port",port,"event_id",fixture.eventId,"blocked_event_id",blocked.eventId,"session_token",fixture.session,"stop_file",stop.toString(),"risk_trigger_file",risk.toString(),"risk_ack_file",ack.toString()));
        long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
        while(!Files.exists(stop)&&System.nanoTime()<deadline) {
            if(Files.exists(risk)&&!Files.exists(ack)) {
                var now=java.time.Instant.now();
                NoCounterEvidenceFixture.append(jdbc,fixture.eventId,"ILLEGAL","[\"NEW_HEIGHT_VIOLATION\"]","[]","mock",now,now);
                Files.writeString(ack,"New reliable risk appended");
            }
            Thread.sleep(500);
        }
        assertThat(Files.exists(stop)).as("Browser acceptance must explicitly signal completion within 15 minutes").isTrue();
    }
}
