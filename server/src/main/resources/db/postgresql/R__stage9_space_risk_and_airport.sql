-- 阶段 9（E2）PostgreSQL 专属约束与索引（H2 测试不加载本目录）。所有 CREATE 必须可重复执行。

-- space_risk_fact 只增：它是某条风险"当时按哪版参数、依据什么事实判成这个等级"的唯一记录，
-- 改写等于把历史结论换掉；纠错以新风险表达。
CREATE OR REPLACE FUNCTION prevent_stage9_space_risk_fact_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'space_risk_fact is append-only' USING ERRCODE = '23514';
END
$$;

DROP TRIGGER IF EXISTS trg_stage9_space_risk_fact_append_only ON space_risk_fact;
CREATE TRIGGER trg_stage9_space_risk_fact_append_only
BEFORE UPDATE OR DELETE ON space_risk_fact
FOR EACH ROW EXECUTE FUNCTION prevent_stage9_space_risk_fact_mutation();

-- rule_evaluation_run：只允许 RUNNING 收尾一次（状态与计数一次性写入），身份列与窗口永不可改；DELETE 禁止。
CREATE OR REPLACE FUNCTION prevent_stage9_rule_evaluation_run_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'rule_evaluation_run is append-only' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'finished rule_evaluation_run is immutable' USING ERRCODE = '23514';
    END IF;
    IF (NEW.run_id, NEW.rule_code, NEW.trigger_kind, NEW.window_from, NEW.window_to, NEW.actor_id, NEW.started_at)
       IS DISTINCT FROM
       (OLD.run_id, OLD.rule_code, OLD.trigger_kind, OLD.window_from, OLD.window_to, OLD.actor_id, OLD.started_at) THEN
        RAISE EXCEPTION 'rule_evaluation_run identity columns are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage9_rule_evaluation_run_append_only ON rule_evaluation_run;
CREATE TRIGGER trg_stage9_rule_evaluation_run_append_only
BEFORE UPDATE OR DELETE ON rule_evaluation_run
FOR EACH ROW EXECUTE FUNCTION prevent_stage9_rule_evaluation_run_mutation();

-- 目标位置快照同样要求 4326 且坐标合法：坏坐标进库后地图会把风险标在错误位置。
ALTER TABLE space_risk_fact DROP CONSTRAINT IF EXISTS ck_stage9_space_fact_location;
ALTER TABLE space_risk_fact ADD CONSTRAINT ck_stage9_space_fact_location CHECK (
    target_location IS NULL OR (ST_SRID(target_location) = 4326 AND ST_IsValid(target_location)
        AND ST_X(target_location) BETWEEN -180 AND 180 AND ST_Y(target_location) BETWEEN -90 AND 90)
);

CREATE INDEX IF NOT EXISTS idx_stage9_space_fact_location_gist ON space_risk_fact USING GIST (target_location);

-- 机场几何：SRID 必须是 4326 且几何有效，经纬度落在合法范围；坏坐标进库后所有距离判定都会静默出错。
ALTER TABLE airport DROP CONSTRAINT IF EXISTS ck_stage9_airport_reference_point;
ALTER TABLE airport ADD CONSTRAINT ck_stage9_airport_reference_point CHECK (
    ST_SRID(reference_point) = 4326 AND ST_IsValid(reference_point)
    AND ST_X(reference_point) BETWEEN -180 AND 180 AND ST_Y(reference_point) BETWEEN -90 AND 90
);

ALTER TABLE airport_runway DROP CONSTRAINT IF EXISTS ck_stage9_runway_centerline;
ALTER TABLE airport_runway ADD CONSTRAINT ck_stage9_runway_centerline CHECK (
    centerline IS NULL OR (ST_SRID(centerline) = 4326 AND ST_IsValid(centerline))
);

ALTER TABLE airport_procedure_route DROP CONSTRAINT IF EXISTS ck_stage9_procedure_centerline;
ALTER TABLE airport_procedure_route ADD CONSTRAINT ck_stage9_procedure_centerline CHECK (
    ST_SRID(centerline) = 4326 AND ST_IsValid(centerline)
);

ALTER TABLE airport_protected_target DROP CONSTRAINT IF EXISTS ck_stage9_protected_location;
ALTER TABLE airport_protected_target ADD CONSTRAINT ck_stage9_protected_location CHECK (
    ST_SRID(location) = 4326 AND ST_IsValid(location)
    AND ST_X(location) BETWEEN -180 AND 180 AND ST_Y(location) BETWEEN -90 AND 90
);

CREATE INDEX IF NOT EXISTS idx_stage9_airport_reference_gist ON airport USING GIST (reference_point);
CREATE INDEX IF NOT EXISTS idx_stage9_procedure_centerline_gist ON airport_procedure_route USING GIST (centerline);
CREATE INDEX IF NOT EXISTS idx_stage9_protected_location_gist ON airport_protected_target USING GIST (location);
