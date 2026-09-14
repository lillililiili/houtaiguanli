-- 阶段 4 只登记动作目录，不给任何生产角色默认授权，避免迁移后静默扩大告警和风险可见范围。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('alarm:verify', 'Stage 4 alarm verification', NULL, 920, 'alarm', 'ACTION', 'verify', 'Verify UAV events'),
    ('risk:read', 'Stage 4 risk read', NULL, 921, 'risk', 'ACTION', 'read', 'Read flight risks'),
    ('risk:verify', 'Stage 4 risk verification', NULL, 922, 'risk', 'ACTION', 'verify', 'Verify flight risks');
