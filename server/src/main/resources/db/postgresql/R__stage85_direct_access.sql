-- 阶段 8.5：飞手位置两列的 WGS-84 约束与空间索引（PostgreSQL 专属；H2 测试不加载本目录）。
-- 与阶段 8 的 source_observation.location 同一把尺子：越界或非 4326 的点会污染所有大圆距离计算，
-- 而 C02-6 超视距判定正是拿飞手位置与目标位置算距离——一个坏点会让"超没超视距"这个结论直接失真。

DO $$
BEGIN
    ALTER TABLE source_observation DROP CONSTRAINT IF EXISTS ck_stage85_observation_pilot_location_wgs84;
    ALTER TABLE source_observation ADD CONSTRAINT ck_stage85_observation_pilot_location_wgs84 CHECK (
        pilot_location IS NULL
        OR (
            NOT ST_IsEmpty(pilot_location)
            AND ST_IsValid(pilot_location)
            AND ST_SRID(pilot_location) = 4326
            AND ST_X(pilot_location) BETWEEN -180 AND 180
            AND ST_Y(pilot_location) BETWEEN -90 AND 90
        )
    );
END
$$;

CREATE INDEX IF NOT EXISTS idx_stage85_observation_pilot_location_gist
    ON source_observation USING GIST (pilot_location);

DO $$
BEGIN
    ALTER TABLE target_latest_state DROP CONSTRAINT IF EXISTS ck_stage85_target_state_pilot_location_wgs84;
    ALTER TABLE target_latest_state ADD CONSTRAINT ck_stage85_target_state_pilot_location_wgs84 CHECK (
        pilot_location IS NULL
        OR (
            NOT ST_IsEmpty(pilot_location)
            AND ST_IsValid(pilot_location)
            AND ST_SRID(pilot_location) = 4326
            AND ST_X(pilot_location) BETWEEN -180 AND 180
            AND ST_Y(pilot_location) BETWEEN -90 AND 90
        )
    );
END
$$;

CREATE INDEX IF NOT EXISTS idx_stage85_target_state_pilot_location_gist
    ON target_latest_state USING GIST (pilot_location);

-- 来源观测只增：它是"某台设备在某一刻报了什么"的原始证据。
-- 阶段 7 的 C02-6 超视距、C01 身份维度、以及所有关联与降级结论都建立在这些行上；
-- 改一条观测等于让已经保存的研判失去依据，删一条等于让"当时为什么这么判"再也说不清。
-- 与阶段 8 给 target_lineage / fusion_event 装的只增触发器同款（R__stage8_postgres_constraints_and_indexes.sql）。
CREATE OR REPLACE FUNCTION prevent_stage85_source_observation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'source observations are append-only' USING ERRCODE = '23514';
END
$$;

DROP TRIGGER IF EXISTS trg_stage85_source_observation_append_only ON source_observation;
CREATE TRIGGER trg_stage85_source_observation_append_only
BEFORE UPDATE OR DELETE ON source_observation
FOR EACH ROW EXECUTE FUNCTION prevent_stage85_source_observation_mutation();
