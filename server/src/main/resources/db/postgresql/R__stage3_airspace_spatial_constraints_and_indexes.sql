-- 走廊宽度以米计；候选阶段仍使用 4326 GiST，精确米制关系只能在 geography 上计算。
CREATE INDEX IF NOT EXISTS idx_stage3_airspace_version_boundary_gist
    ON airspace_version USING GIST (boundary);

ALTER TABLE airspace_version DROP CONSTRAINT IF EXISTS ck_stage3_airspace_boundary_wgs84;
ALTER TABLE airspace_version ADD CONSTRAINT ck_stage3_airspace_boundary_wgs84 CHECK (
    boundary IS NULL OR (
        NOT ST_IsEmpty(boundary)
        AND ST_SRID(boundary) = 4326
        AND ST_IsValid(boundary)
        AND GeometryType(boundary) = 'MULTIPOLYGON'
        -- GeoJSON/WGS-84 固定 [longitude, latitude]，先约束包络避免越界坐标被前端误绘。
        AND ST_XMin(Box3D(boundary)) >= -180 AND ST_XMax(Box3D(boundary)) <= 180
        AND ST_YMin(Box3D(boundary)) >= -90 AND ST_YMax(Box3D(boundary)) <= 90
    )
);

CREATE OR REPLACE FUNCTION prevent_stage3_airspace_version_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- 历史空域版本是已保存研判的输入证据，更新或删除会让历史结论漂移。
    RAISE EXCEPTION 'airspace versions are immutable' USING ERRCODE = '23514';
END
$$;

DROP TRIGGER IF EXISTS trg_stage3_airspace_version_immutable ON airspace_version;
CREATE TRIGGER trg_stage3_airspace_version_immutable
BEFORE UPDATE OR DELETE ON airspace_version
FOR EACH ROW EXECUTE FUNCTION prevent_stage3_airspace_version_mutation();

CREATE OR REPLACE FUNCTION prevent_stage3_assessment_result_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- 研判结论与规则/计划/航线输入共同构成可追溯证据；只能追加新结果，不能覆写或删除历史。
    RAISE EXCEPTION 'assessment results are append-only' USING ERRCODE = '23514';
END
$$;

DROP TRIGGER IF EXISTS trg_stage3_assessment_result_append_only ON assessment_result;
CREATE TRIGGER trg_stage3_assessment_result_append_only
BEFORE UPDATE OR DELETE ON assessment_result
FOR EACH ROW EXECUTE FUNCTION prevent_stage3_assessment_result_mutation();

CREATE OR REPLACE FUNCTION prevent_stage3_referenced_rule_version_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- 规则版本一旦成为研判输入即为历史证据；原位修改会使同一 assessment 的规则含义漂移。
    IF EXISTS (SELECT 1 FROM assessment_result ar WHERE ar.rule_version_id = OLD.rule_version_id)
       AND (NEW.rule_code, NEW.version_no, NEW.status_code, NEW.valid_from, NEW.valid_to,
            NEW.source_mode, NEW.source_snapshot)
           IS DISTINCT FROM
           (OLD.rule_code, OLD.version_no, OLD.status_code, OLD.valid_from, OLD.valid_to,
            OLD.source_mode, OLD.source_snapshot) THEN
        RAISE EXCEPTION 'referenced rule versions are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage3_referenced_rule_version_immutable ON rule_version;
CREATE TRIGGER trg_stage3_referenced_rule_version_immutable
BEFORE UPDATE ON rule_version
FOR EACH ROW EXECUTE FUNCTION prevent_stage3_referenced_rule_version_mutation();
