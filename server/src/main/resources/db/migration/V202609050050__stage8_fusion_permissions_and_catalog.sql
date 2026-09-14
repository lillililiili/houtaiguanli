-- 阶段 8 只登记动作目录，不给任何角色默认授权。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('fusion:read', 'Stage 8 fusion read', NULL, 950, 'fusion', 'ACTION', 'read', 'Read fusion config, lineage and metrics'),
    ('fusion:revise', 'Stage 8 fusion revise', NULL, 951, 'fusion', 'ACTION', 'revise', 'Revise target class, merge or split targets'),
    ('fusion:manage', 'Stage 8 fusion manage', NULL, 952, 'fusion', 'ACTION', 'manage', 'Activate fusion config versions');

-- 来源类型目录：只有雷达有协议资料，其余三路与融合箱为 Demo 字段；schema_status 让页面和文档能如实标注。
CREATE TABLE source_type_catalog (
    source_type         VARCHAR(16) PRIMARY KEY,
    display_name        VARCHAR(64) NOT NULL,
    schema_status       VARCHAR(16) NOT NULL,
    spec_ref            VARCHAR(256),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stage8_source_type_status CHECK (schema_status IN ('CONFIRMED', 'DEMO'))
);

INSERT INTO source_type_catalog (source_type, display_name, schema_status, spec_ref, created_at) VALUES
    ('RADAR', '雷达', 'CONFIRMED', '设备资料/雷达/低空监视雷达网络通信协议_v3.0.0.docx', CURRENT_TIMESTAMP),
    ('EO', '光电', 'DEMO', NULL, CURRENT_TIMESTAMP),
    ('TDOA', 'TDOA/AOA', 'DEMO', NULL, CURRENT_TIMESTAMP),
    ('FIVE_G_A', '5G-A 基站', 'DEMO', NULL, CURRENT_TIMESTAMP),
    ('FUSION_BOX', '融合感知箱', 'DEMO', NULL, CURRENT_TIMESTAMP);

-- 来源挂上类型；已有来源由种子/配置回填，生产未知类型保持 NULL 而不是猜。
ALTER TABLE integration_source ADD COLUMN source_type VARCHAR(16);
ALTER TABLE integration_source ADD CONSTRAINT fk_stage8_source_type
    FOREIGN KEY (source_type) REFERENCES source_type_catalog (source_type) ON DELETE RESTRICT;

-- 融合参数外置并带版本：demo-v1 全部为演示阈值（schema_status=DEMO），正式权重由算法方确认后另发版本。
CREATE TABLE fusion_config (
    config_version      VARCHAR(32) PRIMARY KEY,
    status              VARCHAR(16) NOT NULL,
    schema_status       VARCHAR(16) NOT NULL,
    params              JSON NOT NULL,
    note                VARCHAR(500),
    created_by          VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at        TIMESTAMP WITH TIME ZONE,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_stage8_fusion_config_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_stage8_fusion_config_schema CHECK (schema_status IN ('DEMO', 'CONFIRMED')),
    CONSTRAINT ck_stage8_fusion_config_version CHECK (version >= 0)
);

INSERT INTO fusion_config (config_version, status, schema_status, params, note, created_by, created_at, activated_at, version) VALUES (
    'demo-v1', 'ACTIVE', 'DEMO',
    '{"filter":{"alpha":0.6,"beta":0.25,"max_dt_ms":3000,"pred_max_frames":3,"bridge_max_gap_ms":6000,"accuracy_default_m":{"RADAR":15,"TDOA":60,"EO":25,"FIVE_G_A":80,"FUSION_BOX":20}},"association":{"gate_sigma":3.0,"w_pos":1.0,"w_alt":0.5,"w_time":0.3,"w_motion":0.6,"w_class":0.4,"w_hist":0.3,"alt_scale_m":30,"time_scale_ms":1500,"speed_scale_mps":6,"heading_scale_deg":45,"cost_max":6.0,"pending_confirm_frames":3,"pending_expire_frames":6},"identity":{"tentative_to_stable_hits":3,"short_lost_after_ms":3000,"terminate_after_ms":15000,"merge_min_frames":4,"merge_max_dist_sigma":2.0,"split_min_frames":4,"split_min_separation_m":100},"weights":{"RADAR":{"position":0.45,"motion":0.5,"class":0.2,"identity":0.0},"EO":{"position":0.2,"motion":0.1,"class":0.6,"identity":0.1},"TDOA":{"position":0.25,"motion":0.2,"class":0.0,"identity":0.5},"FIVE_G_A":{"position":0.1,"motion":0.2,"class":0.2,"identity":0.4},"FUSION_BOX":{"position":0.4,"motion":0.4,"class":0.3,"identity":0.0}},"quality":{"latency_penalty_ms":2000,"loss_window_frames":10,"loss_penalty_per_miss":0.08,"anomaly_zscore":4.0,"anomaly_downweight":0.25},"degradation":{"three_source_min":3,"undetermined_deficit":0.5,"single_source_deficit":0.35,"fusion_box_only_deficit":0.2,"lost_step_deficit":0.1}}',
    'DEMO：全部阈值与权重为演示值，未经算法方确认', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
);
