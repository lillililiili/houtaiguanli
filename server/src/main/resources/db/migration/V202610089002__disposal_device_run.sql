-- 反制设备打开后一直算“反制中”，到时自动全部关闭，设备回“已关闭”才记完成（2026-10-08 验收预跑 3-9 / 3-6，新-20）。
-- 以前四通道设备一回“已打开”授权就记完成：设备其实一直开着，急停又只认进行中的授权，页面上没有办法把它关掉。
-- 设备回“已打开”时记一行，off_due_at 到了由系统下发全部关闭；转干扰的那条沿用来源反制的关闭时刻，两条一起关。
-- 关闭指令没成功时按 off_attempts 重试，试满仍不成功记 gave_up_at，授权保持“反制中”，等人按急停或到现场关。
CREATE TABLE disposal_device_run (
    authorization_id  VARCHAR(36) PRIMARY KEY,
    device_id         VARCHAR(36) NOT NULL,
    on_command_id     VARCHAR(36) NOT NULL,
    on_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    off_due_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    off_command_id    VARCHAR(36),
    off_attempts      INTEGER NOT NULL DEFAULT 0,
    last_attempt_at   TIMESTAMP WITH TIME ZONE,
    gave_up_at        TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_disposal_device_run_authorization FOREIGN KEY (authorization_id)
        REFERENCES disposal_authorization (authorization_id) ON DELETE CASCADE,
    CONSTRAINT ck_disposal_device_run_attempts CHECK (off_attempts >= 0),
    CONSTRAINT ck_disposal_device_run_window CHECK (off_due_at >= on_at)
);

CREATE INDEX idx_disposal_device_run_due ON disposal_device_run (off_due_at);
CREATE INDEX idx_disposal_device_run_device ON disposal_device_run (device_id);

-- 运行时长进策略（决策 13-2，代码里不写阈值）。60 秒是演示值，写进待确认事项等客户定。
UPDATE disposal_policy
SET params = CAST('{"approval_required":true,"two_person_rule":true,"max_active_per_subject":1,"time_limit_min":{"COUNTERMEASURE":10,"JAMMING":10,"DISPERSAL":15,"DECOY":30},"requires_confirmed_event":{"COUNTERMEASURE":true,"JAMMING":true,"DISPERSAL":false,"DECOY":true},"command_map":{"COUNTERMEASURE":{"operation_type":1,"operation_cmd":60003},"JAMMING":{"operation_type":1,"operation_cmd":60002},"DISPERSAL":{"operation_type":1,"operation_cmd":70001},"DECOY":{"operation_type":1,"operation_cmd":50002}},"device_run_seconds":60}' AS JSON),
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE policy_code = 'demo-v1';
