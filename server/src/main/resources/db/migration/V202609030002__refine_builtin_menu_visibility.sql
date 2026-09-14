-- 值班角色保留审计模块的只读原子权限，用于服务端最小必要校验，
-- 但系统管理组不作为其工作入口；审计入口仅向管理员与审计员开放。
UPDATE app_role_permission
SET menu_enabled = FALSE
WHERE role_code = 'ROLE-DUTY'
  AND permission_code = 'audit'
  AND menu_enabled = TRUE;
