package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.fusion.application.FusionIngestWorker;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineWorker;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskEvaluationJob;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import io.moquette.broker.Server;

/** Original Python simulator may connect only to this disposable QA schema and loopback broker. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.address=127.0.0.1")
@ActiveProfiles({"test","postgres-test"})
@Import({DeviceMonitoringPostgresFixture.NoScheduledJobs.class,SimulatorMqttBrowserFixtureTest.NoExternalActions.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
class SimulatorMqttBrowserFixtureTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        for(String key:List.of("app.mqtt.enabled","app.fusion.enabled","app.rule-engine.enabled","app.rule-engine.c04.enabled","app.rule-engine.allow-demo-active"))
            registry.add(key,()->true);
        registry.add("app.fusion.replay.seed-enabled",()->false);
        registry.add("app.dev-seed.password",()->"changeme");
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttSessionSupervisor mqtt;
    @Autowired FusionIngestWorker fusion;
    @Autowired RuleEngineWorker legality;
    @Autowired SpaceRiskEvaluationJob spaceRisk;
    @LocalServerPort int port;
    @TempDir Path brokerDirectory;

    @Test @EnabledIfSystemProperty(named="qa.simulator.mqtt.browser",matches="true")
    void serveOriginalSimulatorWithIsolatedPostgisAndRealMqtt() throws Exception {
        Path output=Path.of("target","simulator-mqtt-browser").toAbsolutePath(); Files.createDirectories(output);
        Path stop=output.resolve("stop"); Files.deleteIfExists(stop);
        int mqttPort; try(var socket=new java.net.ServerSocket(0,50,java.net.InetAddress.getLoopbackAddress())) { mqttPort=socket.getLocalPort(); }
        var properties=new Properties();
        properties.setProperty("host","127.0.0.1"); properties.setProperty("port",Integer.toString(mqttPort));
        properties.setProperty("allow_anonymous","true"); properties.setProperty("persistence_enabled","false");
        properties.setProperty("telemetry_enabled","false"); properties.setProperty("data_path",brokerDirectory.toString());
        var broker=new Server(); String brokerId=null;
        try {
            broker.startServer(properties);
            String user=jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class);
            AuthContext.set(new AuthUser(user,"admin1","隔离模拟器验收","ROLE-ADMIN",1,false,"ALL"));
            brokerId=configuration.create(new BrokerInput("local-lingyun-replay","127.0.0.1",mqttPort,false,null,null,
                    "127.0.0.1/32","replay","seed-stage3-org","seed-stage3-district",null),UUID.randomUUID().toString()).brokerId();
            configuration.enable(brokerId,0,true,UUID.randomUUID().toString()); AuthContext.clear(); mqtt.reconcile();
            var identity=jdbc.queryForMap("select current_database() as database,current_schema() as schema");
            assertThat(identity.get("database").toString()).matches("stage456_verify_[a-z0-9_]+");
            assertThat(identity.get("schema").toString()).matches("monitor_events_[a-f0-9]{32}");
            Files.writeString(output.resolve("manifest.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "http_port",port,"mqtt_port",mqttPort,"broker_id",brokerId,"database",identity.get("database"),"schema",identity.get("schema"),
                    "source_mode","replay","simulated",true,"controlled_actions_enabled",false,"account","admin1")));
            long deadline=System.nanoTime()+Duration.ofMinutes(35).toNanos(),nextEvaluation=0;
            while(!Files.exists(stop) && System.nanoTime()<deadline) {
                mqtt.reconcile(); fusion.drain();
                if(System.nanoTime()>=nextEvaluation) { legality.tick(); spaceRisk.tick(); nextEvaluation=System.nanoTime()+Duration.ofSeconds(2).toNanos(); }
                writeState(output.resolve("state.json")); Thread.sleep(500);
            }
            writeState(output.resolve("final.json"));
            assertThat(Files.exists(stop)).as("The browser review must explicitly close its isolated fixture").isTrue();
            assertThat(jdbc.queryForObject("select count(*) from device_command",Integer.class)).isZero();
            Files.deleteIfExists(stop);
        } finally {
            AuthContext.clear();
            if(brokerId!=null) { jdbc.update("update mqtt_broker set enabled=false where broker_id=?",brokerId); mqtt.reconcile(); }
            broker.stopServer();
        }
    }

    private void writeState(Path path) throws Exception {
        var state=new java.util.LinkedHashMap<String,Object>();
        state.put("simulated",true); state.put("recorded_at",java.time.Instant.now().toString());
        state.put("devices",jdbc.queryForList("select d.device_id,d.device_no,d.name,d.longitude,d.latitude,s.connectivity,s.health_code,s.work_state_code,s.observed_at from ops_device d join ops_device_state s on s.device_id=d.device_id where d.device_no like 'sim-%' order by d.device_no"));
        state.put("targets",jdbc.queryForList("select distinct t.target_id,t.target_no,t.object_type_code,t.uav_sn,ST_X(s.location) as longitude,ST_Y(s.location) as latitude,s.observed_at from target t join target_latest_state s on s.target_id=t.target_id join target_source_link l on l.target_id=t.target_id join mqtt_device_binding b on b.source_id=l.source_id where b.provider_code='map-sim' order by t.target_no"));
        state.put("evaluations",jdbc.queryForList("select e.target_id,e.legal_status,e.violation_reasons,e.unknown_reasons,e.evaluated_at from rule_evaluation e where e.target_id in (select l.target_id from target_source_link l join mqtt_device_binding b on b.source_id=l.source_id where b.provider_code='map-sim') order by e.evaluated_at desc fetch first 100 rows only"));
        state.put("routes",jdbc.queryForList("select r.route_no,v.route_version_id,ST_AsGeoJSON(v.centerline) as geometry from route r join route_version v on v.route_id=r.route_id where r.route_no like 'sim-%'"));
        state.put("airspaces",jdbc.queryForList("select a.airspace_no,v.kind_code,ST_AsGeoJSON(v.boundary) as geometry from airspace a join airspace_version v on v.airspace_id=a.airspace_id where a.airspace_no like 'sim-%'"));
        state.put("controlled_commands",jdbc.queryForObject("select count(*) from device_command",Integer.class));
        Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(state));
    }

    /** Test-only ingress restriction: registering sensors stays available; controlled actions stay unavailable. */
    @org.springframework.boot.test.context.TestConfiguration
    static class NoExternalActions {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> simulatorReadAndSensorOnly() {
            jakarta.servlet.Filter filter=(request,response,chain)-> {
                var http=(jakarta.servlet.http.HttpServletRequest)request;
                String uri=http.getRequestURI();
                if(!"GET".equals(http.getMethod()) && (uri.contains("/commands/") || uri.contains("/disposal-authorizations")
                        || uri.contains("/emergency-stop") || uri.contains("/handoffs") || uri.contains("/advisory"))) {
                    var result=(jakarta.servlet.http.HttpServletResponse)response; result.setStatus(403); result.setContentType("application/json");
                    result.getWriter().write("{\"ok\":false,\"error\":{\"code\":\"ISOLATED_SENSING_ONLY\",\"message\":\"This isolated fixture accepts sensing input only\"}}");
                } else chain.doFilter(request,response);
            };
            var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter); registration.setOrder(Integer.MIN_VALUE); return registration;
        }
    }
}
