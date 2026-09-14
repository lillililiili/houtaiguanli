-- Payload bytes are hashed before parsing; diagnostics contain no credentials or raw payload.
CREATE TABLE mqtt_receive_diagnostic (
    diagnostic_id VARCHAR(36) PRIMARY KEY,
    broker_id VARCHAR(36) NOT NULL REFERENCES mqtt_broker(broker_id),
    ops_device_id VARCHAR(36) REFERENCES ops_device(device_id),
    topic VARCHAR(1024) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    received_at BIGINT NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    reason VARCHAR(64) NOT NULL
);
CREATE INDEX idx_mqtt_diagnostic_device ON mqtt_receive_diagnostic(ops_device_id,received_at);
-- Persistent QoS1 transport duplicate detection for static messages without a protocol sequence.
CREATE TABLE mqtt_delivery_receipt (
    broker_id VARCHAR(36) NOT NULL REFERENCES mqtt_broker(broker_id),
    packet_id INTEGER NOT NULL,
    topic VARCHAR(1024) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (broker_id,packet_id)
);
