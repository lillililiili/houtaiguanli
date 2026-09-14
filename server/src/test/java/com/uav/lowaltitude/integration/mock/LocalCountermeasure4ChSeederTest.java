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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LocalCountermeasure4ChSeederTest {

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationArguments arguments;

    @Test
    void testProfileDoesNotRegisterSeederOrSimulator() {
        assertThat(context.containsBean("localCountermeasure4ChSeeder")).isFalse();
        assertThat(context.containsBean("localCountermeasure4ChSimulator")).isFalse();
        assertThat(LocalCountermeasure4ChSeeder.class.getAnnotation(Profile.class).value())
                .containsExactly("!production & local");
        assertThat(LocalCountermeasure4ChSimulator.class.getAnnotation(Profile.class).value())
                .containsExactly("!production & local");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE device_no=?",
                Integer.class, LocalCountermeasure4ChSeeder.DEVICE_NO)).isZero();
    }

    @Test
    void seedsLoopbackDeviceIdempotentlyWithoutFieldAddress() throws Exception {
        LocalCountermeasure4ChSimulator simulator = new LocalCountermeasure4ChSimulator(0);
        simulator.start();
        try {
            LocalCountermeasure4ChSeeder seeder = new LocalCountermeasure4ChSeeder(jdbc, simulator);
            seeder.run(arguments);
            assertSeed(simulator.port());
            String deviceId = jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no=?",
                    String.class, LocalCountermeasure4ChSeeder.DEVICE_NO);
            seeder.run(arguments);
            assertSeed(simulator.port());
            assertThat(jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no=?",
                    String.class, LocalCountermeasure4ChSeeder.DEVICE_NO)).isEqualTo(deviceId);
            assertThat(jdbc.queryForObject("SELECT host FROM device_connection_profile WHERE device_id=?",
                    String.class, deviceId)).isEqualTo("127.0.0.1");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE device_no=? AND source_mode='live' AND simulated=TRUE",
                    Integer.class, LocalCountermeasure4ChSeeder.DEVICE_NO)).isEqualTo(1);
        } finally {
            simulator.stop();
        }
    }

    private void assertSeed(int port) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device WHERE device_no=?",
                Integer.class, LocalCountermeasure4ChSeeder.DEVICE_NO)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT s.protocol_code FROM ops_device d
                JOIN ops_integration_source s ON s.source_id=d.source_id WHERE d.device_no=?
                """, String.class, LocalCountermeasure4ChSeeder.DEVICE_NO))
                .isEqualTo(DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0);
        assertThat(jdbc.queryForObject("""
                SELECT s.allowed_cidrs FROM ops_device d
                JOIN ops_integration_source s ON s.source_id=d.source_id WHERE d.device_no=?
                """, String.class, LocalCountermeasure4ChSeeder.DEVICE_NO))
                .isEqualTo("127.0.0.1/32");
        assertThat(jdbc.queryForObject("""
                SELECT p.port FROM ops_device d JOIN device_connection_profile p ON p.device_id=d.device_id
                WHERE d.device_no=?
                """, Integer.class, LocalCountermeasure4ChSeeder.DEVICE_NO)).isEqualTo(port);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM device_connection_profile p JOIN ops_device d ON d.device_id=p.device_id
                WHERE d.device_no=? AND p.host='192.168.0.7'
                """, Integer.class, LocalCountermeasure4ChSeeder.DEVICE_NO)).isZero();
    }
}
