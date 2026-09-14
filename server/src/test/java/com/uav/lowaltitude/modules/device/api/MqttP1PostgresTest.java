package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * P1 MQTT 迁移在真实 PostgreSQL 上的约束：inbox JSON、去重键、broker/设备身份外键。
 * 仅连接名称匹配 stage456_verify_* 的隔离库，并只删除本次随机 schema。
 */
@SpringBootTest
@ActiveProfiles("postgres-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_URL，MqttP1PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_USER", matches = ".+",
        disabledReason = "未验证：缺少 POSTGRES_TEST_USER，MqttP1PostgresTest 未在真实 PostgreSQL 上执行")
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_PASSWORD", matches = ".*",
        disabledReason = "未验证：缺少 POSTGRES_TEST_PASSWORD，MqttP1PostgresTest 未在真实 PostgreSQL 上执行")
class MqttP1PostgresTest {

    private static final String SCHEMA_PREFIX = "stage456_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE_PATTERN = "^stage456_verify_[a-z0-9_]+$";
    private static boolean schemaCreated;

    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        initializeSchema();
        registry.add("spring.datasource.url", () -> schemaUrl(requiredEnvironment("POSTGRES_TEST_URL")));
        registry.add("spring.datasource.username", () -> requiredEnvironment("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("app.dev-seed.enabled", () -> "false");
        registry.add("app.live-device.enabled", () -> "false");
        registry.add("app.mqtt.enabled", () -> "false");
        registry.add("app.fusion.enabled", () -> "false");
        registry.add("app.rule-engine.enabled", () -> "false");
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (!schemaCreated) return;
        assertSafeSchema();
        try (Connection connection = rootDataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop schema " + SCHEMA + " cascade");
        } finally {
            schemaCreated = false;
        }
    }

    @Test
    void mqttMigrationsCreateIdentityFksAndInboxDedup() {
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema=current_schema() and table_name in ('mqtt_broker','mqtt_device_binding','mqtt_receive_diagnostic','mqtt_delivery_receipt')",
                Integer.class)).isEqualTo(4);
        String org = id(), district = id(), broker = id(), opsSource = id(), source = id(), opsDevice = id(), device = id();
        long now = System.currentTimeMillis();
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,?,?,0)",
                org, "ORG-MQTT-" + org.substring(0, 8), "mqtt-org", now, now);
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,?,?,0)",
                district, "DST-MQTT-" + district.substring(0, 8), "mqtt-district", now, now);
        jdbc.update("""
                insert into mqtt_broker (broker_id,name,host,port,tls,client_id,allowed_cidrs,source_mode,owner_org_id,district_id,enabled,version,created_at,updated_at)
                values (?,?,?,?,false,?,?,?,?,?,false,0,?,?)
                """, broker, "pg-broker", "192.0.2.8", 1883, "uav-" + broker, "192.0.2.0/24", "replay", org, district, now, now);
        jdbc.update("insert into mqtt_session_lease (broker_id,updated_at) values (?,?)", broker, now);
        jdbc.update("""
                insert into ops_integration_source (source_id,source_code,name,protocol_code,protocol_version,source_mode,enabled,simulated,created_at,updated_at)
                values (?,?,?,'LINGYUN_MQTT_V8_6','8.6','replay',true,true,?,?)
                """, opsSource, "mqtt-" + opsSource, "radar", now, now);
        jdbc.update("""
                insert into integration_source (source_id,source_code,name,protocol_code,protocol_version,source_mode,enabled,source_type,created_at,updated_at)
                values (?,?,?,'LINGYUN_MQTT_V8_6','8.6','replay',true,'RADAR',?,?)
                """, source, "mqtt-" + source, "radar", new java.sql.Timestamp(now), new java.sql.Timestamp(now));
        jdbc.update("""
                insert into ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,channel,source_mode,simulated,created_at,updated_at)
                values (?,?,?,?,?,'radar','雷达','凌云 MQTT','replay',true,?,?)
                """, opsDevice, opsSource, "ext-1", "DEV-MQTT-1", "radar-1", now, now);
        jdbc.update("""
                insert into device (device_id,source_id,external_device_id,device_no,name,device_type_code,source_mode,owner_org_id,district_id,created_at,updated_at)
                values (?,?,?,?,?,'RADAR','replay',?,?,?,?)
                """, device, source, "ext-1", "DEV-MQTT-1", "radar-1", org, district, new java.sql.Timestamp(now), new java.sql.Timestamp(now));
        jdbc.update("insert into mqtt_device_binding (ops_device_id,device_id,ops_source_id,source_id,broker_id,provider_code,device_type_abbr,external_device_id,source_mode) values (?,?,?,?,?,?,?,?,?)",
                opsDevice, device, opsSource, source, broker, "provider", "radar", "ext-1", "replay");
        String inboxSource = "lingyun:radar:" + device;
        jdbc.update("""
                insert into inbox_message (inbox_id,source,source_msg_id,source_id,payload_hash,payload,received_at,status)
                values (?,?,?,?,?,cast(? as json),?,'RECEIVED')
                """, id(), inboxSource, "1000:1", source, sha256("{}"), "{}", now);
        assertThatThrownBy(() -> jdbc.update("""
                insert into inbox_message (inbox_id,source,source_msg_id,source_id,payload_hash,payload,received_at,status)
                values (?,?,?,?,?,cast(? as json),?,'RECEIVED')
                """, id(), inboxSource, "1000:1", source, sha256("{x}"), "{x}", now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update mqtt_device_binding set source_mode='live' where ops_device_id=?", opsDevice))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static synchronized void initializeSchema() {
        if (schemaCreated) return;
        assertSafeSchema();
        JdbcTemplate root = new JdbcTemplate(rootDataSource());
        String database = root.queryForObject("select current_database()", String.class);
        if (database == null || !database.matches(DATABASE_PATTERN)) {
            throw new IllegalStateException("Refusing MQTT P1 verification outside a stage456_verify_ database");
        }
        root.execute("create schema " + SCHEMA);
        schemaCreated = true;
        try {
            Flyway.configure().dataSource(rootDataSource()).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false).cleanDisabled(true)
                    .locations("classpath:db/migration", "classpath:db/postgresql").load().migrate();
        } catch (RuntimeException exception) {
            try { root.execute("drop schema " + SCHEMA + " cascade"); } finally { schemaCreated = false; }
            throw exception;
        }
    }

    private static DataSource rootDataSource() {
        return new DriverManagerDataSource(requiredEnvironment("POSTGRES_TEST_URL"), requiredEnvironment("POSTGRES_TEST_USER"), requiredEnvironment("POSTGRES_TEST_PASSWORD"));
    }

    private static String schemaUrl(String baseUrl) {
        if (!baseUrl.startsWith("jdbc:postgresql:") || baseUrl.toLowerCase().contains("currentschema=")) {
            throw new IllegalStateException("POSTGRES_TEST_URL must be PostgreSQL without currentSchema");
        }
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA + ",public";
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void assertSafeSchema() {
        if (!SCHEMA.matches("^" + SCHEMA_PREFIX + "[a-f0-9]{32}$")) throw new IllegalStateException("Unsafe MQTT P1 verification schema");
    }

    private static String id() { return UUID.randomUUID().toString(); }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
