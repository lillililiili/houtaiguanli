-- 单位不再提供停用能力；恢复历史上被逻辑停用的存量单位。
UPDATE app_org
SET enabled = TRUE,
    version = version + 1
WHERE enabled = FALSE;
