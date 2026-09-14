ALTER TABLE integration_source ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE integration_source ADD COLUMN allowed_cidrs VARCHAR(1000);

ALTER TABLE inbox_message ADD COLUMN source_id VARCHAR(36) REFERENCES integration_source (source_id);
ALTER TABLE inbox_message ADD COLUMN protocol_message_key VARCHAR(256);
ALTER TABLE inbox_message ADD COLUMN payload_sha256 VARCHAR(64);
ALTER TABLE inbox_message ADD COLUMN payload_bytes BYTEA;
ALTER TABLE inbox_message ADD COLUMN processing_status VARCHAR(24) NOT NULL DEFAULT 'PROCESSED';
ALTER TABLE inbox_message ADD COLUMN processed_at BIGINT;
ALTER TABLE inbox_message ADD COLUMN lease_token VARCHAR(64);
ALTER TABLE inbox_message ADD COLUMN lease_until BIGINT;
ALTER TABLE inbox_message ADD COLUMN failure_reason VARCHAR(1000);

-- 调测任务必须冻结协议路由与安全边界；后续修改来源或设备配置不能改写历史任务语义。
ALTER TABLE commission_task ADD COLUMN protocol_code VARCHAR(64);
ALTER TABLE commission_task ADD COLUMN protocol_configuration_json TEXT;
ALTER TABLE commission_task ADD COLUMN allowed_cidrs_snapshot VARCHAR(1000);
ALTER TABLE commission_task ADD COLUMN source_credential_ref_snapshot VARCHAR(256);

CREATE INDEX idx_inbox_protocol_message ON inbox_message (source_id, protocol_message_key);
CREATE INDEX idx_inbox_processing ON inbox_message (processing_status, lease_until, received_at);

CREATE TABLE radar_v3_profile (
    device_id                       VARCHAR(36) PRIMARY KEY REFERENCES device (device_id) ON DELETE CASCADE,
    login_role                      VARCHAR(16) NOT NULL DEFAULT 'DATA',
    recognition_code_ref            VARCHAR(256),
    rtk_enabled                     BOOLEAN NOT NULL DEFAULT FALSE,
    coordinate_transform_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    version                         BIGINT NOT NULL DEFAULT 0,
    updated_at                      BIGINT NOT NULL,
    CONSTRAINT ck_radar_login_role CHECK (login_role = 'DATA')
);

CREATE TABLE countermeasure_4ch_profile (
    device_id               VARCHAR(36) PRIMARY KEY REFERENCES device (device_id) ON DELETE CASCADE,
    device_address          INTEGER NOT NULL DEFAULT 1,
    wire_encoding           VARCHAR(32) NOT NULL DEFAULT 'AUTO',
    poll_interval_millis    INTEGER NOT NULL DEFAULT 5000,
    version                 BIGINT NOT NULL DEFAULT 0,
    updated_at              BIGINT NOT NULL,
    CONSTRAINT ck_countermeasure_address CHECK (device_address >= 1 AND device_address <= 244),
    CONSTRAINT ck_countermeasure_encoding CHECK (wire_encoding IN ('AUTO','RAW_BYTES','ASCII_HEX_SPACED','ASCII_HEX_COMPACT')),
    CONSTRAINT ck_countermeasure_poll CHECK (poll_interval_millis >= 1000 AND poll_interval_millis <= 60000)
);

CREATE TABLE device_connection_lease (
    device_id       VARCHAR(36) PRIMARY KEY REFERENCES device (device_id) ON DELETE CASCADE,
    owner_id        VARCHAR(128) NOT NULL,
    lease_token     VARCHAR(64) NOT NULL,
    lease_until     BIGINT NOT NULL,
    reconnect_at    BIGINT,
    failure_count   INTEGER NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000),
    updated_at      BIGINT NOT NULL
);

CREATE INDEX idx_device_connection_lease_due ON device_connection_lease (lease_until, reconnect_at);

CREATE TABLE protocol_runtime_state (
    device_id               VARCHAR(36) PRIMARY KEY REFERENCES device (device_id) ON DELETE CASCADE,
    protocol_code           VARCHAR(64) NOT NULL,
    connection_state        VARCHAR(24) NOT NULL DEFAULT 'DISCONNECTED',
    login_state             VARCHAR(24),
    session_key             VARCHAR(160),
    detected_wire_encoding  VARCHAR(32),
    last_valid_frame_at     BIGINT,
    last_frame_id           VARCHAR(32),
    last_query_at           BIGINT,
    crc_error_count         BIGINT NOT NULL DEFAULT 0,
    reconnect_count         BIGINT NOT NULL DEFAULT 0,
    active_track_count      INTEGER NOT NULL DEFAULT 0,
    raw_status_word         BIGINT,
    channel_state_json      TEXT,
    coordinate_reference_state VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    blocking_reason         VARCHAR(1000),
    updated_at              BIGINT NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_protocol_connection_state CHECK (connection_state IN ('DISCONNECTED','CONNECTING','CONNECTED','ONLINE','OFFLINE','ERROR'))
);

CREATE TABLE radar_rtk_sample (
    sample_id           VARCHAR(36) PRIMARY KEY,
    device_id           VARCHAR(36) NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    frame_id            VARCHAR(32) NOT NULL,
    latitude_deg        DECIMAL(13, 10) NOT NULL,
    longitude_deg       DECIMAL(13, 10) NOT NULL,
    heading_deg         DECIMAL(13, 10) NOT NULL,
    satellite_count     INTEGER,
    received_at         BIGINT NOT NULL,
    UNIQUE (device_id, frame_id)
);

