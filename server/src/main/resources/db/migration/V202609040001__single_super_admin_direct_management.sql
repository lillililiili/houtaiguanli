UPDATE app_role
SET name = '超级管理员',
    description = '唯一内置角色，拥有全部菜单、数据范围和操作权限',
    builtin = TRUE,
    enabled = TRUE,
    updated_at = 0,
    version = version + 1
WHERE role_code = 'ROLE-ADMIN';

UPDATE app_role_permission
SET permission_level = 'AUTH',
    menu_enabled = CASE
        WHEN permission_code IN (SELECT permission_code FROM app_permission WHERE route_key IS NOT NULL) THEN TRUE
        ELSE FALSE
    END
WHERE role_code = 'ROLE-ADMIN';

UPDATE app_role
SET name = CASE role_code
        WHEN 'ROLE-AUTH' THEN '迁移角色-处置授权人'
        WHEN 'ROLE-DUTY' THEN '迁移角色-值班员'
        WHEN 'ROLE-JUDGE' THEN '迁移角色-研判员'
        WHEN 'ROLE-OPS' THEN '迁移角色-设备运维'
        WHEN 'ROLE-AUDIT' THEN '迁移角色-审计员'
    END,
    description = '由旧版内置角色迁移而来，请由超级管理员重新核对权限',
    builtin = FALSE,
    updated_at = 0,
    version = version + 1
WHERE role_code IN ('ROLE-AUTH', 'ROLE-DUTY', 'ROLE-JUDGE', 'ROLE-OPS', 'ROLE-AUDIT')
  AND EXISTS (SELECT 1 FROM app_user u WHERE u.role_code = app_role.role_code);

UPDATE app_role_permission
SET permission_level = 'NONE', menu_enabled = FALSE
WHERE role_code <> 'ROLE-ADMIN'
  AND permission_code IN ('users', 'roles', 'audit', 'countermeasure');

DELETE FROM pending_user_registration;

UPDATE access_change_request
SET status = 'REJECTED',
    review_comment = '审批流程已取消，申请未执行',
    reviewed_at = requested_at,
    version = version + 1
WHERE status = 'PENDING';

DELETE FROM app_role_permission
WHERE role_code IN ('ROLE-AUTH', 'ROLE-DUTY', 'ROLE-JUDGE', 'ROLE-OPS', 'ROLE-AUDIT')
  AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.role_code = app_role_permission.role_code);

DELETE FROM app_role
WHERE role_code IN ('ROLE-AUTH', 'ROLE-DUTY', 'ROLE-JUDGE', 'ROLE-OPS', 'ROLE-AUDIT')
  AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.role_code = app_role.role_code);
