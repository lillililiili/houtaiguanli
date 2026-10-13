-- User confirmation 2026-10-11: bird count affects severity, not spatial-risk eligibility.
-- Publish immutable definitions only; activate via the authenticated/audited rule-set API.
-- Base is the explicitly confirmed catalogue, not arbitrary deployment-specific active settings.
DO $$
DECLARE
  base RECORD;
  next_number INTEGER;
BEGIN
  SELECT * INTO STRICT base FROM rule_set_version
    WHERE rule_set_version_id='space-current-20261009';

  SELECT COALESCE(MAX(version_no),0)+1 INTO next_number FROM rule_version WHERE rule_code='C04';
  INSERT INTO rule_version(rule_version_id,rule_code,version_no,status_code,valid_from,
    source_mode,source_snapshot,created_at)
  VALUES('confirmed-20261011-C04','C04',next_number,'PUBLISHED',CURRENT_TIMESTAMP,'live',
    '{"confirmed_at":"2026-10-11","basis":"用户确认：鸟类风险按有效观测、航线距离、高度关系及任务时段判定；数量只参与分级，未知不按零处理、不阻断空间风险评估。","base_rule_set_version_id":"space-current-20261009","history":"旧规则、风险、核验、通知及冻结材料不回填"}'::JSONB,
    CURRENT_TIMESTAMP);

  SELECT COALESCE(MAX(version_no),0)+1 INTO next_number FROM rule_set_version
    WHERE rule_set_id=base.rule_set_id;
  INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,
    valid_from,description,source_mode,created_at,published_at)
  VALUES('space-bird-spatial-20261011',base.rule_set_id,next_number,'PUBLISHED','CONFIRMED',CURRENT_TIMESTAMP,
    '2026-10-11用户确认：鸟类按有效观测、航线距离、高度关系和任务时段评估空间风险；鸟类数量未知或不足20只不阻断评估。可信数量达到20只仍可上调一级；数量未知保留说明。沿用现用空间距离、AGL高度带、时间窗口和趋势规则，C05保持。旧规则与历史结果保留。',
    'live',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);

  INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled)
    SELECT 'space-bird-spatial-20261011',
      CASE WHEN v.rule_code='C04' THEN 'confirmed-20261011-C04' ELSE m.rule_version_id END,
      m.priority,m.enabled
    FROM rule_set_member m JOIN rule_version v ON v.rule_version_id=m.rule_version_id
    WHERE m.rule_set_version_id=base.rule_set_version_id;

  INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)
    SELECT 'space-bird-spatial-20261011:'||rule_code||':'||param_key,'space-bird-spatial-20261011',
      rule_code,param_key,value_text,value_type,unit,param_status,
      CASE WHEN rule_code='C04' AND param_key='flock_count_threshold'
        THEN '2026-10-11用户确认：可信鸟类数量达到本门槛可上调风险等级，不再作为产生空间风险的必要条件；数量未知不按零处理。'
        ELSE note END
    FROM rule_param WHERE rule_set_version_id=base.rule_set_version_id;

  INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)
  VALUES('space-bird-spatial-20261011:C04:flock_count_required','space-bird-spatial-20261011',
    'C04','flock_count_required','false','BOOLEAN',NULL,'CONFIRMED',
    '2026-10-11用户确认：鸟类数量仅参与风险分级；false表示空间风险评估不以数量达标为前提。历史版本缺此参数时继续沿用原数量门槛。');
END $$;
