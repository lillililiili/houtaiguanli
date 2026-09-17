-- 预览与原件下载独立授权；仅注册动作，绝不自动扩大现有角色的证据访问权。
INSERT INTO app_permission (permission_code,module_name,route_key,sort_order,module_code,permission_kind,action_code,name)
VALUES ('evidence:preview','证据',NULL,966,'evidence','ACTION','preview','预览证据');
ALTER TABLE evidence_access_log DROP CONSTRAINT ck_evidence_access_action;
ALTER TABLE evidence_access_log ADD CONSTRAINT ck_evidence_access_action
CHECK (action IN ('VIEW','DOWNLOAD','VERIFY','EXPORT','PREVIEW','THUMBNAIL'));
