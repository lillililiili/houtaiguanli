-- A P5: protocol B control commands. Receipts use a non-fusion inbox prefix (control-resp:).
ALTER TABLE device_command ADD COLUMN authorization_id VARCHAR(64);

CREATE TABLE lingyun_control_command (
    command_id        VARCHAR(36) PRIMARY KEY REFERENCES device_command (command_id),
    msg_no            VARCHAR(64) NOT NULL UNIQUE,
    operation_type    INTEGER NOT NULL,
    operation_cmd     INTEGER NOT NULL,
    params_json       TEXT,
    authorization_id  VARCHAR(64) NOT NULL,
    CONSTRAINT ck_lingyun_control_type CHECK (operation_type IN (0, 1, 2))
);

CREATE INDEX idx_lingyun_control_msg ON lingyun_control_command (msg_no);
