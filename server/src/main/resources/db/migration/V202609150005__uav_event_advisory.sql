CREATE TABLE uav_event_advisory (
    record_id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
    event_version BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('SMS_SIMULATED','CONTACT_RECORDED','OBSERVATION')),
    created_at BIGINT NOT NULL,
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    recipient_name VARCHAR(120),
    contact_basis VARCHAR(500),
    content VARCHAR(1000),
    outcome VARCHAR(24) CHECK (outcome IN ('DEPARTED','STILL_INSIDE','UNKNOWN')),
    danger VARCHAR(16) CHECK (danger IN ('HIGH','MEDIUM','LOW','UNKNOWN')),
    note VARCHAR(1000),
    urgent BOOLEAN NOT NULL,
    simulated BOOLEAN NOT NULL,
    delivery_status VARCHAR(32),
    UNIQUE(event_id,event_version),
    CHECK ((kind='OBSERVATION' AND outcome IS NOT NULL AND danger IS NOT NULL AND note IS NOT NULL)
        OR (kind IN ('SMS_SIMULATED','CONTACT_RECORDED') AND recipient_name IS NOT NULL AND contact_basis IS NOT NULL AND content IS NOT NULL)),
    CHECK (urgent=FALSE OR (kind='OBSERVATION' AND outcome='STILL_INSIDE' AND danger='HIGH')),
    CHECK ((kind='SMS_SIMULATED' AND simulated=TRUE AND delivery_status='SIMULATED_DELIVERED') OR (kind<>'SMS_SIMULATED' AND simulated=FALSE AND delivery_status IS NULL))
);
CREATE INDEX idx_advisory_event ON uav_event_advisory(event_id,event_version);
CREATE TABLE uav_event_advisory_request (
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    request_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
    response_text TEXT NOT NULL,
    PRIMARY KEY(actor_id,request_key)
);
