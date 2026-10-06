-- Local/QA simulator input registrations and normalized observations.
-- These are explicit operator inputs, not demo seed data.
INSERT INTO source_type_catalog (source_type, display_name, schema_status, spec_ref, created_at)
SELECT 'SIM_NORMALIZED', '规范化模拟观测', 'DEMO', NULL, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM source_type_catalog WHERE source_type = 'SIM_NORMALIZED');

CREATE TABLE simulator_observation_source (
    source_id       VARCHAR(36) PRIMARY KEY REFERENCES integration_source(source_id) ON DELETE RESTRICT,
    device_id       VARCHAR(36) NOT NULL REFERENCES device(device_id) ON DELETE RESTRICT,
    message_id      VARCHAR(64) NOT NULL UNIQUE,
    owner_org_id    VARCHAR(36) NOT NULL REFERENCES app_org(org_id) ON DELETE RESTRICT,
    district_id     VARCHAR(36) NOT NULL REFERENCES app_district(district_id) ON DELETE RESTRICT,
    request_hash    VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE simulator_weather_device (
    source_id       VARCHAR(36) PRIMARY KEY REFERENCES integration_source(source_id) ON DELETE RESTRICT,
    device_id       VARCHAR(36) NOT NULL UNIQUE REFERENCES device(device_id) ON DELETE RESTRICT,
    message_id      VARCHAR(64) NOT NULL UNIQUE,
    owner_org_id    VARCHAR(36) NOT NULL REFERENCES app_org(org_id) ON DELETE RESTRICT,
    district_id     VARCHAR(36) NOT NULL REFERENCES app_district(district_id) ON DELETE RESTRICT,
    request_hash    VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE simulator_weather_observation (
    message_id          VARCHAR(64) PRIMARY KEY,
    device_id           VARCHAR(36) NOT NULL REFERENCES device(device_id) ON DELETE RESTRICT,
    owner_org_id        VARCHAR(36) NOT NULL REFERENCES app_org(org_id) ON DELETE RESTRICT,
    district_id         VARCHAR(36) NOT NULL REFERENCES app_district(district_id) ON DELETE RESTRICT,
    observed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    location            GEOMETRY(POINT, 4326),
    payload             JSONB NOT NULL,
    request_hash        VARCHAR(64) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_simulator_weather_observation_device_time
    ON simulator_weather_observation(device_id, observed_at DESC, message_id);
