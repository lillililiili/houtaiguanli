-- 阶段 13：处置授权域的 PostgreSQL 专属约束（H2 测试不加载本目录）。
-- 可重复迁移（R__）：Flyway 在校验和变化时重跑，因此这里的每一条都必须可重复执行。

-- 事件流只增。一次反制/干扰的全过程是有法律后果的事实链：谁批的、几点执行的、设备回了什么，
-- 都可能成为处罚案件的证据。改一行等于改口供，所以在库层堵死，而不是只靠应用层自觉。
CREATE OR REPLACE FUNCTION prevent_stage13_disposal_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'disposal authorization events are append-only' USING ERRCODE = '23514';
END;
$$;

-- 触发器与 CHECK 不在这里建：R__ 只在校验和变化时重跑，一个先停在中间版本（表未建）再前进到 0102 的库，
-- 会把本脚本记为"已应用"而永远不再补触发器——只增保证就静默丢了（审查第 13 轮 P1-1）。
-- 因此触发器与 CHECK 放在版本化的 PG 专属迁移 V202609070103__stage13_disposal_pg.sql（0102 之后只跑一次，不需守卫）；
-- 本脚本只保留无表依赖的函数定义；V202609070103 里也有一份同名 CREATE OR REPLACE FUNCTION（全新库上版本化先于 R__ 执行，
-- 挂触发器时函数必须已存在），两处都要保留，本脚本负责后续幂等刷新。0103 已在验收库应用，不改其内容（校验和）。
