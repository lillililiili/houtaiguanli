-- 通知对象配置页已撤回。保留权限行和历史授权，只取消菜单入口。
UPDATE app_permission
SET route_key = NULL
WHERE permission_code = 'notificationSettings'
   OR route_key = 'notificationSettings';
