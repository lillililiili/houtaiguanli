-- Ordering is independent from permission to verify or start a countermeasure.
CREATE TABLE response_order_policy (
    policy_id VARCHAR(32) PRIMARY KEY,
    version BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    minimum_risk VARCHAR(16) NOT NULL,
    prediction_enabled BOOLEAN NOT NULL,
    observed_enabled BOOLEAN NOT NULL,
    scope_mode VARCHAR(16) NOT NULL,
    schedule_mode VARCHAR(16) NOT NULL,
    start_time VARCHAR(5) NOT NULL,
    end_time VARCHAR(5) NOT NULL,
    max_wait_seconds INTEGER NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT ck_response_order_risk CHECK(minimum_risk IN ('HIGH','MEDIUM')),
    CONSTRAINT ck_response_order_scope CHECK(scope_mode IN ('ALL','AIRSPACES')),
    CONSTRAINT ck_response_order_schedule CHECK(schedule_mode IN ('ALL_DAY','DAILY')),
    CONSTRAINT ck_response_order_wait CHECK(max_wait_seconds BETWEEN 5 AND 300)
);
INSERT INTO response_order_policy VALUES ('uav',1,FALSE,'HIGH',TRUE,TRUE,'ALL','ALL_DAY','00:00','00:00',60,0);

CREATE TABLE response_order_scope (
    policy_id VARCHAR(32) NOT NULL REFERENCES response_order_policy(policy_id),
    airspace_id VARCHAR(64) NOT NULL REFERENCES airspace(airspace_id),
    ordinal INTEGER NOT NULL,
    PRIMARY KEY(policy_id,airspace_id)
);
CREATE TABLE response_order_change (
    change_id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL UNIQUE,
    actor_id VARCHAR(64) NOT NULL,
    actor VARCHAR(160) NOT NULL,
    created_at BIGINT NOT NULL,
    before_json TEXT NOT NULL,
    after_json TEXT NOT NULL
);
-- A deadline belongs to the risk, not to the currently displayed event or policy version.
CREATE TABLE response_order_window (
    risk_key VARCHAR(100) PRIMARY KEY,
    first_event_id VARCHAR(64) NOT NULL,
    policy_version BIGINT NOT NULL,
    started_at BIGINT NOT NULL,
    deadline_at BIGINT NOT NULL,
    max_wait_seconds INTEGER NOT NULL,
    CONSTRAINT ck_response_order_window CHECK(deadline_at >= started_at)
);
ALTER TABLE uav_auto_voice_task ADD COLUMN send_started_at BIGINT;
UPDATE uav_auto_voice_task SET send_started_at=COALESCE(triggered_at,updated_at) WHERE attempt_count>0;

ALTER TABLE automation_runtime_run ADD COLUMN risk_case_id VARCHAR(36) REFERENCES uav_risk_case(risk_case_id);
CREATE TABLE automation_case_state (
 risk_case_id VARCHAR(36) NOT NULL REFERENCES uav_risk_case(risk_case_id),
 category VARCHAR(16) NOT NULL CHECK(category IN ('verify','counter')),
 event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
 group_version BIGINT NOT NULL,
 run_id VARCHAR(36) REFERENCES automation_runtime_run(run_id),
 status VARCHAR(32) NOT NULL,
 unknown_since BIGINT,
 holds_json TEXT NOT NULL DEFAULT '{}',
 signature TEXT NOT NULL DEFAULT '',
 PRIMARY KEY(category,risk_case_id)
);
