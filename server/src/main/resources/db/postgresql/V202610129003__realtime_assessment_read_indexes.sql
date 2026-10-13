-- 大屏实时快照按评估时间取各主体/模式的最新研判。
-- 原索引按 observed_at 或规则版本排序，无法高效查找跨版本的后继评估。
-- 只补充读取索引，不改变最新记录、日期、来源或权限筛选。
CREATE INDEX idx_realtime_evaluation_latest_target
    ON rule_evaluation (target_id, mode, evaluated_at DESC, evaluation_id DESC)
    WHERE subject_kind = 'TARGET';

CREATE INDEX idx_realtime_evaluation_latest_plan
    ON rule_evaluation (plan_id, mode, evaluated_at DESC, evaluation_id DESC)
    WHERE subject_kind = 'PLAN';

CREATE INDEX idx_realtime_evaluation_time
    ON rule_evaluation (evaluated_at DESC, evaluation_id DESC);
