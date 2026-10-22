package com.uav.lowaltitude.modules.prediction.application;

import java.util.Collection;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Called inside the fusion transaction, only after its MEAS points have been persisted. */
@Component
public class PredictionJobQueue {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    public PredictionJobQueue(JdbcTemplate jdbc, DataSource source, @Value("${app.prediction.enabled:true}") boolean configured) {
        this.jdbc = jdbc;
        try (var connection = source.getConnection()) {
            enabled = configured && connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
    }
    public boolean enabled() { return enabled; }
    public void register(Collection<String> trackIds, long at) {
        if (!enabled) return;
        for (String trackId : trackIds) jdbc.update("""
                INSERT INTO prediction_job(target_id,requested_at)
                SELECT t.target_id,? FROM track tr JOIN target t ON t.target_id=tr.target_id
                WHERE tr.track_id=? AND tr.layer='FUSED'
                ON CONFLICT(target_id) DO UPDATE SET requested_at=GREATEST(prediction_job.requested_at,EXCLUDED.requested_at),
                  next_attempt_at=LEAST(prediction_job.next_attempt_at,EXCLUDED.requested_at)
                """, at, trackId);
    }
}
