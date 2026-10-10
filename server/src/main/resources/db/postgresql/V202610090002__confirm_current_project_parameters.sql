-- 2026-10-09: user confirmed the project's CURRENT parameter values, with later workflow decisions retained.
-- Document: 规则与做法确认书-新版-2026-10-08.docx
-- SHA-256: 4cec49368ba934683290bef91686d6227b6a4d94444f3e4aa194f653a34c5dd5
-- Publish immutable copies only. Activation must use the existing authenticated, versioned, audited API.
-- Do not infer approval for unrelated/custom parameters from whatever version happens to be active.
DO $$
DECLARE
  entry RECORD;
  base RECORD;
  next_number INTEGER;
BEGIN
  FOR entry IN SELECT * FROM (VALUES
    ('legality-confirmed-20261008','legality-current-20261009'),
    ('space-risk-confirmed-20261008','space-current-20261009')
  ) AS versions(base_id,new_id)
  LOOP
    SELECT * INTO STRICT base FROM rule_set_version WHERE rule_set_version_id=entry.base_id;
    SELECT COALESCE(MAX(version_no),0)+1 INTO next_number FROM rule_set_version WHERE rule_set_id=base.rule_set_id;
    INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,
      valid_to,description,source_mode,created_at,published_at)
    VALUES(entry.new_id,base.rule_set_id,next_number,'PUBLISHED','CONFIRMED',CURRENT_TIMESTAMP,NULL,
      '2026-10-09用户确认按项目现用参数执行；依据《规则与做法确认书-新版-2026-10-08》及本轮确认。保持现用数值、类型、单位与成员状态，不改变后续已确认的超视距提示、通知、设备检查和页面流程。无计划参数沿用现用ILLEGAL，既有低空豁免保留；不采用旧r2的LEGAL配置。旧版本、研判及冻结材料不回填。SHA-256:4cec49368ba934683290bef91686d6227b6a4d94444f3e4aa194f653a34c5dd5',
      'live',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

    INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled)
      SELECT entry.new_id,rule_version_id,priority,enabled FROM rule_set_member WHERE rule_set_version_id=entry.base_id;

    -- The previous confirmed catalogue contains the same numeric defaults, plus eight versioned policy
    -- switches absent from the currently adopted v1. Do not silently opt into those switches here.
    INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)
      SELECT entry.new_id||':'||rule_code||':'||param_key,entry.new_id,rule_code,param_key,value_text,value_type,unit,'CONFIRMED',
        '2026-10-09用户确认按项目现用参数执行；新版确认书及本轮确认。数值沿用现用版本，不改变后续已确认做法；确认不代表真实设备能力或统计准确率已经验收。'
      FROM rule_param WHERE rule_set_version_id=entry.base_id
        AND (rule_code,param_key) NOT IN (
          ('C01','distance_basis'),('C01','time_window_end_inclusive'),
          ('C02-3','distance_basis'),('C02-4','grace_end_inclusive'),
          ('C03','quality_window_basis'),('C03','own_plan_time_mismatch_policy'),
          ('C06','auto_close_basis'),('C06','upgrade_window_basis'));
  END LOOP;
END $$;
