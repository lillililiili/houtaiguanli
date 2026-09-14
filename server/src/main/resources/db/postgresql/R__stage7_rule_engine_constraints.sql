-- 阶段 7 规则引擎 PostgreSQL 专属约束（H2 测试不加载本目录）。
-- 只增表用触发器守住：研判事实、运行记录一旦落库即为证据，纠错只能追加新行。

-- rule_evaluation：禁止 DELETE；UPDATE 只允许一次性回填三个关联列（assessment_id / alarm_id / alarm_outcome 各自只能从 NULL 变为非 NULL），
-- 其余列不得变化。投影行与告警都必须在研判行存在之后才能产生（外键指向研判行），所以这些关联无法在 INSERT 时写入。
CREATE OR REPLACE FUNCTION prevent_stage7_rule_evaluation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'rule evaluations are append-only' USING ERRCODE = '23514';
    END IF;
    IF (OLD.alarm_id IS NOT NULL AND NEW.alarm_id IS DISTINCT FROM OLD.alarm_id)
       OR (OLD.alarm_outcome IS NOT NULL AND NEW.alarm_outcome IS DISTINCT FROM OLD.alarm_outcome)
       OR (OLD.assessment_id IS NOT NULL AND NEW.assessment_id IS DISTINCT FROM OLD.assessment_id) THEN
        RAISE EXCEPTION 'rule evaluation linkage can only be backfilled once' USING ERRCODE = '23514';
    END IF;
    IF (NEW.evaluation_id, NEW.run_id, NEW.rule_set_version_id, NEW.mode, NEW.subject_kind, NEW.target_id, NEW.track_id,
        NEW.plan_id, NEW.route_version_id, NEW.observed_at, NEW.as_of, NEW.evaluated_at, NEW.freshness_code,
        NEW.plan_match_code, NEW.legal_status, NEW.score, NEW.grade, NEW.violation_reasons, NEW.hit_details,
        NEW.unknown_reasons, NEW.evidence_references, NEW.input_snapshot, NEW.supersedes_evaluation_id,
        NEW.owner_org_id, NEW.district_id, NEW.source_mode, NEW.created_at)
       IS DISTINCT FROM
       (OLD.evaluation_id, OLD.run_id, OLD.rule_set_version_id, OLD.mode, OLD.subject_kind, OLD.target_id, OLD.track_id,
        OLD.plan_id, OLD.route_version_id, OLD.observed_at, OLD.as_of, OLD.evaluated_at, OLD.freshness_code,
        OLD.plan_match_code, OLD.legal_status, OLD.score, OLD.grade, OLD.violation_reasons, OLD.hit_details,
        OLD.unknown_reasons, OLD.evidence_references, OLD.input_snapshot, OLD.supersedes_evaluation_id,
        OLD.owner_org_id, OLD.district_id, OLD.source_mode, OLD.created_at) THEN
        RAISE EXCEPTION 'rule evaluations are append-only' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage7_rule_evaluation_append_only ON rule_evaluation;
CREATE TRIGGER trg_stage7_rule_evaluation_append_only
BEFORE UPDATE OR DELETE ON rule_evaluation
FOR EACH ROW EXECUTE FUNCTION prevent_stage7_rule_evaluation_mutation();

-- rule_run：禁止 DELETE；只有 RUNNING 的运行可以被收尾（写 finished_at/status/计数/错误摘要），完成后不可再改。
CREATE OR REPLACE FUNCTION prevent_stage7_rule_run_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'rule runs are append-only' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'finished rule runs are immutable' USING ERRCODE = '23514';
    END IF;
    IF (NEW.run_id, NEW.rule_set_id, NEW.rule_set_version_id, NEW.mode, NEW.trigger_kind, NEW.replay_dataset_code,
        NEW.triggered_by, NEW.as_of, NEW.started_at, NEW.source_mode, NEW.created_at)
       IS DISTINCT FROM
       (OLD.run_id, OLD.rule_set_id, OLD.rule_set_version_id, OLD.mode, OLD.trigger_kind, OLD.replay_dataset_code,
        OLD.triggered_by, OLD.as_of, OLD.started_at, OLD.source_mode, OLD.created_at) THEN
        RAISE EXCEPTION 'rule run identity columns are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage7_rule_run_append_only ON rule_run;
CREATE TRIGGER trg_stage7_rule_run_append_only
BEFORE UPDATE OR DELETE ON rule_run
FOR EACH ROW EXECUTE FUNCTION prevent_stage7_rule_run_mutation();

-- rule_param：版本发布后参数即为历史研判的输入证据，不得原位修改或删除；改参数只能发新版本。
CREATE OR REPLACE FUNCTION prevent_stage7_published_rule_param_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM rule_set_version v
               WHERE v.rule_set_version_id = OLD.rule_set_version_id AND v.status_code <> 'DRAFT') THEN
        RAISE EXCEPTION 'parameters of a published rule set version are immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_stage7_rule_param_published_immutable ON rule_param;
CREATE TRIGGER trg_stage7_rule_param_published_immutable
BEFORE UPDATE OR DELETE ON rule_param
FOR EACH ROW EXECUTE FUNCTION prevent_stage7_published_rule_param_mutation();

-- 同一数据集对同一版本只允许一次回放运行；RuleReplayRunner 依赖它做"已有则跳过"。
CREATE UNIQUE INDEX IF NOT EXISTS uk_stage7_rule_run_replay_dataset
    ON rule_run (replay_dataset_code, rule_set_version_id)
    WHERE trigger_kind = 'REPLAY';


-- 人工复核历史只增：纠错以新历史行表达，任何 UPDATE/DELETE 都会破坏审计链。
CREATE OR REPLACE FUNCTION prevent_stage7_review_history_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'legality review history is append-only' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_stage7_review_history_append_only ON legality_review_history;
CREATE TRIGGER trg_stage7_review_history_append_only
BEFORE UPDATE OR DELETE ON legality_review_history
FOR EACH ROW EXECUTE FUNCTION prevent_stage7_review_history_mutation();

-- 同目标同类型同一时刻只允许一个 OPEN 合并组（H2 不支持部分唯一索引，只放 PostgreSQL）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_stage7_merge_group_open
    ON alarm_merge_group (target_id, alarm_type)
    WHERE state = 'OPEN';
