-- 设备异常处置：把重启指令挂到异常单，并留下恢复校验快照。
-- 关闭仍走 stage=RECOVERED + closed_at；校验失败不得标已恢复。

ALTER TABLE device_incident ADD COLUMN reboot_command_id VARCHAR(36);
ALTER TABLE device_incident ADD CONSTRAINT fk_device_incident_reboot_command
    FOREIGN KEY (reboot_command_id) REFERENCES device_command (command_id);

CREATE TABLE device_recovery_check (
    check_id        VARCHAR(36) PRIMARY KEY,
    incident_id     VARCHAR(36) NOT NULL REFERENCES device_incident (incident_id),
    checked_by      VARCHAR(36) REFERENCES app_user (user_id),
    checked_at      BIGINT NOT NULL,
    result          VARCHAR(16) NOT NULL,
    rule_version    VARCHAR(64) NOT NULL,
    state_snapshot  TEXT NOT NULL,
    reason          TEXT,
    CONSTRAINT ck_device_recovery_result CHECK (result IN ('PASS', 'FAIL', 'UNKNOWN'))
);

CREATE INDEX idx_device_recovery_check_incident ON device_recovery_check (incident_id, checked_at DESC, check_id DESC);
