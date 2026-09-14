-- 阶段 9：airspace_version.kind_code 历史写法归一（PostgreSQL 专属；H2 测试不加载本目录）。
--
-- 为什么单独成一个 PG 迁移：
-- 阶段 3/7 的种子写过 HEIGHT_LIMIT 与 TEMPORARY 两个同义写法，决策 9-3 要求收敛到固定字典。
-- 但 airspace_version 上有阶段 3 的 trg_stage3_airspace_version_immutable，禁止一切 UPDATE；
-- 该触发器建在 R__stage3_airspace_spatial_constraints_and_indexes.sql 里，而 Flyway 先执行全部
-- 版本迁移、再执行可重复迁移。因此：
--   * 全新库：版本迁移阶段触发器还不存在，UPDATE 能过；
--   * 已启动过的库：触发器已在，UPDATE 被拒，整个迁移回滚，服务起不来。
-- 归一是一次性的历史数据订正，不是业务写入，所以在本迁移内临时摘掉表上的用户触发器再恢复。
-- 用 DISABLE TRIGGER USER 而不是按名字点名，是为了同时覆盖“触发器还不存在（全新库）”和
-- “已经被 R__stage9 换成 trg_stage9_airspace_version_succession（重复升级）”两种情况。

ALTER TABLE airspace_version DISABLE TRIGGER USER;

UPDATE airspace_version SET kind_code = 'ALTITUDE_LIMIT'   WHERE kind_code = 'HEIGHT_LIMIT';
UPDATE airspace_version SET kind_code = 'TEMPORARY_CONTROL' WHERE kind_code = 'TEMPORARY';

ALTER TABLE airspace_version ENABLE TRIGGER USER;
