-- K03 has its own immutable evidence and mutable lifecycle. No measured/legality history is rewritten.
CREATE TABLE prediction_parameter_version (
    version_id VARCHAR(64) PRIMARY KEY,
    parameters_json JSON NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE TABLE prediction_job (
    target_id VARCHAR(36) PRIMARY KEY REFERENCES target(target_id),
    requested_at BIGINT NOT NULL,
    processed_at BIGINT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500)
);
CREATE INDEX idx_prediction_job_due ON prediction_job(next_attempt_at,target_id);
CREATE TABLE prediction_snapshot (
    prediction_id VARCHAR(36) PRIMARY KEY,
    target_id VARCHAR(36) NOT NULL REFERENCES target(target_id),
    anchor_point_id VARCHAR(36),
    generated_at BIGINT NOT NULL,
    based_on_observed_at BIGINT,
    valid_until BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('READY','UNKNOWN')),
    reason VARCHAR(128) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    rule_version VARCHAR(64) NOT NULL REFERENCES prediction_parameter_version(version_id),
    input_json JSON NOT NULL,
    config_json JSON NOT NULL,
    result_json JSON NOT NULL,
    owner_org_id VARCHAR(36) NOT NULL,
    district_id VARCHAR(36) NOT NULL,
    source_mode VARCHAR(8) NOT NULL,
    simulated BOOLEAN NOT NULL
);
CREATE INDEX idx_prediction_snapshot_target ON prediction_snapshot(target_id,generated_at);
CREATE TABLE prediction_current (
    target_id VARCHAR(36) PRIMARY KEY REFERENCES target(target_id),
    prediction_id VARCHAR(36) NOT NULL REFERENCES prediction_snapshot(prediction_id)
);
CREATE TABLE prediction_episode (
    episode_id VARCHAR(36) PRIMARY KEY,
    target_id VARCHAR(36) NOT NULL REFERENCES target(target_id),
    airspace_id VARCHAR(36) NOT NULL,
    active_key VARCHAR(80) UNIQUE,
    source_mode VARCHAR(8) NOT NULL,
    owner_org_id VARCHAR(36) NOT NULL,
    district_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status IN ('CANDIDATE','ACTIVE','UNKNOWN','CLEARED','ENTERED')),
    version BIGINT NOT NULL DEFAULT 0,
    latest_prediction_id VARCHAR(36) REFERENCES prediction_snapshot(prediction_id),
    first_prediction_id VARCHAR(36) REFERENCES prediction_snapshot(prediction_id),
    last_observed_at BIGINT,
    valid_until BIGINT NOT NULL,
    current_evidence_valid BOOLEAN NOT NULL DEFAULT FALSE,
    entered_evidence_valid BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(500) NOT NULL,
    started_at BIGINT NOT NULL,
    closed_at BIGINT,
    manual_counter_only BOOLEAN NOT NULL DEFAULT TRUE,
    last_paused_version BIGINT NOT NULL DEFAULT -1,
    stability_json JSON NOT NULL,
    warning_json JSON NOT NULL
);
CREATE INDEX idx_prediction_episode_target ON prediction_episode(target_id,status);
CREATE TABLE prediction_event_link (
    event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
    episode_id VARCHAR(36) NOT NULL REFERENCES prediction_episode(episode_id),
    role VARCHAR(16) NOT NULL CHECK(role IN ('PREDICTED','OBSERVED')),
    created_at BIGINT NOT NULL,
    PRIMARY KEY(event_id,episode_id)
);
CREATE INDEX idx_prediction_event_episode ON prediction_event_link(episode_id);
CREATE TABLE prediction_episode_snapshot (
    episode_id VARCHAR(36) NOT NULL REFERENCES prediction_episode(episode_id),
    prediction_id VARCHAR(36) NOT NULL REFERENCES prediction_snapshot(prediction_id),
    created_at BIGINT NOT NULL,
    PRIMARY KEY(episode_id,prediction_id)
);
CREATE TABLE prediction_episode_history (
    history_id VARCHAR(36) PRIMARY KEY,
    episode_id VARCHAR(36) NOT NULL REFERENCES prediction_episode(episode_id),
    prediction_id VARCHAR(36) REFERENCES prediction_snapshot(prediction_id),
    version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(episode_id,version)
);
CREATE TABLE prediction_verification_basis (
    history_id VARCHAR(36) PRIMARY KEY REFERENCES uav_event_verification(history_id),
    prediction_id VARCHAR(36) NOT NULL REFERENCES prediction_snapshot(prediction_id),
    checked_prediction_id VARCHAR(36) NOT NULL REFERENCES prediction_snapshot(prediction_id),
    episode_id VARCHAR(36) NOT NULL REFERENCES prediction_episode(episode_id)
);
CREATE TABLE prediction_actual_entry_link (
    event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
    target_id VARCHAR(36) NOT NULL REFERENCES target(target_id),
    airspace_id VARCHAR(36) NOT NULL,
    observed_at BIGINT NOT NULL,
    evaluation_id VARCHAR(36) NOT NULL REFERENCES rule_evaluation(evaluation_id),
    airspace_version_id VARCHAR(36) NOT NULL,
    PRIMARY KEY(event_id,evaluation_id,airspace_id)
);
CREATE INDEX idx_prediction_actual_entry_target ON prediction_actual_entry_link(target_id,airspace_id,observed_at);
-- System safety stops carry an explicit origin, never an invented human operator.
ALTER TABLE disposal_emergency_stop ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE disposal_emergency_stop ADD COLUMN request_origin VARCHAR(32) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE disposal_emergency_stop_event ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE device_command ALTER COLUMN requested_by DROP NOT NULL;

INSERT INTO integration_source
    (source_id,source_code,name,protocol_code,protocol_version,enabled,credential_ref,source_mode,created_at,updated_at,version)
VALUES
    ('k03-prediction-live','K03-PREDICTION-LIVE','K03短时预测（实测来源）','K03','1',TRUE,NULL,'live',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),
    ('k03-prediction-replay','K03-PREDICTION-REPLAY','K03短时预测（实时模拟来源）','K03','1',TRUE,NULL,'replay',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0);
