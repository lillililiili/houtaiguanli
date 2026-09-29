package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

import io.moquette.broker.Server;

/** Opt-in loopback MQTT transport and disposable PostGIS database; no physical device actions. */
@EnabledIfSystemProperty(named="qa.mqtt.browser", matches="true")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/mqtt_browser_verify_[a-z0-9_]+")
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "server.address=127.0.0.1", "app.network.allow-loopback-when-listed=true",
        "app.source-mode=replay", "app.live-device.enabled=false", "app.mqtt.enabled=true",
        "app.fusion.enabled=true", "app.rule-engine.enabled=false", "app.automation-rules.enabled=false",
        "app.device-monitor-events.enabled=false", "app.device.mock-adapter.enabled=false",
        "app.fusion.replay.run-on-start=false", "app.rule-engine.replay.run-on-start=false"})
class MqttHeartbeatBrowserFixtureTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttRepository repository;
    @Autowired AppClock clock;
    @LocalServerPort int port;
    @TempDir Path temporary;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null || !url.matches("jdbc:postgresql://[^/]+/mqtt_browser_verify_[a-z0-9_]+"))
            throw new IllegalArgumentException("Only disposable MQTT browser databases are allowed");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
    }

    @Test void serveBrowserFixture() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).matches("jdbc:postgresql://[^/]+/mqtt_browser_verify_[a-z0-9_]+(?:\\?.*)?");
        }
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        AuthContext.set(new AuthUser(user,"admin1","MQTT browser fixture","ROLE-ADMIN",1,false,"ALL"));
        String org=jdbc.queryForObject("SELECT org_id FROM app_org WHERE org_code='ORG-DEV'",String.class);
        String district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY",String.class);
        Path dir=Path.of("target","mqtt-browser").toAbsolutePath();
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve("control"));
        int mqttPort;
        try(var socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))) { mqttPort=socket.getLocalPort(); }
        Properties properties=new Properties();
        properties.setProperty("host","127.0.0.1"); properties.setProperty("port",String.valueOf(mqttPort));
        properties.setProperty("allow_anonymous","true"); properties.setProperty("persistence_enabled","false");
        properties.setProperty("telemetry_enabled","false"); properties.setProperty("data_path",temporary.toString());
        Server broker=new Server();
        broker.startServer(properties);
        String brokerId=null;
        MqttClient publisher=new MqttClient("tcp://127.0.0.1:"+mqttPort,"qa-browser-"+UUID.randomUUID(),new MemoryPersistence());
        long firstHeartbeat=clock.nowMillis();
        long count=0;
        String mode="RUN";
        try {
            brokerId=configuration.create(new BrokerInput("QA-F32本机隔离心跳", "127.0.0.1",mqttPort,false,
                    null,null,"127.0.0.1/32","replay",org,district,null),key()).brokerId();
            configuration.enable(brokerId,0,true,key());
            String device=configuration.register(new Registration(LingyunEnvelope.PROTOCOL,brokerId,"qa-f32",
                    "QA-F32-SENSOR","radar","replay",org,district,"QA-F32-MQTT","QA-F32隔离心跳雷达",null,null,null),key());
            Binding binding=repository.binding(device,false);
            publisher.connect();
            long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
            long lastPublish=0;
            while(System.nanoTime()<deadline) {
                Path control=dir.resolve("control");
                if(Files.exists(control)) {
                    String requested=Files.readString(control).trim();
                    Files.delete(control);
                    if("STOP".equals(requested))break;
                    assertThat(List.of("RUN","PAUSE","TARGET_ONLY","HEARTBEAT_ONLY","FAULT","OLD","FUTURE")).contains(requested);
                    mode=requested;
                    if("OLD".equals(mode) || "FUTURE".equals(mode)) {
                        publishHeartbeat(publisher,binding,"OLD".equals(mode)?firstHeartbeat:clock.nowMillis()+86_400_000,1);
                        mode="PAUSE";
                    }
                }
                long now=clock.nowMillis();
                if(now-lastPublish>=1000 && Boolean.TRUE.equals(repository.status(device).get("subscribed"))) {
                    if(List.of("RUN","HEARTBEAT_ONLY","FAULT").contains(mode))
                        publishHeartbeat(publisher,binding,now,"FAULT".equals(mode)?2:1);
                    if(List.of("RUN","TARGET_ONLY","FAULT").contains(mode)) {
                        Map<String,Object> object=Map.of("objectId","QA-F32-TARGET","time",now-150,
                                "longitude",118.37,"latitude",37.46,
                                "extension",Map.of("objectType",30,"altitude",80));
                        publish(publisher,binding.topic(true),Map.of("deviceId",binding.externalDeviceId(),
                                "ptTime",now-100,"msgCnt",++count,"objects",List.of(object)));
                    }
                    lastPublish=now;
                }
                Map<String,Object> manifest=new LinkedHashMap<>();
                manifest.put("port",port); manifest.put("mqtt_port",mqttPort); manifest.put("broker_id",brokerId);
                manifest.put("device_id",device); manifest.put("sensing_device_id",binding.deviceId());
                manifest.put("mode",mode); manifest.put("simulated",true); manifest.put("clock",now);
                manifest.put("sense_published",count); manifest.put("heartbeat_timeout_ms",30000);
                manifest.put("state",jdbc.queryForMap("SELECT connectivity,work_state_code,health_code,last_heartbeat_at,observed_at,received_at FROM ops_device_state WHERE device_id=?",device));
                manifest.put("incidents",jdbc.queryForList("SELECT incident_id,incident_type,stage,detected_at,closed_at FROM device_incident WHERE device_id=? ORDER BY detected_at",device));
                manifest.put("diagnostics",jdbc.queryForList("SELECT outcome,reason,COUNT(*) AS count FROM mqtt_receive_diagnostic WHERE ops_device_id=? GROUP BY outcome,reason",device));
                Files.writeString(dir.resolve("manifest.json"),mapper.writeValueAsString(manifest));
                Thread.sleep(250);
            }
            assertThat(count).isPositive();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source=?",Long.class,binding.source())).isPositive();
        } finally {
            if(brokerId!=null) {
                var current=repository.broker(brokerId,false);
                configuration.enable(brokerId,current.version(),false,key());
            }
            if(publisher.isConnected())publisher.disconnect();
            publisher.close(); broker.stopServer(); AuthContext.clear();
        }
    }

    private void publishHeartbeat(MqttClient publisher,Binding binding,long time,int state) throws Exception {
        publish(publisher,binding.topic(false),Map.of("providerCode",binding.providerCode(),"deviceId",binding.externalDeviceId(),
                "deviceName","QA-F32隔离心跳雷达","deviceType",1,"workState",state,"ptTime",time,
                "deviceLongitude",118.36,"deviceLatitude",37.45,"deviceAltitude",25));
    }
    private void publish(MqttClient publisher,String topic,Object payload) throws Exception {
        publisher.publish(topic,mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8),1,false);
    }
    private static String key() { return UUID.randomUUID().toString(); }
}
