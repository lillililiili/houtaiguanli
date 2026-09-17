ALTER TABLE uav_event_advisory ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE uav_event_advisory ADD COLUMN trigger_mode VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE uav_event_advisory ADD COLUMN actor_label VARCHAR(120);
ALTER TABLE uav_event_advisory ADD COLUMN policy_code VARCHAR(64);
ALTER TABLE uav_event_advisory ADD CONSTRAINT ck_advisory_actor CHECK ((trigger_mode='MANUAL' AND actor_id IS NOT NULL) OR (trigger_mode='AUTO' AND actor_id IS NULL AND actor_label IS NOT NULL AND policy_code IS NOT NULL));
CREATE TABLE uav_auto_sms_task (
    event_id VARCHAR(36) PRIMARY KEY REFERENCES uav_event(event_id),
    status VARCHAR(32) NOT NULL CHECK (status IN ('WAITING','SENDING','SIMULATED_DELIVERED','FAILED','UNAVAILABLE','BLOCKED')),
    policy_code VARCHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    trigger_source VARCHAR(40),
    evaluation_id VARCHAR(36),
    evaluated_at BIGINT,
    data_updated_at BIGINT,
    triggered_at BIGINT,
    updated_at BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    lease_until BIGINT,
    claim_token VARCHAR(36),
    delivery_record_id VARCHAR(36) REFERENCES uav_event_advisory(record_id),
    provider_key VARCHAR(100) NOT NULL UNIQUE,
    CHECK (status<>'SIMULATED_DELIVERED' OR delivery_record_id IS NOT NULL)
);
CREATE INDEX idx_auto_sms_status ON uav_auto_sms_task(status,updated_at);
