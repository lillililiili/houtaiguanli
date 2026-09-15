ALTER TABLE protocol_runtime_state ADD COLUMN radar_registers_json TEXT;
ALTER TABLE protocol_runtime_state ADD COLUMN radar_registers_at BIGINT;
ALTER TABLE eo_device_binding ADD COLUMN heartbeat_json TEXT;
ALTER TABLE eo_device_binding ADD COLUMN camera_received_at BIGINT;
