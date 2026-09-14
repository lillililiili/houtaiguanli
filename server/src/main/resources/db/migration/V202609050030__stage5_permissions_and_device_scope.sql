-- 阶段 5 只登记动作目录，不给任何生产角色默认授权；工作台/交接可见范围由源模块读权限与精确元组共同决定。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('workbench:read', 'Stage 5 workbench read', NULL, 930, 'workbench', 'ACTION', 'read', 'Read workbench items'),
    ('handoff:read', 'Stage 5 handoff read', NULL, 931, 'handoff', 'ACTION', 'read', 'Read handoffs'),
    ('handoff:create', 'Stage 5 handoff create', NULL, 932, 'handoff', 'ACTION', 'create', 'Create handoffs');

-- 运维设备（ops_device）只有单位/区域名称，没有组织/区域 ID；业务范围必须显式映射，
-- 禁止按名称推断，也禁止与阶段 2 device 表按 ID 硬连。没有映射的设备不进入工作台和统计。
CREATE TABLE device_business_scope (
    ops_device_id       VARCHAR(36) PRIMARY KEY,
    owner_org_id        VARCHAR(36) NOT NULL,
    district_id         VARCHAR(36) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage5_device_scope_device FOREIGN KEY (ops_device_id) REFERENCES ops_device (device_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage5_device_scope_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage5_device_scope_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT
);

CREATE INDEX idx_stage5_device_scope_tuple ON device_business_scope (owner_org_id, district_id, ops_device_id);
