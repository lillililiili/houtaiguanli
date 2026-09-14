CREATE TABLE integration_source (
    source_id           VARCHAR(36) PRIMARY KEY,
    source_code         VARCHAR(64) NOT NULL UNIQUE,
    name                VARCHAR(128) NOT NULL,
    protocol_code       VARCHAR(64),
    protocol_version    VARCHAR(64),
    source_mode         VARCHAR(16) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    credential_ref      VARCHAR(256),
    simulated           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          BIGINT NOT NULL,
    updated_at          BIGINT NOT NULL,
    CONSTRAINT ck_integration_source_mode CHECK (source_mode IN ('mock', 'replay', 'live'))
);

CREATE TABLE device (
    device_id           VARCHAR(36) PRIMARY KEY,
    source_id           VARCHAR(36) REFERENCES integration_source (source_id),
    external_device_id  VARCHAR(128),
    device_no           VARCHAR(64) NOT NULL UNIQUE,
    name                VARCHAR(128) NOT NULL,
    device_type_code    VARCHAR(32),
    device_type_name    VARCHAR(64) NOT NULL,
    channel             VARCHAR(64) NOT NULL,
    model               VARCHAR(128),
    vendor              VARCHAR(128),
    owner_name          VARCHAR(128),
    region_name         VARCHAR(128),
    address             VARCHAR(256),
    longitude           DECIMAL(10, 7),
    latitude            DECIMAL(10, 7),
    coordinate_system   VARCHAR(16),
    altitude_m          DECIMAL(10, 2),
    altitude_datum      VARCHAR(16),
    firmware_version    VARCHAR(64),
    installed_at        BIGINT,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    source_mode         VARCHAR(16) NOT NULL,
    simulated           BOOLEAN NOT NULL DEFAULT FALSE,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          BIGINT NOT NULL,
    updated_at          BIGINT NOT NULL,
    CONSTRAINT uq_device_source_external UNIQUE (source_id, external_device_id),
    CONSTRAINT ck_device_source_pair CHECK (
        (source_id IS NULL AND external_device_id IS NULL)
        OR (source_id IS NOT NULL AND external_device_id IS NOT NULL)
    ),
    CONSTRAINT ck_device_longitude CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180)),
    CONSTRAINT ck_device_latitude CHECK (latitude IS NULL OR (latitude >= -90 AND latitude <= 90)),
    CONSTRAINT ck_device_position_pair CHECK (
        (longitude IS NULL AND latitude IS NULL)
        OR (longitude IS NOT NULL AND latitude IS NOT NULL)
    ),
    CONSTRAINT ck_device_source_mode CHECK (source_mode IN ('mock', 'replay', 'live'))
);

CREATE INDEX idx_device_filters ON device (enabled, region_name, channel, device_type_code);
CREATE INDEX idx_device_source ON device (source_id, external_device_id);

CREATE TABLE device_connection_profile (
    device_id                    VARCHAR(36) PRIMARY KEY REFERENCES device (device_id) ON DELETE CASCADE,
    transport                    VARCHAR(16),
    host                         VARCHAR(255),
    port                         INTEGER,
    path                         VARCHAR(512),
    data_format                  VARCHAR(32),
    charset_name                 VARCHAR(32),
    auth_mode                    VARCHAR(32),
    credential_ref               VARCHAR(256),
    heartbeat_interval_seconds   INTEGER,
    report_interval_millis       INTEGER,
    sampling_rate_hz             DECIMAL(10, 3),
    compression_enabled          BOOLEAN,
    retransmission_enabled       BOOLEAN,
    timeout_millis               INTEGER,
    retry_count                  INTEGER,
    longitude_offset_deg         DECIMAL(12, 9),
    latitude_offset_deg          DECIMAL(12, 9),
    altitude_offset_m            DECIMAL(10, 3),
    time_sync_mode               VARCHAR(32),
    time_server                  VARCHAR(255),
    timezone_name                VARCHAR(64),
    time_sync_interval_seconds   INTEGER,
    version                      BIGINT NOT NULL DEFAULT 0,
    updated_at                   BIGINT NOT NULL,
    CONSTRAINT ck_connection_port CHECK (port IS NULL OR (port >= 1 AND port <= 65535)),
    CONSTRAINT ck_connection_intervals CHECK (
        (heartbeat_interval_seconds IS NULL OR heartbeat_interval_seconds > 0)
        AND (report_interval_millis IS NULL OR report_interval_millis > 0)
        AND (timeout_millis IS NULL OR timeout_millis > 0)
        AND (retry_count IS NULL OR retry_count >= 0)
    )
);

CREATE TABLE device_state (
    device_id            VARCHAR(36) PRIMARY KEY REFERENCES device (device_id) ON DELETE CASCADE,
    connectivity         VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    work_state_code      VARCHAR(32),
    has_alarm            BOOLEAN,
    health_code          VARCHAR(16),
    observed_at          BIGINT,
    received_at          BIGINT NOT NULL,
    last_heartbeat_at    BIGINT,
    metrics_json         TEXT,
    unknown_reason       VARCHAR(128),
    simulated            BOOLEAN NOT NULL DEFAULT FALSE,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_device_connectivity CHECK (connectivity IN ('UNKNOWN', 'ONLINE', 'OFFLINE', 'ABNORMAL')),
    CONSTRAINT ck_device_health CHECK (health_code IS NULL OR health_code IN ('UNKNOWN', 'GOOD', 'DEGRADED', 'BAD'))
);

CREATE INDEX idx_device_state_connectivity ON device_state (connectivity, last_heartbeat_at);

