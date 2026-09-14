-- A P1: MQTT sessions are broker-scoped, independent of TCP device leases.
CREATE TABLE mqtt_broker (
    broker_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535),
    tls BOOLEAN NOT NULL DEFAULT TRUE,
    client_id VARCHAR(64) NOT NULL UNIQUE,
    username VARCHAR(128),
    credential_ref VARCHAR(256),
    allowed_cidrs VARCHAR(2048) NOT NULL,
    source_mode VARCHAR(8) NOT NULL CHECK (source_mode IN ('replay','live')),
    owner_org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
    district_id VARCHAR(36) NOT NULL REFERENCES app_district(district_id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CHECK (credential_ref IS NULL OR credential_ref LIKE 'env:%')
);
CREATE TABLE mqtt_session_lease (
    broker_id VARCHAR(36) PRIMARY KEY REFERENCES mqtt_broker(broker_id),
    owner_id VARCHAR(36),
    lease_until BIGINT NOT NULL DEFAULT 0,
    connection_state VARCHAR(24) NOT NULL DEFAULT 'DISCONNECTED',
    last_error VARCHAR(64),
    updated_at BIGINT NOT NULL
);
CREATE TABLE mqtt_device_binding (
    ops_device_id VARCHAR(36) PRIMARY KEY REFERENCES ops_device(device_id),
    device_id VARCHAR(36) NOT NULL UNIQUE REFERENCES device(device_id),
    ops_source_id VARCHAR(36) NOT NULL UNIQUE REFERENCES ops_integration_source(source_id),
    source_id VARCHAR(36) NOT NULL UNIQUE REFERENCES integration_source(source_id),
    broker_id VARCHAR(36) NOT NULL REFERENCES mqtt_broker(broker_id),
    provider_code VARCHAR(64) NOT NULL,
    device_type_abbr VARCHAR(8) NOT NULL CHECK (device_type_abbr IN ('radar','5ga','tdoa')),
    external_device_id VARCHAR(128) NOT NULL,
    source_mode VARCHAR(8) NOT NULL CHECK (source_mode IN ('replay','live')),
    subscribed BOOLEAN NOT NULL DEFAULT FALSE,
    last_static_at BIGINT,
    last_static_pt_time BIGINT,
    last_sense_at BIGINT,
    last_pt_time BIGINT,
    last_msg_cnt BIGINT,
    duplicate_count BIGINT NOT NULL DEFAULT 0,
    conflict_count BIGINT NOT NULL DEFAULT 0,
    suspected_gap_count BIGINT NOT NULL DEFAULT 0,
    UNIQUE (broker_id,provider_code,device_type_abbr,external_device_id)
);
