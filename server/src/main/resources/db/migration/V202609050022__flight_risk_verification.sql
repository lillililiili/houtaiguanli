CREATE TABLE flight_risk (
    risk_id                     VARCHAR(36) PRIMARY KEY,
    source_id                   VARCHAR(36) NOT NULL REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    source_risk_id              VARCHAR(128) NOT NULL,
    plan_id                     VARCHAR(36) NOT NULL,
    route_version_id            VARCHAR(36) NOT NULL,
    assessment_id               VARCHAR(36) REFERENCES assessment_result (assessment_id) ON DELETE RESTRICT,
    target_id                   VARCHAR(36) REFERENCES target (target_id) ON DELETE RESTRICT,
    track_id                    VARCHAR(36) REFERENCES track (track_id) ON DELETE RESTRICT,
    risk_type                   VARCHAR(64) NOT NULL,
    severity                    VARCHAR(16) NOT NULL,
    state_code                  VARCHAR(32) NOT NULL,
    reason_code                 VARCHAR(64) NOT NULL,
    reason_text                 VARCHAR(1000) NOT NULL,
    occurred_at                 TIMESTAMP WITH TIME ZONE,
    received_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_altitude_m         NUMERIC(10,2),
    observed_altitude_datum     VARCHAR(16),
    height_relation             VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    source_mode                 VARCHAR(8) NOT NULL,
    owner_org_id                VARCHAR(36) NOT NULL REFERENCES app_org (org_id) ON DELETE RESTRICT,
    district_id                 VARCHAR(36) NOT NULL REFERENCES app_district (district_id) ON DELETE RESTRICT,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    version                     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_stage4_risk_source_identity UNIQUE (source_id, source_risk_id),
    CONSTRAINT fk_stage4_risk_plan_route FOREIGN KEY (plan_id, route_version_id)
        REFERENCES flight_plan (plan_id, route_version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage4_risk_type CHECK (TRIM(risk_type) <> ''),
    CONSTRAINT ck_stage4_risk_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT ck_stage4_risk_state CHECK (state_code IN ('PENDING_VERIFICATION','PENDING_NOTIFICATION','NOTIFIED','EXCLUDED')),
    CONSTRAINT ck_stage4_risk_reason CHECK (TRIM(reason_code) <> '' AND TRIM(reason_text) <> ''),
    -- 必须显式写 IS NOT NULL：NULL IN (...) 结果为 UNKNOWN，而 CHECK 对 UNKNOWN 放行，会让“只有高度没有基准”的半个事实入库。
    CONSTRAINT ck_stage4_risk_altitude_pair CHECK (
        (observed_altitude_m IS NULL AND observed_altitude_datum IS NULL)
        OR (observed_altitude_m IS NOT NULL AND observed_altitude_datum IS NOT NULL AND observed_altitude_datum IN ('AGL','AMSL'))
    ),
    CONSTRAINT ck_stage4_risk_height_relation CHECK (height_relation IN ('UNKNOWN','WITHIN','OUTSIDE')),
    CONSTRAINT ck_stage4_risk_source_mode CHECK (source_mode IN ('mock','replay','live')),
    CONSTRAINT ck_stage4_risk_version CHECK (version >= 0)
);

CREATE INDEX idx_stage4_risk_scope_received ON flight_risk
    (owner_org_id, district_id, received_at DESC, risk_id DESC);
CREATE INDEX idx_stage4_risk_plan_received ON flight_risk
    (plan_id, received_at DESC, risk_id DESC);

CREATE TABLE flight_risk_verification (
    history_id                  VARCHAR(36) PRIMARY KEY,
    risk_id                     VARCHAR(36) NOT NULL REFERENCES flight_risk (risk_id) ON DELETE RESTRICT,
    version                     BIGINT NOT NULL,
    previous_state              VARCHAR(32) NOT NULL,
    resulting_state             VARCHAR(32) NOT NULL,
    conclusion                  VARCHAR(16) NOT NULL,
    note                        VARCHAR(1000) NOT NULL,
    actor_id                    VARCHAR(36) NOT NULL REFERENCES app_user (user_id) ON DELETE RESTRICT,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_stage4_risk_verification_version UNIQUE (risk_id, version),
    CONSTRAINT ck_stage4_risk_verification_conclusion CHECK (conclusion IN ('CONFIRMED','EXCLUDED')),
    CONSTRAINT ck_stage4_risk_verification_note CHECK (TRIM(note) <> ''),
    CONSTRAINT ck_stage4_risk_verification_version CHECK (version >= 1),
    CONSTRAINT ck_stage4_risk_verification_transition CHECK (
        previous_state = 'PENDING_VERIFICATION'
        AND ((conclusion = 'CONFIRMED' AND resulting_state = 'PENDING_NOTIFICATION')
          OR (conclusion = 'EXCLUDED' AND resulting_state = 'EXCLUDED'))
    )
);

CREATE INDEX idx_stage4_risk_verification_history ON flight_risk_verification
    (risk_id, version ASC, history_id ASC);
