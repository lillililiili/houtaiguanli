package com.uav.lowaltitude.modules.device.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.platform.time.AppClock;

/** 仅测试夹具。local/integration 运行库不再预置运维模拟设备。 */
@Component
@Profile("test")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(20)
public class LocalDeviceSeeder implements ApplicationRunner {

    private static final String SOURCE_ID = stable("device-source:local-mock");
    private static final List<Seed> SEEDS = List.of(
            new Seed("DEV-MOCK-001", "东营区开发样例雷达01", "radar", "雷达", "融合感知箱", "东营区", "ONLINE", "GOOD"),
            new Seed("DEV-MOCK-002", "东营区开发样例光电01", "oe", "光电", "融合感知箱", "东营区", "ONLINE", "GOOD"),
            new Seed("DEV-MOCK-003", "河口区开发样例雷达01", "radar", "雷达", "融合感知箱", "河口区", "ABNORMAL", "BAD"),
            new Seed("DEV-MOCK-004", "河口区开发样例TDOA01", "tdoa", "TDOA", "TDOA", "河口区", "ONLINE", "DEGRADED"),
            new Seed("DEV-MOCK-005", "垦利区开发样例AOA01", "aoa", "AOA", "TDOA", "垦利区", "OFFLINE", "UNKNOWN"),
            new Seed("DEV-MOCK-006", "垦利区开发样例RemoteID01", "rid", "RemoteID", "TDOA", "垦利区", "ONLINE", "GOOD"),
            new Seed("DEV-MOCK-007", "广饶县开发样例雷达01", "radar", "雷达", "融合感知箱", "广饶县", "ONLINE", "GOOD"),
            new Seed("DEV-MOCK-008", "广饶县开发样例光电01", "oe", "光电", "融合感知箱", "广饶县", "ONLINE", "GOOD"),
            new Seed("DEV-MOCK-009", "利津县开发样例干扰01", "ifr", "干扰", "融合感知箱", "利津县", "ONLINE", "GOOD"),
            new Seed("DEV-MOCK-010", "利津县开发样例融合终端01", "other", "融合终端", "融合感知箱", "利津县", "UNKNOWN", "UNKNOWN"),
            new Seed("DEV-MOCK-011", "东营港开发样例5G-A01", "5ga", "5G-A基站", "5G-A", "东营港经济区", "ONLINE", "GOOD"),
            new Seed("DEV-MOCK-012", "东营港开发样例协议破解01", "dcd", "协议破解", "融合感知箱", "东营港经济区", "ONLINE", "DEGRADED")
    );

    private final JdbcTemplate jdbc;
    private final AppClock clock;

    public LocalDeviceSeeder(JdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ops_device", Long.class);
        if (count != null && count > 0) return;
        long now = clock.nowMillis();
        jdbc.update("""
                INSERT INTO ops_integration_source (source_id,source_code,name,source_mode,enabled,simulated,created_at,updated_at)
                VALUES (?,?,?,'mock',TRUE,TRUE,?,?)
                """, SOURCE_ID, "LOCAL-MOCK", "本地开发模拟适配器", now, now);
        for (int i = 0; i < SEEDS.size(); i++) seed(SEEDS.get(i), i, now);
        seedHistoricCommission(now);
    }

