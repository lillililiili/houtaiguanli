-- 阶段 8（E2）：融合层派生表。四张表都以 target 为主键或外键，不改阶段 2 表结构。
-- 只增表（target_classification_revision、fusion_event）的 UPDATE/DELETE 触发器放 PostgreSQL 专属 R__ 文件；H2 不加载。

-- 属性优选结果：每个目标一行，记录本帧各属性取自哪一路来源；人工修订类别后 manual_class_override=TRUE，
-- 之后引擎不得用来源类别覆盖 target.object_type_code。
CREATE TABLE target_attribute_selection (
    target_id               VARCHAR(36) PRIMARY KEY,
    position_source_id      VARCHAR(36),
    class_source_id         VARCHAR(36),
    identity_source_id      VARCHAR(36),
    motion_source_id        VARCHAR(36),
    class_code              VARCHAR(32),
    class_confidence        NUMERIC(6, 5),
    identity_clue           VARCHAR(128),
    selected_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    config_version          VARCHAR(32) NOT NULL,
    manual_class_override   BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stage8_attr_sel_target FOREIGN KEY (target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_attr_sel_position FOREIGN KEY (position_source_id) REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_attr_sel_class FOREIGN KEY (class_source_id) REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_attr_sel_identity FOREIGN KEY (identity_source_id) REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_attr_sel_motion FOREIGN KEY (motion_source_id) REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_attr_sel_config FOREIGN KEY (config_version) REFERENCES fusion_config (config_version) ON DELETE RESTRICT,
    CONSTRAINT ck_stage8_attr_sel_confidence CHECK (class_confidence IS NULL OR (class_confidence >= 0 AND class_confidence <= 1)),
    CONSTRAINT ck_stage8_attr_sel_version CHECK (version >= 0)
);

-- 降级状态：level 与 confidence_deficit 逐帧覆盖；determined=FALSE 表示不可判定（此时 target_latest_state.fusion_confidence 为 NULL）。
CREATE TABLE target_degradation (
    target_id               VARCHAR(36) PRIMARY KEY,
    level                   VARCHAR(16) NOT NULL,
    available_source_ids    JSON NOT NULL,
    confidence_deficit      NUMERIC(6, 5) NOT NULL,
    determined              BOOLEAN NOT NULL,
    since                   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage8_degradation_target FOREIGN KEY (target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage8_degradation_level CHECK (level IN ('THREE_SOURCE', 'FUSION_BOX_ONLY', 'SINGLE_SOURCE', 'NONE')),
    CONSTRAINT ck_stage8_degradation_deficit CHECK (confidence_deficit >= 0 AND confidence_deficit <= 1)
);

-- 人工类别修订：只增；(target_id, target_version) 唯一保证同一版本只能修订一次，并发以版本冲突而不是双写收场。
CREATE TABLE target_classification_revision (
    revision_id             VARCHAR(36) PRIMARY KEY,
    target_id               VARCHAR(36) NOT NULL,
    previous_class_code     VARCHAR(32),
    new_class_code          VARCHAR(32) NOT NULL,
    note                    VARCHAR(1000) NOT NULL,
    actor_id                VARCHAR(36) NOT NULL,
    target_version          BIGINT NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage8_class_rev_target FOREIGN KEY (target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_class_rev_actor FOREIGN KEY (actor_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage8_class_rev_target_version UNIQUE (target_id, target_version),
    CONSTRAINT ck_stage8_class_rev_code CHECK (new_class_code IN ('UAV', 'BIRD', 'VEHICLE', 'PERSON', 'UNKNOWN')),
    CONSTRAINT ck_stage8_class_rev_note CHECK (LENGTH(TRIM(note)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_stage8_class_rev_version CHECK (target_version >= 0)
);

CREATE INDEX idx_stage8_class_rev_target_created ON target_classification_revision (target_id, created_at DESC, revision_id DESC);

-- 融合事件：只增、独立于设备 outbox（决策 8-3），供态势页轮询；payload 只放安全摘要。
CREATE TABLE fusion_event (
    event_id                VARCHAR(36) PRIMARY KEY,
    event_type              VARCHAR(32) NOT NULL,
    target_id               VARCHAR(36) NOT NULL,
    payload                 JSON NOT NULL,
    occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage8_fusion_event_target FOREIGN KEY (target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage8_fusion_event_type CHECK (event_type IN ('STATUS_STABLE', 'MERGED', 'SPLIT', 'UNDETERMINED', 'CLASS_REVISED'))
);

CREATE INDEX idx_stage8_fusion_event_target_time ON fusion_event (target_id, occurred_at DESC, event_id DESC);
CREATE INDEX idx_stage8_fusion_event_time ON fusion_event (occurred_at DESC, event_id DESC);
