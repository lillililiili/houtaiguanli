ALTER TABLE flight_plan_verification ALTER COLUMN handled_by DROP NOT NULL;
ALTER TABLE flight_plan_verification ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE flight_plan_verification ADD CONSTRAINT ck_verification_actor CHECK
 ((trigger_type='USER' AND handled_by IS NOT NULL) OR (trigger_type='SYSTEM' AND handled_by IS NULL));
ALTER TABLE ops_device_maintenance_task ALTER COLUMN reported_by DROP NOT NULL;
ALTER TABLE ops_device_maintenance_task ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE ops_device_maintenance_task ADD CONSTRAINT ck_maintenance_actor CHECK
 ((trigger_type='USER' AND reported_by IS NOT NULL) OR (trigger_type='SYSTEM' AND reported_by IS NULL));

CREATE TABLE flight_device_check_schedule (
 plan_id VARCHAR(36) PRIMARY KEY REFERENCES flight_plan(plan_id),
 scope_key VARCHAR(64) NOT NULL,
 plan_version BIGINT NOT NULL,
 next_check_at BIGINT NOT NULL,
 last_checked_at BIGINT,
 state VARCHAR(24) NOT NULL,
 fingerprint VARCHAR(64),
 snapshot_json TEXT,
 verification_id VARCHAR(36) REFERENCES flight_plan_verification(verification_id),
 failure_code VARCHAR(64)
);
CREATE INDEX ix_flight_device_check_due ON flight_device_check_schedule(next_check_at,plan_id);
CREATE TABLE flight_device_check_fault (
 plan_id VARCHAR(36) NOT NULL REFERENCES flight_plan(plan_id),
 device_id VARCHAR(36) NOT NULL REFERENCES ops_device(device_id),
 episode_id VARCHAR(36) NOT NULL,
 incident_key VARCHAR(64) NOT NULL,
 active BOOLEAN NOT NULL,
 task_id VARCHAR(36) REFERENCES ops_device_maintenance_task(task_id),
 PRIMARY KEY(plan_id,device_id)
);
