-- ZT-06：规则引擎改为每秒取一次待评估目标，老目标同一规则版本至多每 5 秒评估一次。
-- 取数时要按 (目标, 模式, 版本) 查"最近一次评估时刻"和"是否评估过"；原有索引按 observed_at 排序，
-- 长时间飞行的目标评估记录多，按目标扫全部记录会越来越慢。这里补一个按评估时刻倒序的索引。
CREATE INDEX idx_zt06_rule_evaluation_recent
    ON rule_evaluation (target_id, mode, rule_set_version_id, evaluated_at DESC);
