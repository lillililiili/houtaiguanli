-- 消费方新增记录单独冻结对象；不为旧短信、电话或运维任务补造接收历史。
ALTER TABLE organization_profile ADD COLUMN responsibilities VARCHAR(1000);
ALTER TABLE ops_device_maintenance_task ADD COLUMN recipient_snapshot TEXT;
ALTER TABLE ops_device_maintenance_task ADD COLUMN notification_setting_id VARCHAR(36) REFERENCES notification_setting(setting_id);
ALTER TABLE ops_device_maintenance_task ADD COLUMN notification_delivery_status VARCHAR(32);
ALTER TABLE ops_device_maintenance_task ADD COLUMN notification_receipt_status VARCHAR(32);
ALTER TABLE ops_device_maintenance_task ADD COLUMN notification_blocked_reason VARCHAR(256);
ALTER TABLE ops_device_maintenance_task ADD CONSTRAINT ck_maintenance_notice_delivery CHECK(notification_delivery_status IS NULL OR notification_delivery_status IN('PENDING_DELIVERY','SUBMITTED','DELIVERED','FAILED'));
ALTER TABLE ops_device_maintenance_task ADD CONSTRAINT ck_maintenance_notice_receipt CHECK(notification_receipt_status IS NULL OR notification_receipt_status IN('NOT_EXPECTED','PENDING','ACKNOWLEDGED','TIMEOUT'));
ALTER TABLE uav_auto_sms_task ADD COLUMN recipient_snapshot TEXT;
ALTER TABLE uav_auto_voice_task ADD COLUMN recipient_snapshot TEXT;
ALTER TABLE uav_event_advisory ADD COLUMN recipient_snapshot TEXT;
ALTER TABLE uav_event_advisory ADD COLUMN notification_setting_id VARCHAR(36) REFERENCES notification_setting(setting_id);
ALTER TABLE uav_event_voice_advisory ADD COLUMN recipient_snapshot TEXT;
ALTER TABLE uav_event_voice_advisory ADD COLUMN recipient_name VARCHAR(128);
ALTER TABLE uav_event_voice_advisory ADD COLUMN notification_setting_id VARCHAR(36) REFERENCES notification_setting(setting_id);
