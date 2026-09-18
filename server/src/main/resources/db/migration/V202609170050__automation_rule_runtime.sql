-- Runtime state and immutable decision snapshots. Configuration starts empty; no demo thresholds or grants.
CREATE TABLE automation_runtime_worker (
 worker_key VARCHAR(32) PRIMARY KEY,
 heartbeat_at BIGINT NOT NULL DEFAULT 0,
 last_error VARCHAR(500),
 cursor_event_id VARCHAR(36) NOT NULL DEFAULT ''
);
INSERT INTO automation_runtime_worker(worker_key) VALUES('automation-rules');
CREATE TABLE automation_runtime_run (
 run_id VARCHAR(36) PRIMARY KEY,
 category VARCHAR(16) NOT NULL REFERENCES automation_rule_group(category),
 event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
 target_id VARCHAR(36),
 group_version BIGINT NOT NULL,
 status VARCHAR(32) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 evaluated_at BIGINT NOT NULL,
 observed_at BIGINT,
 source_mode VARCHAR(16),
 conditions_json TEXT NOT NULL,
 facts_json TEXT NOT NULL
);
CREATE INDEX idx_automation_runtime_run ON automation_runtime_run(category,evaluated_at DESC,run_id);
CREATE TABLE automation_runtime_state (
 category VARCHAR(16) NOT NULL REFERENCES automation_rule_group(category),
 event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
 group_version BIGINT NOT NULL,
 run_id VARCHAR(36) REFERENCES automation_runtime_run(run_id),
 status VARCHAR(32) NOT NULL,
 unknown_since BIGINT,
 holds_json TEXT NOT NULL DEFAULT '{}',
 signature TEXT NOT NULL DEFAULT '',
 PRIMARY KEY(category,event_id)
);
CREATE TABLE automation_runtime_action (
 action_id VARCHAR(36) PRIMARY KEY,
 event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
 category VARCHAR(16) NOT NULL REFERENCES automation_rule_group(category),
 code VARCHAR(32) NOT NULL,
 run_id VARCHAR(36) NOT NULL REFERENCES automation_runtime_run(run_id),
 status VARCHAR(32) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 reference_id VARCHAR(128),
 receipt_json TEXT,
 updated_at BIGINT NOT NULL,
 lease_until BIGINT,
 attempt_count INTEGER NOT NULL DEFAULT 0,
 UNIQUE(event_id,code)
);
CREATE TABLE automation_runtime_run_action (
 run_id VARCHAR(36) NOT NULL REFERENCES automation_runtime_run(run_id),
 action_id VARCHAR(36) NOT NULL REFERENCES automation_runtime_action(action_id),
 PRIMARY KEY(run_id,action_id)
);
-- A system verification is an explicit source; it cannot be attributed to a fictitious human.
ALTER TABLE uav_event_verification ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE uav_event_verification ADD COLUMN automation_run_id VARCHAR(36) REFERENCES automation_runtime_run(run_id);
ALTER TABLE uav_event_verification ADD CONSTRAINT ck_verification_actor_source CHECK(actor_id IS NOT NULL OR automation_run_id IS NOT NULL);
