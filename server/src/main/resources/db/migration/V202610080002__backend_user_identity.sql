-- 后台账号使用不可编辑的固定身份；不根据存量自定义角色的权限推断或提升账号类型。
INSERT INTO app_role (role_code, name, description, builtin, enabled, created_at, updated_at, version)
VALUES ('ROLE-BACKEND', '后台用户', '全部后台功能，无需配置业务角色或菜单', TRUE, TRUE, 0, 0, 0);

-- 只授予后台功能及报表依赖的读取动作；不授予反制申请、审批、直接反制或执行。
INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled)
SELECT 'ROLE-BACKEND', permission_code, 'OP', FALSE
FROM app_permission
WHERE permission_kind = 'ACTION' AND permission_code IN (
    'device:read', 'target:read', 'alarm:read', 'flight:read', 'route:read', 'airspace:read',
    'assessment:read', 'risk:read', 'handoff:read', 'disposal:read', 'punishment:read',
    'evidence:read', 'rule:read', 'rule:manage', 'map:read', 'map:upload', 'map:activate', 'map:delete'
);
