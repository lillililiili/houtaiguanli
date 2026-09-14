ALTER TABLE mqtt_broker ADD CONSTRAINT uq_mqtt_broker_mode UNIQUE(broker_id,source_mode);
ALTER TABLE ops_device ADD CONSTRAINT uq_mqtt_ops_identity UNIQUE(device_id,source_id,external_device_id,source_mode);
ALTER TABLE device ADD CONSTRAINT uq_mqtt_standard_identity UNIQUE(device_id,source_id,external_device_id,source_mode);
ALTER TABLE mqtt_device_binding ADD CONSTRAINT fk_mqtt_broker_mode
    FOREIGN KEY(broker_id,source_mode) REFERENCES mqtt_broker(broker_id,source_mode);
ALTER TABLE mqtt_device_binding ADD CONSTRAINT fk_mqtt_ops_identity
    FOREIGN KEY(ops_device_id,ops_source_id,external_device_id,source_mode)
    REFERENCES ops_device(device_id,source_id,external_device_id,source_mode);
ALTER TABLE mqtt_device_binding ADD CONSTRAINT fk_mqtt_standard_identity
    FOREIGN KEY(device_id,source_id,external_device_id,source_mode)
    REFERENCES device(device_id,source_id,external_device_id,source_mode);