CREATE INDEX idx_radar_rtk_recent ON radar_rtk_sample (device_id, received_at DESC);

CREATE TABLE radar_site_reference (
    device_id           VARCHAR(36) PRIMARY KEY REFERENCES device (device_id) ON DELETE CASCADE,
    latitude_deg        DECIMAL(13, 10) NOT NULL,
    longitude_deg       DECIMAL(13, 10) NOT NULL,
    heading_deg         DECIMAL(13, 10) NOT NULL,
    sample_count        INTEGER NOT NULL,
    verified            BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at          BIGINT NOT NULL
);

CREATE TABLE sensing_target (
    target_id               VARCHAR(36) PRIMARY KEY,
    target_no               VARCHAR(80) NOT NULL UNIQUE,
    primary_device_id       VARCHAR(36) NOT NULL REFERENCES device (device_id),
    radar_classification    INTEGER NOT NULL,
    category_code           VARCHAR(24) NOT NULL,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_at           BIGINT NOT NULL,
    last_seen_at            BIGINT NOT NULL,
    created_at              BIGINT NOT NULL,
    updated_at              BIGINT NOT NULL,
    CONSTRAINT ck_radar_classification CHECK (radar_classification >= 0 AND radar_classification <= 5)
);

CREATE INDEX idx_sensing_target_query ON sensing_target (primary_device_id, active, radar_classification, last_seen_at DESC);

CREATE TABLE target_source_link (
    link_id                 VARCHAR(36) PRIMARY KEY,
    target_id               VARCHAR(36) NOT NULL REFERENCES sensing_target (target_id) ON DELETE CASCADE,
    device_id               VARCHAR(36) NOT NULL REFERENCES device (device_id),
    radar_boot_micros       BIGINT NOT NULL,
    external_track_id       VARCHAR(32) NOT NULL,
    created_at              BIGINT NOT NULL,
    UNIQUE (device_id, radar_boot_micros, external_track_id)
);

CREATE TABLE target_latest_state (
    target_id               VARCHAR(36) PRIMARY KEY REFERENCES sensing_target (target_id) ON DELETE CASCADE,
    raw_x_m                 DECIMAL(18, 3) NOT NULL,
    raw_y_m                 DECIMAL(18, 3) NOT NULL,
    raw_z_m                 DECIMAL(18, 3) NOT NULL,
    velocity_x_mps          DECIMAL(18, 3),
    velocity_y_mps          DECIMAL(18, 3),
    velocity_z_mps          DECIMAL(18, 3),
    snr_db                  DECIMAL(12, 3),
    rcs_legacy_m2           DECIMAL(18, 6),
    rcs_high_resolution_m2  DECIMAL(18, 6),
    selected                BOOLEAN NOT NULL DEFAULT FALSE,
    longitude_deg           DECIMAL(13, 10),
    latitude_deg            DECIMAL(13, 10),
    derived                 BOOLEAN NOT NULL DEFAULT FALSE,
    observed_at             BIGINT NOT NULL,
    received_at             BIGINT NOT NULL,
    frame_id                VARCHAR(32) NOT NULL
);

CREATE TABLE track (
    track_id                VARCHAR(36) PRIMARY KEY,
    target_id               VARCHAR(36) NOT NULL REFERENCES sensing_target (target_id) ON DELETE CASCADE,
    device_id               VARCHAR(36) NOT NULL REFERENCES device (device_id),
    radar_boot_micros       BIGINT NOT NULL,
    external_track_id       VARCHAR(32) NOT NULL,
    started_at              BIGINT NOT NULL,
    last_point_at           BIGINT NOT NULL,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (device_id, radar_boot_micros, external_track_id)
);

CREATE TABLE track_point (
    track_point_id          VARCHAR(36) PRIMARY KEY,
    track_id                VARCHAR(36) NOT NULL REFERENCES track (track_id) ON DELETE CASCADE,
    frame_id                VARCHAR(32) NOT NULL,
    observed_at             BIGINT NOT NULL,
    received_at             BIGINT NOT NULL,
    raw_x_m                 DECIMAL(18, 3) NOT NULL,
    raw_y_m                 DECIMAL(18, 3) NOT NULL,
    raw_z_m                 DECIMAL(18, 3) NOT NULL,
    velocity_x_mps          DECIMAL(18, 3),
    velocity_y_mps          DECIMAL(18, 3),
    velocity_z_mps          DECIMAL(18, 3),
    snr_db                  DECIMAL(12, 3),
    rcs_legacy_m2           DECIMAL(18, 6),
    rcs_high_resolution_m2  DECIMAL(18, 6),
    longitude_deg           DECIMAL(13, 10),
    latitude_deg            DECIMAL(13, 10),
    derived                 BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (track_id, frame_id)
);

CREATE INDEX idx_track_point_history ON track_point (track_id, received_at DESC);

CREATE TABLE radar_point_summary (
    device_id           VARCHAR(36) PRIMARY KEY REFERENCES device (device_id) ON DELETE CASCADE,
    radar_boot_micros   BIGINT NOT NULL,
    frame_id            VARCHAR(32) NOT NULL,
    point_count         INTEGER NOT NULL,
    observed_at         BIGINT,
    received_at         BIGINT NOT NULL,
    summary_json        TEXT NOT NULL
);
