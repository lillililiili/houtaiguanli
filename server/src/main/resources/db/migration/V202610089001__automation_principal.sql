-- 自动规则的发起人随安装建好（决策：自动反制记录写"系统自动发起"，不写审批人）。
-- 过去只有开发种子 LocalAlarmFlowRuleSeeder 建这个账号；验收与正式库关闭种子后没有它，
-- AlarmRuleCounter 找不到发起人直接返回：反制规则配好了也永远不会自动发起，页面上也没有任何提示。
-- 取值与种子一致：角色不授予任何菜单和操作权限；账号停用，登录在校验密码前就被拒绝；
-- 密码哈希是随机口令的 BCrypt 串，口令生成后即丢弃，没有人知道。
-- 种子已经建过的库不重复插入、不改原值；名称或账号被别的记录占用时换一个，不让迁移失败而起不来。
-- 空库判断（InitialAdminBootstrap、SuperAdminIntegrityInitializer）不把这个账号算作已有账号。
INSERT INTO app_role (role_code, name, description, builtin, enabled, created_at, updated_at, version, system_role)
SELECT 'ROLE-AUTOMATION',
       CASE WHEN EXISTS (SELECT 1 FROM app_role WHERE name = '自动规则') THEN '自动规则（系统）' ELSE '自动规则' END,
       '只作为自动规则发起人，不授予菜单和操作权限', FALSE, TRUE, 1791302400000, 1791302400000, 0, FALSE
WHERE NOT EXISTS (SELECT 1 FROM app_role WHERE role_code = 'ROLE-AUTOMATION');

INSERT INTO app_user (user_id, account, name, role_code, status, password_hash, fail_count, scope_mode,
                      must_change_password, permission_version, created_at, updated_at, version)
SELECT 'automation-rule-runner',
       CASE WHEN EXISTS (SELECT 1 FROM app_user WHERE account = 'automation-rule')
            THEN 'automation-rule-runner' ELSE 'automation-rule' END,
       '自动规则', 'ROLE-AUTOMATION', 'DISABLED',
       '$2a$10$UzaqXHFA3Lo0kREEqHyyaeKyMFbhvBW59aJvsNr/xQeVkp4QoSWo6', 0, 'ALL',
       FALSE, 0, 1791302400000, 1791302400000, 0
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE user_id = 'automation-rule-runner')
  AND NOT (EXISTS (SELECT 1 FROM app_user WHERE account = 'automation-rule')
           AND EXISTS (SELECT 1 FROM app_user WHERE account = 'automation-rule-runner'));
