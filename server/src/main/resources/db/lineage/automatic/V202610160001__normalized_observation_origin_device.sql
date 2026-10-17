-- Optional explicit identity for newly registered normalized simulator inputs.
-- Existing registrations remain unbound; historical inputs are never inferred or backfilled.
ALTER TABLE simulator_observation_source ADD COLUMN origin_ops_device_id VARCHAR(36)
    REFERENCES ops_device(device_id) ON DELETE RESTRICT;
ALTER TABLE simulator_observation_source ADD CONSTRAINT ck_sim_observation_origin_nonblank
    CHECK (origin_ops_device_id IS NULL OR LENGTH(TRIM(origin_ops_device_id)) > 0);
CREATE INDEX idx_sim_observation_origin_device ON simulator_observation_source(origin_ops_device_id);
