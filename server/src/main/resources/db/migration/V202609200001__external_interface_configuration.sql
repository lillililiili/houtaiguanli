-- Configuration drafts only. No adapter, credential or business sample is enabled by migration.
CREATE TABLE external_interface_config (
    kind VARCHAR(32) PRIMARY KEY,
    name VARCHAR(128), source_code VARCHAR(64), direction VARCHAR(16),
    endpoint VARCHAR(512), credential_ref VARCHAR(128), allowed_cidrs VARCHAR(512),
    area_name VARCHAR(128), interval_minutes INTEGER, validity_minutes INTEGER,
    version BIGINT NOT NULL DEFAULT 0, updated_at BIGINT,
    CONSTRAINT ck_external_interface_kind CHECK (kind IN ('FLIGHT_PLAN','WEATHER_FORECAST')),
    CONSTRAINT ck_external_interface_direction CHECK (direction IS NULL OR direction IN ('PUSH','PULL')),
    CONSTRAINT ck_external_interface_intervals CHECK ((interval_minutes IS NULL OR interval_minutes > 0)
        AND (validity_minutes IS NULL OR validity_minutes > 0))
);
INSERT INTO external_interface_config(kind) VALUES ('FLIGHT_PLAN'),('WEATHER_FORECAST');
