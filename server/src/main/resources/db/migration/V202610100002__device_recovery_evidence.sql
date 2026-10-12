-- Explicit health/alarm evidence has its own time and provenance. Historical values remain unproven.
ALTER TABLE ops_device_state ADD COLUMN health_observed_at BIGINT;
ALTER TABLE ops_device_state ADD COLUMN health_source_id VARCHAR(36);
ALTER TABLE ops_device_state ADD COLUMN health_source_mode VARCHAR(16);
ALTER TABLE ops_device_state ADD COLUMN health_simulated BOOLEAN;
ALTER TABLE ops_device_state ADD COLUMN alarm_observed_at BIGINT;
ALTER TABLE ops_device_state ADD COLUMN alarm_source_id VARCHAR(36);
ALTER TABLE ops_device_state ADD COLUMN alarm_source_mode VARCHAR(16);
ALTER TABLE ops_device_state ADD COLUMN alarm_simulated BOOLEAN;
ALTER TABLE device_recovery_check ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE device_recovery_check ADD CONSTRAINT ck_device_recovery_actor_type
    CHECK (actor_type IN ('USER','SYSTEM'));
