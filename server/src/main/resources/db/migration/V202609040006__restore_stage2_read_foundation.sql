-- The device-management delivery and the T02 read contract use different models.
-- Preserve the former under explicit ops_* names before restoring the Stage 2 names.
ALTER TABLE integration_source RENAME TO ops_integration_source;
ALTER TABLE device RENAME TO ops_device;
ALTER TABLE device_state RENAME TO ops_device_state;
ALTER TABLE device_state_history RENAME TO ops_device_state_history;
ALTER TABLE target_source_link RENAME TO ops_target_source_link;
ALTER TABLE target_latest_state RENAME TO ops_target_latest_state;
ALTER TABLE track RENAME TO ops_track;
ALTER TABLE track_point RENAME TO ops_track_point;

ALTER TABLE inbox_message RENAME COLUMN source_id TO ops_source_id;
ALTER TABLE inbox_message RENAME COLUMN processed_at TO ops_processed_at;
ALTER TABLE inbox_message RENAME COLUMN lease_token TO ops_lease_token;
ALTER TABLE inbox_message RENAME COLUMN lease_until TO ops_lease_until;

-- Extend the integrated identity catalog with the Stage 2 action-permission shape.
ALTER TABLE app_district ADD COLUMN parent_id VARCHAR(36) REFERENCES app_district (district_id);
ALTER TABLE app_district ADD CONSTRAINT ck_stage2_district_not_self
    CHECK (parent_id IS NULL OR parent_id <> district_id);

ALTER TABLE app_role ADD COLUMN system_role BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE app_role SET system_role = builtin;

ALTER TABLE app_permission ADD COLUMN module_code VARCHAR(48);
ALTER TABLE app_permission ADD COLUMN permission_kind VARCHAR(16);
ALTER TABLE app_permission ADD COLUMN action_code VARCHAR(32);
ALTER TABLE app_permission ADD COLUMN name VARCHAR(128);
ALTER TABLE app_permission ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE app_role_permission ALTER COLUMN permission_code SET DATA TYPE VARCHAR(96);
ALTER TABLE app_permission ALTER COLUMN permission_code SET DATA TYPE VARCHAR(96);
UPDATE app_permission
SET module_code = permission_code,
    permission_kind = 'MODULE',
    action_code = 'access',
    name = module_name;
ALTER TABLE app_permission ALTER COLUMN module_code SET NOT NULL;
ALTER TABLE app_permission ALTER COLUMN permission_kind SET NOT NULL;
ALTER TABLE app_permission ALTER COLUMN action_code SET NOT NULL;
ALTER TABLE app_permission ALTER COLUMN name SET NOT NULL;
ALTER TABLE app_permission ADD CONSTRAINT uk_stage2_permission_tuple
    UNIQUE (module_code, permission_kind, action_code);

ALTER TABLE app_role_permission ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE app_user_data_scope ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE app_user ALTER COLUMN permission_version SET DATA TYPE BIGINT;
ALTER TABLE app_user ADD CONSTRAINT ck_stage2_permission_version CHECK (permission_version >= 0);
ALTER TABLE app_org ADD CONSTRAINT ck_stage2_org_not_self
    CHECK (parent_id IS NULL OR parent_id <> org_id);

INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('device:read', 'T02 device read', NULL, 901, 'device', 'ACTION', 'read', 'Read devices'),
    ('target:read', 'T02 target read', NULL, 902, 'target', 'ACTION', 'read', 'Read targets'),
    ('alarm:read', 'T02 alarm read', NULL, 903, 'alarm', 'ACTION', 'read', 'Read alarms');

CREATE INDEX idx_stage2_district_parent ON app_district (parent_id);
CREATE INDEX idx_stage2_user_role_scope ON app_user (role_code, scope_mode);
CREATE INDEX idx_stage2_role_permission_reverse
    ON app_role_permission (permission_code, role_code);
