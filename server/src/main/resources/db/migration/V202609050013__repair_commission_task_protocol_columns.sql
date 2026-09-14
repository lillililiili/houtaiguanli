-- 上游 V202609040007 在本仓库重编号为 V202609050013（版本号与本地 V202609040006 冲突）。
-- V202609040003 已登记成功，但本库 commission_task 未落到协议冻结列。
-- 列表/创建调测任务会查询这些列，缺失时接口返回 INTERNAL_ERROR。
-- 新库执行 IF NOT EXISTS 为无操作。

ALTER TABLE commission_task ADD COLUMN IF NOT EXISTS protocol_code VARCHAR(64);
ALTER TABLE commission_task ADD COLUMN IF NOT EXISTS protocol_configuration_json TEXT;
ALTER TABLE commission_task ADD COLUMN IF NOT EXISTS allowed_cidrs_snapshot VARCHAR(1000);
ALTER TABLE commission_task ADD COLUMN IF NOT EXISTS source_credential_ref_snapshot VARCHAR(256);
