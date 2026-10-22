package com.uav.lowaltitude.modules.prediction.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

/** Real PostGIS and immutable source provenance, only in a disposable stage456_verify_* schema. */
@SpringBootTest(properties = "app.prediction.enabled=false")
@ActiveProfiles(value = { "test", "postgres-test" }, inheritProfiles = false)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class PredictionInputPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void close() { DATABASE.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    private final PredictionEngine.Config config = PredictionEngine.Config.defaults();
    private long now;
    private String org, district, target, track, version, mode;
    private String[] sources, devices;
    private boolean normalized;
    private double lon, lat;

    @Test
    void actualLiveMeasAndAlternatingSourcesShareOneContinuityKey() {
        fixture("live", false, 10);
        var repository = repository("test");
        var first = repository.load(target, now, config);
        assertThat(first.invalidReason()).isNull();
        assertThat(first.points()).hasSize(10);
        assertThat(first.target().simulated()).isFalse();
        assertThat(first.evidenceNotes()).contains("FUSION_CURRENT_CONFIG:" + version);
        append(10, now + 1_000, 0, false);
        var next = repository.load(target, now + 1_000, config);
        assertThat(next.invalidReason()).isNull();
        assertThat(next.continuityKey()).isEqualTo(first.continuityKey());
    }

    @Test
    void ordinaryReplayNeverBecomesCurrentJustBecauseWallClockIsFresh() {
        fixture("replay", false, 10);
        assertThat(repository("local", "qa").load(target, now, config).invalidReason()).isEqualTo("HISTORICAL_REPLAY_NOT_CURRENT");
    }

    @Test
    void explicitlyRegisteredRealtimeSimulationKeepsDefaultPrecisionProvenance() {
        fixture("replay", true, 10);
        var input = repository("local", "qa").load(target, now, config);
        assertThat(input.invalidReason()).isNull();
        assertThat(input.target().simulated()).isTrue();
        assertThat(input.evidenceNotes()).contains("ACCURACY_DEFAULTED", "FUSION_TRACK_CONFIG:" + version,
                "SIMULATOR_DEFAULT_CONFIGURATION_NOT_HISTORICALLY_FROZEN");
        assertThat(repository("local", "qa", "production").load(target, now, config).invalidReason()).isEqualTo("REALTIME_SIMULATION_DISABLED");
    }

    @Test
    void disabledOrChangedSourceScopeInvalidatesTheWindow() {
        fixture("live", false, 10);
        jdbc.update("UPDATE integration_source SET enabled=FALSE WHERE source_id=?", sources[0]);
        assertThat(repository("test").load(target, now, config).invalidReason()).isEqualTo("SOURCE_SCOPE_OR_TIME_INVALID");
    }

    @Test
    void latestPredictedPointCannotStandInForCurrentMeas() {
        fixture("live", false, 10);
        jdbc.update("""
                INSERT INTO track_point(point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,created_at,
                  point_kind,position_accuracy_m,contributing)
                SELECT ?,track_id,100,observed_at,received_at,location,altitude_amsl_m,created_at,'PRED',position_accuracy_m,CAST('[]' AS JSON)
                FROM track_point WHERE track_id=? AND point_seq=9
                """, id(), track);
        assertThat(repository("test").load(target, now, config).invalidReason()).isEqualTo("CURRENT_MEASUREMENT_CONFLICT");
    }

    @Test
    void actualInsideIsAvailableWithoutEnoughHistoryToForecastAndExpiresWithTheMeasurement() {
        fixture("live", false, 1);
        String area = airspace("AMSL", -50, -50, 50, 50, now - 60_000, null);
        var input = repository("test").load(target, now, config);
        assertThat(input.invalidReason()).isNull();
        assertThat(input.actuallyInside()).contains(area);
        assertThat(input.actuallyOutside()).doesNotContain(area);
        assertThat(new PredictionEngine().forecast(input.points(), now, config).reliable()).isFalse();
        var expired = repository("test").load(target, now + 3_001, config);
        assertThat(expired.actuallyInside()).isEmpty();
        assertThat(expired.actuallyOutside()).isEmpty();
    }

    @Test
    void currentOutsideRequiresMatchingHeightButFutureVersionCanBeNotEffective() {
        fixture("live", false, 1);
        String missingHeight = airspace("AGL", 500, 500, 600, 600, now - 60_000, null);
        String outside = airspace("AMSL", 500, 500, 600, 600, now - 60_000, null);
        String future = airspace("AGL", -50, -50, 50, 50, now + 10_000, null);
        var input = repository("test").load(target, now, config);
        assertThat(input.actuallyOutside()).contains(outside, future).doesNotContain(missingHeight);
        assertThat(input.airspaces()).anySatisfy(v -> assertThat(v.airspaceId()).isEqualTo(future));
    }

    @Test
    void simultaneousDifferentKindsDoNotProduceAnUnambiguousActualEntry() {
        fixture("live", false, 1);
        String area = airspace("AMSL", -50, -50, 50, 50, now - 60_000, null);
        jdbc.update("""
                INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,
                  altitude_datum,valid_from,created_at)
                SELECT ?,airspace_id,2,'RESTRICTED',boundary,min_altitude_m,max_altitude_m,altitude_datum,valid_from,created_at
                FROM airspace_version WHERE airspace_id=?
                """, id(), area);
        var input = repository("test").load(target, now, config);
        assertThat(input.actuallyInside()).doesNotContain(area);
        assertThat(input.actuallyOutside()).doesNotContain(area);
    }

    private PredictionInputRepository repository(String... profiles) {
        MockEnvironment env = new MockEnvironment().withProperty("app.flight-device-check.simulator-device-bridge-enabled", "true");
        env.setActiveProfiles(profiles);
        return new PredictionInputRepository(jdbc, dataSource, json, new SimulationPolicy(env), env);
    }

    private void fixture(String sourceMode, boolean registeredNormalized, int count) {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toEpochMilli();
        mode = sourceMode; normalized = registeredNormalized;
        org = id(); district = id(); target = id(); track = id();
        // Different coordinates and identities on every test prevent accidental dependence on an acceptance target.
        lon = 117 + (Math.abs(target.hashCode() % 1000) / 1000.0);
        lat = 36 + (Math.abs(org.hashCode() % 1000) / 1000.0);
        version = jdbc.queryForObject("SELECT config_version FROM fusion_config ORDER BY created_at,config_version FETCH FIRST 1 ROW ONLY", String.class);
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", org, org, "K03输入测试单位");
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)", district, district, "K03输入测试区域");
        jdbc.update("""
                INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,first_seen_at,last_seen_at,
                  created_at,updated_at,version,unified) VALUES (?,?,'UAV',?,?,?,?,?,?,?,0,TRUE)
                """, target, target, mode, org, district, ts(now - 60_000), ts(now), ts(now), ts(now));
        jdbc.update("INSERT INTO track(track_id,target_id,external_track_id,layer,config_version,started_at,created_at) VALUES (?,?,?,'FUSED',?,?,?)",
                track, target, track, version, ts(now - 60_000), ts(now));
        jdbc.update("INSERT INTO target_track_status(target_id,status,since,confirm_hits,miss_frames,updated_at,version) VALUES (?,'STABLE',?,10,0,?,0)",
                target, ts(now - 10_000), ts(now));
        jdbc.update("""
                INSERT INTO target_degradation(target_id,level,available_source_ids,confidence_deficit,determined,since,updated_at)
                VALUES (?,'SINGLE_SOURCE',CAST('[]' AS JSON),0,TRUE,?,?)
                """, target, ts(now), ts(now));
        sources = new String[] { id(), id() }; devices = new String[] { id(), id() };
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                    INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,source_type,created_at,updated_at,version)
                    VALUES (?,?,?,?,'1',TRUE,?,?,?,?,0)
                    """, sources[i], sources[i], "K03测试来源", normalized ? "SIM_NORMALIZED" : "RADAR", mode,
                    normalized ? "SIM_NORMALIZED" : "RADAR", ts(now), ts(now));
            jdbc.update("""
                    INSERT INTO device(device_id,source_id,external_device_id,device_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                    VALUES (?,?,?,?,?,TRUE,?,?,?,?,?,0)
                    """, devices[i], sources[i], devices[i], devices[i], "K03测试设备", mode, org, district, ts(now), ts(now));
            jdbc.update("INSERT INTO target_source_link(link_id,target_id,source_id,device_id,source_session_key,external_target_id,created_at) VALUES (?,?,?,?,?,?,?)",
                    id(), target, sources[i], devices[i], "session", target, ts(now - 60_000));
            if (normalized) jdbc.update("""
                    INSERT INTO simulator_observation_source(source_id,device_id,message_id,owner_org_id,district_id,request_hash,created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, sources[i], devices[i], id(), org, district, "0".repeat(64), ts(now - 60_000));
        }
        jdbc.update("""
                INSERT INTO target_attribute_selection(target_id,position_source_id,class_source_id,class_code,class_confidence,selected_at,
                  config_version,manual_class_override,updated_at,version) VALUES (?, ?,?,'UAV',0.99,?,?,FALSE,?,0)
                """, target, sources[0], sources[0], ts(now), version, ts(now));
        for (int i = 0; i < count; i++) append(i, now - (count - 1 - i) * 1_000L, i % 2, normalized);
    }

    private void append(int seq, long observed, int sourceIndex, boolean defaulted) {
        double[] coordinate = AlphaBetaFilter.fromEnu(lon, lat, seq * 10, 0);
        String geom = "SRID=4326;POINT(" + coordinate[0] + " " + coordinate[1] + ")";
        String inbox = id(), observation = id();
        String incoming = normalized ? "sim-normalized:" + sources[sourceIndex] : "live".equals(mode) ? "live-radar:" + devices[sourceIndex] : "replay:" + sources[sourceIndex];
        jdbc.update("""
                INSERT INTO inbox_message(inbox_id,source,source_msg_id,source_id,payload_hash,payload,received_at,status)
                VALUES (?,?,?,?,?,CAST('{}' AS JSON),?,'RECEIVED')
                """, inbox, incoming, id(), sources[sourceIndex], "0".repeat(64), observed);
        jdbc.update("""
                INSERT INTO source_observation(observation_id,inbox_id,source_id,device_id,source_type,source_session_key,external_target_id,
                  observed_at,received_at,location,position_accuracy_m,altitude_amsl_m,quality,source_mode,owner_org_id,district_id,created_at)
                VALUES (?,?,?,?,?,'session',?,?,?,CAST(? AS GEOMETRY),?,100,CAST(? AS JSON),?,?,?,?)
                """, observation, inbox, sources[sourceIndex], devices[sourceIndex], normalized ? "SIM_NORMALIZED" : "RADAR",
                target, ts(observed), ts(observed), geom, defaulted ? null : 1,
                defaulted ? "{\"accuracy_defaulted\":true}" : "{}", mode, org, district, ts(observed));
        String contributing = "[{\"observation_id\":\"" + observation + "\",\"source_id\":\"" + sources[sourceIndex] + "\",\"weight\":1}]";
        jdbc.update("""
                INSERT INTO track_point(point_id,track_id,point_seq,observed_at,received_at,location,altitude_amsl_m,created_at,
                  point_kind,position_accuracy_m,contributing,position_source_id,observation_id)
                VALUES (?,?,?,?,?,CAST(? AS GEOMETRY),100,?,'MEAS',1,CAST(? AS JSON),?,?)
                """, id(), track, seq, ts(observed), ts(observed), geom, ts(observed), contributing, sources[sourceIndex], observation);
        if (jdbc.update("""
                UPDATE target_latest_state SET location=CAST(? AS GEOMETRY),altitude_amsl_m=100,observed_at=?,received_at=?,updated_at=?
                WHERE target_id=?
                """, geom, ts(observed), ts(observed), ts(observed), target) == 0)
            jdbc.update("""
                    INSERT INTO target_latest_state(target_id,location,altitude_amsl_m,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                    VALUES (?,CAST(? AS GEOMETRY),100,?,?,CAST('[]' AS JSON),?,?,0)
                    """, target, geom, ts(observed), ts(observed), ts(observed), ts(observed));
        jdbc.update("UPDATE target_attribute_selection SET selected_at=?,position_source_id=? WHERE target_id=?", ts(observed), sources[sourceIndex], target);
    }

    private String airspace(String datum, double x1, double y1, double x2, double y2, long from, Long to) {
        String area = id();
        jdbc.update("""
                INSERT INTO airspace(airspace_id,airspace_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                VALUES (?,?,?,'live',?,?,?,?,0)
                """, area, area, "K03测试禁飞区", org, district, ts(now), ts(now));
        StringBuilder wkt = new StringBuilder("SRID=4326;MULTIPOLYGON(((");
        for (double[] xy : List.of(new double[]{x1,y1}, new double[]{x2,y1}, new double[]{x2,y2}, new double[]{x1,y2}, new double[]{x1,y1})) {
            double[] ll = AlphaBetaFilter.fromEnu(lon, lat, xy[0], xy[1]);
            if (wkt.charAt(wkt.length() - 1) != '(') wkt.append(',');
            wkt.append(ll[0]).append(' ').append(ll[1]);
        }
        wkt.append(")))");
        jdbc.update("""
                INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,min_altitude_m,max_altitude_m,altitude_datum,
                  valid_from,valid_to,created_at) VALUES (?,?,1,'PROHIBITED',CAST(? AS GEOMETRY),0,200,?,?,?,?)
                """, id(), area, wkt.toString(), datum, ts(from), to == null ? null : ts(to), ts(now));
        return area;
    }
    private static Timestamp ts(long at) { return Timestamp.from(Instant.ofEpochMilli(at)); }
    private static String id() { return UUID.randomUUID().toString(); }
}
