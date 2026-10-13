package com.uav.lowaltitude.modules.device.infrastructure;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads this task's AI evidence, including unlinked and rejected observations. */
@Repository
public class EoFusionObservationRepository {
    public record Evidence(Instant observedAt, Instant receivedAt, String qualityJson) { }
    private final JdbcTemplate jdbc;
    public EoFusionObservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Evidence latest(String sourceId, String deviceId, String taskId) {
        return jdbc.query("""
                SELECT observed_at,received_at,quality FROM source_observation
                WHERE source_id=? AND device_id=? AND source_session_key=? AND external_target_id=?
                  AND class_source='EO_TRACKING'
                ORDER BY observed_at DESC,received_at DESC,observation_id DESC FETCH FIRST 1 ROW ONLY
                """, (rs, row) -> new Evidence(rs.getTimestamp("observed_at").toInstant(),
                        rs.getTimestamp("received_at").toInstant(),rs.getString("quality")),
                sourceId,deviceId,taskId,taskId).stream().findFirst().orElse(null);
    }
}
