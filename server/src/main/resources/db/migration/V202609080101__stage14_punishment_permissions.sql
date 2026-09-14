-- 阶段 14：处罚案件域的动作权限目录。只登记，不给任何非超级管理员角色默认授权（决策 14-13）。
-- 领导先落这一支：超级管理员种子会按 PermissionCode 枚举给 ROLE-ADMIN 授权，目录行必须先于代码枚举存在。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('punishment:read', 'Stage 14 punishment read', NULL, 975, 'punishment', 'ACTION', 'read', 'Read punishment cases, discretions, decision documents and reviews'),
    ('punishment:file', 'Stage 14 punishment file', NULL, 976, 'punishment', 'ACTION', 'file', 'File a punishment case from a punishment handoff, assign an officer, record leads'),
    ('punishment:decide', 'Stage 14 punishment decide', NULL, 977, 'punishment', 'ACTION', 'decide', 'Draft and confirm penalty discretion, issue or revoke decision documents'),
    ('punishment:review', 'Stage 14 punishment review', NULL, 978, 'punishment', 'ACTION', 'review', 'Review the case basis and record missing leads'),
    ('punishment:close', 'Stage 14 punishment close', NULL, 979, 'punishment', 'ACTION', 'close', 'Close or withdraw a punishment case');
