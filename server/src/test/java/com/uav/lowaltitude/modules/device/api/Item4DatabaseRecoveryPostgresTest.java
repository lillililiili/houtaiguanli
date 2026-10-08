package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.moquette.broker.Server;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;

/** Opt-in destructive test confined to a labeled, loopback-only disposable database container. */
@ActiveProfiles(value={"test","postgres-test"},inheritProfiles=false)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="ITEM4_DATABASE_CONTAINER",matches="item4-postgis-[0-9]{8}[a-z0-9]*")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_item4_[0-9]{8}[a-z0-9]*")
class Item4DatabaseRecoveryPostgresTest extends LingyunControlMqttTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.outbox.enabled",()->true);
        registry.add("spring.datasource.hikari.connection-timeout",()->5000);
        registry.add("spring.datasource.hikari.validation-timeout",()->2000);
    }
    @AfterAll static void closeDatabase(){DATABASE.close();}

    @Test void databaseLossClosesTransportAndReacquiresLeaseWithoutChangingBusinessRecords() throws Exception {
        String container=System.getenv("ITEM4_DATABASE_CONTAINER");
        var description=mapper.readTree(docker("inspect",container)).get(0);
        String suffix=container.substring("item4-postgis-".length());
        assertThat(description.path("Name").asText()).isEqualTo("/"+container);
        assertThat(description.path("Config").path("Labels").path("uav.acceptance").asText()).isEqualTo("item4-"+suffix);
        var ports=description.path("HostConfig").path("PortBindings").path("5432/tcp");
        assertThat(ports.size()).isEqualTo(1);
        assertThat(ports.get(0).path("HostIp").asText()).isEqualTo("127.0.0.1");
        assertThat(ports.get(0).path("HostPort").asText()).isEqualTo("25432");
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stage456_verify_item4_"+suffix);
        String ownedId=description.path("Id").asText();
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        Properties settings=new Properties();settings.setProperty("host","127.0.0.1");settings.setProperty("port",String.valueOf(port));
        settings.setProperty("allow_anonymous","true");settings.setProperty("persistence_enabled","false");
        settings.setProperty("data_path",temporary.toString());settings.setProperty("telemetry_enabled","false");
        Server broker=new Server();broker.startServer(settings);
        var supervisor=new MqttSessionSupervisor(mqtt,ingress,configuration,network,credentials,clock);
        MqttClient peer=new MqttClient("tcp://127.0.0.1:"+port,"item4-db-peer-"+UUID.randomUUID(),new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
        List<Map<String,Object>> evidence=new ArrayList<>();
        try {
            configuration.enable(brokerId,1,false,UUID.randomUUID().toString());
            brokerId=configuration.create(new BrokerInput("item4-database-fault","127.0.0.1",port,false,null,null,"127.0.0.1/32",
                    "replay",org,district,null),UUID.randomUUID().toString()).brokerId();
            configuration.enable(brokerId,0,true,UUID.randomUUID().toString());
            AtomicInteger received=new AtomicInteger();String topic="item4/probe/"+UUID.randomUUID();
            peer.connect();peer.subscribe(topic,1,(t,p)->received.incrementAndGet());
            long commands=jdbc.queryForObject("select count(*) from device_command",Long.class);
            long inbox=jdbc.queryForObject("select count(*) from inbox_message",Long.class);
            for(int round=1;round<=3;round++) {
                supervisor.reconcile();
                supervisor.publish(brokerId,topic,"before".getBytes(StandardCharsets.UTF_8));
                final int before=round*2-1;
                await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(received.get()).isEqualTo(before));
                docker("kill",ownedId);
                try {
                    supervisor.reconcile();
                    assertThatThrownBy(()->supervisor.publish(brokerId,topic,"forbidden".getBytes(StandardCharsets.UTF_8)))
                        .isInstanceOf(IllegalStateException.class).hasMessage("MQTT_NOT_CONNECTED");
                    assertThat(received.get()).isEqualTo(before);
                } finally {docker("start",ownedId);}
                await().atMost(Duration.ofSeconds(40)).ignoreExceptions().until(()->jdbc.queryForObject("select 1",Integer.class)==1);
                supervisor.reconcile();
                assertThat(jdbc.queryForObject("select connection_state from mqtt_session_lease where broker_id=?",String.class,brokerId)).isEqualTo("CONNECTED");
                supervisor.publish(brokerId,topic,"after".getBytes(StandardCharsets.UTF_8));
                final int after=round*2;
                await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(received.get()).isEqualTo(after));
                assertThat(jdbc.queryForObject("select count(*) from device_command",Long.class)).isEqualTo(commands);
                assertThat(jdbc.queryForObject("select count(*) from inbox_message",Long.class)).isEqualTo(inbox);
                evidence.add(Map.of("round",round,"blocked_while_database_down",true,"recovered",true,"received",after));
            }
            Path output=Path.of("target","item4-database-recovery");Files.createDirectories(output);
            mapper.writeValue(output.resolve(UUID.randomUUID()+".json").toFile(),evidence);
        } finally {
            docker("start",ownedId);
            supervisor.shutdown();
            if(peer.isConnected())peer.disconnect();peer.close();broker.stopServer();
        }
    }

    private String docker(String... arguments) throws Exception {
        List<String> command=new ArrayList<>();command.add(System.getenv("ITEM4_DOCKER"));command.addAll(List.of(arguments));
        // Docker inspect can exceed the Windows pipe buffer; drain it while waiting.
        Path output=Files.createTempFile(temporary,"item4-docker-",".json");
        try {
            Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);throw new IllegalStateException("isolated Docker operation timed out");}
            String result=Files.readString(output,StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as("isolated Docker operation").isZero();return result;
        } finally {Files.deleteIfExists(output);}
    }
}
