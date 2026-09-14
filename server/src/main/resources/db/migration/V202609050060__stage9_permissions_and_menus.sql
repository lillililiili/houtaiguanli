-- 阶段 9：飞行监管补齐与第二业务线的权限目录与菜单。只登记动作目录，不给任何非超级管理员角色默认授权。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('airspace:manage', 'Stage 9 airspace manage', NULL, 960, 'airspace', 'ACTION', 'manage', 'Create airspaces, add versions, import GeoJSON'),
    ('airport:read', 'Stage 9 airport read', NULL, 961, 'airport', 'ACTION', 'read', 'Read airport base data'),
    ('airport:manage', 'Stage 9 airport manage', NULL, 962, 'airport', 'ACTION', 'manage', 'Create airports, runways, procedure routes, protected and notification targets'),
    ('risk:evaluate', 'Stage 9 risk evaluate', NULL, 963, 'risk', 'ACTION', 'evaluate', 'Trigger C04/C05 space risk evaluation'),
    ('flight:authorize', 'Stage 9 flight authorize', NULL, 964, 'flight', 'ACTION', 'authorize', 'Record external flight plan authorizations');

-- 既有 MODULE 行 airspace / risk 从"并入飞行计划的别名"变为真实菜单：只补 route_key，不改任何角色的 menu_enabled，
-- 因此除超级管理员（自动拥有全部带 route_key 的菜单）外，其他角色要看到新菜单必须由管理员显式开启。
UPDATE app_permission SET route_key = 'airspace', module_name = '飞行监管', name = '空域与航线规则' WHERE permission_code = 'airspace';
UPDATE app_permission SET route_key = 'risk', module_name = '飞行监管', name = '空间安全风险' WHERE permission_code = 'risk';
