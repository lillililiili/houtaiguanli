package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.MqttNetworkPolicy;
import com.uav.lowaltitude.integration.mqtt.MqttSessionSupervisor;
import com.uav.lowaltitude.modules.device.application.EoEdgeIngressService;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.application.MqttIngressService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.modules.fusion.application.FusionProperties;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import io.moquette.broker.Server;

/**
 * 2026-10-08: restarting the backend while an EO stop command was being sent left every device offline until a
 * second restart. The command dispatcher locks the device row and publishes inside that transaction; the restarted
 * supervisor's first reconcile records the subscription on the same row while holding its own monitor. PostgreSQL
 * row locks never time out, so the two waited on each other for good. Only PostgreSQL shows it.
 */
@SpringBootTest(properties = {"app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false",
        "app.eo-edge.heartbeat-timeout-millis=3000", "app.device-monitor-events.enabled=false"})
@ActiveProfiles(value = {"test", "postgres-test"}, inheritProfiles = false)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EoDispatchDuringReconnectPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @Autowired MqttConfigurationService configuration;
    @Autowired EoEdgeIngressService ingress;
    @Autowired EoEdgeRepository edges;
    @Autowired MqttRepository mqtt;
    @Autowired MqttNetworkPolicy network;
    @Autowired EnvironmentCredentialResolver credentials;
    @Autowired AppClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;

    @AfterEach void cleanup() {
        jdbc.update("UPDATE mqtt_broker SET enabled=FALSE");
        AuthContext.clear();
    }

    @Test void commandHoldingTheDeviceRowIsSentWhileTheFirstReconcileAfterRestartWaitsForThatRow() throws Exception {
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        AuthContext.set(new AuthUser(user, "admin1", "EO restart test", "ROLE-ADMIN", 1, false, "ALL"));
        String org = jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROW ONLY", String.class);
        String district = jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY", String.class);
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Properties settings = new Properties();
        settings.setProperty("host", "127.0.0.1"); settings.setProperty("port", String.valueOf(port));
        settings.setProperty("allow_anonymous", "true"); settings.setProperty("persistence_enabled", "false");
        settings.setProperty("data_path", temporary.toString()); settings.setProperty("telemetry_enabled", "false");
        Server broker = new Server(); broker.startServer(settings);
        String brokerId = configuration.create(new BrokerInput("EO restart", "127.0.0.1", port, false, null, null,
                "127.0.0.1/32", "replay", org, district, null), key()).brokerId();
        configuration.enable(brokerId, 0, true, key());
        String opsId = configuration.register(new Registration(EoEdgeEnvelope.PROTOCOL, brokerId, null, "eo-restart", null,
                "replay", org, district, "EO-" + UUID.randomUUID(), "光电夹具", null, null, null,
                "edge-restart-" + UUID.randomUUID().toString().substring(0, 8)), key());
        var binding = edges.binding(opsId, false);
        // A freshly started backend: its first reconcile connects, then marks the device row as subscribed.
        var supervisor = new MqttSessionSupervisor(mqtt, new MqttIngressService(mqtt, clock, new FusionProperties()),
                configuration, network, credentials, clock, ingress, edges, 3000);
        MqttClient edge = new MqttClient("tcp://127.0.0.1:" + port, "edge-" + key(), new MemoryPersistence());
        ExecutorService threads = Executors.newFixedThreadPool(2);
        AtomicInteger dispatcherPid = new AtomicInteger();
        try {
            var received = new CountDownLatch(1);
            edge.connect();
            edge.subscribe(binding.dispatcherTopic(), 1, (topic, message) -> received.countDown());
            var rowLocked = new CountDownLatch(1);
            // Same order as EoEdgeCommandService.dispatch sending a stop: lock the device row, then publish in that transaction.
            Future<?> dispatcher = threads.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                dispatcherPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                edges.binding(opsId, true);
                rowLocked.countDown();
                await().atMost(Duration.ofSeconds(20)).until(() -> waitingFor(dispatcherPid.get()));
                supervisor.publish(brokerId, binding.dispatcherTopic(), "EndTracking".getBytes(StandardCharsets.UTF_8));
            }));
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> reconcile = threads.submit(supervisor::reconcile);
            try {
                dispatcher.get(20, TimeUnit.SECONDS);
                reconcile.get(20, TimeUnit.SECONDS);
            } catch (TimeoutException stuck) {
                // End the dispatcher's transaction so the suite does not hang on the cycle, then report it.
                jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, dispatcherPid.get());
                fail("command sending and the reconnect check waited on each other", stuck);
            }
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT connection_state FROM mqtt_session_lease WHERE broker_id=?", String.class, brokerId))
                    .isEqualTo("CONNECTED");
            assertThat(jdbc.queryForObject("SELECT subscribed FROM eo_device_binding WHERE ops_device_id=?", Boolean.class, opsId)).isTrue();
        } finally {
            threads.shutdownNow();
            supervisor.shutdown();
            if (edge.isConnected()) edge.disconnect();
            edge.close();
            broker.stopServer();
        }
    }

    /** True once another session waits for a lock held by the given backend. */
    private boolean waitingFor(int pid) {
        Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                Integer.class, pid);
        return waiting != null && waiting > 0;
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
