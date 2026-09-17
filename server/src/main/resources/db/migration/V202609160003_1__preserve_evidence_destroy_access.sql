-- 保留人工销毁既有访问动作，补充预览/缩略图不能收窄旧审计枚举。
ALTER TABLE evidence_access_log DROP CONSTRAINT ck_evidence_access_action;
ALTER TABLE evidence_access_log ADD CONSTRAINT ck_evidence_access_action
CHECK (action IN ('VIEW','DOWNLOAD','VERIFY','EXPORT','DESTROY','PREVIEW','THUMBNAIL'));
