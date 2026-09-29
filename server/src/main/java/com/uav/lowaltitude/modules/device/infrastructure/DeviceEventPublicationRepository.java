package com.uav.lowaltitude.modules.device.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Assigns API delivery order only after raw events become visible to the reading transaction. */
@Repository
public class DeviceEventPublicationRepository {
    private final JdbcTemplate jdbc;

    public DeviceEventPublicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The singleton lock is retained through the caller's transaction commit, including its API read. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCommitted() {
        long next = jdbc.queryForObject("SELECT next_seq FROM device_event_publication_cursor WHERE singleton=1 FOR UPDATE", Long.class);
        var unpublished = jdbc.queryForList("""
                SELECT e.event_seq FROM device_event_log e
                WHERE NOT EXISTS (SELECT 1 FROM device_event_publication p WHERE p.raw_event_seq=e.event_seq)
                ORDER BY e.event_seq
                """, Long.class);
        // No raw-row locks: a lower identity may still be uncommitted and will be published on a later poll.
        for (long raw : unpublished)
            jdbc.update("INSERT INTO device_event_publication(raw_event_seq,event_seq) VALUES (?,?)", raw, next++);
        if (!unpublished.isEmpty())
            jdbc.update("UPDATE device_event_publication_cursor SET next_seq=? WHERE singleton=1", next);
    }
}
