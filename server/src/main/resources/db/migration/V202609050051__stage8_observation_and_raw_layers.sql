-- 阶段 8 融合引擎：来源观测层、原始/融合轨迹分层、待定关联。
-- 列名即契约（docs/backend-stage8/fusion-api-contract.md）。H2（PostgreSQL 模式）与 PostgreSQL 都要能跑：
-- 几何列写法沿用迁移 0006（GEOMETRY(POINT, 4326)），JSON 列用 JSONB 且 INSERT 时 CAST(? AS JSON)；
-- 只在 PostgreSQL 有效的 CHECK（几何合法性、分层与 link_id 的等价关系）与 GIST/GIN 索引放 R__stage8。

-- 来源观测：每条来源每帧每目标一行，是融合的唯一输入；缺字段保持 NULL，原因写进 quality，绝不补默认值。
CREATE TABLE source_observation (
    observation_id          VARCHAR(36) PRIMARY KEY,
    inbox_id                VARCHAR(36),
    source_id               VARCHAR(36) NOT NULL,
    device_id               VARCHAR(36),
    source_type             VARCHAR(16),
    source_session_key      VARCHAR(128) NOT NULL,
    external_target_id      VARCHAR(128) NOT NULL,
    external_track_id       VARCHAR(128),
    observed_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    location                GEOMETRY(POINT, 4326),
    position_accuracy_m     NUMERIC(10, 2),
    altitude_amsl_m         NUMERIC(10, 2),
    height_agl_m            NUMERIC(10, 2),
    speed_mps               NUMERIC(10, 3),
    heading_deg             NUMERIC(6, 2),
    class_code              VARCHAR(32),
    class_confidence        NUMERIC(6, 5),
    identity_clue           VARCHAR(128),
    identity_confidence     NUMERIC(6, 5),
    latency_ms              INTEGER,
    quality                 JSONB NOT NULL DEFAULT '{}',
    source_mode             VARCHAR(8) NOT NULL,
    owner_org_id            VARCHAR(36),
    district_id             VARCHAR(36),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage8_observation_inbox FOREIGN KEY (inbox_id) REFERENCES inbox_message (inbox_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_observation_source FOREIGN KEY (source_id) REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_observation_device FOREIGN KEY (device_id) REFERENCES device (device_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_observation_source_type FOREIGN KEY (source_type) REFERENCES source_type_catalog (source_type) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_observation_owner_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_observation_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage8_observation_identity UNIQUE (source_id, source_session_key, external_target_id, observed_at),
    CONSTRAINT ck_stage8_observation_session_nonblank CHECK (TRIM(source_session_key) <> ''),
    CONSTRAINT ck_stage8_observation_external_nonblank CHECK (TRIM(external_target_id) <> ''),
    CONSTRAINT ck_stage8_observation_accuracy CHECK (position_accuracy_m IS NULL OR position_accuracy_m > 0),
    CONSTRAINT ck_stage8_observation_speed CHECK (speed_mps IS NULL OR speed_mps >= 0),
    CONSTRAINT ck_stage8_observation_heading CHECK (heading_deg IS NULL OR (heading_deg >= 0 AND heading_deg < 360)),
    CONSTRAINT ck_stage8_observation_class_confidence CHECK (class_confidence IS NULL OR (class_confidence >= 0 AND class_confidence <= 1)),
    CONSTRAINT ck_stage8_observation_identity_confidence CHECK (identity_confidence IS NULL OR (identity_confidence >= 0 AND identity_confidence <= 1)),
    CONSTRAINT ck_stage8_observation_latency CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_stage8_observation_source_mode CHECK (source_mode IN ('mock', 'replay', 'live'))
);

CREATE INDEX idx_stage8_observation_source_time ON source_observation (source_id, observed_at DESC, observation_id ASC);
CREATE INDEX idx_stage8_observation_inbox ON source_observation (inbox_id);
CREATE INDEX idx_stage8_observation_scope_time ON source_observation (source_mode, owner_org_id, district_id, observed_at DESC);

-- 轨迹分层：RAW 一条 link 一条（滤波状态挂在轨迹上），FUSED 一个目标一条且 link_id 为 NULL。
-- link_id 放开可空只为 FUSED 层；"layer='FUSED' ⇔ link_id IS NULL" 的等价 CHECK 放 R__stage8（PostgreSQL）。
ALTER TABLE track ADD COLUMN layer VARCHAR(8) NOT NULL DEFAULT 'RAW';
ALTER TABLE track ADD COLUMN filter_state JSONB;
ALTER TABLE track ADD COLUMN config_version VARCHAR(32);
ALTER TABLE track ADD COLUMN ended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE track ALTER COLUMN link_id DROP NOT NULL;
ALTER TABLE track ADD CONSTRAINT ck_stage8_track_layer CHECK (layer IN ('RAW', 'FUSED'));
ALTER TABLE track ADD CONSTRAINT fk_stage8_track_config FOREIGN KEY (config_version) REFERENCES fusion_config (config_version) ON DELETE RESTRICT;
ALTER TABLE track ADD CONSTRAINT ck_stage8_track_ended_after_start CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at);

CREATE INDEX idx_stage8_track_layer_target ON track (layer, target_id, started_at DESC);

-- 轨迹点扩展：实测/插值/预测种类、对应观测、精度、贡献来源（FUSED 层）、主源切换与降级标记。
ALTER TABLE track_point ADD COLUMN point_kind VARCHAR(8) NOT NULL DEFAULT 'MEAS';
ALTER TABLE track_point ADD COLUMN observation_id VARCHAR(36);
ALTER TABLE track_point ADD COLUMN position_accuracy_m NUMERIC(10, 2);
ALTER TABLE track_point ADD COLUMN contributing JSONB;
ALTER TABLE track_point ADD COLUMN position_source_id VARCHAR(36);
ALTER TABLE track_point ADD COLUMN source_switched BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE track_point ADD COLUMN degradation_level VARCHAR(16);
ALTER TABLE track_point ADD CONSTRAINT ck_stage8_track_point_kind CHECK (point_kind IN ('MEAS', 'BRIDGE', 'PRED'));
ALTER TABLE track_point ADD CONSTRAINT ck_stage8_track_point_accuracy CHECK (position_accuracy_m IS NULL OR position_accuracy_m > 0);
ALTER TABLE track_point ADD CONSTRAINT ck_stage8_track_point_degradation
    CHECK (degradation_level IS NULL OR degradation_level IN ('THREE_SOURCE', 'FUSION_BOX_ONLY', 'SINGLE_SOURCE', 'NONE'));
ALTER TABLE track_point ADD CONSTRAINT fk_stage8_track_point_observation FOREIGN KEY (observation_id) REFERENCES source_observation (observation_id) ON DELETE RESTRICT;
ALTER TABLE track_point ADD CONSTRAINT fk_stage8_track_point_position_source FOREIGN KEY (position_source_id) REFERENCES integration_source (source_id) ON DELETE RESTRICT;

CREATE INDEX idx_stage8_track_point_observation ON track_point (observation_id);

-- 待定关联：门限内歧义（GATE_AMBIGUOUS）、同源同帧多回波（ONE_TO_MANY，分裂候选）、两目标持续贴近（MANY_TO_ONE，合并候选）。
-- 连续帧计数在这里累积；落定或过期后只写 resolved_at/resolution，不删行。
CREATE TABLE association_pending (
    pending_id              VARCHAR(36) PRIMARY KEY,
    fusion_domain_key       VARCHAR(128) NOT NULL,
    observation_id          VARCHAR(36),
    candidate_target_ids    JSONB NOT NULL,
    reason                  VARCHAR(16) NOT NULL,
    pending_key             VARCHAR(256) NOT NULL,
    first_seen_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    frames_seen             INTEGER NOT NULL DEFAULT 1,
    resolved_at             TIMESTAMP WITH TIME ZONE,
    resolution              VARCHAR(16),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage8_pending_observation FOREIGN KEY (observation_id) REFERENCES source_observation (observation_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage8_pending_reason CHECK (reason IN ('ONE_TO_MANY', 'MANY_TO_ONE', 'GATE_AMBIGUOUS')),
    CONSTRAINT ck_stage8_pending_frames CHECK (frames_seen >= 1),
    CONSTRAINT ck_stage8_pending_resolution CHECK (resolution IS NULL OR resolution IN ('CONFIRMED', 'EXPIRED', 'SPLIT', 'MERGED', 'DROPPED')),
    CONSTRAINT ck_stage8_pending_resolved_pair CHECK ((resolved_at IS NULL AND resolution IS NULL) OR (resolved_at IS NOT NULL AND resolution IS NOT NULL))
);

CREATE INDEX idx_stage8_pending_open ON association_pending (fusion_domain_key, pending_key, resolved_at);

-- 领导集成补充（审查 P2）：融合摄取的领取次数。租约过期的 PROCESSING 行可被重领，但毒帧不能每个轮询周期无限重领：
-- 超过 app.fusion.max-attempts 的行由 Worker 置 FAILED（processed_at 同时写入以满足 ck_stage2_inbox_processed_at）。
ALTER TABLE inbox_message ADD COLUMN fusion_attempts INTEGER NOT NULL DEFAULT 0;
