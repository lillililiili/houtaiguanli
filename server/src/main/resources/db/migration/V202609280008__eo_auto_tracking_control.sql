CREATE TABLE eo_target_control (
    target_id VARCHAR(36) PRIMARY KEY REFERENCES target(target_id),
    auto_paused BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by VARCHAR(36) REFERENCES app_user(user_id),
    updated_at BIGINT NOT NULL
);
CREATE TABLE eo_control_request (
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    request_key VARCHAR(128) NOT NULL,
    target_id VARCHAR(36) NOT NULL REFERENCES target(target_id),
    action VARCHAR(8) NOT NULL CHECK (action IN ('PAUSE','RESUME')),
    created_at BIGINT NOT NULL,
    PRIMARY KEY(actor_id, request_key)
);
ALTER TABLE eo_tracking_task ADD COLUMN origin VARCHAR(8) NOT NULL DEFAULT 'MANUAL'
    CHECK (origin IN ('AUTO','MANUAL'));
UPDATE eo_tracking_task SET origin='AUTO' WHERE fusion_event_id IS NOT NULL;
CREATE INDEX idx_eo_tracking_target ON eo_tracking_task(target_id,status,created_at);
