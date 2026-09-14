package com.uav.lowaltitude.integration.mock;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Broker;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

/**
 * 仅 local：登记本机 Mosquitto 回放连接与 NDJSON 设备。不挂 test（运维台账单测总数为 12）。
 * S85R1 是凌云 MQTT 雷达回放，不是现场 T02 TCP。生产 profile 不注册。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(52)
public class LocalMqttSimSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalMqttSimSeeder.class);

    public static final String BROKER_NAME = "local-lingyun-replay";
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 1883;
    public static final String ALLOWED_CIDRS = "127.0.0.1/32";
    public static final String PROVIDER = "dongying";
    public static final String EO_EDGE_ID = "S85E1";
    public static final String EO_DEVICE_NO = "S85E1D1";

    public static final List<LingyunDevice> LINGYUN_DEVICES = List.of(
            new LingyunDevice("radar", "S85R1", "凌云 MQTT 回放雷达"),
            new LingyunDevice("tdoa", "S85T1", "凌云 MQTT 回放 TDOA"),
            new LingyunDevice("aoa", "S85A1", "凌云 MQTT 回放 AOA"),
            new LingyunDevice("5ga", "S85G1", "凌云 MQTT 回放 5G-A"),
            new LingyunDevice("dcd", "S85D1", "凌云 MQTT 回放协议破解"),
            new LingyunDevice("rid", "S85I1", "凌云 MQTT 回放 RemoteID"),
            new LingyunDevice("dec", "S85Y1", "凌云 MQTT 回放诱骗"),
            new LingyunDevice("ifr", "S85F1", "凌云 MQTT 回放干扰"),
            new LingyunDevice("bsc", "S85B1", "凌云 MQTT 回放驱鸟炮"));

    private final JdbcTemplate jdbc;
    private final MqttConfigurationService configuration;

    public LocalMqttSimSeeder(JdbcTemplate jdbc, MqttConfigurationService configuration) {
        this.jdbc = jdbc;
        this.configuration = configuration;
    }

    public record LingyunDevice(String typeAbbr, String externalId, String name) { }

    @Override
    public void run(ApplicationArguments args) {
        AuthUser admin = admin1();
        AuthContext.set(admin);
        try {
            String brokerId = ensureBroker();
            for (LingyunDevice device : LINGYUN_DEVICES) {
                registerLingyun(brokerId, device);
            }
            registerEo(brokerId);
        } finally {
            AuthContext.clear();
        }
    }

    private String ensureBroker() {
        List<String> existing = jdbc.queryForList(
                "SELECT broker_id FROM mqtt_broker WHERE name=? ORDER BY broker_id", String.class, BROKER_NAME);
        if (!existing.isEmpty()) {
            if (existing.size() > 1) {
                log.warn("local MQTT sim seed: multiple brokers named {}, using {}", BROKER_NAME, existing.get(0));
            } else {
                log.info("local MQTT sim seed: broker {} already exists, leaving configuration unchanged", BROKER_NAME);
            }
            return existing.get(0);
        }
        Broker created = configuration.create(new BrokerInput(
                BROKER_NAME, HOST, PORT, false, null, null, ALLOWED_CIDRS, "replay",
                LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID, LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID, null),
                key());
        Broker enabled = configuration.enable(created.brokerId(), created.version(), true, key());
        log.info("local MQTT sim seed: created and enabled broker {} ({})", BROKER_NAME, enabled.brokerId());
        return enabled.brokerId();
    }

    private void registerLingyun(String brokerId, LingyunDevice device) {
        if (exists("SELECT COUNT(*) FROM ops_device WHERE device_no=?", device.externalId())
                || exists("""
                        SELECT COUNT(*) FROM mqtt_device_binding
                        WHERE provider_code=? AND device_type_abbr=? AND external_device_id=?
                        """, PROVIDER, device.typeAbbr(), device.externalId())) {
            log.info("local MQTT sim seed: device {} already present, skip", device.externalId());
            return;
        }
        Registration registration = new Registration(
                LingyunEnvelope.PROTOCOL, brokerId, PROVIDER, device.externalId(), device.typeAbbr(),
                "replay", LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID, LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID,
                device.externalId(), device.name(), "凌云", null, null);
        try {
            configuration.register(registration, key());
            log.info("local MQTT sim seed: registered {} {}", device.typeAbbr(), device.externalId());
        } catch (DataIntegrityViolationException ex) {
            log.warn("local MQTT sim seed: {} collided, skip", device.externalId());
        }
    }

    private void registerEo(String brokerId) {
        if (exists("SELECT COUNT(*) FROM ops_device WHERE device_no=?", EO_DEVICE_NO)
                || exists("SELECT COUNT(*) FROM eo_device_binding WHERE edge_id=? AND external_device_id=?",
                        EO_EDGE_ID, EO_DEVICE_NO)) {
            log.info("local MQTT sim seed: EO {} already present, skip", EO_DEVICE_NO);
            return;
        }
        Registration registration = new Registration(
                EoEdgeEnvelope.PROTOCOL, brokerId, null, EO_DEVICE_NO, null, "replay",
                LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID, LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID,
                EO_DEVICE_NO, "凌云 MQTT 回放光电", "凌云", null, null, EO_EDGE_ID);
        try {
            configuration.register(registration, key());
            log.info("local MQTT sim seed: registered EO edge {} device {}", EO_EDGE_ID, EO_DEVICE_NO);
        } catch (DataIntegrityViolationException ex) {
            log.warn("local MQTT sim seed: EO {} collided, skip", EO_DEVICE_NO);
        } catch (ApiException ex) {
            if ("MQTT_CONFIG_INVALID".equals(ex.getCode()) && ex.getMessage() != null
                    && ex.getMessage().contains("edgeId")) {
                log.warn("local MQTT sim seed: EO edge {} already bound elsewhere, skip", EO_EDGE_ID);
                return;
            }
            throw ex;
        }
    }

    private AuthUser admin1() {
        List<AuthUser> users = jdbc.query("""
                SELECT user_id, name, role_code, permission_version, must_change_password, scope_mode
                FROM app_user WHERE account='admin1'
                """, (rs, row) -> new AuthUser(
                rs.getString("user_id"), "admin1", rs.getString("name"), rs.getString("role_code"),
                rs.getInt("permission_version"), rs.getBoolean("must_change_password"), rs.getString("scope_mode")));
        if (users.isEmpty()) {
            throw new IllegalStateException("local MQTT sim seed requires admin1");
        }
        return users.get(0);
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
