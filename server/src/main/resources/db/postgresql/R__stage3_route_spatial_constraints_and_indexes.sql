CREATE INDEX IF NOT EXISTS idx_stage3_route_version_centerline_gist
    ON route_version USING GIST (centerline);

DO $$
BEGIN
    ALTER TABLE route_version DROP CONSTRAINT IF EXISTS ck_stage3_route_centerline_wgs84;
    ALTER TABLE route_version ADD CONSTRAINT ck_stage3_route_centerline_wgs84 CHECK (
        NOT ST_IsEmpty(centerline)
        AND ST_SRID(centerline) = 4326
        AND GeometryType(centerline) = 'LINESTRING'
        AND ST_IsValid(centerline)
        AND ST_NPoints(centerline) >= 2
        AND ST_Length(ST_RemoveRepeatedPoints(centerline)) > 0
        AND ST_XMin(ST_Envelope(centerline)) BETWEEN -180 AND 180
        AND ST_XMax(ST_Envelope(centerline)) BETWEEN -180 AND 180
        AND ST_YMin(ST_Envelope(centerline)) BETWEEN -90 AND 90
        AND ST_YMax(ST_Envelope(centerline)) BETWEEN -90 AND 90
    );
END
$$;

CREATE OR REPLACE FUNCTION prevent_stage3_referenced_route_version_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- 计划已锁定精确航线版本；允许原位改写会使历史计划和已保存研判悄然漂移。
    IF EXISTS (SELECT 1 FROM flight_plan WHERE route_version_id=OLD.route_version_id)
       AND (NEW.route_id, NEW.version_no, NEW.centerline, NEW.corridor_width_m,
            NEW.min_altitude_m, NEW.max_altitude_m, NEW.altitude_datum,
            NEW.valid_from, NEW.valid_to, NEW.change_reason)
           IS DISTINCT FROM
           (OLD.route_id, OLD.version_no, OLD.centerline, OLD.corridor_width_m,
            OLD.min_altitude_m, OLD.max_altitude_m, OLD.altitude_datum,
            OLD.valid_from, OLD.valid_to, OLD.change_reason) THEN
        RAISE EXCEPTION 'referenced route version is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage3_route_version_immutable ON route_version;
CREATE TRIGGER trg_stage3_route_version_immutable
BEFORE UPDATE ON route_version
FOR EACH ROW
EXECUTE FUNCTION prevent_stage3_referenced_route_version_change();