CREATE INDEX idx_stage2_user_scope_reverse
    ON app_user_data_scope (org_id, district_id, user_id);

CREATE TABLE integration_source (
    source_id           VARCHAR(36),
    source_code         VARCHAR(64) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    protocol_code       VARCHAR(64),
    protocol_version    VARCHAR(64),
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    credential_ref      VARCHAR(256),
    source_mode         VARCHAR(8) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_stage2_integration_source PRIMARY KEY (source_id),
    CONSTRAINT uk_stage2_integration_source_code UNIQUE (source_code),
    CONSTRAINT ck_stage2_source_code_nonblank CHECK (TRIM(source_code) <> ''),
    CONSTRAINT ck_stage2_source_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage2_source_protocol_code_nonblank
        CHECK (protocol_code IS NULL OR TRIM(protocol_code) <> ''),
    CONSTRAINT ck_stage2_source_protocol_version_nonblank
        CHECK (protocol_version IS NULL OR TRIM(protocol_version) <> ''),
    CONSTRAINT ck_stage2_source_credential_ref_nonblank
        CHECK (credential_ref IS NULL OR TRIM(credential_ref) <> ''),
    CONSTRAINT ck_stage2_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage2_source_version CHECK (version >= 0)
);

CREATE INDEX idx_stage2_source_mode_enabled_code
    ON integration_source (source_mode, enabled, source_code);

ALTER TABLE inbox_message ADD COLUMN source_id VARCHAR(36);
ALTER TABLE inbox_message ADD COLUMN payload_hash VARCHAR(64);
ALTER TABLE inbox_message ADD COLUMN payload JSONB;
ALTER TABLE inbox_message ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED';
ALTER TABLE inbox_message ADD COLUMN processed_at BIGINT;
ALTER TABLE inbox_message ADD COLUMN last_error TEXT;
ALTER TABLE inbox_message ADD COLUMN lease_token VARCHAR(36);
ALTER TABLE inbox_message ADD COLUMN lease_until BIGINT;

ALTER TABLE inbox_message ADD CONSTRAINT fk_stage2_inbox_source
    FOREIGN KEY (source_id) REFERENCES integration_source (source_id) ON DELETE RESTRICT;
ALTER TABLE inbox_message ADD CONSTRAINT ck_stage2_inbox_payload_hash
    CHECK (payload_hash IS NULL OR REGEXP_LIKE(payload_hash, '^[0-9a-f]{64}$'));
ALTER TABLE inbox_message ADD CONSTRAINT ck_stage2_inbox_status
    CHECK (status IN ('RECEIVED', 'PROCESSING', 'DONE', 'FAILED'));
ALTER TABLE inbox_message ADD CONSTRAINT ck_stage2_inbox_processed_at
    CHECK (
        (status IN ('RECEIVED', 'PROCESSING') AND processed_at IS NULL)
        OR (status IN ('DONE', 'FAILED') AND processed_at IS NOT NULL)
    );
ALTER TABLE inbox_message ADD CONSTRAINT ck_stage2_inbox_lease_pair
    CHECK (
        (lease_token IS NULL AND lease_until IS NULL)
        OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)
    );

CREATE INDEX idx_stage2_inbox_source_status_received
    ON inbox_message (source_id, status, received_at, inbox_id);
CREATE INDEX idx_stage2_inbox_status_lease
    ON inbox_message (status, lease_until);

