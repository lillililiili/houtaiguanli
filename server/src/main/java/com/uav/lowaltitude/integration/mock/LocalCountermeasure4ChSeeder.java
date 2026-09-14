package com.uav.lowaltitude.integration.mock;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;

/**
 * 仅 local：登记本机四通道模拟器 CM4-LOCAL。不挂 test（运维台账单测总数为 12）。
 * source_mode=live 且 simulated=true；CIDR 仅 127.0.0.1/32。不写现场地址 192.168.0.7。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(53)
public class LocalCountermeasure4ChSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalCountermeasure4ChSeeder.class);

    public static final String DEVICE_NO = "CM4-LOCAL";
    public static final String SOURCE_CODE = "CM4-LOCAL";
    public static final String ALLOWED_CIDRS = "127.0.0.1/32";

    private final JdbcTemplate jdbc;
    private final LocalCountermeasure4ChSimulator simulator;

    public LocalCountermeasure4ChSeeder(JdbcTemplate jdbc, LocalCountermeasure4ChSimulator simulator) {
        this.jdbc = jdbc;
        this.simulator = simulator;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!simulator.isRunning()) {
            log.warn("local 4ch seed: simulator is not listening, skip {}", DEVICE_NO);
            return;
        }
        int port = simulator.port();
        List<String> existing = jdbc.queryForList(
                "SELECT device_id FROM ops_device WHERE device_no=?", String.class, DEVICE_NO);
        if (!existing.isEmpty()) {
            String deviceId = existing.get(0);
            jdbc.update("UPDATE device_connection_profile SET host=?, port=?, updated_at=? WHERE device_id=?",
                    LocalCountermeasure4ChSimulator.HOST, port, System.currentTimeMillis(), deviceId);
            jdbc.update("UPDATE ops_integration_source SET allowed_cidrs=?, simulated=TRUE, enabled=TRUE, updated_at=? "
                            + "WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)",
                    ALLOWED_CIDRS, System.currentTimeMillis(), deviceId);
            log.info("local 4ch seed: {} already present, refreshed {}:{}", DEVICE_NO,
                    LocalCountermeasure4ChSimulator.HOST, port);
            return;
        }
        long now = System.currentTimeMillis();
        Timestamp time = new Timestamp(now);
        String sourceId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ops_integration_source (source_id,source_code,name,protocol_code,protocol_version,
                    source_mode,enabled,credential_ref,allowed_cidrs,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'live',TRUE,NULL,?,TRUE,0,?,?)
                """, sourceId, SOURCE_CODE, "本机四通道模拟器", DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, "2.0",
                ALLOWED_CIDRS, now, now);
        jdbc.update("""
                INSERT INTO ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,
                    device_type_name,channel,vendor,enabled,source_mode,simulated,version,created_at,updated_at,
                    owner_name,region_name)
                SELECT ?,?,?,?,?,?,?,?,?,TRUE,'live',TRUE,0,?,?,o.name,d.name
                FROM app_org o CROSS JOIN app_district d
                WHERE o.org_id=? AND d.district_id=?
                """, deviceId, sourceId, DEVICE_NO, DEVICE_NO, "本机四通道模拟器", "countermeasure", "反制", "反制直连",
                "本机模拟", now, now, LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID,
                LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID);
        jdbc.update("""
                INSERT INTO device_connection_profile (device_id,transport,host,port,timeout_millis,retry_count,version,updated_at)
                VALUES (?,'TCP',?,?,3000,3,0,?)
                """, deviceId, LocalCountermeasure4ChSimulator.HOST, port, now);
        jdbc.update("""
                INSERT INTO countermeasure_4ch_profile (device_id,device_address,wire_encoding,poll_interval_millis,version,updated_at)
                VALUES (?,1,'AUTO',5000,0,?)
                """, deviceId, now);
        jdbc.update("""
                INSERT INTO ops_device_state (device_id,connectivity,has_alarm,health_code,observed_at,received_at,
                    last_heartbeat_at,simulated,version)
                VALUES (?,'ONLINE',FALSE,'GOOD',?,?,?,TRUE,0)
                """, deviceId, now, now, now);
        jdbc.update("""
                INSERT INTO device_business_scope (ops_device_id,owner_org_id,district_id,created_at,updated_at)
                VALUES (?,?,?,?,?)
                """, deviceId, LocalStage5DeviceScopeSeeder.PLATFORM_ORG_ID,
                LocalStage5DeviceScopeSeeder.DONGYING_DISTRICT_ID, time, time);
        log.info("local 4ch seed: registered {} on {}:{} (live+simulated; not field RF)", DEVICE_NO,
                LocalCountermeasure4ChSimulator.HOST, port);
    }
}
