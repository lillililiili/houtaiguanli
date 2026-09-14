-- 阶段 15（决策 15-24）：动作权限目录的 module_name/name 原本是英文开发描述
-- （'Stage 14 punishment read' / 'Read punishment cases, ...'），/permissions/actions 原样透出，
-- 直接上屏就是给一线人员看英文。这里逐码改成中文。
--
-- 中文取自前端 dongying-vue/src/ui/labels.js 的 ACTION_MODULE_LABEL / ACTION_CODE_LABEL——
-- 那份字典是页面当前实际显示的措辞，服务端换别的说法会让同一个码有两个中文。
-- 前端那两张表在服务端中文落地后可以退成兜底。
--
-- module_name 用动作域名（设备/告警/处置授权……），不用 MODULE 行的菜单分组名（飞行监管等）：
-- flight/route/airspace/assessment/risk/rule/airport 七个域都归在“飞行监管”下，
-- 用分组名会让权限编辑页出现七个同名分组，分不出哪个是哪个。
--
-- listActionCatalog() 用每个模块第一行的 module_name 当分组名，所以同模块各行必须一致。

UPDATE app_permission SET module_name = '设备',     name = '查看设备'         WHERE permission_code = 'device:read';
UPDATE app_permission SET module_name = '目标',     name = '查看目标'         WHERE permission_code = 'target:read';

UPDATE app_permission SET module_name = '告警',     name = '查看告警'         WHERE permission_code = 'alarm:read';
UPDATE app_permission SET module_name = '告警',     name = '核实无人机事件'   WHERE permission_code = 'alarm:verify';

UPDATE app_permission SET module_name = '飞行计划', name = '查看飞行计划'     WHERE permission_code = 'flight:read';
UPDATE app_permission SET module_name = '飞行计划', name = '登记飞行授权'     WHERE permission_code = 'flight:authorize';

UPDATE app_permission SET module_name = '航线',     name = '查看航线'         WHERE permission_code = 'route:read';

UPDATE app_permission SET module_name = '空域',     name = '查看空域'         WHERE permission_code = 'airspace:read';
UPDATE app_permission SET module_name = '空域',     name = '维护空域'         WHERE permission_code = 'airspace:manage';

UPDATE app_permission SET module_name = '合法性研判', name = '查看研判'       WHERE permission_code = 'assessment:read';
UPDATE app_permission SET module_name = '合法性研判', name = '发起研判'       WHERE permission_code = 'assessment:evaluate';
UPDATE app_permission SET module_name = '合法性研判', name = '修订研判结论'   WHERE permission_code = 'assessment:revise';
UPDATE app_permission SET module_name = '合法性研判', name = '上报研判'       WHERE permission_code = 'assessment:escalate';

UPDATE app_permission SET module_name = '飞行风险', name = '查看风险'         WHERE permission_code = 'risk:read';
UPDATE app_permission SET module_name = '飞行风险', name = '核验风险'         WHERE permission_code = 'risk:verify';
UPDATE app_permission SET module_name = '飞行风险', name = '触发风险评估'     WHERE permission_code = 'risk:evaluate';

UPDATE app_permission SET module_name = '工作台',   name = '查看工作台'       WHERE permission_code = 'workbench:read';

UPDATE app_permission SET module_name = '业务交接', name = '查看交接'         WHERE permission_code = 'handoff:read';
UPDATE app_permission SET module_name = '业务交接', name = '提交交接'         WHERE permission_code = 'handoff:create';

UPDATE app_permission SET module_name = '规则引擎', name = '查看规则集'       WHERE permission_code = 'rule:read';
UPDATE app_permission SET module_name = '规则引擎', name = '维护规则集'       WHERE permission_code = 'rule:manage';

UPDATE app_permission SET module_name = '融合感知', name = '查看融合结果'     WHERE permission_code = 'fusion:read';
UPDATE app_permission SET module_name = '融合感知', name = '人工修订类别'     WHERE permission_code = 'fusion:revise';
UPDATE app_permission SET module_name = '融合感知', name = '维护融合配置'     WHERE permission_code = 'fusion:manage';

UPDATE app_permission SET module_name = '证据',     name = '查看证据'         WHERE permission_code = 'evidence:read';
UPDATE app_permission SET module_name = '证据',     name = '证据入库'         WHERE permission_code = 'evidence:ingest';
UPDATE app_permission SET module_name = '证据',     name = '下载证据'         WHERE permission_code = 'evidence:download';
UPDATE app_permission SET module_name = '证据',     name = '关联证据'         WHERE permission_code = 'evidence:link';
UPDATE app_permission SET module_name = '证据',     name = '证据封存'         WHERE permission_code = 'evidence:hold';
UPDATE app_permission SET module_name = '证据',     name = '证据销毁'         WHERE permission_code = 'evidence:destroy';

UPDATE app_permission SET module_name = '机场',     name = '查看机场'         WHERE permission_code = 'airport:read';
UPDATE app_permission SET module_name = '机场',     name = '维护机场'         WHERE permission_code = 'airport:manage';

UPDATE app_permission SET module_name = '处置授权', name = '查看处置授权'     WHERE permission_code = 'disposal:read';
UPDATE app_permission SET module_name = '处置授权', name = '发起处置申请'     WHERE permission_code = 'disposal:request';
UPDATE app_permission SET module_name = '处置授权', name = '审批处置'         WHERE permission_code = 'disposal:approve';
UPDATE app_permission SET module_name = '处置授权', name = '执行处置'         WHERE permission_code = 'disposal:execute';
UPDATE app_permission SET module_name = '处置授权', name = '停止处置'         WHERE permission_code = 'disposal:stop';

UPDATE app_permission SET module_name = '处罚案件', name = '查看处罚案件'     WHERE permission_code = 'punishment:read';
UPDATE app_permission SET module_name = '处罚案件', name = '立案与调查'       WHERE permission_code = 'punishment:file';
UPDATE app_permission SET module_name = '处罚案件', name = '裁量与文书'       WHERE permission_code = 'punishment:decide';
UPDATE app_permission SET module_name = '处罚案件', name = '复核案件'         WHERE permission_code = 'punishment:review';
UPDATE app_permission SET module_name = '处罚案件', name = '结案与撤案'       WHERE permission_code = 'punishment:close';

-- 决策 15-25（修订）：动作行等级只允许 READ/OP，AUTH 是模块矩阵才有的"可授权他人"。
--
-- 升级库上这条**确实会改到行**：uav_stage10_verify 实测 42 行 AUTH 动作行，全属 ROLE-ADMIN。
-- 成因是 SuperAdminIntegrityInitializer(@Order 30) 每次启动把 ROLE-ADMIN 的授权行全置 AUTH，
-- 而 LocalStage2AccessSeeder(@Order 40) 在它之后才插入 42 条 READ 动作行——首次启动抹不到，
-- 第二次启动就抹到了。那条 UPDATE 已限定只作用于 MODULE 行（同一决策），这里把存量抹回来。
--
-- 新库不再产生这类行；API 一侧另有 ActionAssignment 的 @Pattern(NONE|READ|OP) 兜着。
UPDATE app_role_permission SET permission_level = 'OP'
WHERE permission_level = 'AUTH'
  AND permission_code IN (SELECT permission_code FROM app_permission WHERE permission_kind = 'ACTION');
