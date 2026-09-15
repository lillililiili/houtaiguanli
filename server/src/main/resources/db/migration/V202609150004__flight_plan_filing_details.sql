-- 报备事实：未知字段留空；起降点是报备位置，不是实际轨迹端点。
ALTER TABLE flight_plan ADD COLUMN pilot_name VARCHAR(128);
ALTER TABLE flight_plan ADD COLUMN operator_name VARCHAR(256);
ALTER TABLE flight_plan ADD COLUMN takeoff_site_name VARCHAR(256);
ALTER TABLE flight_plan ADD COLUMN landing_site_name VARCHAR(256);
ALTER TABLE flight_plan ADD COLUMN takeoff_longitude NUMERIC(10,7);
ALTER TABLE flight_plan ADD COLUMN takeoff_latitude NUMERIC(10,7);
ALTER TABLE flight_plan ADD COLUMN landing_longitude NUMERIC(10,7);
ALTER TABLE flight_plan ADD COLUMN landing_latitude NUMERIC(10,7);
ALTER TABLE flight_plan ADD CONSTRAINT ck_flight_takeoff_wgs84 CHECK (
    (takeoff_longitude IS NULL AND takeoff_latitude IS NULL)
    OR (takeoff_longitude IS NOT NULL AND takeoff_latitude IS NOT NULL
        AND takeoff_longitude BETWEEN -180 AND 180 AND takeoff_latitude BETWEEN -90 AND 90));
ALTER TABLE flight_plan ADD CONSTRAINT ck_flight_landing_wgs84 CHECK (
    (landing_longitude IS NULL AND landing_latitude IS NULL)
    OR (landing_longitude IS NOT NULL AND landing_latitude IS NOT NULL
        AND landing_longitude BETWEEN -180 AND 180 AND landing_latitude BETWEEN -90 AND 90));
