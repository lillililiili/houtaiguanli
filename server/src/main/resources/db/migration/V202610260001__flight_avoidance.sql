-- 2026-10-26 用户确认航班避让：ADS-B 航班接收机登记、航班数据、反制前检查与记录。
-- 距离、高度可在规则管理里修改；禁止范围有航班一律不能反制，提醒范围或收不到航班信息时人工确认后可以反制，自动反制不启动。

CREATE TABLE flight_avoidance_policy (
    policy_id VARCHAR(32) PRIMARY KEY,
    version BIGINT NOT NULL,
    force_land_block_km NUMERIC(6,2) NOT NULL,
    force_land_warn_km NUMERIC(6,2) NOT NULL,
    force_land_max_altitude_m INTEGER NOT NULL,
    drive_away_warn_km NUMERIC(6,2) NOT NULL,
    drive_away_max_altitude_m INTEGER NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT ck_flight_avoidance_force_land CHECK (force_land_block_km > 0 AND force_land_warn_km >= force_land_block_km
        AND force_land_warn_km <= 350),
    CONSTRAINT ck_flight_avoidance_drive_away CHECK (drive_away_warn_km > 0 AND drive_away_warn_km <= 350),
    CONSTRAINT ck_flight_avoidance_altitude CHECK (force_land_max_altitude_m BETWEEN 100 AND 15000
        AND drive_away_max_altitude_m BETWEEN 100 AND 15000)
);
INSERT INTO flight_avoidance_policy VALUES ('counter',1,10,30,3000,5,1000,0);

CREATE TABLE flight_avoidance_change (
    change_id VARCHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL UNIQUE,
    actor_id VARCHAR(64) NOT NULL,
    actor VARCHAR(160) NOT NULL,
    created_at BIGINT NOT NULL,
    before_json TEXT NOT NULL,
    after_json TEXT NOT NULL
);

-- 航班接收机是设备台账里的一类设备；接收范围只决定地图上显示多远的航班。
CREATE TABLE adsb_receiver (
    device_id VARCHAR(36) PRIMARY KEY REFERENCES ops_device(device_id) ON DELETE CASCADE,
    receive_range_km INTEGER NOT NULL,
    last_data_at BIGINT,
    CONSTRAINT ck_adsb_receiver_range CHECK (receive_range_km BETWEEN 10 AND 600)
);

-- 每台接收机每架航班只留最新一条；高度是航班广播的气压高度（米）。平时的航班不长期保存，反制时的情况另存检查记录。
CREATE TABLE adsb_aircraft (
    receiver_id VARCHAR(36) NOT NULL REFERENCES adsb_receiver(device_id) ON DELETE CASCADE,
    icao24 VARCHAR(6) NOT NULL,
    callsign VARCHAR(16),
    airline VARCHAR(64),
    longitude NUMERIC(10,7) NOT NULL,
    latitude NUMERIC(10,7) NOT NULL,
    altitude_m INTEGER,
    ground_speed_mps NUMERIC(7,2),
    track_deg NUMERIC(5,1),
    observed_at BIGINT NOT NULL,
    received_at BIGINT NOT NULL,
    simulated BOOLEAN NOT NULL,
    PRIMARY KEY (receiver_id, icao24),
    CONSTRAINT ck_adsb_aircraft_position CHECK (longitude BETWEEN -180 AND 180 AND latitude BETWEEN -90 AND 90)
);
CREATE INDEX idx_adsb_aircraft_received ON adsb_aircraft(received_at);

-- 每次反制前、反制中检查的结果和当时附近的航班。没有接入航班接收机时不检查，也不记录。
CREATE TABLE counter_flight_check (
    check_id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36),
    authorization_id VARCHAR(36),
    device_id VARCHAR(36) NOT NULL,
    countermeasure_mode VARCHAR(16) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    result VARCHAR(16) NOT NULL,
    acknowledged BOOLEAN NOT NULL,
    actor_id VARCHAR(36),
    aircraft_json TEXT NOT NULL,
    policy_version BIGINT NOT NULL,
    checked_at BIGINT NOT NULL,
    CONSTRAINT ck_counter_flight_check_mode CHECK (countermeasure_mode IN ('FORCE_LAND','DRIVE_AWAY')),
    CONSTRAINT ck_counter_flight_check_stage CHECK (stage IN ('REQUEST','DIRECT','EXECUTE','AUTO','RUNNING')),
    CONSTRAINT ck_counter_flight_check_result CHECK (result IN ('CLEAR','WARN','BLOCK','NO_DATA'))
);
CREATE INDEX idx_counter_flight_check_event ON counter_flight_check(event_id, checked_at);
CREATE INDEX idx_counter_flight_check_authorization ON counter_flight_check(authorization_id, checked_at);

ALTER TABLE disposal_authorization_event DROP CONSTRAINT ck_stage13_event_kind;
ALTER TABLE disposal_authorization_event ADD CONSTRAINT ck_stage13_event_kind CHECK (event_kind IN (
    'REQUEST','DIRECT_AUTHORIZE','APPROVE','REJECT','EXECUTE','RECEIPT','STOP','COMPLETE','FAIL','EXPIRE','CANCEL','MANUAL_RESULT',
    'DEVICE_STOP_UNAVAILABLE','DEVICE_CONTROL_UNAVAILABLE','DEVICE_NOT_BOUND','PROTOCOL_NOT_OPENED','DEVICE_OFFLINE',
    'DEVICE_ALL_OFF_ISSUED','DEVICE_FAULT','DEVICE_BUSY','AIRCRAFT_NEARBY'));
