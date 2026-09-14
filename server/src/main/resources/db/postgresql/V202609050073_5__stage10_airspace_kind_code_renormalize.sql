-- 阶段 10（决策 10-13）：在 074 收紧 kind_code CHECK 之前，把 065 之后又被旧版种子写入的历史写法再归一一次。
--
-- 为什么还需要一次：065 只归一了它执行那一刻的存量行；而阶段 7 种子在阶段 10 之前一直写 HEIGHT_LIMIT / TEMPORARY，
-- 任何"先建库（跑过 065）、后跑种子"的库都会重新出现旧值——本地联调库与各验收库全部如此。
-- 074 只重建 CHECK、不含 UPDATE（决策 10-5），遇到旧值行会以 "violated by some row" 失败；没有这一步，每个已有库都起不来。
--
-- 为什么放在 db/postgresql 且版本号取 073.5：只有 PostgreSQL 有阶段 3/9 的不可变/接替触发器需要临时摘掉，
-- H2 测试库迁移先于种子、不会有旧值行；版本号夹在 073 与 074 之间，保证先归一再收紧。
-- 这是与 065 同类的一次性历史订正，禁止把临时摘触发器的写法复制到任何业务迁移；
-- 之后所有写入路径（种子、POST /airspaces、导入）都只产生五值字典，不会再有第三次。
ALTER TABLE airspace_version DISABLE TRIGGER USER;
UPDATE airspace_version SET kind_code = 'ALTITUDE_LIMIT'    WHERE kind_code = 'HEIGHT_LIMIT';
UPDATE airspace_version SET kind_code = 'TEMPORARY_CONTROL' WHERE kind_code = 'TEMPORARY';
ALTER TABLE airspace_version ENABLE TRIGGER USER;
