package com.uav.lowaltitude.modules.device.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Trends reuse the existing numeric history; report totals count accepted messages, never polling requests. */
@Repository
public class DeviceTrendRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public DeviceTrendRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    public boolean sensing(String id) {
        return !jdbc.queryForList("SELECT ops_device_id FROM mqtt_device_binding WHERE ops_device_id=:id AND device_type_abbr IN ('radar','5ga','tdoa','aoa','dcd','rid')", Map.of("id",id)).isEmpty();
    }

    public static void record(JdbcTemplate jdbc, String id, String code, Number value, long at, boolean simulated) {
        if (value == null) return;
        jdbc.update("""
                INSERT INTO ops_device_state_history
                (state_id,device_id,connectivity,observed_at,received_at,metric_code,metric_value,metric_unit,simulated)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), id, "connection_state".equals(code) && value.intValue() == 0 ? "OFFLINE" : "ONLINE",
                at, at, code, value, null, simulated);
    }

    public List<Map<String,Object>> series(String id, long start, long end, long step, boolean simulated, boolean mqtt, boolean reports) {
        String source = reports && mqtt ? """
                SELECT received_at, CASE reason WHEN 'STATIC_UPDATED' THEN 'report_static'
                  WHEN 'HEARTBEAT' THEN 'report_heartbeat' WHEN 'HEARTBEAT_UPDATED' THEN 'report_heartbeat'
                  WHEN 'INBOX_RECEIVED' THEN 'report_sensing' ELSE 'report_other' END AS code,
                  1.0 AS sample_value FROM mqtt_receive_diagnostic
                WHERE ops_device_id=:id AND outcome='ACCEPTED' AND received_at>=:start AND received_at<=:end
                """ : """
                SELECT received_at, metric_code AS code, metric_value AS sample_value FROM ops_device_state_history
                WHERE device_id=:id AND received_at>=:start AND received_at<=:end AND simulated=:simulated
                """;
        if (reports && !mqtt) source = """
                SELECT received_at, CASE metric_code WHEN 'active_track_count' THEN 'report_track'
                  WHEN 'latest_point_count' THEN 'report_point' WHEN 'rtk_satellite_count' THEN 'report_rtk'
                  ELSE 'report_status' END AS code, 1.0 AS sample_value FROM ops_device_state_history
                WHERE device_id=:id AND received_at>=:start AND received_at<=:end AND simulated=:simulated
                  AND metric_code IN ('active_track_count','latest_point_count','rtk_satellite_count','report_status')
                """;
        return jdbc.queryForList("WITH raw AS (" + source + """
                ), numbered AS (
                  SELECT *, FLOOR(CAST((received_at-:start) AS NUMERIC)/:step) AS bucket,
                    LAG(received_at) OVER (PARTITION BY code ORDER BY received_at) AS previous_at
                  FROM raw
                ), ranked AS (
                  SELECT *, ROW_NUMBER() OVER (PARTITION BY code,bucket ORDER BY received_at DESC) AS position
                  FROM numbered
                )
                SELECT code,bucket,COUNT(*) AS samples,AVG(sample_value) AS average,MIN(sample_value) AS minimum,MAX(sample_value) AS maximum,
                  MAX(CASE WHEN position=1 THEN sample_value END) AS latest,
                  MAX(received_at) AS last_at, AVG((received_at-previous_at)/1000.0) AS interval_seconds
                FROM ranked GROUP BY code,bucket ORDER BY code,bucket
                """, Map.of("id",id,"start",start,"end",end,"step",step,"simulated",simulated));
    }
}
