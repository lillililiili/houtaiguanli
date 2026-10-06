package com.uav.lowaltitude.modules.integrationconfig.infrastructure;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for explicitly submitted local simulator registrations and weather facts. */
@Repository
public class LocalObservationSimulatorRepository {
    private final JdbcTemplate jdbc;

    public LocalObservationSimulatorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockActor(String userId) {
        jdbc.queryForObject("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE", String.class, userId);
    }

    public boolean activeScope(String ownerOrgId, String districtId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app_org o CROSS JOIN app_district d
                WHERE o.org_id=? AND d.district_id=? AND o.enabled=TRUE AND d.enabled=TRUE
                """, Long.class, ownerOrgId, districtId);
        return count != null && count == 1;
    }

    public Registration registration(String messageId) {
        List<Registration> rows = jdbc.query("""
                SELECT source_id,device_id,message_id,owner_org_id,district_id,request_hash
                FROM simulator_observation_source WHERE message_id=?
                """, (r, n) -> new Registration(r.getString("source_id"), r.getString("device_id"),
                r.getString("message_id"), r.getString("owner_org_id"), r.getString("district_id"),
                r.getString("request_hash")), messageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public WeatherRegistration weatherRegistration(String messageId) {
        List<WeatherRegistration> rows = jdbc.query("""
                SELECT source_id,device_id,message_id,owner_org_id,district_id,request_hash
                FROM simulator_weather_device WHERE message_id=?
                """, (r, n) -> new WeatherRegistration(r.getString("source_id"), r.getString("device_id"),
                r.getString("message_id"), r.getString("owner_org_id"), r.getString("district_id"),
                r.getString("request_hash")), messageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SourceBinding sourceBinding(String sourceId) {
        List<SourceBinding> rows = jdbc.query("""
                SELECT r.source_id,r.device_id,r.owner_org_id,r.district_id,d.enabled,s.enabled AS source_enabled
                FROM simulator_observation_source r
                JOIN integration_source s ON s.source_id=r.source_id
                JOIN device d ON d.device_id=r.device_id
                WHERE r.source_id=?
                """, (r, n) -> new SourceBinding(r.getString("source_id"), r.getString("device_id"),
                r.getString("owner_org_id"), r.getString("district_id"), r.getBoolean("enabled"),
                r.getBoolean("source_enabled")), sourceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public WeatherBinding weatherBinding(String deviceId) {
        List<WeatherBinding> rows = jdbc.query("""
                SELECT r.source_id,r.device_id,r.owner_org_id,r.district_id,d.enabled,s.enabled AS source_enabled
                FROM simulator_weather_device r
                JOIN integration_source s ON s.source_id=r.source_id
                JOIN device d ON d.device_id=r.device_id
                WHERE r.device_id=?
                """, (r, n) -> new WeatherBinding(r.getString("source_id"), r.getString("device_id"),
                r.getString("owner_org_id"), r.getString("district_id"), r.getBoolean("enabled"),
                r.getBoolean("source_enabled")), deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertObservationRegistration(String sourceId, String deviceId, String messageId,
            String ownerOrgId, String districtId, String requestHash, String sourceCode, String name,
            String location, long now) {
        Timestamp timestamp = new Timestamp(now);
        jdbc.update("""
                INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,
                    enabled,source_mode,source_type,created_at,updated_at,version)
                VALUES(?,?,?,'SIM_NORMALIZED','1.0',TRUE,'replay','SIM_NORMALIZED',?,?,0)
                """, sourceId, sourceCode, name, timestamp, timestamp);
        jdbc.update("""
                INSERT INTO device(device_id,source_id,external_device_id,device_no,name,device_type_code,
                    location,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                VALUES(?,?,?,?,?,'normalized_source',CAST(? AS GEOMETRY),TRUE,'replay',?,?,?, ?,0)
                """, deviceId, sourceId, sourceId, sourceCode, name, location, ownerOrgId, districtId, timestamp, timestamp);
        jdbc.update("""
                INSERT INTO simulator_observation_source(source_id,device_id,message_id,owner_org_id,district_id,request_hash,created_at)
                VALUES(?,?,?,?,?,?,?)
                """, sourceId, deviceId, messageId, ownerOrgId, districtId, requestHash, timestamp);
    }

    public void insertWeatherRegistration(String sourceId, String deviceId, String messageId,
            String ownerOrgId, String districtId, String requestHash, String sourceCode, String name,
            String deviceNo, String location, long now) {
        Timestamp timestamp = new Timestamp(now);
        jdbc.update("""
                INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,
                    enabled,source_mode,created_at,updated_at,version)
                VALUES(?,?,?,'WEATHER_SIMULATOR','1.0',TRUE,'replay',?,?,0)
                """, sourceId, sourceCode, name, timestamp, timestamp);
        jdbc.update("""
                INSERT INTO device(device_id,source_id,external_device_id,device_no,name,device_type_code,
                    location,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                VALUES(?,?,?,?,?,'weather_sensor',CAST(? AS GEOMETRY),TRUE,'replay',?,?,?, ?,0)
                """, deviceId, sourceId, deviceId, deviceNo, name, location, ownerOrgId, districtId, timestamp, timestamp);
        jdbc.update("""
                INSERT INTO simulator_weather_device(source_id,device_id,message_id,owner_org_id,district_id,request_hash,created_at)
                VALUES(?,?,?,?,?,?,?)
                """, sourceId, deviceId, messageId, ownerOrgId, districtId, requestHash, timestamp);
    }

    public ExistingWeatherObservation weatherObservation(String messageId) {
        List<ExistingWeatherObservation> rows = jdbc.query("""
                SELECT message_id,request_hash FROM simulator_weather_observation WHERE message_id=?
                """, (r, n) -> new ExistingWeatherObservation(r.getString(1), r.getString(2)), messageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertWeatherObservation(String messageId, String deviceId, String ownerOrgId, String districtId,
            long observedAt, String location, String payload, String requestHash, long now) {
        jdbc.update("""
                INSERT INTO simulator_weather_observation(message_id,device_id,owner_org_id,district_id,
                    observed_at,location,payload,request_hash,created_at)
                VALUES(?,?,?,?,?,CAST(? AS GEOMETRY),CAST(? AS JSON),?,?)
                """, messageId, deviceId, ownerOrgId, districtId, new Timestamp(observedAt), location,
                payload, requestHash, new Timestamp(now));
    }

    public record Registration(String sourceId, String deviceId, String messageId, String ownerOrgId,
            String districtId, String requestHash) { }
    public record WeatherRegistration(String sourceId, String deviceId, String messageId, String ownerOrgId,
            String districtId, String requestHash) { }
    public record SourceBinding(String sourceId, String deviceId, String ownerOrgId, String districtId,
            boolean deviceEnabled, boolean sourceEnabled) { }
    public record WeatherBinding(String sourceId, String deviceId, String ownerOrgId, String districtId,
            boolean deviceEnabled, boolean sourceEnabled) { }
    public record ExistingWeatherObservation(String messageId, String requestHash) { }
}
