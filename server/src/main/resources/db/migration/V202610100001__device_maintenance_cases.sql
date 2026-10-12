-- New cases share one active device/source identity; historical reports remain unchanged.
ALTER TABLE ops_device_maintenance_task ADD COLUMN workflow_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE ops_device_maintenance_task ADD COLUMN workflow_source_id VARCHAR(36);
ALTER TABLE ops_device_maintenance_task ADD COLUMN recovery_since_at BIGINT;
ALTER TABLE ops_device_maintenance_task ADD COLUMN recovery_snapshot TEXT;
ALTER TABLE ops_device_maintenance_task ADD COLUMN recovery_fingerprint VARCHAR(64);
ALTER TABLE ops_device_maintenance_task ADD COLUMN next_recovery_check_at BIGINT;
ALTER TABLE ops_device_maintenance_task ALTER COLUMN plan_id DROP NOT NULL;
ALTER TABLE ops_device_maintenance_task ALTER COLUMN owner_org_id DROP NOT NULL;
ALTER TABLE ops_device_maintenance_task ALTER COLUMN district_id DROP NOT NULL;
ALTER TABLE ops_device_maintenance_task DROP CONSTRAINT ck_maintenance_workflow_state;
ALTER TABLE ops_device_maintenance_task ADD CONSTRAINT ck_maintenance_workflow_state CHECK (
 (status='PENDING' AND workflow_state IN ('PENDING','PROCESSING','PENDING_VERIFICATION','READY_TO_COMPLETE'))
 OR (status='HANDLED' AND workflow_state IN ('COMPLETED','LEGACY_HANDLED')));
ALTER TABLE ops_device_maintenance_task ADD CONSTRAINT ck_maintenance_case_version CHECK (
 workflow_version IN (1,2) AND (workflow_version=2 OR (plan_id IS NOT NULL AND owner_org_id IS NOT NULL AND district_id IS NOT NULL)));
ALTER TABLE ops_maintenance_workflow_event ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE ops_device_maintenance_submission ADD COLUMN plan_id VARCHAR(36) REFERENCES flight_plan(plan_id);
UPDATE ops_device_maintenance_submission SET plan_id=(SELECT t.plan_id FROM ops_device_maintenance_task t WHERE t.task_id=ops_device_maintenance_submission.task_id);
CREATE INDEX idx_maintenance_due ON ops_device_maintenance_task(status,next_recovery_check_at,task_id);
CREATE TABLE ops_maintenance_plan (
 task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
 plan_id VARCHAR(36) NOT NULL REFERENCES flight_plan(plan_id),
 first_reported_at BIGINT NOT NULL,
 PRIMARY KEY(task_id,plan_id)
);
CREATE TABLE ops_maintenance_report (
 report_id VARCHAR(36) PRIMARY KEY,
 task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
 plan_id VARCHAR(36) REFERENCES flight_plan(plan_id),
 actor_id VARCHAR(36) REFERENCES app_user(user_id),
 actor_name VARCHAR(128) NOT NULL,
 reason TEXT NOT NULL,
 reported_at BIGINT NOT NULL,
 report_key VARCHAR(200) NOT NULL,
 UNIQUE(task_id,report_key)
);
CREATE TABLE ops_maintenance_incident (
 task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
 incident_id VARCHAR(36) NOT NULL REFERENCES device_incident(incident_id),
 PRIMARY KEY(task_id,incident_id)
);
