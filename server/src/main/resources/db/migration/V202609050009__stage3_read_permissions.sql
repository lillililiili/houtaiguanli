-- 这里只登记阶段 3 只读动作，不能在迁移中给生产角色默认授权，避免上线后意外扩大数据访问范围。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('flight:read', 'Stage 3 flight read', NULL, 904, 'flight', 'ACTION', 'read', 'Read flight plans'),
    ('route:read', 'Stage 3 route read', NULL, 905, 'route', 'ACTION', 'read', 'Read routes'),
    ('airspace:read', 'Stage 3 airspace read', NULL, 906, 'airspace', 'ACTION', 'read', 'Read airspaces'),
    ('assessment:read', 'Stage 3 assessment read', NULL, 907, 'assessment', 'ACTION', 'read', 'Read legality assessments');