CREATE TABLE device (
    device_id           VARCHAR(36),
    source_id           VARCHAR(36),
    external_device_id  VARCHAR(128),
    device_no           VARCHAR(64) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    device_type_code    VARCHAR(32),
    model               VARCHAR(128),
    vendor              VARCHAR(128),
    location            GEOMETRY(POINT, 4326),
    altitude_m          NUMERIC(10, 2),
    altitude_datum      VARCHAR(16),
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    source_mode         VARCHAR(8) NOT NULL,
    owner_org_id        VARCHAR(36),
    district_id         VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_stage2_device PRIMARY KEY (device_id),
    CONSTRAINT uk_stage2_device_number UNIQUE (device_no),
    CONSTRAINT fk_stage2_device_source FOREIGN KEY (source_id)
        REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_device_owner_org FOREIGN KEY (owner_org_id)
        REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_device_district FOREIGN KEY (district_id)
        REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage2_device_external_source_pair CHECK (
        (source_id IS NULL AND external_device_id IS NULL)
        OR (source_id IS NOT NULL AND external_device_id IS NOT NULL)
    ),
    CONSTRAINT ck_stage2_device_number_nonblank CHECK (TRIM(device_no) <> ''),
    CONSTRAINT ck_stage2_device_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage2_device_external_id_nonblank
        CHECK (external_device_id IS NULL OR TRIM(external_device_id) <> ''),
    CONSTRAINT ck_stage2_device_altitude_pair CHECK (
        (altitude_m IS NULL AND altitude_datum IS NULL)
        OR (altitude_m IS NOT NULL AND altitude_datum IS NOT NULL)
    ),
    CONSTRAINT ck_stage2_device_altitude_datum
        CHECK (altitude_datum IS NULL OR altitude_datum IN ('AGL', 'AMSL')),
    CONSTRAINT ck_stage2_device_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage2_device_version CHECK (version >= 0)
);

CREATE INDEX idx_stage2_device_scope_number
    ON device (owner_org_id, district_id, device_no, device_id);
CREATE INDEX idx_stage2_device_source_type_enabled
    ON device (source_id, device_type_code, enabled, device_id);

