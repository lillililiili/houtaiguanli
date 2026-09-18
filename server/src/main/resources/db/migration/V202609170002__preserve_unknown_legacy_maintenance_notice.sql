-- 001 曾把没有任何通知资料的旧待办也转成首次通知；此类记录不代表发送事实。
-- 仅纠正 001 生成的确定性历史占位，不修改原待办或真实/模拟发送记录。
DELETE FROM ops_device_maintenance_notice_attempt
WHERE historical=TRUE AND attempt_no=1 AND attempt_id=task_id
  AND reason='历史首次通知记录' AND request_key IS NULL
  AND recipient_snapshot IS NULL AND notification_setting_id IS NULL
  AND delivery_status IS NULL AND receipt_status IS NULL AND blocked_reason IS NULL
  AND EXISTS (
    SELECT 1 FROM ops_device_maintenance_task t
    WHERE t.task_id=ops_device_maintenance_notice_attempt.task_id
      AND t.recipient_snapshot IS NULL AND t.notification_setting_id IS NULL
      AND t.notification_delivery_status IS NULL AND t.notification_receipt_status IS NULL
      AND t.notification_blocked_reason IS NULL
  );
