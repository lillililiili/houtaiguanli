-- Publish configuration only; never change active/shadow/previous pointers or create business facts.
-- The historical default display name becomes neutral; custom names and all version inputs are preserved.
-- Rule definitions are PUBLISHED, not ACTIVE. Engine selection remains gated by rule_set.active_version_id.
-- Activation is a separate authenticated/audited management API action.
-- Confirmation: 规则与做法确认书（含验收方法）-2026-10-08 .docx, II:2-1..2-13 and IV:4-1..4-6.
-- SHA-256: b6e07da1a820843ea2b3634fa64fe2cc08a043c3f4d9c7f8274c685506877566
DO $$
DECLARE
  legality_set VARCHAR(36);
  space_set VARCHAR(36);
  next_number INTEGER;
  entry RECORD;
  evidence JSONB := '{"document":"规则与做法确认书（含验收方法）-2026-10-08 .docx","sha256":"b6e07da1a820843ea2b3634fa64fe2cc08a043c3f4d9c7f8274c685506877566","confirmed_sections":["II:2-1..2-13","IV:4-1..4-6"],"scope":"客户确认业务规则及列明参数；未列出的实现系数沿用既有实现，未单独逐值确认","supplement":"2026-10-08本会话确认：4-1沿用现有AGL分层，低于150米为爬升高度带、150至不足300米为进近高度带，无新增阶段字段"}'::JSONB;
