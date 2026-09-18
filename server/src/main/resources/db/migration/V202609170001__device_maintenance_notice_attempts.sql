-- 每次通知独立保存；历史首条通知按原事实迁入，不补造投递或回执时间。
CREATE TABLE ops_device_maintenance_notice_attempt (
    attempt_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
    attempt_no INTEGER NOT NULL CHECK(attempt_no > 0),
    requested_by VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    requested_by_name VARCHAR(128) NOT NULL,
    request_key VARCHAR(128),
    expected_attempt_no INTEGER,
    requested_at BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    recipient_snapshot TEXT,
    notification_setting_id VARCHAR(36) REFERENCES notification_setting(setting_id),
    delivery_status VARCHAR(32),
    receipt_status VARCHAR(32),
    receipt_result VARCHAR(128),
    blocked_reason VARCHAR(500),
    submitted_at BIGINT,
    delivered_at BIGINT,
    acknowledged_at BIGINT,
    outcome_state VARCHAR(16) NOT NULL CHECK(outcome_state IN('NOT_SENT','COMPLETED','SUBMITTED','UNKNOWN')),
    historical BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(task_id,attempt_no),
    UNIQUE(requested_by,request_key)
);
CREATE INDEX idx_maintenance_attempt_task ON ops_device_maintenance_notice_attempt(task_id,attempt_no);
INSERT INTO ops_device_maintenance_notice_attempt(attempt_id,task_id,attempt_no,requested_by,
 requested_by_name,requested_at,reason,recipient_snapshot,notification_setting_id,delivery_status,
 receipt_status,blocked_reason,outcome_state,historical)
SELECT task_id,task_id,1,reported_by,reported_by_name,reported_at,'历史首次通知记录',
 recipient_snapshot,notification_setting_id,notification_delivery_status,notification_receipt_status,
 notification_blocked_reason,CASE WHEN notification_delivery_status IN('DELIVERED','FAILED') THEN 'COMPLETED'
 WHEN notification_delivery_status='SUBMITTED' THEN 'SUBMITTED' ELSE 'UNKNOWN' END,TRUE
FROM ops_device_maintenance_task;
