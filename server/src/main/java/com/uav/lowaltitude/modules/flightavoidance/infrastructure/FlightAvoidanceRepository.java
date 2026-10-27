package com.uav.lowaltitude.modules.flightavoidance.infrastructure;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.uav.lowaltitude.modules.flightavoidance.domain.FlightAvoidancePolicy;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck.Aircraft;

@Repository
public class FlightAvoidanceRepository {
    private final JdbcTemplate jdbc;

    public FlightAvoidanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /* ---- 规则 ---- */

    public FlightAvoidancePolicy policy(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM flight_avoidance_policy WHERE policy_id='counter'" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new FlightAvoidancePolicy(r.getLong("version"), r.getBigDecimal("force_land_block_km"),
                        r.getBigDecimal("force_land_warn_km"), r.getInt("force_land_max_altitude_m"),
                        r.getBigDecimal("drive_away_warn_km"), r.getInt("drive_away_max_altitude_m"), r.getLong("updated_at")));
    }

    public int updatePolicy(long expectedVersion, BigDecimal forceLandBlockKm, BigDecimal forceLandWarnKm, int forceLandMaxAltitudeM,
            BigDecimal driveAwayWarnKm, int driveAwayMaxAltitudeM, long now) {
        return jdbc.update("""
                UPDATE flight_avoidance_policy SET version=version+1,force_land_block_km=?,force_land_warn_km=?,
                    force_land_max_altitude_m=?,drive_away_warn_km=?,drive_away_max_altitude_m=?,updated_at=?
                WHERE policy_id='counter' AND version=?
                """, forceLandBlockKm, forceLandWarnKm, forceLandMaxAltitudeM, driveAwayWarnKm, driveAwayMaxAltitudeM, now,
                expectedVersion);
    }

    public void change(String id, long version, String actorId, String actor, long now, String before, String after) {
        jdbc.update("INSERT INTO flight_avoidance_change VALUES(?,?,?,?,?,?,?)", id, version, actorId, actor, now, before, after);
    }

    public List<Map<String, Object>> changes(int page, int size) {
        return jdbc.queryForList("SELECT * FROM flight_avoidance_change ORDER BY version DESC LIMIT ? OFFSET ?", size, (page - 1) * size);
    }

    public long changeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM flight_avoidance_change", Long.class);
    }

    /* ---- 每台反制设备单独设置的距离 ---- */

    /** 这台设备单独设置的距离；没有单独设置时返回 null。version 沿用默认规则的版本号，便于检查记录对照。 */
    public FlightAvoidancePolicy devicePolicy(String deviceId, long policyVersion) {
        var rows = jdbc.query("SELECT * FROM counter_flight_distance WHERE device_id=?",
                (r, n) -> new FlightAvoidancePolicy(policyVersion, r.getBigDecimal("force_land_block_km"),
                        r.getBigDecimal("force_land_warn_km"), r.getInt("force_land_max_altitude_m"),
                        r.getBigDecimal("drive_away_warn_km"), r.getInt("drive_away_max_altitude_m"), r.getLong("updated_at")),
                deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> deviceDistances() {
        return jdbc.queryForList("""
                SELECT c.* FROM counter_flight_distance c JOIN ops_device d ON d.device_id=c.device_id
                WHERE d.deleted_at IS NULL ORDER BY c.device_id
                """);
    }

    public Map<String, Object> deviceDistance(String deviceId) {
        var rows = jdbc.queryForList("SELECT * FROM counter_flight_distance WHERE device_id=?", deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 四通道反制设备才有航班安全距离；返回设备名称，不是反制设备或已删除时返回 null。 */
    public String counterDeviceName(String deviceId) {
        var rows = jdbc.queryForList("""
                SELECT d.name FROM ops_device d JOIN countermeasure_4ch_profile p ON p.device_id=d.device_id
                WHERE d.device_id=? AND d.deleted_at IS NULL
                """, deviceId);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("name"));
    }

    public void saveDeviceDistance(String deviceId, BigDecimal forceLandBlockKm, BigDecimal forceLandWarnKm, int forceLandMaxAltitudeM,
            BigDecimal driveAwayWarnKm, int driveAwayMaxAltitudeM, long now) {
        if (jdbc.update("""
                UPDATE counter_flight_distance SET version=version+1,force_land_block_km=?,force_land_warn_km=?,
                    force_land_max_altitude_m=?,drive_away_warn_km=?,drive_away_max_altitude_m=?,updated_at=? WHERE device_id=?
                """, forceLandBlockKm, forceLandWarnKm, forceLandMaxAltitudeM, driveAwayWarnKm, driveAwayMaxAltitudeM, now, deviceId) == 0)
            jdbc.update("INSERT INTO counter_flight_distance VALUES(?,1,?,?,?,?,?,?)", deviceId, forceLandBlockKm, forceLandWarnKm,
                    forceLandMaxAltitudeM, driveAwayWarnKm, driveAwayMaxAltitudeM, now);
    }

    public int clearDeviceDistance(String deviceId) {
        return jdbc.update("DELETE FROM counter_flight_distance WHERE device_id=?", deviceId);
    }

    /* ---- 航班接收机与航班 ---- */

    /**
     * 在用的航班接收机：已登记、启用、未删除。待接入的真实设备是停用状态，不算。
     * 设备模拟器的接收机只在模拟运行时算：simulatedActiveAfter 之后没再收到数据的（模拟已停、没来得及收尾）不算。
     */
    public List<Map<String, Object>> activeReceivers(long simulatedActiveAfter) {
        return jdbc.queryForList("""
                SELECT d.device_id,d.device_no,d.name,d.longitude,d.latitude,d.simulated,a.receive_range_km,a.last_data_at
                FROM adsb_receiver a JOIN ops_device d ON d.device_id=a.device_id
                WHERE d.enabled=TRUE AND d.deleted_at IS NULL AND (d.simulated=FALSE OR a.last_data_at>=?) ORDER BY d.device_no
                """, simulatedActiveAfter);
    }

    public List<Aircraft> aircraftSince(long receivedAfter) {
        return jdbc.query("""
                SELECT x.* FROM adsb_aircraft x JOIN ops_device d ON d.device_id=x.receiver_id
                WHERE x.received_at>=? AND d.enabled=TRUE AND d.deleted_at IS NULL
                ORDER BY x.icao24,x.observed_at
                """, (r, n) -> new Aircraft(r.getString("icao24"), r.getString("callsign"), r.getString("airline"),
                r.getBigDecimal("longitude").doubleValue(), r.getBigDecimal("latitude").doubleValue(),
                (Integer) r.getObject("altitude_m", Integer.class),
                r.getBigDecimal("ground_speed_mps") == null ? null : r.getBigDecimal("ground_speed_mps").doubleValue(),
                r.getBigDecimal("track_deg") == null ? null : r.getBigDecimal("track_deg").doubleValue(),
                r.getLong("observed_at")), receivedAfter);
    }

    public boolean lockReceiver(String deviceId) {
        return !jdbc.queryForList("SELECT device_id FROM adsb_receiver WHERE device_id=? FOR UPDATE", deviceId).isEmpty();
    }

    public void upsertAircraft(String receiverId, Aircraft a, long receivedAt, boolean simulated) {
        int updated = jdbc.update("""
                UPDATE adsb_aircraft SET callsign=?,airline=?,longitude=?,latitude=?,altitude_m=?,ground_speed_mps=?,track_deg=?,
                    observed_at=?,received_at=?,simulated=?
                WHERE receiver_id=? AND icao24=? AND observed_at<=?
                """, a.callsign(), a.airline(), a.longitude(), a.latitude(), a.altitudeM(), a.groundSpeedMps(), a.trackDeg(),
                a.observedAt(), receivedAt, simulated, receiverId, a.icao24(), a.observedAt());
        if (updated == 0 && jdbc.queryForObject("SELECT COUNT(*) FROM adsb_aircraft WHERE receiver_id=? AND icao24=?",
                Long.class, receiverId, a.icao24()) == 0) {
            jdbc.update("""
                    INSERT INTO adsb_aircraft(receiver_id,icao24,callsign,airline,longitude,latitude,altitude_m,ground_speed_mps,
                        track_deg,observed_at,received_at,simulated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, receiverId, a.icao24(), a.callsign(), a.airline(), a.longitude(), a.latitude(), a.altitudeM(),
                    a.groundSpeedMps(), a.trackDeg(), a.observedAt(), receivedAt, simulated);
        }
    }

    public void touchReceiver(String deviceId, long receivedAt) {
        jdbc.update("UPDATE adsb_receiver SET last_data_at=? WHERE device_id=? AND (last_data_at IS NULL OR last_data_at<?)",
                receivedAt, deviceId, receivedAt);
    }

    /** 平时的航班数据只留最近一段，不长期保存。 */
    public int pruneAircraft(long receivedBefore) {
        return jdbc.update("DELETE FROM adsb_aircraft WHERE received_at<?", receivedBefore);
    }

    /** 反制设备的位置，内部检查用，不按当前账号的设备范围过滤。 */
    public Map<String, Object> devicePosition(String deviceId) {
        var rows = jdbc.queryForList("SELECT device_id,name,longitude,latitude FROM ops_device WHERE device_id=? AND deleted_at IS NULL",
                deviceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* ---- 检查记录 ---- */

    public void insertCheck(String id, String eventId, String authorizationId, String deviceId, String mode, String stage,
            String result, boolean acknowledged, String actorId, String aircraftJson, long policyVersion, long at) {
        jdbc.update("""
                INSERT INTO counter_flight_check(check_id,event_id,authorization_id,device_id,countermeasure_mode,stage,result,
                    acknowledged,actor_id,aircraft_json,policy_version,checked_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, eventId, authorizationId, deviceId, mode, stage, result, acknowledged, actorId, aircraftJson,
                policyVersion, at);
    }

    /** 本条授权有没有人确认过附近航班后继续反制。 */
    public boolean acknowledged(String authorizationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM counter_flight_check WHERE authorization_id=? AND acknowledged=TRUE",
                Long.class, authorizationId) > 0;
    }

    /** 自动反制没启动的原因只记一次；结果变了（例如从提醒变成禁止）再记一条。 */
    public String latestAutomaticResult(String eventId) {
        var rows = jdbc.queryForList("""
                SELECT result FROM counter_flight_check WHERE event_id=? AND stage='AUTO'
                ORDER BY checked_at DESC FETCH FIRST 1 ROWS ONLY
                """, String.class, eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final String CHECK_SELECT = """
            SELECT c.*,u.name AS actor_name,d.name AS device_name FROM counter_flight_check c
            LEFT JOIN app_user u ON u.user_id=c.actor_id LEFT JOIN ops_device d ON d.device_id=c.device_id
            """;

    public List<Map<String, Object>> checksForEvent(String eventId, List<String> authorizationIds) {
        if (authorizationIds.isEmpty())
            return jdbc.queryForList(CHECK_SELECT + "WHERE c.event_id=? ORDER BY c.checked_at,c.check_id", eventId);
        String marks = String.join(",", java.util.Collections.nCopies(authorizationIds.size(), "?"));
        Object[] args = new Object[authorizationIds.size() + 1];
        args[0] = eventId;
        for (int i = 0; i < authorizationIds.size(); i++) args[i + 1] = authorizationIds.get(i);
        return jdbc.queryForList(CHECK_SELECT + "WHERE c.event_id=? OR c.authorization_id IN (" + marks
                + ") ORDER BY c.checked_at,c.check_id", args);
    }

    public List<String> authorizationIdsForEvent(String eventId) {
        return jdbc.queryForList("SELECT authorization_id FROM disposal_authorization WHERE subject_kind='UAV_EVENT' AND subject_id=?",
                String.class, eventId);
    }

    /* ---- 反制中 ---- */

    /** 正在用四通道反制设备反制的授权。 */
    public List<Map<String, Object>> executingCounters() {
        return jdbc.queryForList("""
                SELECT authorization_id,subject_kind,subject_id,device_id,action_type,countermeasure_mode
                FROM disposal_authorization WHERE status='EXECUTING' AND channel='COUNTERMEASURE_4CH'
                    AND action_type IN ('COUNTERMEASURE','JAMMING') AND device_id IS NOT NULL
                """);
    }
}
