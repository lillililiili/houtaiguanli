ALTER TABLE ops_device_maintenance_task ADD COLUMN workflow_state VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE ops_device_maintenance_task ADD COLUMN workflow_source_mode VARCHAR(16);
ALTER TABLE ops_device_maintenance_task ADD COLUMN assigned_to VARCHAR(36) REFERENCES app_user(user_id);
ALTER TABLE ops_device_maintenance_task ADD COLUMN assigned_to_name VARCHAR(128);
ALTER TABLE ops_device_maintenance_task ADD COLUMN recovery_result VARCHAR(16);
ALTER TABLE ops_device_maintenance_task ADD COLUMN recovery_reason TEXT;
ALTER TABLE ops_device_maintenance_task ADD COLUMN recovery_checked_at BIGINT;
UPDATE ops_device_maintenance_task SET workflow_state='LEGACY_HANDLED' WHERE status='HANDLED';
UPDATE ops_device_maintenance_task SET workflow_source_mode=(SELECT d.source_mode FROM ops_device d WHERE d.device_id=ops_device_maintenance_task.device_id);
ALTER TABLE ops_device_maintenance_task ADD CONSTRAINT ck_maintenance_workflow_state CHECK (
 (status='PENDING' AND workflow_state IN ('PENDING','PROCESSING','PENDING_VERIFICATION'))
 OR (status='HANDLED' AND workflow_state IN ('COMPLETED','LEGACY_HANDLED')));
ALTER TABLE ops_device_maintenance_task ADD CONSTRAINT ck_maintenance_recovery_result CHECK (recovery_result IN ('PASS','FAIL','UNKNOWN'));
CREATE INDEX idx_maintenance_workflow ON ops_device_maintenance_task(workflow_state,reported_at,task_id);
CREATE INDEX idx_maintenance_assignee ON ops_device_maintenance_task(assigned_to);
CREATE TABLE ops_maintenance_workflow_event (
 event_id VARCHAR(36) PRIMARY KEY,
 task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
 action VARCHAR(32) NOT NULL,
 note TEXT,
 actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
 actor_name VARCHAR(128) NOT NULL,
 occurred_at BIGINT NOT NULL,
 task_version BIGINT NOT NULL,
 UNIQUE(task_id,task_version)
);
CREATE INDEX idx_maintenance_event_actor ON ops_maintenance_workflow_event(actor_id);
CREATE TABLE ops_maintenance_workflow_request (
 actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
 request_key VARCHAR(128) NOT NULL,
 task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
 request_body TEXT NOT NULL,
 response_body TEXT NOT NULL,
 PRIMARY KEY(actor_id,request_key)
);
CREATE INDEX idx_maintenance_request_task ON ops_maintenance_workflow_request(task_id);
CREATE TABLE ops_maintenance_commission (
 task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
 commission_id VARCHAR(36) NOT NULL REFERENCES commission_task(commission_id),
 PRIMARY KEY(task_id,commission_id)
);
CREATE INDEX idx_maintenance_commission_id ON ops_maintenance_commission(commission_id);
CREATE TABLE ops_maintenance_message_read (
 actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
 task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
 read_at BIGINT NOT NULL,
 PRIMARY KEY(actor_id,task_id)
);
CREATE INDEX idx_maintenance_read_task ON ops_maintenance_message_read(task_id);
