package com.uav.lowaltitude.modules.device.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A committed intent before socket I/O is conservative proof that a start may have reached the controller. */
@Service
public class CountermeasureStartAttempts {
    private final JdbcTemplate jdbc;
    public CountermeasureStartAttempts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean exists(String commandId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM countermeasure_start_attempt WHERE command_id=?",
                Integer.class, commandId) > 0;
    }

    /** The caller serializes this command; no command/device FK or lock is acquired in this independent transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(String commandId, String authorizationId, String deviceId, long at) {
        if (exists(commandId)) return false;
        jdbc.update("INSERT INTO countermeasure_start_attempt(command_id,authorization_id,device_id,attempted_at) VALUES (?,?,?,?)",
                commandId, authorizationId, deviceId, at);
        return true;
    }
}
