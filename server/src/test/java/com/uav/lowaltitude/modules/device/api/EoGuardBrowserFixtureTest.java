package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Opt-in loopback UI fixture. All mutable data is confined to this disposable H2 context. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:eo_guard_browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.mqtt.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false",
        "app.eo-edge.auto-track.enabled=false"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfSystemProperty(named = "qa.eo.guard.browser", matches = "true")
class EoGuardBrowserFixtureTest extends EoManualTrackApiTest {
    @LocalServerPort int port;

    @Test void serveGuardedTargets() throws Exception {
        String missing = insertTarget(false), busy = insertTarget(true), weather = UUID.randomUUID().toString();
        jdbc.update("UPDATE target SET target_no='QA-无位置' WHERE target_id=?", missing);
        jdbc.update("UPDATE target SET target_no='QA-设备忙' WHERE target_id=?", busy);
        jdbc.update("UPDATE eo_device_binding SET work_state=1 WHERE ops_device_id=?", binding.opsDeviceId());
        jdbc.update("""
                INSERT INTO flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,risk_type,severity,state_code,
                    reason_code,reason_text,occurred_at,received_at,height_relation,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                VALUES(?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal','WEATHER','HIGH','PENDING_VERIFICATION',
                    'WEATHER','QA-仅气象无目标',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'UNKNOWN','mock','seed-stage3-org','seed-stage3-district',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, weather, weather);
        jdbc.update("""
                INSERT INTO weather_risk_fact(risk_id,polygon_json,published_at,valid_from,valid_to)
                VALUES(?,'[[118.60,37.40],[118.62,37.40],[118.62,37.42],[118.60,37.42],[118.60,37.40]]' format json,
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,DATEADD('HOUR',1,CURRENT_TIMESTAMP))
                """, weather);
        long tasks = jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class);
        long commands = jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class);
        Path directory = Path.of("target", "eo-guard-browser").toAbsolutePath(); Files.createDirectories(directory);
        Path stop = directory.resolve("stop"); Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"), mapper.writeValueAsString(Map.of("port", port,
                "missing_target", missing, "busy_target", busy, "weather_risk", weather, "simulated", true)));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(20).toNanos();
        while (!Files.exists(stop) && System.nanoTime() < deadline) {
            Timestamp now = new Timestamp(clock.nowMillis());
            for (String target : List.of(missing, busy))
                jdbc.update("UPDATE target SET first_seen_at=COALESCE(first_seen_at,?),last_seen_at=?,updated_at=? WHERE target_id=?", now, now, now, target);
            jdbc.update("UPDATE target_latest_state SET observed_at=?,received_at=?,updated_at=? WHERE target_id=?", now, now, now, busy);
            jdbc.update("UPDATE eo_device_binding SET last_heartbeat_at=? WHERE ops_device_id=?", clock.nowMillis(), binding.opsDeviceId());
            Thread.sleep(1000);
        }
        Files.deleteIfExists(stop);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class)).isEqualTo(tasks);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class)).isEqualTo(commands);
    }
}
