-- 我的工作台入口已撤回。去掉角色授权和权限目录，会话不再带出 workbench:read。
-- 审计名称仍由应用字典保留，旧日志继续显示「工作台」。
DELETE FROM app_role_permission WHERE permission_code = 'workbench:read';
DELETE FROM app_permission WHERE permission_code = 'workbench:read';