CREATE TABLE device_state (
    device_id           VARCHAR(36),
    connectivity        VARCHAR(16) NOT NULL,
    work_state_code     VARCHAR(32),
    has_alarm           BOOLEAN,
    health_code         VARCHAR(32),
    observed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    last_heartbeat_at   TIMESTAMP WITH TIME ZONE,
    source_seq          BIGINT,
    metrics             JSONB,
    unknown_reason      VARCHAR(64),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_stage2_device_state PRIMARY KEY (device_id),
    CONSTRAINT fk_stage2_device_state_device FOREIGN KEY (device_id)
        REFERENCES device (device_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage2_device_state_connectivity
        CHECK (connectivity IN ('ONLINE', 'OFFLINE', 'DEGRADED', 'UNKNOWN')),
    CONSTRAINT ck_stage2_device_state_unknown_reason CHECK (
        (connectivity = 'UNKNOWN' AND unknown_reason IS NOT NULL AND TRIM(unknown_reason) <> '')
        OR (connectivity <> 'UNKNOWN' AND unknown_reason IS NULL)
    ),
    CONSTRAINT ck_stage2_device_state_version CHECK (version >= 0)
);

CREATE INDEX idx_stage2_device_state_connectivity_received
    ON device_state (connectivity, received_at DESC, device_id);

CREATE TABLE device_state_history (
    state_id            VARCHAR(36),
    device_id           VARCHAR(36) NOT NULL,
    inbox_id            VARCHAR(36),
    connectivity        VARCHAR(16) NOT NULL,
    observed_at         TIMESTAMP WITH TIME ZONE,
    received_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    snapshot            JSONB NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stage2_device_state_history PRIMARY KEY (state_id),
    CONSTRAINT fk_stage2_device_state_history_device FOREIGN KEY (device_id)
        REFERENCES device (device_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_device_state_history_inbox FOREIGN KEY (inbox_id)
        REFERENCES inbox_message (inbox_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage2_device_state_history_connectivity
        CHECK (connectivity IN ('ONLINE', 'OFFLINE', 'DEGRADED', 'UNKNOWN'))
);

CREATE INDEX idx_stage2_device_state_history_inbox
    ON device_state_history (inbox_id);

CREATE TABLE target (
    target_id           VARCHAR(36),
    target_no           VARCHAR(64) NOT NULL,
    object_type_code    VARCHAR(32),
    subtype             VARCHAR(64),
    uav_sn              VARCHAR(128),
    first_seen_at       TIMESTAMP WITH TIME ZONE,
    last_seen_at        TIMESTAMP WITH TIME ZONE,
    source_mode         VARCHAR(8) NOT NULL,
    owner_org_id        VARCHAR(36),
    district_id         VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_stage2_target PRIMARY KEY (target_id),
    CONSTRAINT uk_stage2_target_number UNIQUE (target_no),
    CONSTRAINT fk_stage2_target_owner_org FOREIGN KEY (owner_org_id)
        REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_target_district FOREIGN KEY (district_id)
        REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage2_target_number_nonblank CHECK (TRIM(target_no) <> ''),
    CONSTRAINT ck_stage2_target_seen_range CHECK (
        (first_seen_at IS NULL AND last_seen_at IS NULL)
        OR (first_seen_at IS NOT NULL AND last_seen_at IS NOT NULL AND first_seen_at <= last_seen_at)
    ),
    CONSTRAINT ck_stage2_target_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage2_target_version CHECK (version >= 0)
);

CREATE INDEX idx_stage2_target_scope_last_seen
    ON target (owner_org_id, district_id, last_seen_at DESC NULLS LAST, target_id ASC);
CREATE INDEX idx_stage2_target_type_last_seen
    ON target (object_type_code, last_seen_at DESC NULLS LAST, target_id ASC);

CREATE TABLE target_source_link (
    link_id                 VARCHAR(36),
    target_id               VARCHAR(36) NOT NULL,
    source_id               VARCHAR(36) NOT NULL,
    device_id               VARCHAR(36),
    source_session_key      VARCHAR(128) NOT NULL,
    external_target_id      VARCHAR(128) NOT NULL,
    protocol_version        VARCHAR(64),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stage2_target_source_link PRIMARY KEY (link_id),
    CONSTRAINT fk_stage2_target_source_link_target FOREIGN KEY (target_id)
        REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_target_source_link_source FOREIGN KEY (source_id)
        REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_target_source_link_device FOREIGN KEY (device_id)
        REFERENCES device (device_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage2_target_source_identity
        UNIQUE (source_id, source_session_key, external_target_id),
    CONSTRAINT ck_stage2_target_link_session_nonblank CHECK (TRIM(source_session_key) <> ''),
    CONSTRAINT ck_stage2_target_link_external_nonblank CHECK (TRIM(external_target_id) <> '')
);

CREATE INDEX idx_stage2_target_source_link_target
    ON target_source_link (target_id, link_id);

CREATE TABLE target_latest_state (
    target_id                   VARCHAR(36),
    location                    GEOMETRY(POINT, 4326),
    altitude_amsl_m             NUMERIC(10, 2),
    height_agl_m                NUMERIC(10, 2),
    speed_mps                   NUMERIC(10, 3),
    heading_deg                 NUMERIC(6, 2),
    classification_confidence   NUMERIC(6, 5),
    fusion_confidence           NUMERIC(6, 5),
    observed_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    unknown_fields              JSONB NOT NULL DEFAULT '[]',
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    version                     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_stage2_target_latest_state PRIMARY KEY (target_id),
    CONSTRAINT fk_stage2_target_latest_state_target FOREIGN KEY (target_id)
        REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage2_target_state_speed CHECK (speed_mps IS NULL OR speed_mps >= 0),
    CONSTRAINT ck_stage2_target_state_heading
        CHECK (heading_deg IS NULL OR (heading_deg >= 0 AND heading_deg < 360)),
    CONSTRAINT ck_stage2_target_classification_confidence
        CHECK (classification_confidence IS NULL
            OR (classification_confidence >= 0 AND classification_confidence <= 1)),
    CONSTRAINT ck_stage2_target_fusion_confidence
        CHECK (fusion_confidence IS NULL OR (fusion_confidence >= 0 AND fusion_confidence <= 1)),
    CONSTRAINT ck_stage2_target_state_version CHECK (version >= 0)
);

CREATE TABLE track (
    track_id            VARCHAR(36),
    target_id           VARCHAR(36) NOT NULL,
    link_id             VARCHAR(36) NOT NULL,
    external_track_id   VARCHAR(128) NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stage2_track PRIMARY KEY (track_id),
    CONSTRAINT fk_stage2_track_target FOREIGN KEY (target_id)
        REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_track_link FOREIGN KEY (link_id)
        REFERENCES target_source_link (link_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage2_track_link_external UNIQUE (link_id, external_track_id),
    CONSTRAINT ck_stage2_track_external_nonblank CHECK (TRIM(external_track_id) <> '')
);

CREATE INDEX idx_stage2_track_target_started
    ON track (target_id, started_at DESC NULLS LAST, track_id ASC);

CREATE TABLE track_point (
    point_id            VARCHAR(36),
    track_id            VARCHAR(36) NOT NULL,
    inbox_id            VARCHAR(36),
    point_seq           BIGINT NOT NULL,
    observed_at         TIMESTAMP WITH TIME ZONE,
    received_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    location            GEOMETRY(POINT, 4326) NOT NULL,
    altitude_amsl_m     NUMERIC(10, 2),
    height_agl_m        NUMERIC(10, 2),
    raw_position        JSONB,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stage2_track_point PRIMARY KEY (point_id),
    CONSTRAINT fk_stage2_track_point_track FOREIGN KEY (track_id)
        REFERENCES track (track_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_track_point_inbox FOREIGN KEY (inbox_id)
        REFERENCES inbox_message (inbox_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage2_track_point_sequence UNIQUE (track_id, point_seq),
    CONSTRAINT ck_stage2_track_point_sequence CHECK (point_seq >= 0)
);

CREATE INDEX idx_stage2_track_point_inbox ON track_point (inbox_id);

CREATE TABLE alarm (
    alarm_id            VARCHAR(36),
    target_id           VARCHAR(36),
    source_id           VARCHAR(36) NOT NULL,
    source_alarm_id     VARCHAR(128) NOT NULL,
    alarm_type          VARCHAR(64) NOT NULL,
    severity            VARCHAR(16) NOT NULL,
    occurred_at         TIMESTAMP WITH TIME ZONE,
    received_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    inbox_id            VARCHAR(36),
    detail              JSONB,
    source_mode         VARCHAR(8) NOT NULL,
    owner_org_id        VARCHAR(36),
    district_id         VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_stage2_alarm PRIMARY KEY (alarm_id),
    CONSTRAINT fk_stage2_alarm_target FOREIGN KEY (target_id)
        REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_alarm_source FOREIGN KEY (source_id)
        REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_alarm_inbox FOREIGN KEY (inbox_id)
        REFERENCES inbox_message (inbox_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_alarm_owner_org FOREIGN KEY (owner_org_id)
        REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage2_alarm_district FOREIGN KEY (district_id)
        REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage2_alarm_source_identity UNIQUE (source_id, source_alarm_id),
    CONSTRAINT ck_stage2_alarm_source_id_nonblank CHECK (TRIM(source_alarm_id) <> ''),
    CONSTRAINT ck_stage2_alarm_type_nonblank CHECK (TRIM(alarm_type) <> ''),
    CONSTRAINT ck_stage2_alarm_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN')),
    CONSTRAINT ck_stage2_alarm_source_mode CHECK (source_mode IN ('mock', 'replay', 'live'))
);

CREATE INDEX idx_stage2_alarm_scope_received
    ON alarm (owner_org_id, district_id, received_at DESC, alarm_id ASC);
CREATE INDEX idx_stage2_alarm_target_received
    ON alarm (target_id, received_at DESC, alarm_id ASC);
CREATE INDEX idx_stage2_alarm_inbox ON alarm (inbox_id);
