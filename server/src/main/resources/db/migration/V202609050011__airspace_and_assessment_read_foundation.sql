CREATE TABLE airspace (
    airspace_id      VARCHAR(36) PRIMARY KEY,
    airspace_no      VARCHAR(64) NOT NULL UNIQUE,
    name             VARCHAR(128) NOT NULL,
    source_id        VARCHAR(36),
    source_mode      VARCHAR(8) NOT NULL,
    owner_org_id     VARCHAR(36),
    district_id      VARCHAR(36),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stage3_airspace_source FOREIGN KEY (source_id)
        REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_airspace_owner_org FOREIGN KEY (owner_org_id)
        REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_airspace_district FOREIGN KEY (district_id)
        REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage3_airspace_no_nonblank CHECK (TRIM(airspace_no) <> ''),
    CONSTRAINT ck_stage3_airspace_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage3_airspace_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage3_airspace_ownership_pair CHECK (
        (owner_org_id IS NULL AND district_id IS NULL)
        OR (owner_org_id IS NOT NULL AND district_id IS NOT NULL)
    ),
    CONSTRAINT ck_stage3_airspace_version CHECK (version >= 0)
);

CREATE TABLE airspace_version (
    airspace_version_id  VARCHAR(36) PRIMARY KEY,
    airspace_id          VARCHAR(36) NOT NULL,
    version_no           INTEGER NOT NULL,
    kind_code            VARCHAR(32) NOT NULL,
    boundary             GEOMETRY(MULTIPOLYGON, 4326),
    min_altitude_m       NUMERIC(10, 2),
    max_altitude_m       NUMERIC(10, 2),
    altitude_datum       VARCHAR(16),
    valid_from           TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to             TIMESTAMP WITH TIME ZONE,
    change_reason        VARCHAR(256),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage3_airspace_version_airspace FOREIGN KEY (airspace_id)
        REFERENCES airspace (airspace_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage3_airspace_version_number UNIQUE (airspace_id, version_no),
    CONSTRAINT ck_stage3_airspace_version_number CHECK (version_no > 0),
    CONSTRAINT ck_stage3_airspace_kind_nonblank CHECK (TRIM(kind_code) <> ''),
    -- 高程数字离开 AGL/AMSL 基准没有可复算含义，读取端会如实返回不可判定。
    CONSTRAINT ck_stage3_airspace_altitude_pair CHECK (
        (min_altitude_m IS NULL AND max_altitude_m IS NULL AND altitude_datum IS NULL)
        OR (min_altitude_m IS NOT NULL AND max_altitude_m IS NOT NULL
            AND altitude_datum IN ('AGL', 'AMSL') AND min_altitude_m <= max_altitude_m)
    ),
    CONSTRAINT ck_stage3_airspace_version_validity CHECK (valid_to IS NULL OR valid_from < valid_to),
    CONSTRAINT ck_stage3_airspace_change_reason_nonblank CHECK (
        change_reason IS NULL OR TRIM(change_reason) <> ''
    )
);

CREATE TABLE rule_version (
    rule_version_id   VARCHAR(36) PRIMARY KEY,
    rule_code         VARCHAR(64) NOT NULL,
    version_no        INTEGER NOT NULL,
    status_code       VARCHAR(32) NOT NULL,
    valid_from        TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to          TIMESTAMP WITH TIME ZONE,
    source_mode       VARCHAR(8) NOT NULL,
    source_snapshot   JSONB,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_stage3_rule_version_number UNIQUE (rule_code, version_no),
    CONSTRAINT ck_stage3_rule_code_nonblank CHECK (TRIM(rule_code) <> ''),
    CONSTRAINT ck_stage3_rule_version_number CHECK (version_no > 0),
    CONSTRAINT ck_stage3_rule_status_nonblank CHECK (TRIM(status_code) <> ''),
    CONSTRAINT ck_stage3_rule_validity CHECK (valid_to IS NULL OR valid_from < valid_to),
    CONSTRAINT ck_stage3_rule_source_mode CHECK (source_mode IN ('mock', 'replay', 'live'))
);

ALTER TABLE flight_plan ADD CONSTRAINT uk_stage3_flight_plan_route_version_pair
    UNIQUE (plan_id, route_version_id);

CREATE TABLE assessment_result (
    assessment_id       VARCHAR(36) PRIMARY KEY,
    plan_id             VARCHAR(36) NOT NULL,
    target_id           VARCHAR(36),
    track_id            VARCHAR(36),
    route_version_id    VARCHAR(36) NOT NULL,
    rule_version_id     VARCHAR(36) NOT NULL,
    assessed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    conclusion_code     VARCHAR(16) NOT NULL,
    checks              JSONB NOT NULL,
    unknown_reasons     JSONB NOT NULL,
    evidence_references JSONB NOT NULL,
    source_mode         VARCHAR(8) NOT NULL,
    source_snapshot     JSONB,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    -- 已保存研判必须钉住计划当时的精确航线版本，禁止仅引用可变的“当前航线”。
    CONSTRAINT fk_stage3_assessment_plan_route_version FOREIGN KEY (plan_id, route_version_id)
        REFERENCES flight_plan (plan_id, route_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_assessment_target FOREIGN KEY (target_id)
        REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_assessment_track FOREIGN KEY (track_id)
        REFERENCES track (track_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_assessment_rule_version FOREIGN KEY (rule_version_id)
        REFERENCES rule_version (rule_version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage3_assessment_conclusion CHECK (
        conclusion_code IN ('LEGAL', 'ILLEGAL', 'UNDETERMINED', 'NOT_APPLICABLE')
    ),
    CONSTRAINT ck_stage3_assessment_source_mode CHECK (source_mode IN ('mock', 'replay', 'live'))
);

CREATE INDEX idx_stage3_airspace_scope_updated
    ON airspace (owner_org_id, district_id, updated_at DESC, airspace_id ASC);
CREATE INDEX idx_stage3_airspace_source
    ON airspace (source_id, source_mode, airspace_id);
CREATE INDEX idx_stage3_airspace_version_validity
    ON airspace_version (airspace_id, valid_from DESC, airspace_version_id ASC);
CREATE INDEX idx_stage3_assessment_plan_assessed
    ON assessment_result (plan_id, assessed_at DESC, assessment_id ASC);
CREATE INDEX idx_stage3_assessment_target_assessed
    ON assessment_result (target_id, assessed_at DESC, assessment_id ASC);
