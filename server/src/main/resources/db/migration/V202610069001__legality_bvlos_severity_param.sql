-- 超视距（C02-6，原因码 BVLOS_EXCEEDED）不通过时会进入 C03 的违规原因，C03 评分要查 C03.severity.BVLOS_EXCEEDED。
-- 合法性规则集的演示版本一直缺这一项：研判直接报"参数缺失"失败，这架目标之后再也判不出来（验收问题 ZT-05）。
-- 这里给已有 C03 严重度目录、参数仍为 DEMO 的版本补这一行：演示值 0.3，与夜航同级，尚未经业务方确认。
-- 只补缺行、不改已有参数（已发布版本的参数不可改，追加缺项与种子目录的"只补缺行"一致）；
-- 已确认（CONFIRMED）的版本不擅自补演示值，缺项时 C03 按 0 计入评分，结论不受影响。
-- 新库在启动时由规则目录（LocalStage7RuleEngineSeeder.DEMO_PARAMS）带上同一项，本迁移只照顾已有库。
INSERT INTO rule_param (rule_param_id, rule_set_version_id, rule_code, param_key, value_text, value_type, unit, param_status, note)
SELECT v.rule_set_version_id || ':C03:severity.BVLOS_EXCEEDED', v.rule_set_version_id, 'C03', 'severity.BVLOS_EXCEEDED', '0.3', 'NUMBER', NULL,
       'DEMO', '演示参数，尚未经业务方确认'
  FROM rule_set_version v
 WHERE v.param_status = 'DEMO'
   AND EXISTS (SELECT 1 FROM rule_param p
                WHERE p.rule_set_version_id = v.rule_set_version_id AND p.rule_code = 'C03' AND p.param_key = 'severity.NO_AUTHORIZATION')
   AND NOT EXISTS (SELECT 1 FROM rule_param p
                    WHERE p.rule_set_version_id = v.rule_set_version_id AND p.rule_code = 'C03' AND p.param_key = 'severity.BVLOS_EXCEEDED');
