package com.uav.lowaltitude.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/** Uses its own disposable database, including public-schema notification migrations. */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class RealtimePostgresTest {
    private static final String DATABASE = "realtime_" + UUID.randomUUID().toString().replace("-", "");
    private static final String USER = System.getenv("POSTGRES_TEST_USER");
    private static final String PASSWORD = System.getenv("POSTGRES_TEST_PASSWORD");
    private static String url;

    @BeforeAll static void migrate() throws Exception {
        String base = System.getenv("POSTGRES_TEST_URL");
        if (!base.startsWith("jdbc:postgresql://") || base.contains("?"))
            throw new IllegalStateException("Use an isolated PostgreSQL database URL without parameters");
        url = base.substring(0, base.lastIndexOf('/') + 1) + DATABASE;
        try (var root = DriverManager.getConnection(base, USER, PASSWORD); var sql = root.createStatement()) {
            sql.execute("CREATE DATABASE " + DATABASE);
        }
        try (var database = DriverManager.getConnection(url, USER, PASSWORD); var sql = database.createStatement()) {
            sql.execute("CREATE EXTENSION postgis");
        }
        Flyway.configure().dataSource(url, USER, PASSWORD)
                .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
    }

    @AfterAll static void cleanup() throws Exception {
        if (url == null) return;
        try (var root = DriverManager.getConnection(System.getenv("POSTGRES_TEST_URL"), USER, PASSWORD); var sql = root.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    @Test void allNewTablesHaveThreeNotificationTriggersAndEmptyUpdatesStayQuiet() throws Exception {
        String[] tables = {"device_sensing_profile", "device_connection_profile", "ops_maintenance_workflow_event",
            "ops_maintenance_commission", "ops_maintenance_message_read", "ops_device_maintenance_notice_attempt",
            "ops_maintenance_plan", "ops_maintenance_report", "ops_maintenance_incident", "flight_device_check_schedule", "fusion_config"};
        try (var connection = connection(); var sql = connection.createStatement()) {
            sql.execute("LISTEN app_data_change");
            for (String table : tables) {
                try (var rows = sql.executeQuery("SELECT count(*) FROM pg_trigger WHERE tgrelid='public." + table
                        + "'::regclass AND tgname LIKE 'trg_app_data_change_%'")) {
                    rows.next(); assertThat(rows.getInt(1)).as(table).isEqualTo(3);
                }
                sql.execute("DELETE FROM " + table + " WHERE FALSE");
            }
            assertThat(connection.unwrap(PGConnection.class).getNotifications(200)).isNullOrEmpty();
        }
    }

    @Test void notificationsAreCoalescedAfterCommitAndNeverDeliveredOnRollback() throws Exception {
        try (var receiver = connection(); var writer = connection(); var listen = receiver.createStatement(); var sql = writer.createStatement()) {
            listen.execute("LISTEN app_data_change");
            var pg = receiver.unwrap(PGConnection.class);
            writer.setAutoCommit(false);
            sql.executeUpdate("UPDATE fusion_config SET version=version+1");
            sql.executeUpdate("UPDATE fusion_config SET version=version+1");
            assertThat(pg.getNotifications(200)).isNullOrEmpty();
            writer.commit();
            assertThat(pg.getNotifications(1000)).extracting(PGNotification::getParameter).containsExactly("fusion");
            sql.executeUpdate("UPDATE fusion_config SET version=version+1");
            writer.rollback();
            assertThat(pg.getNotifications(200)).isNullOrEmpty();
        }
    }

    @Test void listenerPublishesChangesAndSignalsDatabaseLossAndRecovery() throws Exception {
        String application = "realtime-test-" + UUID.randomUUID();
        var hub = mock(RealtimeHub.class);
        var listener = new DataChangeListener(hub, true, url + "?ApplicationName=" + application, USER, PASSWORD, 50);
        try (var connection = connection(); var sql = connection.createStatement()) {
            listener.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(hub).setListening(true));
            verify(hub, timeout(5000)).publish(Set.of("*"));
            clearInvocations(hub);
            sql.executeUpdate("UPDATE fusion_config SET version=version+1");
            verify(hub, timeout(5000)).publish(Set.of("fusion"));
            clearInvocations(hub);
            sql.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='" + application + "'");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(hub).setListening(false));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(hub).setListening(true));
            verify(hub, timeout(5000)).publish(anySet());
            assertThat(listener.isListening()).isTrue();
        } finally {
            listener.stop();
            await().atMost(Duration.ofSeconds(5)).until(() -> !listener.isListening());
        }
    }

    private Connection connection() throws Exception { return DriverManager.getConnection(url, USER, PASSWORD); }
}
