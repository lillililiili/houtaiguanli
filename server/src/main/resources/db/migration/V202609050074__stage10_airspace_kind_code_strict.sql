-- 阶段 10：airspace_version.kind_code 的 CHECK 收紧为 AirspaceKind 的五值字典（决策 10-2 / 10-5）。
--
-- 只做 DROP CONSTRAINT + 重建同名 CHECK，不含任何 UPDATE：
--   * PostgreSQL 上 airspace_version 带接替式触发器（R__stage9_airspace_succession），它拒绝任何非 0 行的 UPDATE，
--     迁移里的归一 UPDATE 在已有旧值行的库上会被触发器打回，整条迁移回滚；
--   * PG 的存量行已由 db/postgresql/V202609050065 归一（该迁移临时摘触发器只做过一次），这里不需要再改行；
--   * H2 只在测试里用，且 Flyway 迁移先于全部种子执行，库里不可能有旧值行；阶段 7 种子自 10.2 起只写五值。
-- 若某个库仍有 HEIGHT_LIMIT / TEMPORARY 行，ADD CONSTRAINT 会以 PostgreSQL 自带的
-- "check constraint ... is violated by some row" 明确失败，不会静默放过：运维按 V202609050065 的方式
-- （临时 DISABLE TRIGGER USER → 两条 UPDATE 归一 → ENABLE）处理后重跑迁移即可。
-- H2 与 PostgreSQL 同一句 SQL；两者的 Flyway 都从 db/migration 加载本文件。
ALTER TABLE airspace_version DROP CONSTRAINT ck_stage9_airspace_kind_code;
ALTER TABLE airspace_version ADD CONSTRAINT ck_stage9_airspace_kind_code CHECK (
    kind_code IN ('PROHIBITED', 'RESTRICTED', 'ALTITUDE_LIMIT', 'PERMITTED', 'TEMPORARY_CONTROL')
);
