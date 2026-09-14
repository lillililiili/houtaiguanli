-- 上游 V202609040006 在本仓库重编号为 V202609050012：本地 V202609040006 已把运维设备模型改名为 ops_*，
-- 此处按 ops_* 表名执行同样的清理。
-- 运维管理运行库不再保留开发模拟台账。测试 profile 仍可由 LocalDeviceSeeder 注入隔离夹具。

DELETE FROM command_receipt
 WHERE command_id IN (
        SELECT command_id FROM device_command
         WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE));

DELETE FROM outbox_event
 WHERE payload IN (
        SELECT command_id FROM device_command
         WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE))
    OR payload IN (
        SELECT commission_id FROM commission_task
         WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE));

DELETE FROM device_command
 WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE);

UPDATE commission_task SET previous_task_id = NULL
 WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE);

DELETE FROM commission_task_event
 WHERE commission_id IN (
        SELECT commission_id FROM commission_task
         WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE));

DELETE FROM commission_task
 WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE);

DELETE FROM ops_track_point
 WHERE track_id IN (
        SELECT track_id FROM ops_track
         WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE));

DELETE FROM ops_target_latest_state
 WHERE target_id IN (
        SELECT target_id FROM sensing_target
         WHERE primary_device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE));

DELETE FROM ops_target_source_link
 WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE)
    OR target_id IN (
        SELECT target_id FROM sensing_target
         WHERE primary_device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE));

DELETE FROM ops_track
 WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE);

DELETE FROM sensing_target
 WHERE primary_device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE);

DELETE FROM device_incident
 WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE);

DELETE FROM device_event_log
 WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE);

DELETE FROM ops_device_state_history
 WHERE device_id IN (SELECT device_id FROM ops_device WHERE source_mode = 'mock' AND simulated = TRUE);

DELETE FROM inbox_message
 WHERE ops_source_id IN (SELECT source_id FROM ops_integration_source WHERE source_mode = 'mock' AND simulated = TRUE)
    OR source = 'mock-device-adapter';

DELETE FROM ops_device
 WHERE source_mode = 'mock' AND simulated = TRUE;

DELETE FROM ops_integration_source
 WHERE source_mode = 'mock' AND simulated = TRUE;
