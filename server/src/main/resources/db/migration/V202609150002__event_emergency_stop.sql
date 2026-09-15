CREATE TABLE disposal_emergency_stop (
    stop_id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
    requested_by VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    requested_at BIGINT NOT NULL,
    reason_pending BOOLEAN NOT NULL DEFAULT TRUE,
    note VARCHAR(500) NOT NULL
);
CREATE INDEX idx_emergency_stop_event ON disposal_emergency_stop(event_id, requested_at);
CREATE TABLE disposal_emergency_stop_authorization (
    stop_id VARCHAR(36) NOT NULL REFERENCES disposal_emergency_stop(stop_id),
    authorization_id VARCHAR(36) NOT NULL REFERENCES disposal_authorization(authorization_id),
    PRIMARY KEY(stop_id, authorization_id),
    UNIQUE(authorization_id)
);
CREATE TABLE disposal_emergency_stop_device (
    stop_id VARCHAR(36) NOT NULL REFERENCES disposal_emergency_stop(stop_id),
    device_id VARCHAR(80) NOT NULL,
    authorization_id VARCHAR(36) NOT NULL REFERENCES disposal_authorization(authorization_id),
    device_name VARCHAR(128) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    source_mode VARCHAR(16) NOT NULL,
    simulated BOOLEAN NOT NULL,
    command_id VARCHAR(36) REFERENCES device_command(command_id),
    stop_status VARCHAR(40) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    confirmed_by VARCHAR(36) REFERENCES app_user(user_id),
    confirmed_at BIGINT,
    confirmation_note VARCHAR(500),
    PRIMARY KEY(stop_id, device_id),
    CHECK ((confirmed_by IS NULL AND confirmed_at IS NULL AND confirmation_note IS NULL)
        OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND confirmation_note IS NOT NULL))
);
CREATE TABLE disposal_emergency_stop_event (
    event_id VARCHAR(36) PRIMARY KEY,
    stop_id VARCHAR(36) NOT NULL REFERENCES disposal_emergency_stop(stop_id),
    kind VARCHAR(40) NOT NULL,
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    occurred_at BIGINT NOT NULL,
    note VARCHAR(500) NOT NULL
);
CREATE TABLE disposal_emergency_stop_request (
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    request_key VARCHAR(128) NOT NULL,
    operation VARCHAR(500) NOT NULL,
    request_note VARCHAR(500) NOT NULL,
    stop_id VARCHAR(36) NOT NULL REFERENCES disposal_emergency_stop(stop_id),
    PRIMARY KEY(actor_id, request_key)
);
