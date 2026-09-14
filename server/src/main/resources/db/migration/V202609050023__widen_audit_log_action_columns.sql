-- 失败审计以 "METHOD /api/v1/uav-events/{36 位 UUID}/verifications" 作为 action、以完整路径作为 object_id，
-- 原 VARCHAR(64) 会让插入失败并被异常处理器吞掉，导致 403/404/409/500 全部不留痕。加宽列不改变已有数据。
ALTER TABLE audit_log ALTER COLUMN action SET DATA TYPE VARCHAR(256);
ALTER TABLE audit_log ALTER COLUMN object_id SET DATA TYPE VARCHAR(256);
