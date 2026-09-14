package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.eclipse.paho.client.mqttv3.*;
import io.moquette.broker.Server;
import com.uav.lowaltitude.integration.mqtt.*;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.modules.device.application.*;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.device.infrastructure.LiveDeviceRepository;
import com.uav.lowaltitude.platform.security.*;
import com.uav.lowaltitude.platform.time.AppClock;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mqtt_p1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.mqtt.enabled=false","app.fusion.enabled=false","app.rule-engine.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MqttIngressTest {
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttIngressService ingress;
    @MockitoSpyBean MqttRepository repository;
    @Autowired DeviceService devices;
    @Autowired LiveDeviceRepository tcp;
    @Autowired MqttNetworkPolicy network;
    @Autowired EnvironmentCredentialResolver credentials;
    @Autowired AppClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository;
    @Autowired DeviceIncidentService incidents;
    @TempDir Path temporary;
    String org,district,owner,brokerId;

    @BeforeEach void setup() {
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        AuthContext.set(new AuthUser(user,"admin1","MQTT test","ROLE-ADMIN",1,false,"ALL"));
        org=jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROW ONLY",String.class);
        district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY",String.class);
        owner=UUID.randomUUID().toString();
        brokerId=configuration.create(input(1883),key()).brokerId();
        configuration.enable(brokerId,0,true,key());
        assertThat(repository.claim(brokerId,owner,clock.nowMillis())).isTrue();
    }
    @AfterEach void cleanup() {
        jdbc.update("UPDATE mqtt_broker SET enabled=FALSE");
        AuthContext.clear();
    }
    private BrokerInput input(int port) {
        return new BrokerInput("MQTT test", "127.0.0.1",port,false,null,null,"127.0.0.1/32","replay",org,district,null);
    }
    private Registration registration(String type) {
        return new Registration(LingyunEnvelope.PROTOCOL,brokerId,"fixture-provider","external-1",type,"replay",org,district,
                "MQ-"+UUID.randomUUID(),"MQTT fixture",null,null,null);
    }
    private Binding register(String type) { return repository.binding(configuration.register(registration(type),key()),false); }
    private void receive(Binding b,String body,boolean sense,int packet,boolean retained,boolean dup) {
        ingress.receive(brokerId,owner,b.topic(sense),body.getBytes(StandardCharsets.UTF_8),packet,1,retained,dup,clock.nowMillis());
    }
    private String sense(long time,long count) { return "{\"deviceId\":\"external-1\",\"ptTime\":"+time+",\"msgCnt\":"+count+",\"objects\":[]}"; }
    private String heartbeat(String type,int workState,Long time) {
        return heartbeat(type, workState, time, null, null, null);
    }
    private String heartbeat(String type,int workState,Long time, Double longitude, Double latitude, Double altitude) {
        StringBuilder body = new StringBuilder("{\"providerCode\":\"fixture-provider\",\"deviceId\":\"external-1\",\"deviceName\":\"fixture\",\"deviceType\":")
                .append(LingyunEnvelope.TYPES.get(type)).append(",\"workState\":").append(workState);
        if (time != null) body.append(",\"ptTime\":").append(time);
        if (longitude != null) body.append(",\"deviceLongitude\":").append(longitude);
        if (latitude != null) body.append(",\"deviceLatitude\":").append(latitude);
        if (altitude != null) body.append(",\"deviceAltitude\":").append(altitude);
        return body.append("}").toString();
    }
    private long inboxCount(Binding b) { return jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source=?",Long.class,b.source()); }
    private static String key() { return UUID.randomUUID().toString(); }

    @Test void heartbeatOutageCreatesOneIncidentAndOnlyFreshHeartbeatCanRecoverIt() throws Exception {
        Binding b = register("radar");
        MqttConnectivityIncidentJob job = new MqttConnectivityIncidentJob(deviceRepository, clock);
        Runnable reconcile = () -> new TransactionTemplate(transactions).executeWithoutResult(s -> job.reconcile());
        reconcile.run();
        String count = "SELECT COUNT(*) FROM device_incident WHERE device_id=?";
        assertThat(jdbc.queryForObject(count, Long.class, b.opsDeviceId())).isZero();
        receive(b, heartbeat("radar", 0, clock.nowMillis()), false, 1, false, false);
        jdbc.update("UPDATE ops_device_state SET last_heartbeat_at=? WHERE device_id=?", clock.nowMillis()-31_000, b.opsDeviceId());
        repository.expire(clock.nowMillis());
        reconcile.run();
        reconcile.run();
        assertThat(jdbc.queryForObject(count, Long.class, b.opsDeviceId())).isOne();
        String incident = jdbc.queryForObject("SELECT incident_id FROM device_incident WHERE device_id=?", String.class, b.opsDeviceId());
        assertThat(incidents.get(incident).allowedActions()).containsExactly("VERIFY_RECOVERY");
        assertThatThrownBy(() -> incidents.reboot(incident, key(), "测试重启能力"))
                .hasMessageContaining("未定义重启指令");
        assertThat(incidents.recoveryCheck(incident, key()).result()).isEqualTo("FAIL");
        Thread.sleep(5);
        receive(b, heartbeat("radar", 0, clock.nowMillis()), false, 2, false, false);
        String recoveryKey = key();
        assertThat(incidents.recoveryCheck(incident, recoveryKey).result()).isEqualTo("PASS");
        assertThat(incidents.recoveryCheck(incident, recoveryKey).result()).isEqualTo("PASS");
        assertThat(incidents.get(incident).incident().stage()).isEqualTo("RECOVERED");
        jdbc.update("UPDATE ops_device_state SET last_heartbeat_at=? WHERE device_id=?", clock.nowMillis()-31_000, b.opsDeviceId());
        repository.expire(clock.nowMillis());
        reconcile.run();
        assertThat(jdbc.queryForObject(count, Long.class, b.opsDeviceId())).isEqualTo(2);
    }

    @Test void threeTypesCreateBothIdentitiesAndReceiveOnlyTargetsIntoInbox() {
        for(String type:List.of("radar","5ga","tdoa","aoa","dcd","rid")) {
            Binding b=register(type);
            assertThat(b.deviceId()).isNotEqualTo(b.opsDeviceId());
            String expectedType = switch (type) {
                case "radar" -> "RADAR"; case "5ga" -> "FIVE_G_A"; case "tdoa" -> "TDOA";
                case "aoa" -> "AOA"; case "dcd" -> "DCD"; case "rid" -> "RID";
                default -> throw new IllegalStateException(type);
            };
            assertThat(jdbc.queryForObject("SELECT source_type FROM integration_source WHERE source_id=?",
                    String.class, b.sourceId())).isEqualTo(expectedType);
            receive(b,heartbeat(type,0,null),false,1,false,false);
            assertThat(devices.state(b.opsDeviceId()).connectivity()).isEqualTo("ONLINE");
            assertThat(devices.state(b.opsDeviceId()).workStateCode()).isEqualTo("0");
            assertThat(inboxCount(b)).isZero();
            receive(b,sense(1000,1),true,2,false,false);
            assertThat(inboxCount(b)).isOne();
            assertThat(jdbc.queryForObject("SELECT status FROM inbox_message WHERE source=?",String.class,b.source())).isEqualTo("RECEIVED");
            assertThat(jdbc.queryForObject("SELECT received_at FROM inbox_message WHERE source=?",Long.class,b.source())).isGreaterThan(1000);
        }
        assertThat(tcp.enabledDevices()).noneMatch(row -> LingyunEnvelope.PROTOCOL.equals(row.get("protocol_code")));
    }
    @Test void controlStaticGoesOnlineWithoutInboxAndSenseDataIsRejected() {
        for (String type : List.of("dec", "ifr", "bsc")) {
            Binding b = register(type);
            assertThat(jdbc.queryForObject("SELECT source_type FROM integration_source WHERE source_id=?",
                    String.class, b.sourceId())).isNull();
            receive(b, heartbeat(type, 0, null), false, 1, false, false);
            assertThat(devices.state(b.opsDeviceId()).connectivity()).isEqualTo("ONLINE");
            assertThat(devices.state(b.opsDeviceId()).workStateCode()).isEqualTo("0");
            assertThat(inboxCount(b)).isZero();
            ingress.receive(brokerId, owner, b.topic(true), sense(1000, 1).getBytes(StandardCharsets.UTF_8),
                    2, 1, false, false, clock.nowMillis());
            assertThat(inboxCount(b)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mqtt_receive_diagnostic WHERE broker_id=? AND reason='UNSUPPORTED_TYPE'",
                    Long.class, brokerId)).isGreaterThanOrEqualTo(1L);
        }
    }
    @Test void duplicatesConflictsWrapAndOutOfOrderPreserveFirstPayloadAndLatestCounter() {
        Binding b=register("radar");
        receive(b,sense(1000,Integer.MAX_VALUE),true,1,false,false);
        receive(b,sense(1000,Integer.MAX_VALUE),true,2,false,false);
        receive(b,sense(1000,Integer.MAX_VALUE)+" ",true,3,false,false);
        receive(b,sense(2000,0),true,4,false,false);
        receive(b,sense(500,10),true,5,false,false);
        receive(b,sense(3000,4),true,6,false,false);
        assertThat(inboxCount(b)).isEqualTo(4);
        var status=configuration.status(b.opsDeviceId());
        assertThat(((Number)status.get("duplicate_count")).longValue()).isOne();
        assertThat(((Number)status.get("conflict_count")).longValue()).isOne();
        assertThat(((Number)status.get("suspected_gap_count")).longValue()).isOne();
        assertThat(repository.binding(b.opsDeviceId(),false).lastPtTime()).isEqualTo(3000);
    }
    @Test void staticLocationFillsEmptyLedgerThenLeavesExistingCoordinates() {
        Binding b = register("dcd");
        receive(b, heartbeat("dcd", 1, 2000L), false, 1, false, false);
        assertThat(jdbc.queryForObject("SELECT longitude FROM ops_device WHERE device_id=?", Double.class, b.opsDeviceId())).isNull();
        receive(b, heartbeat("dcd", 1, 3000L, 118.62, 37.42, 12.5), false, 2, false, false);
        assertThat(jdbc.queryForObject("SELECT longitude FROM ops_device WHERE device_id=?", java.math.BigDecimal.class, b.opsDeviceId()))
                .isEqualByComparingTo("118.62");
        assertThat(jdbc.queryForObject("SELECT latitude FROM ops_device WHERE device_id=?", java.math.BigDecimal.class, b.opsDeviceId()))
                .isEqualByComparingTo("37.42");
        assertThat(jdbc.queryForObject("SELECT altitude_m FROM ops_device WHERE device_id=?", java.math.BigDecimal.class, b.opsDeviceId()))
                .isEqualByComparingTo("12.5");
        assertThat(jdbc.queryForObject("SELECT coordinate_system FROM ops_device WHERE device_id=?", String.class, b.opsDeviceId()))
                .isEqualTo("WGS-84");
        receive(b, heartbeat("dcd", 1, 4000L, 119.0, 38.0, 99.0), false, 3, false, false);
        assertThat(jdbc.queryForObject("SELECT longitude FROM ops_device WHERE device_id=?", java.math.BigDecimal.class, b.opsDeviceId()))
                .isEqualByComparingTo("118.62");
        receive(b, heartbeat("dcd", 1, 5000L, 118.1, null, null), false, 4, false, false);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mqtt_receive_diagnostic WHERE broker_id=? AND outcome='REJECTED' AND reason='INVALID_ENVELOPE'",
                Long.class, brokerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT longitude FROM ops_device WHERE device_id=?", java.math.BigDecimal.class, b.opsDeviceId()))
                .isEqualByComparingTo("118.62");
        assertThat(inboxCount(b)).isZero();
    }

    @Test void retainedRedeliveryAndOldStaticCannotRefreshOnlineOrRegressWorkState() {
        Binding b=register("tdoa");
        receive(b,heartbeat("tdoa",1,2000L),false,1,false,false);
        long received=devices.state(b.opsDeviceId()).lastHeartbeatAt();
        receive(b,heartbeat("tdoa",0,1000L),false,2,false,false);
        receive(b,heartbeat("tdoa",1,2000L),false,1,false,true);
        receive(b,heartbeat("tdoa",2,4000L),false,3,true,false);
        receive(b,sense(4000,4),true,4,true,false);
        assertThat(devices.state(b.opsDeviceId()).lastHeartbeatAt()).isEqualTo(received);
        assertThat(devices.state(b.opsDeviceId()).workStateCode()).isEqualTo("1");
        repository.expire(received+30_000);
        assertThat(devices.state(b.opsDeviceId()).connectivity()).isEqualTo("OFFLINE");
        assertThat(inboxCount(b)).isZero();
    }
    @Test void rejectsUnknownMalformedMismatchedDisabledAndUnsupportedWithoutCreatingDevices() {
        Binding b=register("radar"); long before=jdbc.queryForObject("SELECT COUNT(*) FROM ops_device",Long.class);
        for(String topic:List.of("bridge/unknown/device_data/radar/external-1", "bridge/fixture-provider/device_data/oe/external-1"))
            ingress.receive(brokerId,owner,topic,sense(1000,1).getBytes(StandardCharsets.UTF_8),1,1,false,false,clock.nowMillis());
        receive(b,"{",true,2,false,false);
        receive(b,sense(1000,1).replace("external-1","wrong"),true,3,false,false);
        receive(b,"{}",false,4,false,false);
        configuration.enableDevice(b.opsDeviceId(),0,false,key());
        receive(b,sense(1000,1),true,5,false,false);
        assertThat(inboxCount(b)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device",Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mqtt_receive_diagnostic WHERE broker_id=? AND outcome='REJECTED'",Long.class,brokerId)).isEqualTo(6);
    }
    @Test void registrationRollbackIdempotencyVersionAndPermissions() {
        var p=registration("radar"); configuration.register(p,key());
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM integration_source",Long.class);
        assertThatThrownBy(() -> configuration.register(p,key())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM integration_source",Long.class)).isEqualTo(before);
        assertThatThrownBy(() -> configuration.enable(brokerId,0,false,key())).hasMessageContaining("刷新");
        String repeat=key(); configuration.enable(brokerId,1,false,repeat);
        assertThatThrownBy(() -> configuration.enable(brokerId,1,false,repeat)).hasMessageContaining("已提交");
        var actor=AuthContext.require();
        AuthContext.set(new AuthUser(actor.userId(),actor.account(),actor.name(),actor.roleCode(),1,false,"NONE"));
        assertThatThrownBy(() -> configuration.register(registration("5ga"),key())).hasMessageContaining("范围");
    }
    @Test void concurrentLeaseAndDuplicateDeliveryHaveOneEffectiveOwnerAndInboxRecord() throws Exception {
        Binding b=register("radar");
        assertThat(repository.claim(brokerId,"competitor",clock.nowMillis())).isFalse();
        var executor=Executors.newFixedThreadPool(2);
        try {
            var barrier=new CyclicBarrier(2);
            Callable<Void> action=() -> { barrier.await(); receive(b,sense(1000,1),true,4,false,false); return null; };
            var first=executor.submit(action); var second=executor.submit(action);
            first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS);
            assertThat(inboxCount(b)).isOne();
        } finally { executor.shutdownNow(); }
        assertThatThrownBy(() -> ingress.receive(brokerId,"competitor",b.topic(true),new byte[0],1,1,false,false,clock.nowMillis()))
                .hasMessage("MQTT_LEASE_LOST");
    }
    @Test void liveAndReplayIdentitiesCannotBeMixedAndMissingLiveCredentialsFailClosed() {
        var bad=new BrokerInput("live", "127.0.0.1",1883,false,null,null,"127.0.0.1/32","live",org,district,null);
        assertThatThrownBy(() -> configuration.create(bad,key())).hasMessageContaining("白名单");
        var live=new BrokerInput("live", "192.0.2.1",1883,false,null,null,"192.0.2.0/24","live",org,district,null);
        Broker b=configuration.create(live,key());
        assertThatThrownBy(() -> configuration.enable(b.brokerId(),0,true,key())).hasMessageContaining("凭据");
        assertThat(repository.broker(b.brokerId(),false).enabled()).isFalse();
        String originalBroker=brokerId;
        Binding replay=register("radar");
        brokerId=configuration.create(input(1884),key()).brokerId();
        Binding other=register("radar");
        assertThat(replay.source()).isNotEqualTo(other.source());
        assertThatThrownBy(() -> jdbc.update("UPDATE mqtt_device_binding SET source_mode='live' WHERE ops_device_id=?",replay.opsDeviceId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        brokerId=originalBroker;
    }
    @Test void databaseFailureRollsBackReceiptInboxAndDiagnosticBeforeSuccessfulRetry() {
        Binding b=register("radar");
        doThrow(new DataAccessResourceFailureException("injected")).doCallRealMethod().when(repository).inbox(any(),any(),anyLong());
        assertThatThrownBy(() -> receive(b,sense(1000,1),true,8,false,false)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(inboxCount(b)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mqtt_delivery_receipt WHERE broker_id=?",Long.class,brokerId)).isZero();
        receive(b,sense(1000,1),true,8,false,true);
        assertThat(inboxCount(b)).isOne();
    }
    @Test void realMqttRedeliveryReconnectAndServiceRestartUsePersistentSession() throws Exception {
        int port;
        try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        Properties props=new Properties(); props.setProperty("host","127.0.0.1"); props.setProperty("port",String.valueOf(port));
        props.setProperty("allow_anonymous","true"); props.setProperty("persistence_enabled","false");
        props.setProperty("data_path",temporary.toString()); props.setProperty("telemetry_enabled","false");
        Server server=new Server(); server.startServer(props);
        configuration.enable(brokerId,1,false,key()); repository.release(brokerId,owner,clock.nowMillis());
        brokerId=configuration.create(input(port),key()).brokerId(); configuration.enable(brokerId,0,true,key());
        Binding b=register("radar");
        MqttSessionSupervisor supervisor=new MqttSessionSupervisor(repository,ingress,configuration,network,credentials,clock);
        MqttClient publisher=new MqttClient("tcp://127.0.0.1:"+port,"fixture-"+key(),new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
        try {
            publisher.connect(); supervisor.reconcile();
            assertThat(repository.status(b.opsDeviceId()).get("subscribed")).isEqualTo(true);
            doThrow(new DataAccessResourceFailureException("injected")).doCallRealMethod().when(repository).inbox(any(),any(),anyLong());
            publisher.publish(b.topic(true),sense(1000,1).getBytes(StandardCharsets.UTF_8),1,false);
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                supervisor.reconcile(); assertThat(inboxCount(b)).isOne();
            });
            verify(repository,atLeast(2)).inbox(any(),any(),anyLong());
            publisher.publish(b.topic(false),heartbeat("radar",0,null).getBytes(StandardCharsets.UTF_8),1,false);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT connectivity FROM ops_device_state WHERE device_id=?", String.class, b.opsDeviceId())).isEqualTo("ONLINE"));
            supervisor.shutdown();
            publisher.publish(b.topic(true),sense(2000,2).getBytes(StandardCharsets.UTF_8),1,false);
            MqttSessionSupervisor restarted=new MqttSessionSupervisor(repository,ingress,configuration,network,credentials,clock);
            try {
                await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                    restarted.reconcile(); assertThat(inboxCount(b)).isEqualTo(2);
                });
                configuration.enableDevice(b.opsDeviceId(),0,false,key()); restarted.reconcile();
                assertThat(repository.status(b.opsDeviceId()).get("subscribed")).isEqualTo(false);
            } finally { restarted.shutdown(); }
        } finally {
            supervisor.shutdown(); if(publisher.isConnected()) publisher.disconnect(); publisher.close(); server.stopServer();
        }
    }
}