BEGIN
  SELECT rule_set_id INTO legality_set FROM rule_set WHERE rule_set_code='LEGALITY-DEMO';
  IF legality_set IS NULL THEN
    -- Established catalogue identity remains compatible with the optional local QA catalog.
    legality_set := 'seed-stage7-ruleset';
    INSERT INTO rule_set(rule_set_id,rule_set_code,name,version,created_at,updated_at)
      VALUES(legality_set,'LEGALITY-DEMO','合法性研判规则集',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
  END IF;
  UPDATE rule_set SET name='合法性研判规则集'
    WHERE rule_set_id=legality_set AND name='合法性研判演示规则集';
  SELECT rule_set_id INTO space_set FROM rule_set WHERE rule_set_code='SPACE-RISK-DEMO';
  IF space_set IS NULL THEN
    space_set := 'space-risk-demo';
    INSERT INTO rule_set(rule_set_id,rule_set_code,name,version,created_at,updated_at)
      VALUES(space_set,'SPACE-RISK-DEMO','空间安全风险规则集',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
  END IF;
  -- Freeze provenance in independent definitions; reserve v1 for the legacy QA definitions on a clean database.
  FOR entry IN SELECT * FROM (VALUES
    ('C01',100),('C02-1',210),('C02-2',220),('C02-3',230),('C02-4',240),
    ('C02-5',250),('C02-6',260),('C02-7',270),('C02-8',280),('C03',300),('C06',400),
    ('C04',10),('C05',20)) AS rules(code,priority)
  LOOP
    SELECT GREATEST(COALESCE(MAX(version_no),0)+1,2) INTO next_number FROM rule_version WHERE rule_code=entry.code;
    INSERT INTO rule_version(rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,source_snapshot,created_at)
      VALUES('confirmed-20261008-'||entry.code,entry.code,next_number,'PUBLISHED',CURRENT_TIMESTAMP,'live',evidence,CURRENT_TIMESTAMP);
  END LOOP;
  -- Reserve set v1/v2 for the unchanged QA catalog; this migration neither invokes nor enables seeders.
  SELECT GREATEST(COALESCE(MAX(version_no),0)+1,3) INTO next_number FROM rule_set_version WHERE rule_set_id=legality_set;
  INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at)
    VALUES('legality-confirmed-20261008',legality_set,next_number,'PUBLISHED','CONFIRMED',CURRENT_TIMESTAMP,
      '按《规则与做法确认书（含验收方法）-2026-10-08 .docx》第二部分2-1至2-13发布；确认业务方法及列明参数，未列明的评分系数沿用既有实现、未单独逐值确认。SHA-256:b6e07da1a820843ea2b3634fa64fe2cc08a043c3f4d9c7f8274c685506877566',
      'live',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
  SELECT COALESCE(MAX(version_no),0)+1 INTO next_number FROM rule_set_version WHERE rule_set_id=space_set;
  INSERT INTO rule_set_version(rule_set_version_id,rule_set_id,version_no,status_code,param_status,valid_from,description,source_mode,created_at,published_at)
    VALUES('space-risk-confirmed-20261008',space_set,next_number,'PUBLISHED','CONFIRMED',CURRENT_TIMESTAMP,
      '按《规则与做法确认书（含验收方法）-2026-10-08 .docx》第四部分4-1至4-6发布；C04/C05规则及参数已确认，4-1按2026-10-08本会话补充确认沿用AGL分层，设备检查沿用服务器配置。SHA-256:b6e07da1a820843ea2b3634fa64fe2cc08a043c3f4d9c7f8274c685506877566',
      'live',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
  INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled)
    SELECT 'legality-confirmed-20261008','confirmed-20261008-'||code,priority,TRUE
    FROM (VALUES ('C01',100),('C02-1',210),('C02-2',220),('C02-3',230),('C02-4',240),
      ('C02-5',250),('C02-6',260),('C02-7',270),('C02-8',280),('C03',300),('C06',400)) AS rules(code,priority);
  INSERT INTO rule_set_member(rule_set_version_id,rule_version_id,priority,enabled) VALUES
    ('space-risk-confirmed-20261008','confirmed-20261008-C04',10,TRUE),
    ('space-risk-confirmed-20261008','confirmed-20261008-C05',20,TRUE);
  INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)
    SELECT 'confirmed-20261008:'||code||':'||key,'legality-confirmed-20261008',code,key,value,type,unit,'CONFIRMED',
      '《规则与做法确认书（含验收方法）-2026-10-08 .docx》第二部分'||basis
    FROM (VALUES
      ('C01','time_window_min','10','INTEGER','min','2-1：计划前后10分钟'),
      ('C01','corridor_tolerance_m','100','NUMBER','m','2-1：距报备航线100米以内'),
      ('C01','distance_basis','ROUTE_CENTERLINE','STRING',NULL,'2-1：距离按报备航线中心线计算'),
      ('C01','time_window_end_inclusive','true','BOOLEAN',NULL,'2-1、2-7：10分钟内含恰好10分钟'),
      ('C02-1','kinds','PROHIBITED,RESTRICTED','LIST',NULL,'2-3：禁飞区与管制区'),
      ('C02-2','kinds','ALTITUDE_LIMIT','LIST',NULL,'2-4：限高区'),
      ('C02-3','tolerance_m','20','NUMBER','m','2-6：偏离报备航线超过20米'),
      ('C02-3','distance_basis','ROUTE_CENTERLINE','STRING',NULL,'2-6：距离按报备航线中心线计算'),
      ('C02-4','grace_min','10','INTEGER','min','2-7：计划结束后10分钟宽限'),
      ('C02-4','grace_end_inclusive','true','BOOLEAN',NULL,'2-7：超过10分钟才算超时'),
      ('C02-5','timezone','Asia/Shanghai','STRING',NULL,'2-9：北京时间'),
      ('C02-5','night_from','20','INTEGER','h','2-9：夜间开始20点'),
      ('C02-5','night_to','6','INTEGER','h','2-9：夜间结束6点'),
      ('C02-6','vlos_m','500','NUMBER','m','2-10：超过500米算超视距'),
      ('C02-8','kinds','TEMPORARY_CONTROL','LIST',NULL,'2-5：临时管控'),
      ('C03','fresh_seconds','120','INTEGER','s','2-11：最近120秒内数据'),
      ('C03','conf_min','0.75','NUMBER',NULL,'2-11：可信度至少75%'),
      ('C03','min_points','3','INTEGER',NULL,'2-11：至少3个轨迹点'),
      ('C03','gap_seconds','30','INTEGER','s','2-11：两点间隔不能超过30秒'),
      ('C03','quality_window_basis','AS_OF','STRING',NULL,'2-11：质量窗口以本次评估时刻为基准，回放按历史评估时刻'),
      ('C03','no_plan_status','ILLEGAL','STRING',NULL,'2-2：无报备计划判无飞行授权'),
      ('C03','own_plan_time_mismatch_policy','OVERRUN','STRING',NULL,'2-7、2-9：身份和航线明确匹配、仅超时不得冒充没有报备计划'),
      ('C03','ignore_undetermined_rules','C02-6','LIST',NULL,'2-10：没有遥控器位置不判超视距'),
      ('C03','grade.high','67','NUMBER',NULL,'2-12：67分及以上高风险'),
      ('C03','grade.medium','34','NUMBER',NULL,'2-12：34分及以上中风险'),
      ('C06','dedup_window_min','5','INTEGER','min','2-13：5分钟内并入原告警'),
      ('C06','upgrade_window_min','10','INTEGER','min','2-13：误报核实后10分钟内更严重违规新建告警'),
      ('C06','auto_close_min','15','INTEGER','min','2-13：违规停止15分钟结束本次合并窗口，不代替人工核实或实际飞离'),
      ('C06','auto_close_basis','LAST_HIT','STRING',NULL,'2-13：从最后一次违规命中计算停止时长'),
      ('C06','upgrade_window_basis','FALSE_POSITIVE_AT','STRING',NULL,'2-13：从误报核实时刻计算10分钟窗口')
    ) AS params(code,key,value,type,unit,basis);

  -- CONFIRMED qualifies the authorized method version; the following implementation coefficients were not individually confirmed.
  INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)
    SELECT 'confirmed-20261008:'||code||':'||key,'legality-confirmed-20261008',code,key,value,type,NULL,'CONFIRMED',
      '《规则与做法确认书（含验收方法）-2026-10-08 .docx》第二部分'||basis||'：客户确认业务方法；数值沿用既有实现系数，未单独确认。CONFIRMED表示本次方法版本可正式使用，不表示客户逐值确认。'
    FROM (VALUES
      ('C03','track_points','10','INTEGER','2-11'),
      ('C03','w.violation','0.40','NUMBER','2-12'),('C03','w.plan_match','0.25','NUMBER','2-12'),
      ('C03','w.airspace','0.15','NUMBER','2-12'),('C03','w.track','0.10','NUMBER','2-12'),('C03','w.confidence','0.10','NUMBER','2-12'),
      ('C03','severity.INSIDE_RESTRICTED_AIRSPACE','1.0','NUMBER','2-12'),
      ('C03','severity.AIRSPACE_ALTITUDE_EXCEEDED','0.9','NUMBER','2-12'),
      ('C03','severity.TEMPORARY_RESTRICTION_ACTIVE','0.9','NUMBER','2-12'),
      ('C03','severity.NO_AUTHORIZATION','0.8','NUMBER','2-12'),
      ('C03','severity.ROUTE_DEVIATION','0.6','NUMBER','2-12'),
      ('C03','severity.PLAN_ALTITUDE_EXCEEDED','0.5','NUMBER','2-12'),
      ('C03','severity.TIME_WINDOW_OVERRUN','0.4','NUMBER','2-12'),
      ('C03','severity.NIGHT_FLIGHT','0.3','NUMBER','2-12'),
      ('C06','severity_by_grade','HIGH:HIGH,MEDIUM:MEDIUM,LOW:LOW','LIST','2-12、2-13')
    ) AS params(code,key,value,type,basis);

  INSERT INTO rule_param(rule_param_id,rule_set_version_id,rule_code,param_key,value_text,value_type,unit,param_status,note)
    SELECT 'confirmed-20261008:'||code||':'||key,'space-risk-confirmed-20261008',code,key,value,type,unit,'CONFIRMED',
      '《规则与做法确认书（含验收方法）-2026-10-08 .docx》第四部分'||basis
    FROM (VALUES
      ('C04','corridor_near_m','300','NUMBER','m','4-1：离计划航线300米以内'),
      ('C04','climb_band_agl_m','150','NUMBER','m','4-1及2026-10-08本会话补充确认：AGL低于150米为爬升高度带，非实际飞行阶段'),
      ('C04','approach_band_agl_m','300','NUMBER','m','4-1及2026-10-08本会话补充确认：AGL150至不足300米为进近高度带，300米及以上不生成C04风险'),
      ('C04','flock_count_threshold','20','INTEGER','只','4-2：20只及以上算鸟群风险'),
      ('C04','trend_window_min','30','INTEGER','min','4-1：参考最近30分钟走向'),
      ('C04','plan_window_pad_min','15','INTEGER','min','4-1：计划前后15分钟'),
      ('C05','procedure_buffer_m','500','NUMBER','m','4-3：机场进离场航线500米以内'),
      ('C05','protected_target_pad_m','200','NUMBER','m','4-3：保护目标周边200米以内')
    ) AS params(code,key,value,type,unit,basis);
END $$;