    private void seed(Seed seed, int index, long now) {
        String id = stable("device:" + seed.deviceNo());
        double lon = 118.30 + index * 0.045;
        double lat = 37.40 + (index % 5) * 0.075;
        long heartbeat = "OFFLINE".equals(seed.connectivity()) ? now - 35 * 60_000L
                : "UNKNOWN".equals(seed.connectivity()) ? now - 2 * 3_600_000L : now - (index % 3) * 20_000L;
        jdbc.update("""
                INSERT INTO ops_device (device_id,source_id,external_device_id,device_no,name,device_type_code,
                    device_type_name,channel,model,vendor,owner_name,region_name,address,longitude,latitude,
                    coordinate_system,altitude_m,altitude_datum,firmware_version,installed_at,enabled,
                    source_mode,simulated,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE,'mock',TRUE,0,?,?)
                """, id, SOURCE_ID, "MOCK-EXT-" + (index + 1), seed.deviceNo(), seed.name(), seed.typeCode(),
                seed.typeName(), seed.channel(), "待设备方确认", "开发模拟厂商", "开发测试单位", seed.region(),
                seed.region() + "开发测试点", lon, lat, "WGS-84", 15 + index, "AMSL", "mock-1.0",
                now - 30L * 86_400_000L, now, now);
        jdbc.update("""
                INSERT INTO device_connection_profile (device_id,transport,host,port,path,data_format,charset_name,
                    auth_mode,heartbeat_interval_seconds,report_interval_millis,sampling_rate_hz,
                    compression_enabled,retransmission_enabled,timeout_millis,retry_count,
                    longitude_offset_deg,latitude_offset_deg,altitude_offset_m,time_sync_mode,time_server,
                    timezone_name,time_sync_interval_seconds,version,updated_at)
                VALUES (?,?,?,?,?,'JSON','UTF-8','Token',30,1000,10,FALSE,TRUE,3000,3,0,0,0,'NTP',?,? ,60,0,?)
                """, id, index % 3 == 0 ? "TCP" : "HTTP", "192.0.2." + (20 + index),
                index % 3 == 0 ? 9001 : 8080, index % 3 == 0 ? null : "/api/v1/data",
                "time.example.invalid", "Asia/Shanghai", now);
        boolean alarm = "ABNORMAL".equals(seed.connectivity()) || "DEGRADED".equals(seed.health());
        String metrics = """
                {"link_latency_ms":{"label":"链路时延","value":%d,"unit":"ms","source":"mock-adapter"},
                 "packet_loss_pct":{"label":"丢包率","value":%.2f,"unit":"%%","source":"mock-adapter"},
                 "rssi_dbm":{"label":"信号强度","value":%d,"unit":"dBm","source":"mock-adapter"}}
                """.formatted(24 + index * 4, 0.15 + index * 0.12, -55 - index * 2).replace("\n", "");
        jdbc.update("""
                INSERT INTO ops_device_state (device_id,connectivity,work_state_code,has_alarm,health_code,
                    observed_at,received_at,last_heartbeat_at,metrics_json,unknown_reason,simulated,version)
                VALUES (?,?,?,?,?,?,?,?,?,?,TRUE,0)
                """, id, seed.connectivity(), "ABNORMAL".equals(seed.connectivity()) ? "2" : "ONLINE".equals(seed.connectivity()) ? "1" : "0",
                alarm, seed.health(), heartbeat, now, heartbeat, metrics,
                "UNKNOWN".equals(seed.connectivity()) ? "开发模拟状态缺失" : null);
        for (int point = 0; point < 24; point++) {
            long at = now - (23L - point) * 150_000L;
            history(id, seed.connectivity(), at, "link_latency_ms", 24 + index * 4 + (point % 5), "ms");
            history(id, seed.connectivity(), at, "packet_loss_pct", 0.15 + index * 0.12 + (point % 4) * .05, "%");
            history(id, seed.connectivity(), at, "rssi_dbm", -55 - index * 2 - (point % 3), "dBm");
        }
        jdbc.update("INSERT INTO device_event_log (event_id,device_id,event_type,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,TRUE)",
                UUID.randomUUID().toString(), id, "STATE_RECEIVED", alarm ? "WARN" : "INFO",
                "开发模拟状态已接收：" + seed.connectivity(), now - index * 35_000L);
        if (alarm || "OFFLINE".equals(seed.connectivity())) {
            String incidentId = stable("incident:" + seed.deviceNo());
            String severity = "ABNORMAL".equals(seed.connectivity()) || "OFFLINE".equals(seed.connectivity()) ? "HIGH" : "MEDIUM";
            jdbc.update("""
                    INSERT INTO device_incident (incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated)
                    VALUES (?,?,?,?,?,'PENDING',?,?,TRUE)
                    """, incidentId, id, "INC-MOCK-" + (index + 1),
                    "OFFLINE".equals(seed.connectivity()) ? "DEVICE_OFFLINE" : "LINK_DEGRADED", severity,
                    now - (index + 1L) * 60_000L, "开发模拟异常，用于验证运维页面状态");
        }
    }

    private void history(String deviceId, String connectivity, long at, String code, double value, String unit) {
        jdbc.update("""
                INSERT INTO ops_device_state_history (state_id,device_id,connectivity,observed_at,received_at,
                    metric_code,metric_value,metric_unit,simulated) VALUES (?,?,?,?,?,?,?,?,TRUE)
                """, UUID.randomUUID().toString(), deviceId, connectivity, at, at, code, value, unit);
    }

    private void seedHistoricCommission(long now) {
        String userId = stable("demo-user:admin1");
        for (int i = 0; i < 2; i++) {
            Seed seed = SEEDS.get(i);
            String id = stable("commission:historic:" + i);
            long start = now - (i + 1L) * 86_400_000L;
            String result = "{\"result_code\":\"MOCK_PASSED\",\"detail\":\"历史开发模拟任务\",\"items\":[]}";
            jdbc.update("""
                    INSERT INTO commission_task (commission_id,commission_no,device_id,requested_by,status,
                        criteria_snapshot,results_json,source_mode,simulated,version,started_at,finished_at,created_at,updated_at)
                    VALUES (?,?,?,?, 'PASSED',?,?,'mock',TRUE,1,?,?,?,?)
                    """, id, "CT-MOCK-HIST-" + (i + 1), stable("device:" + seed.deviceNo()), userId,
                    "{\"source\":\"DEVELOPMENT_SIMULATION\",\"confirmed\":false}", result,
                    start, start + 15_000L, start, start + 15_000L);
            jdbc.update("INSERT INTO commission_task_event (event_id,commission_id,stage_code,level_code,message,occurred_at,simulated) VALUES (?,?,?,?,?,?,TRUE)",
                    UUID.randomUUID().toString(), id, "PASSED", "INFO", "历史开发模拟调测完成", start + 15_000L);
        }
    }

    private static String stable(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record Seed(String deviceNo, String name, String typeCode, String typeName,
                        String channel, String region, String connectivity, String health) { }
}
