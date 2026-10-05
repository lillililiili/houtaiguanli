-- Persist the explicit operator decision to leave a confirmed UAV event
-- without countermeasure, together with idempotent response replays.
CREATE TABLE uav_no_counter_decision (
    decision_id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
    event_version BIGINT NOT NULL,
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    actor_name VARCHAR(128) NOT NULL,
    decided_at BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    evaluation_id VARCHAR(36) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    owner_org_id VARCHAR(36) NOT NULL,
    district_id VARCHAR(36) NOT NULL,
    source_mode VARCHAR(16) NOT NULL,
    basis_text TEXT NOT NULL,
    CONSTRAINT uk_no_counter_decision_event_version UNIQUE(event_id, event_version)
);
CREATE INDEX idx_no_counter_decision_event ON uav_no_counter_decision(event_id, event_version DESC);

CREATE TABLE uav_no_counter_request (
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    request_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
    response_text TEXT NOT NULL,
    PRIMARY KEY(actor_id, request_key)
);
CREATE INDEX idx_no_counter_request_event ON uav_no_counter_request(event_id);
