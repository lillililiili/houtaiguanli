package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocalMqttSimSeederTest {

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired MqttConfigurationService configuration;
    @Autowired ApplicationArguments arguments;

    @Test
    void testProfileDoesNotRegisterSeederAndAnnotationExcludesTest() {
        assertThat(context.containsBean("localMqttSimSeeder")).isFalse();
        assertThat(LocalMqttSimSeeder.class.getAnnotation(Profile.class).value())
                .containsExactly("local & qa & !prod & !production");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ops_device WHERE device_no IN ('S85R1','S85T1','S85A1','S85G1','S85D1','S85I1','S85E1D1','S85Y1','S85F1','S85B1')",
                Integer.class)).isZero();
    }

    @Test
    void seedsReplayBrokerAndFourDevicesIdempotentlyWithoutChangingExistingRows() {
        LocalMqttSimSeeder seeder = new LocalMqttSimSeeder(jdbc, configuration);
        seeder.run(arguments);
        assertSeed();
        String brokerId = jdbc.queryForObject(
                "SELECT broker_id FROM mqtt_broker WHERE name=?", String.class, LocalMqttSimSeeder.BROKER_NAME);
        seeder.run(arguments);
        assertSeed();
        assertThat(jdbc.queryForObject(
                "SELECT broker_id FROM mqtt_broker WHERE name=?", String.class, LocalMqttSimSeeder.BROKER_NAME))
                .isEqualTo(brokerId);
    }

    @Test
    void retiresSampleDevicesOfTypesNotDeployedInTheField() {
        LocalMqttSimSeeder seeder = new LocalMqttSimSeeder(jdbc, configuration);
        seeder.run(arguments);
        String deviceId = jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no='S85T1'", String.class);
        jdbc.update("UPDATE ops_device SET device_no='S85F1',device_type_code='ifr' WHERE device_id=?", deviceId);
        seeder.run(arguments);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL AND NOT enabled FROM ops_device WHERE device_id=?", Boolean.class, deviceId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_event_log WHERE device_id=? AND event_type='CATALOG_DELETED'", Integer.class, deviceId))
                .isEqualTo(1);
        seeder.run(arguments);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_event_log WHERE device_id=? AND event_type='CATALOG_DELETED'", Integer.class, deviceId))
                .isEqualTo(1);
    }

    private void assertSeed() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mqtt_broker WHERE name=?", Integer.class, LocalMqttSimSeeder.BROKER_NAME))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT host FROM mqtt_broker WHERE name=?", String.class, LocalMqttSimSeeder.BROKER_NAME))
                .isEqualTo("127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT port FROM mqtt_broker WHERE name=?", Integer.class, LocalMqttSimSeeder.BROKER_NAME))
                .isEqualTo(1883);
        assertThat(jdbc.queryForObject("SELECT tls FROM mqtt_broker WHERE name=?", Boolean.class, LocalMqttSimSeeder.BROKER_NAME))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT allowed_cidrs FROM mqtt_broker WHERE name=?", String.class, LocalMqttSimSeeder.BROKER_NAME))
                .isEqualTo("127.0.0.1/32");
        assertThat(jdbc.queryForObject("SELECT source_mode FROM mqtt_broker WHERE name=?", String.class, LocalMqttSimSeeder.BROKER_NAME))
                .isEqualTo("replay");
        assertThat(jdbc.queryForObject("SELECT enabled FROM mqtt_broker WHERE name=?", Boolean.class, LocalMqttSimSeeder.BROKER_NAME))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ops_device WHERE device_no IN ('S85R1','S85T1','S85G1','S85E1D1') AND deleted_at IS NULL",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT s.protocol_code FROM ops_device d
                JOIN ops_integration_source s ON s.source_id=d.source_id WHERE d.device_no='S85R1'
                """, String.class)).isEqualTo(DeviceProtocolCodes.LINGYUN_MQTT_V8_6);
        assertThat(jdbc.queryForObject("""
                SELECT s.source_mode FROM ops_device d
                JOIN ops_integration_source s ON s.source_id=d.source_id WHERE d.device_no='S85R1'
                """, String.class)).isEqualTo("replay");
        assertThat(jdbc.queryForObject("""
                SELECT b.provider_code FROM mqtt_device_binding b
                JOIN ops_device d ON d.device_id=b.ops_device_id WHERE d.device_no='S85T1'
                """, String.class)).isEqualTo("dongying");
        assertThat(jdbc.queryForObject("""
                SELECT b.device_type_abbr FROM mqtt_device_binding b
                JOIN ops_device d ON d.device_id=b.ops_device_id WHERE d.device_no='S85T1'
                """, String.class)).isEqualTo("tdoa");
        assertThat(jdbc.queryForObject("""
                SELECT b.external_device_id FROM mqtt_device_binding b
                JOIN ops_device d ON d.device_id=b.ops_device_id WHERE d.device_no='S85T1'
                """, String.class)).isEqualTo("S85T1");
        assertThat(jdbc.queryForObject("""
                SELECT s.protocol_code FROM ops_device d
                JOIN ops_integration_source s ON s.source_id=d.source_id WHERE d.device_no='S85E1D1'
                """, String.class)).isEqualTo(DeviceProtocolCodes.EO_EDGE_MQTT_20250826);
        assertThat(jdbc.queryForObject("""
                SELECT b.edge_id FROM eo_device_binding b
                JOIN ops_device d ON d.device_id=b.ops_device_id WHERE d.device_no='S85E1D1'
                """, String.class)).isEqualTo("S85E1");
        assertThat(jdbc.queryForObject("""
                SELECT b.external_device_id FROM eo_device_binding b
                JOIN ops_device d ON d.device_id=b.ops_device_id WHERE d.device_no='S85E1D1'
                """, String.class)).isEqualTo("S85E1D1");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM device_business_scope s
                JOIN ops_device d ON d.device_id=s.ops_device_id
                WHERE d.device_no IN ('S85R1','S85T1','S85G1','S85E1D1')
                  AND s.owner_org_id=? AND s.district_id=?
                """, Integer.class, LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID,
                LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT b.device_type_abbr FROM mqtt_device_binding b
                JOIN ops_device d ON d.device_id=b.ops_device_id WHERE d.device_no='S85G1'
                """, String.class)).isEqualTo("5ga");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ops_device WHERE device_no IN ('S85A1','S85D1','S85I1','S85Y1','S85F1','S85B1') AND deleted_at IS NULL",
                Integer.class)).isZero();
    }
}
