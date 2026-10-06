-- 演示角色授权补正（2026-10-06，BUG-02 / OBS-03）。
-- 只按角色码补授已存在的本地演示角色，只升不降；没有演示角色的库（例如只有超级管理员的验收库）不受影响。
-- LocalDemoRolesSeeder 同步声明同一组授权，新建的演示库与既有库结果一致。
-- 1) 处置授权人执行已批准的设备反制：需要 disposal:execute 与 devices.op（决策 13-9）；设备管理只给数据权限、不开菜单。
--    执行已批准的申请不需要直接反制，按 2026-09-17 规则不授 disposal:direct。
-- 2) 设备运维办理运维待办：开始、提交核验、完成需要 monitoring.op；接入调测与设备参数需要 commissioning.op、devices.op。
-- 3) 值班员查看态势页设备事件需要 monitoring.read（不开菜单）；查看证据图片与视频需要 evidence:preview。
-- 授权变化后递增这些账号的权限版本，旧会话按 permission_version 失效，重新登录后生效。

INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled, created_at)
SELECT r.role_code, p.permission_code, 'OP', FALSE, CURRENT_TIMESTAMP
FROM app_role r
JOIN app_permission p ON p.permission_code IN ('disposal:execute', 'devices')
WHERE r.role_code = 'ROLE-DEMO-AUTH'
  AND NOT EXISTS (SELECT 1 FROM app_role_permission rp
                  WHERE rp.role_code = r.role_code AND rp.permission_code = p.permission_code);

INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled, created_at)
SELECT r.role_code, p.permission_code, 'OP', FALSE, CURRENT_TIMESTAMP
FROM app_role r
JOIN app_permission p ON p.permission_code IN ('devices', 'commissioning', 'monitoring')
WHERE r.role_code = 'ROLE-DEMO-OPS'
  AND NOT EXISTS (SELECT 1 FROM app_role_permission rp
                  WHERE rp.role_code = r.role_code AND rp.permission_code = p.permission_code);

INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled, created_at)
SELECT r.role_code, p.permission_code, 'READ', FALSE, CURRENT_TIMESTAMP
FROM app_role r
JOIN app_permission p ON p.permission_code IN ('monitoring', 'evidence:preview')
WHERE r.role_code = 'ROLE-DEMO-DUTY'
  AND NOT EXISTS (SELECT 1 FROM app_role_permission rp
                  WHERE rp.role_code = r.role_code AND rp.permission_code = p.permission_code);

UPDATE app_role_permission SET permission_level = 'OP'
WHERE permission_level IN ('NONE', 'READ')
  AND ((role_code = 'ROLE-DEMO-AUTH' AND permission_code IN ('disposal:execute', 'devices'))
    OR (role_code = 'ROLE-DEMO-OPS' AND permission_code IN ('devices', 'commissioning', 'monitoring')));

UPDATE app_role_permission SET permission_level = 'READ'
WHERE permission_level = 'NONE'
  AND role_code = 'ROLE-DEMO-DUTY' AND permission_code IN ('monitoring', 'evidence:preview');

UPDATE app_user SET permission_version = permission_version + 1
WHERE role_code IN ('ROLE-DEMO-AUTH', 'ROLE-DEMO-OPS', 'ROLE-DEMO-DUTY');
