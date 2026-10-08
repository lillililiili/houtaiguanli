package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import io.moquette.broker.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.fusion.application.FusionIngestWorker;

/** Isolated technical protocol acceptance; no claim of field-device effects or business approval. */
@ActiveProfiles(value={"test","postgres-test"},inheritProfiles=false)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="ITEM1_SIMULATOR_ROOT",matches=".+")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stage456_verify_[a-z0-9_]+")
class ProtocolABPythonPostgresTest extends LingyunControlMqttTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){
        DATABASE.springProperties(p);
        p.add("app.mqtt.enabled",()->true);
        p.add("app.fusion.enabled",()->true);
        p.add("app.outbox.enabled",()->true);
    }
    @AfterAll static void closeDatabase(){DATABASE.close();}
    @Autowired MqttSessionSupervisor supervisor;
    @Autowired FusionIngestWorker fusion;

    @Test void pythonSixSensorsAndFiveReplyModesTravelThroughRealMqtt() throws Exception {
        int port;
        try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        Properties props=new Properties();
        props.setProperty("host","127.0.0.1");props.setProperty("port",String.valueOf(port));
        props.setProperty("allow_anonymous","true");props.setProperty("persistence_enabled","false");
        props.setProperty("data_path",temporary.toString());props.setProperty("telemetry_enabled","false");
        Server broker=new Server();broker.startServer(props);
        Process python=null;
        try {
            configuration.enable(brokerId,1,false,UUID.randomUUID().toString());mqtt.release(brokerId,owner,clock.nowMillis());
            brokerId=configuration.create(new BrokerInput("item1-python","127.0.0.1",port,false,null,null,"127.0.0.1/32",
                    "replay",org,district,null),UUID.randomUUID().toString()).brokerId();
            configuration.enable(brokerId,0,true,UUID.randomUUID().toString());
            Map<String,Object> entries=new LinkedHashMap<>(),devices=new LinkedHashMap<>();
            Map<String,Binding> bindings=new LinkedHashMap<>();
            for(String kind:List.of("radar","5ga","tdoa","aoa","dcd","rid")) {
                String key="item1-"+kind;
                bindings.put(key,registerPeer(key,kind));
                devices.put(key,Map.of("kind",kind));
            }
            String[] modes={"success","failure","no_receipt","late","duplicate","ifr_success","bsc_success"};
            String[] kinds={"dec","ifr","bsc","ifr","dec","ifr","bsc"};
            for(int i=0;i<modes.length;i++) {
                String key="b-"+modes[i];bindings.put(key,registerPeer(key,kinds[i]));
                devices.put(key,Map.of("kind",kinds[i],"protocolB",Map.of("response",i==1?"failure":i==2?"no_receipt":"success",
                        "delayMs",i==3?1500:0,"duplicateCount",i==4?1:0)));
            }
            bindings.forEach((key,b)->entries.put(key,Map.of("external_id",b.externalDeviceId(),"kind",b.deviceTypeAbbr(),"platform_id",b.opsDeviceId())));
            supervisor.reconcile();
            Path config=temporary.resolve("python-peer.json"),ready=temporary.resolve("ready"),events=temporary.resolve("events.ndjson");
            mapper.writeValue(config.toFile(),Map.of("test_scope","stage456_verify","host","127.0.0.1","port",port,
                    "client_id","item1-"+UUID.randomUUID(),"ready",ready.toString(),"events",events.toString(),"devices",devices,
                    "manifest",Map.of("source_mode","replay","provider","item1-python","devices",entries)));
            python=new ProcessBuilder(System.getenv().getOrDefault("ITEM1_PYTHON","python"),"-u",
                    Path.of(System.getenv("ITEM1_SIMULATOR_ROOT"),"tests","run_protocol_ab_peer.py").toString(),config.toString())
                    .redirectErrorStream(true).redirectOutput(temporary.resolve("python.log").toFile()).start();
            await().atMost(Duration.ofSeconds(20)).until(()->Files.exists(ready));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(jdbc.queryForObject(
                    "select count(*) from inbox_message where source like 'lingyun:%' and source in (select 'lingyun:'||device_type_abbr||':'||device_id from mqtt_device_binding where broker_id=?)",Integer.class,brokerId)).isEqualTo(6));
            fusion.drain();
            assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like 'lingyun:%' and status='FAILED'",Integer.class)).isZero();
            Map<String,Object> observations=new LinkedHashMap<>();
            for(String kind:List.of("radar","5ga","tdoa","aoa","dcd","rid")) {
                String source="lingyun:"+kind+":"+bindings.get("item1-"+kind).deviceId();
                assertThat(jdbc.queryForObject("select status from inbox_message where source=?",String.class,source)).isEqualTo("DONE");
                Map<String,Object> row=jdbc.queryForMap("select o.identity_clue, o.location is not null as has_position, o.altitude_amsl_m, cast(o.quality as varchar) as quality from source_observation o join inbox_message i on i.inbox_id=o.inbox_id where i.source=?",source);
                assertThat(row.get("identity_clue")).isEqualTo(List.of("tdoa","dcd","rid").contains(kind)?"SIM-ITEM1-"+kind.toUpperCase(Locale.ROOT):null);
                assertThat(row.get("has_position")).isEqualTo(!kind.equals("aoa"));
                assertThat(row.get("altitude_amsl_m")).isNull();
                assertThat(mapper.readTree((String)row.get("quality")).path("altitude_datum").asText()).isEqualTo("REFERENCE_UNKNOWN");
                observations.put(kind,row);
            }
            Map<String,Object> results=new LinkedHashMap<>();
            for(int i=0;i<modes.length;i++) {
                Binding b=bindings.get("b-"+modes[i]);
                int cmd=kinds[i].equals("bsc")?70001:(kinds[i].equals("dec")?50002:60003);
                String command=control.enqueue(b.opsDeviceId(),UUID.randomUUID().toString(),"AUTH-ITEM1-TEST",1,cmd,Map.of(),"隔离协议响应验证");
                control.dispatch(command);
                if(i==2 || i==3) control.timeout(command,"测试超时与迟到关联，不修改生产超时配置");
                final String expected=i==1?"FAILED":i==2||i==3?"TIMED_OUT":"SUCCEEDED";
                final int receipts=i==2?0:1;
                await().atMost(Duration.ofSeconds(10)).untilAsserted(()-> {
                    assertThat(jdbc.queryForObject("select status from device_command where command_id=?",String.class,command)).isEqualTo(expected);
                    assertThat(jdbc.queryForObject("select count(*) from command_receipt where command_id=?",Integer.class,command)).isEqualTo(receipts);
                });
                if(i==3) assertThat(jdbc.queryForObject("select receipt_kind from command_receipt where command_id=?",String.class,command)).isEqualTo("PROTOCOL_B_LATE");
                results.put(modes[i],Map.of("command_id",command,"status",expected,"receipts",receipts));
            }
            Path evidence=Path.of("target","item1-evidence");Files.createDirectories(evidence);
            Files.copy(events,evidence.resolve("python-mqtt.ndjson"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("protocol-ab-postgres.json").toFile(),Map.of("sensors",observations,"results",results,"physical_device",false));
        } finally {
            if(python!=null){python.destroy();if(!python.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))python.destroyForcibly();}
            supervisor.shutdown();broker.stopServer();
        }
    }

    private Binding registerPeer(String key,String kind){
        Binding b=mqtt.binding(configuration.register(new Registration(LingyunEnvelope.PROTOCOL,brokerId,"item1-python",key,kind,"replay",org,district,
                "ITEM1-"+UUID.randomUUID().toString().substring(0,12),"隔离测试 "+kind,null,null,null),UUID.randomUUID().toString()),false);
        jdbc.update("update ops_device set enabled=true where device_id=?",b.opsDeviceId());
        return b;
    }
}
