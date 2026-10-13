CREATE TABLE eo_dispatch_request (
    request_id VARCHAR(64) PRIMARY KEY,
    target_id VARCHAR(64) NOT NULL REFERENCES target(target_id),
    active_target_id VARCHAR(64) UNIQUE,
    origin VARCHAR(16) NOT NULL,
    requested_by VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    device_id VARCHAR(64),
    stop_task_id VARCHAR(64),
    task_id VARCHAR(64),
    message VARCHAR(512),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CHECK (origin IN ('AUTO','MANUAL')),
    CHECK (status IN ('WAITING','STOPPING','STARTED','FAILED','CANCELLED'))
);
CREATE INDEX idx_eo_dispatch_wait ON eo_dispatch_request(status, created_at, request_id);
CREATE TABLE eo_dispatch_action (
    actor_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    action VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(actor_id,request_key)
);
