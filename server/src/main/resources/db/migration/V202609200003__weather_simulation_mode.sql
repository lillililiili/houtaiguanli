-- Explicit opt-in only. Existing configurations stay in live mode.
ALTER TABLE external_interface_config ADD COLUMN source_mode VARCHAR(16) NOT NULL DEFAULT 'live';
ALTER TABLE external_interface_config ADD CONSTRAINT ck_external_interface_source_mode
    CHECK (source_mode IN ('live','mock') AND (kind='WEATHER_FORECAST' OR source_mode='live'));
