-- 阶段 9：空域版本的接替式约束（PostgreSQL 专属；H2 测试不加载本目录）。
-- 阶段 3 的 trg_stage3_airspace_version_immutable 完全禁止 UPDATE，接替式变更需要把上一版的 valid_to
-- 从 NULL 关闭为新版的 valid_from，因此这里用新触发器替换它，而不是并存两个触发器。

CREATE OR REPLACE FUNCTION prevent_stage9_airspace_version_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- 历史空域版本是已保存研判的输入证据：删除会让引用它的结论失去依据。
        RAISE EXCEPTION 'airspace versions cannot be deleted' USING ERRCODE = '23514';
    END IF;
    -- 唯一放开的写法：把仍然开放的版本关闭到某个时刻（valid_to NULL → 非 NULL）。
    -- 只放开这一列，是因为它表达的是"这一版从何时起不再生效"，属于新增事实；
    -- 几何、高度带、种类、生效起点一旦被研判引用就不能再改，改了等于篡改历史输入。
    IF NOT (OLD.valid_to IS NULL AND NEW.valid_to IS NOT NULL) THEN
        RAISE EXCEPTION 'airspace versions are immutable except closing valid_to' USING ERRCODE = '23514';
    END IF;
    IF (NEW.airspace_version_id, NEW.airspace_id, NEW.version_no, NEW.kind_code, NEW.boundary,
        NEW.min_altitude_m, NEW.max_altitude_m, NEW.altitude_datum, NEW.valid_from, NEW.change_reason, NEW.created_at)
       IS DISTINCT FROM
       (OLD.airspace_version_id, OLD.airspace_id, OLD.version_no, OLD.kind_code, OLD.boundary,
        OLD.min_altitude_m, OLD.max_altitude_m, OLD.altitude_datum, OLD.valid_from, OLD.change_reason, OLD.created_at) THEN
        RAISE EXCEPTION 'only valid_to may change on an airspace version' USING ERRCODE = '23514';
    END IF;
    -- 关闭时刻必须晚于生效起点，否则会出现零长度或倒挂的生效区间。
    IF NEW.valid_to <= NEW.valid_from THEN
        RAISE EXCEPTION 'airspace version valid_to must be later than valid_from' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage3_airspace_version_immutable ON airspace_version;
DROP TRIGGER IF EXISTS trg_stage9_airspace_version_succession ON airspace_version;
CREATE TRIGGER trg_stage9_airspace_version_succession
BEFORE UPDATE OR DELETE ON airspace_version
FOR EACH ROW EXECUTE FUNCTION prevent_stage9_airspace_version_mutation();

-- 导入项的几何与空域版本同标准：非法几何或非 4326 不得进入确认流程，
-- 否则确认时才失败，操作者已经看过一份"看起来没问题"的预览。
DO $$
BEGIN
    ALTER TABLE airspace_import_item DROP CONSTRAINT IF EXISTS ck_stage9_import_item_boundary_wgs84;
    ALTER TABLE airspace_import_item ADD CONSTRAINT ck_stage9_import_item_boundary_wgs84 CHECK (
        boundary IS NULL OR (
            NOT ST_IsEmpty(boundary)
            AND ST_SRID(boundary) = 4326
            AND ST_IsValid(boundary)
            AND GeometryType(boundary) = 'MULTIPOLYGON'
            AND ST_XMin(Box3D(boundary)) >= -180 AND ST_XMax(Box3D(boundary)) <= 180
            AND ST_YMin(Box3D(boundary)) >= -90 AND ST_YMax(Box3D(boundary)) <= 90
        )
    );
END
$$;

CREATE INDEX IF NOT EXISTS idx_stage9_import_item_boundary_gist
    ON airspace_import_item USING GIST (boundary);

-- 版本来源只增：它是"谁在什么时候、依据哪个导入项建了这一版"的痕迹，改写等于抹掉操作者留痕。
CREATE OR REPLACE FUNCTION prevent_stage9_airspace_version_origin_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'airspace version origins are append-only' USING ERRCODE = '23514';
END
$$;

DROP TRIGGER IF EXISTS trg_stage9_airspace_version_origin_append_only ON airspace_version_origin;
CREATE TRIGGER trg_stage9_airspace_version_origin_append_only
BEFORE UPDATE OR DELETE ON airspace_version_origin
FOR EACH ROW EXECUTE FUNCTION prevent_stage9_airspace_version_origin_mutation();

-- 飞行计划外部授权登记已按 F8 裁定撤除（V202609090106 删表）：触发器随表消失，这里只清掉遗留函数。
DROP FUNCTION IF EXISTS prevent_stage9_plan_authorization_mutation();
