-- 2026-10-07 用户确认：飞行计划在页面上统一称为“飞行任务”。只改显示文字，编码、接口和数据结构不变；
-- 仅替换仍为原内置文字的行，管理员已改过的名称保持原样。
UPDATE app_permission SET module_name = '飞行任务' WHERE module_name = '飞行计划';
UPDATE app_permission SET name = '查看飞行任务' WHERE permission_code = 'flight:read' AND name = '查看飞行计划';
UPDATE app_permission SET name = '核实任务执行' WHERE permission_code = 'flight:verify' AND name = '核实计划执行';
UPDATE app_role SET description = '飞行任务、合法性、空域与风险研判'
 WHERE role_code = 'ROLE-JUDGE' AND description = '飞行计划、合法性、空域与风险研判';
UPDATE penalty_rule SET title = '超出任务高度飞行' WHERE rule_code = 'PR-04' AND title = '超出计划高度飞行';
UPDATE rule_param SET note = '演示值：任务时间窗前后放宽'
 WHERE rule_param_id = 'space-risk-demo-v1:C04:plan_window_pad_min' AND note = '演示值：计划时间窗前后放宽';
UPDATE integration_source SET name = '数据模拟器（飞行任务）'
 WHERE source_id = 'local-flight-plan-simulator' AND name = '数据模拟器（飞行计划）';
