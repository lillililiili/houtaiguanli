CREATE TABLE device_report_cursor (
    device_id VARCHAR(36) NOT NULL REFERENCES ops_device(device_id),
    source_mode VARCHAR(12) NOT NULL,
    started_at BIGINT NOT NULL,
    PRIMARY KEY (device_id, source_mode),
    CONSTRAINT ck_report_cursor_source CHECK (source_mode IN ('mock','replay','live'))
);

CREATE TABLE device_report_window (
    device_id VARCHAR(36) NOT NULL REFERENCES ops_device(device_id),
    source_mode VARCHAR(12) NOT NULL,
    window_start BIGINT NOT NULL,
    heartbeat_count BIGINT NOT NULL DEFAULT 0,
    parameters_count BIGINT NOT NULL DEFAULT 0,
    sensing_count BIGINT NOT NULL DEFAULT 0,
    status_count BIGINT NOT NULL DEFAULT 0,
    first_received_at BIGINT NOT NULL,
    last_received_at BIGINT NOT NULL,
    simulated BOOLEAN NOT NULL,
    PRIMARY KEY (device_id, source_mode, window_start),
    CONSTRAINT ck_report_window_source CHECK (source_mode IN ('mock','replay','live')),
    CONSTRAINT ck_report_window_counts CHECK (heartbeat_count >= 0 AND parameters_count >= 0 AND sensing_count >= 0 AND status_count >= 0)
);
CREATE INDEX idx_report_window_due ON device_report_window(window_start,device_id);
