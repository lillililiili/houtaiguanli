-- 阶段 13：处置授权域的动作权限目录。只登记，不给任何非超级管理员角色默认授权（决策 13-8：B 线迁移按实际日期编号）。
-- 领导先落这一支：超级管理员种子会按 PermissionCode 枚举给 ROLE-ADMIN 授权，目录行必须先于代码枚举存在。
-- 经协议 B 自动执行时，执行人还需协作者 A 的设备控制面权限 devices.op（决策 13-9）。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('disposal:read', 'Stage 13 disposal read', NULL, 970, 'disposal', 'ACTION', 'read', 'Read disposal authorizations and events'),
    ('disposal:request', 'Stage 13 disposal request', NULL, 971, 'disposal', 'ACTION', 'request', 'Request countermeasure / jamming / dispersal / decoy authorization'),
    ('disposal:approve', 'Stage 13 disposal approve', NULL, 972, 'disposal', 'ACTION', 'approve', 'Approve or reject disposal authorizations'),
    ('disposal:execute', 'Stage 13 disposal execute', NULL, 973, 'disposal', 'ACTION', 'execute', 'Execute an approved disposal authorization or record a manual result'),
    ('disposal:stop', 'Stage 13 disposal stop', NULL, 974, 'disposal', 'ACTION', 'stop', 'Stop a disposal and withdraw its authorization');
