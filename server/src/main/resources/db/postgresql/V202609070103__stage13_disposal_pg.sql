-- 阶段 13：处置授权域的 PostgreSQL 专属约束（版本化迁移，H2 测试不加载本目录）。
-- 放在 0102 之后的版本化迁移而不是 R__：R__ 在"先停在中间版本再前进"的库上会被记为已应用而不再重跑，
-- 触发器会静默缺失；版本化迁移按序只跑一次，表此时必然已存在，不需要任何守卫（决策 13-32 修订）。
-- 语句写成可重复执行：函数由 R__stage13_disposal.sql 定义（CREATE OR REPLACE），这里只挂触发器与 CHECK。

-- 事件流只增。一次反制/干扰的全过程是有法律后果的事实链：改一行等于改口供，所以在库层堵死。
CREATE OR REPLACE FUNCTION prevent_stage13_disposal_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'disposal authorization events are append-only' USING ERRCODE = '23514';
END;
$$;

DROP TRIGGER IF EXISTS trg_stage13_disposal_event_append_only ON disposal_authorization_event;
CREATE TRIGGER trg_stage13_disposal_event_append_only
    BEFORE UPDATE OR DELETE ON disposal_authorization_event
    FOR EACH ROW EXECUTE FUNCTION prevent_stage13_disposal_event_mutation();

-- 零长度或倒挂的有效期说不清"哪段时间是被授权的"；两列可同时为 NULL（尚未审批），只在都有值时校验。
ALTER TABLE disposal_authorization DROP CONSTRAINT IF EXISTS ck_stage13_authorization_window;
ALTER TABLE disposal_authorization ADD CONSTRAINT ck_stage13_authorization_window
    CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from);
