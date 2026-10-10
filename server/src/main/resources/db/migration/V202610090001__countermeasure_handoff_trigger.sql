-- 单次反制正常结束且停止回执成功后，可沿用原处罚交接规则。
-- 旧触发来源与已经冻结的材料不回写。
ALTER TABLE handoff DROP CONSTRAINT ck_handoff_trigger_source;
ALTER TABLE handoff ADD CONSTRAINT ck_handoff_trigger_source
    CHECK (trigger_source IS NULL OR trigger_source IN ('JAMMING_COMPLETED', 'COUNTERMEASURE_COMPLETED', 'MANUAL'));
