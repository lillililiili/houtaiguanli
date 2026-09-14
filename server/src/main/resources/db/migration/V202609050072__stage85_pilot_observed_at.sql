-- 阶段 8.5（决策 8.5-28）：飞手位置的观测时刻。
--
-- pilot_location 从"每帧重写"改成"只有携带身份主源的帧才有资格改写"（决策 8.5-27）之后，
-- 这一列可能保留自若干帧之前，C02-6 就有可能拿过期的飞手位置判超视距。
-- 单独记下它被写入时的观测时刻，让"这个飞手位置有多旧"成为可读事实，而不是只能靠猜。
-- 本期不设独立过期阈值：由目标整体新鲜度（C03 fresh_seconds）兜底，阈值化留到有真实联调数据之后。
ALTER TABLE target_latest_state ADD COLUMN pilot_observed_at TIMESTAMP WITH TIME ZONE;
