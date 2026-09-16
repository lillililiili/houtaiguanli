CREATE TABLE device_sensing_profile (
    device_id VARCHAR(36) PRIMARY KEY,
    coverage_kind VARCHAR(16) NOT NULL,
    radius_m DECIMAL(12, 2),
    range_m DECIMAL(12, 2),
    azimuth_deg DECIMAL(8, 3),
    fov_deg DECIMAL(8, 3),
    source_label VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_sensing_profile_device FOREIGN KEY (device_id) REFERENCES ops_device(device_id) ON DELETE CASCADE,
    CONSTRAINT ck_sensing_profile_kind CHECK (coverage_kind IN ('CIRCLE', 'SECTOR')),
    CONSTRAINT ck_sensing_profile_version CHECK (version >= 0),
    CONSTRAINT ck_sensing_profile_geometry CHECK (
        (coverage_kind = 'CIRCLE' AND radius_m > 0 AND range_m IS NULL AND azimuth_deg IS NULL AND fov_deg IS NULL)
        OR
        (coverage_kind = 'SECTOR' AND radius_m IS NULL AND range_m > 0
            AND azimuth_deg >= 0 AND azimuth_deg < 360 AND fov_deg > 0 AND fov_deg <= 360)
    )
);
