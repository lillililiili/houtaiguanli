-- Publish an independent candidate for existing legality sets. No active/shadow pointer is changed.
-- New execution parameters deliberately remain absent until business confirmation or isolated simulation.
DO $$
DECLARE base RECORD; candidate VARCHAR(36); next_number INTEGER;
BEGIN
  FOR base IN
    SELECT v.* FROM rule_set s JOIN rule_set_version v ON v.rule_set_version_id=s.active_version_id
    WHERE EXISTS (SELECT 1 FROM rule_set_member m JOIN rule_version r ON r.rule_version_id=m.rule_version_id
      WHERE m.rule_set_version_id=v.rule_set_version_id AND r.rule_code='C01' AND m.enabled)
  LOOP
    candidate := gen_random_uuid()::text;
    SELECT COALESCE(MAX(version_no),0)+1 INTO next_number FROM rule_set_version WHERE rule_set_id=base.rule_set_id;
    INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,
      valid_to,description,source_mode,created_at,published_at)
    VALUES(candidate,base.rule_set_id,next_number,'PUBLISHED','DEMO',CURRENT_TIMESTAMP,base.valid_to,
      '独立执行事实核对候选版本；参数待确认，须先影子验证',base.source_mode,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
    INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled)
      SELECT candidate,m.rule_version_id,m.priority,m.enabled FROM rule_set_member m JOIN rule_version r ON r.rule_version_id=m.rule_version_id
      WHERE m.rule_set_version_id=base.rule_set_version_id AND r.rule_code NOT IN ('C02-9','C02-10','C02-11','C02-12');
    INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled) VALUES
      (candidate,'rule-flight-takeoff-v1',289,TRUE),(candidate,'rule-flight-landing-v1',290,TRUE),
      (candidate,'rule-flight-pilot-v1',291,TRUE),(candidate,'rule-flight-reporting-v1',292,TRUE);
    INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)
      SELECT gen_random_uuid()::text,candidate,rule_code,param_key,value_text,value_type,unit,param_status,note
      FROM rule_param WHERE rule_set_version_id=base.rule_set_version_id AND rule_code NOT IN ('C02-9','C02-10','C02-11','C02-12');
  END LOOP;
END $$;
