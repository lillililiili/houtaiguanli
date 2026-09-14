-- A P3: protocol C edge-center bindings. Inbox source is eo-edge:<edgeId>; commands use device_command.
ALTER TABLE protocol_runtime_state ADD COLUMN work_state INTEGER;
ALTER TABLE protocol_runtime_state ADD COLUMN camera_status_json TEXT;
ALTER TABLE protocol_runtime_state ADD COLUMN last_heartbeat_at BIGINT;
ALTER TABLE device_command ALTER COLUMN requested_by DROP NOT NULL;

CREATE TABLE eo_edge (
    edge_id VARCHAR(64) PRIMARY KEY,
    broker_id VARCHAR(36) NOT NULL,
    source_mode VARCHAR(8) NOT NULL CHECK (source_mode IN ('replay','live')),
    owner_org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
    district_id VARCHAR(36) NOT NULL REFERENCES app_district(district_id),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (broker_id, source_mode) REFERENCES mqtt_broker (broker_id, source_mode)
);

CREATE TABLE eo_device_binding (
    ops_device_id VARCHAR(36) PRIMARY KEY REFERENCES ops_device(device_id),
    device_id VARCHAR(36) NOT NULL UNIQUE REFERENCES device(device_id),
    ops_source_id VARCHAR(36) NOT NULL UNIQUE REFERENCES ops_integration_source(source_id),
    source_id VARCHAR(36) NOT NULL UNIQUE REFERENCES integration_source(source_id),
    edge_id VARCHAR(64) NOT NULL REFERENCES eo_edge(edge_id),
    broker_id VARCHAR(36) NOT NULL,
    external_device_id VARCHAR(128) NOT NULL,
    source_mode VARCHAR(8) NOT NULL CHECK (source_mode IN ('replay','live')),
    subscribed BOOLEAN NOT NULL DEFAULT FALSE,
    last_heartbeat_at BIGINT,
    last_report_at BIGINT,
    work_state INTEGER,
    camera_status_json TEXT,
    duplicate_count BIGINT NOT NULL DEFAULT 0,
    conflict_count BIGINT NOT NULL DEFAULT 0,
    UNIQUE (edge_id, external_device_id),
    FOREIGN KEY (broker_id, source_mode) REFERENCES mqtt_broker (broker_id, source_mode),
    FOREIGN KEY (ops_device_id, ops_source_id, external_device_id, source_mode)
        REFERENCES ops_device (device_id, source_id, external_device_id, source_mode),
    FOREIGN KEY (device_id, source_id, external_device_id, source_mode)
        REFERENCES device (device_id, source_id, external_device_id, source_mode)
);

CREATE TABLE eo_tracking_task (
    task_id VARCHAR(36) PRIMARY KEY,
    target_id VARCHAR(36),
    fusion_event_id VARCHAR(36),
    ops_device_id VARCHAR(36) NOT NULL REFERENCES ops_device(device_id),
    begin_command_id VARCHAR(36) REFERENCES device_command(command_id),
    end_command_id VARCHAR(36) REFERENCES device_command(command_id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN','ENDING','ENDED','FAILED')),
    notes VARCHAR(256),
    bootstrap_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    ended_at BIGINT
);
CREATE INDEX idx_eo_tracking_device ON eo_tracking_task (ops_device_id, status);

CREATE TABLE eo_fusion_cursor (
    cursor_name VARCHAR(32) PRIMARY KEY,
    last_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_event_id VARCHAR(36) NOT NULL
);
INSERT INTO eo_fusion_cursor (cursor_name, last_created_at, last_event_id)
VALUES ('default', TIMESTAMP '1970-01-01 00:00:00+00', '');

CREATE INDEX idx_eo_fusion_event_cursor ON fusion_event (event_type, created_at, event_id);
