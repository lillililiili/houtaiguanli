-- 处罚交接记下是怎么建立的（2026-10-06）：干扰完成后后台自动建立（JAMMING_COMPLETED），
-- 或者启用了多个处罚接收单位、系统不替人选择时，由有权限的人选定接收单位后提交（MANUAL）。
-- 告警处置进度据此写明“已自动移送”还是“已人工移送”。风险通知不填。
ALTER TABLE handoff ADD COLUMN trigger_source VARCHAR(32);
ALTER TABLE handoff ADD CONSTRAINT ck_handoff_trigger_source
    CHECK (trigger_source IS NULL OR trigger_source IN ('JAMMING_COMPLETED', 'MANUAL'));

-- 旧记录按审计补记：后台账号 AUTO_PUNISHMENT 建立的记为自动，登录人员建立的记为人工；
-- 查不到审计的（例如本地演示种子）留空，页面只写“已移送到处罚”，不猜是谁发起的。
UPDATE handoff SET trigger_source = 'JAMMING_COMPLETED'
WHERE handoff_type = 'UAV_PUNISHMENT' AND trigger_source IS NULL AND handoff_id IN (
    SELECT object_id FROM audit_log
    WHERE account = 'AUTO_PUNISHMENT' AND user_id IS NULL AND action = 'handoff_created' AND object_type = 'handoff');
UPDATE handoff SET trigger_source = 'MANUAL'
WHERE handoff_type = 'UAV_PUNISHMENT' AND trigger_source IS NULL AND handoff_id IN (
    SELECT object_id FROM audit_log
    WHERE user_id IS NOT NULL AND action = 'handoff_created' AND object_type = 'handoff');
