-- 2026-10-27 用户确认：每台反制设备可以单独设置附近航班的安全距离；没有设置的设备用规则管理「航班避让」里的默认值。
CREATE TABLE counter_flight_distance (
    device_id VARCHAR(36) PRIMARY KEY REFERENCES ops_device(device_id) ON DELETE CASCADE,
    version BIGINT NOT NULL,
    force_land_block_km NUMERIC(6,2) NOT NULL,
    force_land_warn_km NUMERIC(6,2) NOT NULL,
    force_land_max_altitude_m INTEGER NOT NULL,
    drive_away_warn_km NUMERIC(6,2) NOT NULL,
    drive_away_max_altitude_m INTEGER NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT ck_counter_flight_distance_force_land CHECK (force_land_block_km > 0 AND force_land_warn_km >= force_land_block_km
        AND force_land_warn_km <= 350),
    CONSTRAINT ck_counter_flight_distance_drive_away CHECK (drive_away_warn_km > 0 AND drive_away_warn_km <= 350),
    CONSTRAINT ck_counter_flight_distance_altitude CHECK (force_land_max_altitude_m BETWEEN 100 AND 15000
        AND drive_away_max_altitude_m BETWEEN 100 AND 15000)
);
