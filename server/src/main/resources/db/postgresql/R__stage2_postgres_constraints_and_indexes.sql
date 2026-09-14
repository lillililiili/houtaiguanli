CREATE UNIQUE INDEX IF NOT EXISTS idx_stage2_device_source_external_unique
    ON device (source_id, external_device_id)
    WHERE source_id IS NOT NULL AND external_device_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_stage2_target_source_link_device
    ON target_source_link (device_id, target_id)
    WHERE device_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_stage2_device_location_gist
    ON device USING GIST (location);
CREATE INDEX IF NOT EXISTS idx_stage2_target_latest_location_gist
    ON target_latest_state USING GIST (location);
CREATE INDEX IF NOT EXISTS idx_stage2_track_point_location_gist
    ON track_point USING GIST (location);

CREATE INDEX IF NOT EXISTS idx_stage2_device_history_display_time
    ON device_state_history (
        device_id,
        (COALESCE(observed_at, received_at)) DESC,
        received_at DESC,
        state_id ASC
    );

CREATE INDEX IF NOT EXISTS idx_stage2_track_point_display_time
    ON track_point (
        track_id,
        (COALESCE(observed_at, received_at)) ASC,
        point_seq ASC,
        point_id ASC
    );

DO $$
BEGIN
    ALTER TABLE device DROP CONSTRAINT IF EXISTS ck_stage2_device_location_wgs84;
    ALTER TABLE device ADD CONSTRAINT ck_stage2_device_location_wgs84 CHECK (
        location IS NULL
        OR (
            NOT ST_IsEmpty(location)
            AND ST_SRID(location) = 4326
            AND ST_X(location) BETWEEN -180 AND 180
            AND ST_Y(location) BETWEEN -90 AND 90
        )
    );
END
$$;

DO $$
BEGIN
    ALTER TABLE target_latest_state DROP CONSTRAINT IF EXISTS ck_stage2_target_location_wgs84;
    ALTER TABLE target_latest_state ADD CONSTRAINT ck_stage2_target_location_wgs84 CHECK (
        location IS NULL
        OR (
            NOT ST_IsEmpty(location)
            AND ST_SRID(location) = 4326
            AND ST_X(location) BETWEEN -180 AND 180
            AND ST_Y(location) BETWEEN -90 AND 90
        )
    );
END
$$;

DO $$
BEGIN
    ALTER TABLE track_point DROP CONSTRAINT IF EXISTS ck_stage2_track_point_location_wgs84;
    ALTER TABLE track_point ADD CONSTRAINT ck_stage2_track_point_location_wgs84 CHECK (
        NOT ST_IsEmpty(location)
        AND ST_SRID(location) = 4326
        AND ST_X(location) BETWEEN -180 AND 180
        AND ST_Y(location) BETWEEN -90 AND 90
    );
END
$$;

CREATE OR REPLACE FUNCTION prevent_stage2_app_org_cycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    has_cycle BOOLEAN;
BEGIN
    IF NEW.parent_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'app_org parent changes require READ COMMITTED isolation'
            USING ERRCODE = '25001';
    END IF;
    PERFORM pg_advisory_xact_lock(
        hashtextextended(current_database() || ':' || TG_TABLE_SCHEMA || ':' || TG_TABLE_NAME, 0)
    );
    EXECUTE format(
        $query$
        WITH RECURSIVE ancestors(org_id, parent_id) AS (
            SELECT org_id, parent_id
            FROM %1$I.%2$I
            WHERE org_id = $1
            UNION
            SELECT parent.org_id, parent.parent_id
            FROM %1$I.%2$I parent
            JOIN ancestors child ON parent.org_id = child.parent_id
        )
        SELECT EXISTS (SELECT 1 FROM ancestors WHERE org_id = $2)
        $query$,
        TG_TABLE_SCHEMA,
        TG_TABLE_NAME
    ) INTO has_cycle USING NEW.parent_id, NEW.org_id;
    IF has_cycle THEN
        RAISE EXCEPTION 'app_org hierarchy cycle is not allowed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage2_app_org_cycle ON app_org;
CREATE TRIGGER trg_stage2_app_org_cycle
BEFORE INSERT OR UPDATE OF parent_id ON app_org
FOR EACH ROW
EXECUTE FUNCTION prevent_stage2_app_org_cycle();

CREATE OR REPLACE FUNCTION prevent_stage2_app_district_cycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    has_cycle BOOLEAN;
BEGIN
    IF NEW.parent_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'app_district parent changes require READ COMMITTED isolation'
            USING ERRCODE = '25001';
    END IF;
    PERFORM pg_advisory_xact_lock(
        hashtextextended(current_database() || ':' || TG_TABLE_SCHEMA || ':' || TG_TABLE_NAME, 0)
    );
    EXECUTE format(
        $query$
        WITH RECURSIVE ancestors(district_id, parent_id) AS (
            SELECT district_id, parent_id
            FROM %1$I.%2$I
            WHERE district_id = $1
            UNION
            SELECT parent.district_id, parent.parent_id
            FROM %1$I.%2$I parent
            JOIN ancestors child ON parent.district_id = child.parent_id
        )
        SELECT EXISTS (SELECT 1 FROM ancestors WHERE district_id = $2)
        $query$,
        TG_TABLE_SCHEMA,
        TG_TABLE_NAME
    ) INTO has_cycle USING NEW.parent_id, NEW.district_id;
    IF has_cycle THEN
        RAISE EXCEPTION 'app_district hierarchy cycle is not allowed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage2_app_district_cycle ON app_district;
CREATE TRIGGER trg_stage2_app_district_cycle
BEFORE INSERT OR UPDATE OF parent_id ON app_district
FOR EACH ROW
EXECUTE FUNCTION prevent_stage2_app_district_cycle();
