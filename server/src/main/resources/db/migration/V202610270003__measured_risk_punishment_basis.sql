-- A continuing regional risk has one punishment handoff. Legacy material remains unmodified.
ALTER TABLE handoff ADD COLUMN risk_case_id VARCHAR(36) NOT NULL DEFAULT '';
ALTER TABLE handoff DROP CONSTRAINT uk_stage5_handoff_logical;
ALTER TABLE handoff ADD CONSTRAINT uk_stage5_handoff_logical UNIQUE (source_kind,source_id,handoff_type,recipient_id,risk_case_id);
CREATE INDEX idx_handoff_risk_case ON handoff(risk_case_id,handoff_type);
CREATE TABLE handoff_measured_basis (
    handoff_id VARCHAR(36) PRIMARY KEY REFERENCES handoff(handoff_id),
    risk_case_id VARCHAR(36) NOT NULL UNIQUE REFERENCES uav_risk_case(risk_case_id),
    authorization_id VARCHAR(36) NOT NULL REFERENCES disposal_authorization(authorization_id),
    evaluation_id VARCHAR(36) NOT NULL REFERENCES rule_evaluation(evaluation_id),
    airspace_version_id VARCHAR(36) NOT NULL REFERENCES airspace_version(airspace_version_id),
    observation_id VARCHAR(36) NOT NULL REFERENCES source_observation(observation_id),
    point_id VARCHAR(36) NOT NULL REFERENCES track_point(point_id),
    basis_json JSON NOT NULL,
    created_at BIGINT NOT NULL
);
-- Keep the existing verification/counter state and its historical check constraint intact.
CREATE TABLE automation_dispose_case_state (
    risk_case_id VARCHAR(36) NOT NULL REFERENCES uav_risk_case(risk_case_id),
    category VARCHAR(16) NOT NULL CHECK(category='dispose'),
    event_id VARCHAR(36) NOT NULL REFERENCES uav_event(event_id),
    group_version BIGINT NOT NULL,
    run_id VARCHAR(36) REFERENCES automation_runtime_run(run_id),
    status VARCHAR(32) NOT NULL,
    unknown_since BIGINT,
    holds_json TEXT NOT NULL DEFAULT '{}',
    signature TEXT NOT NULL DEFAULT '',
    PRIMARY KEY(category,risk_case_id)
);
