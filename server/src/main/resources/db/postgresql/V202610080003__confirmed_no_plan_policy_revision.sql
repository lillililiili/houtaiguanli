-- 2026-10-08 conversation supersedes the document's 2-2 and the NO_AUTHORIZATION example in 2-1:
-- no filed/matching plan alone is not illegal. When other applicable checks and evidence pass, it is LEGAL.
-- This does not confirm any alarm, notify anyone, authorize countermeasures, or rewrite historical results.
-- Publish an independent revision; activation remains an authenticated/audited management action.
DO $$
DECLARE
  base RECORD;
  next_number INTEGER;
  revision_id CONSTANT VARCHAR(36) := 'legality-confirmed-20261008-r2';
BEGIN
  SELECT * INTO STRICT base FROM rule_set_version WHERE rule_set_version_id='legality-confirmed-20261008';
  SELECT COALESCE(MAX(version_no),0)+1 INTO next_number FROM rule_set_version WHERE rule_set_id=base.rule_set_id;
  INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,
    valid_to,description,source_mode,created_at,published_at)
  VALUES(revision_id,base.rule_set_id,next_number,'PUBLISHED','CONFIRMED',CURRENT_TIMESTAMP,base.valid_to,
    '2026-10-08本会话最新确认：没有报备/匹配计划本身不判非法；其他适用检查通过且证据充分时判合法，保留未匹配计划提示。覆盖原确认书2-2及2-1验收中的无授权判非法部分。仅修订C03.no_plan_status，其他已确认参数沿用；既有告警流程及历史不变。原文SHA-256:b6e07da1a820843ea2b3634fa64fe2cc08a043c3f4d9c7f8274c685506877566',
    base.source_mode,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
  INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled)
    SELECT revision_id,rule_version_id,priority,enabled FROM rule_set_member WHERE rule_set_version_id=base.rule_set_version_id;
  INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)
    SELECT 'confirmed-20261008-r2:'||rule_code||':'||param_key,revision_id,rule_code,param_key,
      CASE WHEN rule_code='C03' AND param_key='no_plan_status' THEN 'LEGAL' ELSE value_text END,
      value_type,unit,param_status,
      CASE WHEN rule_code='C03' AND param_key='no_plan_status'
        THEN '2026-10-08本会话最新确认：撤回原确认书2-2及2-1中无授权判非法部分。无报备/匹配计划本身不构成违规；其他适用检查通过且证据充分时判合法，并提示未匹配计划。告警核实、通知、反制和历史记录不变。'
        ELSE note END
    FROM rule_param WHERE rule_set_version_id=base.rule_set_version_id;
  -- Only the never-used preparation version is retired. Its parameters, members, provenance and description remain immutable.
  -- An already-used deployment retains its head/history; the new version can replace it through the existing activation API.
  UPDATE rule_set_version v SET status_code='RETIRED'
    WHERE v.rule_set_version_id=base.rule_set_version_id AND v.status_code='PUBLISHED'
      AND NOT EXISTS(SELECT 1 FROM rule_set s WHERE s.active_version_id=v.rule_set_version_id
        OR s.shadow_version_id=v.rule_set_version_id OR s.previous_active_version_id=v.rule_set_version_id)
      AND NOT EXISTS(SELECT 1 FROM rule_set_activation a WHERE a.from_version_id=v.rule_set_version_id OR a.to_version_id=v.rule_set_version_id)
      AND NOT EXISTS(SELECT 1 FROM rule_run r WHERE r.rule_set_version_id=v.rule_set_version_id);
END $$;
