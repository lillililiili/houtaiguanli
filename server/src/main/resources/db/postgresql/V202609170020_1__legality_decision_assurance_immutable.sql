ALTER TABLE rule_evaluation ALTER COLUMN decision_assurance_reasons TYPE JSONB USING decision_assurance_reasons::jsonb;

-- 仅保护新算法事实；不修改已应用的阶段 7 迁移，既有三个关联字段仍可按原约束一次回填。
CREATE OR REPLACE FUNCTION prevent_decision_assurance_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.decision_algorithm_version IS DISTINCT FROM OLD.decision_algorithm_version
       OR NEW.decision_assurance_code IS DISTINCT FROM OLD.decision_assurance_code
       OR NEW.decision_assurance_reasons::jsonb IS DISTINCT FROM OLD.decision_assurance_reasons::jsonb THEN
        RAISE EXCEPTION 'decision assurance is append-only' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_decision_assurance_append_only
BEFORE UPDATE ON rule_evaluation
FOR EACH ROW EXECUTE FUNCTION prevent_decision_assurance_mutation();