CREATE TABLE device_state_history (
    state_id             VARCHAR(36) PRIMARY KEY,
    device_id            VARCHAR(36) NOT NULL REFERENCES device (device_id) ON DELETE CASCADE,
    connectivity         VARCHAR(16) NOT NULL,
    observed_at          BIGINT,
    received_at          BIGINT NOT NULL,
    metric_code          VARCHAR(64),
    metric_value         DECIMAL(18, 6),
    metric_unit          VARCHAR(32),
    simulated            BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_device_history_connectivity CHECK (connectivity IN ('UNKNOWN', 'ONLINE', 'OFFLINE', 'ABNORMAL'))
);

CREATE INDEX idx_device_history_query ON device_state_history (device_id, metric_code, received_at DESC, state_id);

CREATE TABLE device_incident (
    incident_id          VARCHAR(36) PRIMARY KEY,
    device_id            VARCHAR(36) NOT NULL REFERENCES device (device_id),
    incident_no          VARCHAR(64) NOT NULL UNIQUE,
    incident_type        VARCHAR(64) NOT NULL,
    severity             VARCHAR(16) NOT NULL,
    stage                VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    detected_at          BIGINT NOT NULL,
    reason               TEXT NOT NULL,
    closed_at            BIGINT,
    block_reason         TEXT,
    simulated            BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_device_incident_severity CHECK (severity IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_device_incident_stage CHECK (stage IN ('PENDING', 'PROCESSING', 'PENDING_VERIFICATION', 'RECOVERED'))
);

CREATE INDEX idx_device_incident_query ON device_incident (device_id, stage, detected_at DESC);

CREATE TABLE device_event_log (
    event_seq            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    event_id             VARCHAR(36) NOT NULL UNIQUE,
    device_id            VARCHAR(36) REFERENCES device (device_id),
    event_type           VARCHAR(48) NOT NULL,
    level_code           VARCHAR(16) NOT NULL,
    message              TEXT NOT NULL,
    occurred_at          BIGINT NOT NULL,
    simulated            BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_device_event_level CHECK (level_code IN ('INFO', 'WARN', 'ERROR'))
);

CREATE INDEX idx_device_event_query ON device_event_log (device_id, event_seq);

CREATE TABLE device_command (
    command_id           VARCHAR(36) PRIMARY KEY,
    command_no           VARCHAR(64) NOT NULL UNIQUE,
    device_id            VARCHAR(36) NOT NULL REFERENCES device (device_id),
    requested_by         VARCHAR(36) NOT NULL REFERENCES app_user (user_id),
    command_type         VARCHAR(48) NOT NULL,
    reason               TEXT NOT NULL,
    status               VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    source_mode          VARCHAR(16) NOT NULL,
    simulated            BOOLEAN NOT NULL DEFAULT FALSE,
    issued_at            BIGINT,
    deadline_at          BIGINT,
    completed_at         BIGINT,
    result_code          VARCHAR(64),
    result_detail        TEXT,
    created_at           BIGINT NOT NULL,
    updated_at           BIGINT NOT NULL,
    CONSTRAINT ck_device_command_status CHECK (status IN ('QUEUED', 'SENT', 'ACCEPTED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'))
);

CREATE INDEX idx_device_command_query ON device_command (device_id, created_at DESC);
CREATE INDEX idx_device_command_pending ON device_command (status, deadline_at);

CREATE TABLE command_receipt (
    receipt_id           VARCHAR(36) PRIMARY KEY,
    command_id           VARCHAR(36) NOT NULL REFERENCES device_command (command_id),
    inbox_id             VARCHAR(36) NOT NULL REFERENCES inbox_message (inbox_id),
    receipt_kind         VARCHAR(24) NOT NULL,
    device_result_code   VARCHAR(64),
    occurred_at          BIGINT,
    received_at          BIGINT NOT NULL,
    payload              TEXT NOT NULL,
    UNIQUE (command_id, inbox_id, receipt_kind)
);

CREATE TABLE commission_task (
    commission_id        VARCHAR(36) PRIMARY KEY,
    commission_no        VARCHAR(64) NOT NULL UNIQUE,
    previous_task_id     VARCHAR(36) REFERENCES commission_task (commission_id),
    device_id            VARCHAR(36) NOT NULL REFERENCES device (device_id),
    requested_by         VARCHAR(36) NOT NULL REFERENCES app_user (user_id),
    status               VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    protocol_version     VARCHAR(64),
    configuration_json   TEXT,
    criteria_snapshot    TEXT,
    results_json         TEXT,
    source_mode          VARCHAR(16) NOT NULL,
    simulated            BOOLEAN NOT NULL DEFAULT FALSE,
    version              BIGINT NOT NULL DEFAULT 0,
    started_at           BIGINT,
    finished_at          BIGINT,
    created_at           BIGINT NOT NULL,
    updated_at           BIGINT NOT NULL,
    CONSTRAINT ck_commission_status CHECK (status IN ('CREATED', 'CONNECTING', 'CONNECTED', 'READY', 'RUNNING', 'PASSED', 'FAILED', 'UNTESTABLE', 'CANCELLED'))
);

CREATE INDEX idx_commission_query ON commission_task (device_id, created_at DESC);

CREATE TABLE commission_task_event (
    event_seq            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    event_id             VARCHAR(36) NOT NULL UNIQUE,
    commission_id        VARCHAR(36) NOT NULL REFERENCES commission_task (commission_id) ON DELETE CASCADE,
    stage_code           VARCHAR(32) NOT NULL,
    level_code           VARCHAR(16) NOT NULL,
    message              TEXT NOT NULL,
    occurred_at          BIGINT NOT NULL,
    simulated            BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_commission_event_level CHECK (level_code IN ('INFO', 'WARN', 'ERROR'))
);

CREATE INDEX idx_commission_event_query ON commission_task_event (commission_id, event_seq);
