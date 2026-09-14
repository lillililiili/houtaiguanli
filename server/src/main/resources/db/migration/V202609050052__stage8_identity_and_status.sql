-- 阶段 8 融合引擎：目标 ID 状态机、血缘与别名。
-- target_lineage 只增（PostgreSQL 触发器放 R__stage8）；被并目标的 target 行不删不改名，只通过 target_current_alias 指向当前目标，
-- 历史外键（alarm / uav_event / flight_risk / handoff / assessment / rule_evaluation 的 target_id）保持可解析。

-- 目标轨迹状态：一目标一行；由摄取 Worker 每帧条件更新，人工合并/分裂由 E2 写入 MERGE/SPLIT。
-- primary_source_id 是 E1 记录的当前位置主源（用于识别主源切换并写 SWITCH 血缘），契约表外的可空列。
CREATE TABLE target_track_status (
    target_id               VARCHAR(36) PRIMARY KEY,
    status                  VARCHAR(16) NOT NULL,
    since                   TIMESTAMP WITH TIME ZONE NOT NULL,
    confirm_hits            INTEGER NOT NULL DEFAULT 0,
    miss_frames             INTEGER NOT NULL DEFAULT 0,
    last_observed_at        TIMESTAMP WITH TIME ZONE,
    primary_source_id       VARCHAR(36),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stage8_track_status_target FOREIGN KEY (target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_track_status_primary_source FOREIGN KEY (primary_source_id) REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage8_track_status CHECK (status IN ('TENTATIVE', 'STABLE', 'SHORT_LOST', 'SPLIT', 'MERGE', 'TERMINATED')),
    CONSTRAINT ck_stage8_track_status_hits CHECK (confirm_hits >= 0),
    CONSTRAINT ck_stage8_track_status_misses CHECK (miss_frames >= 0),
    CONSTRAINT ck_stage8_track_status_version CHECK (version >= 0)
);

CREATE INDEX idx_stage8_track_status_status ON target_track_status (status, last_observed_at DESC);

-- 血缘：每次创建/合并/分裂/主源切换/类别修订/状态变更一行；basis 与 snapshots 只放安全字段，不放原始载荷。
CREATE TABLE target_lineage (
    lineage_id              VARCHAR(36) PRIMARY KEY,
    op                      VARCHAR(16) NOT NULL,
    occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    survivor_target_id      VARCHAR(36),
    origin_target_id        VARCHAR(36),
    member_target_ids       JSONB NOT NULL,
    source_target_ids       JSONB NOT NULL DEFAULT '[]',
    basis                   JSONB NOT NULL DEFAULT '{}',
    algo_version            VARCHAR(32) NOT NULL,
    config_version          VARCHAR(32) NOT NULL,
    operator_kind           VARCHAR(8) NOT NULL,
    operator_id             VARCHAR(36),
    note                    VARCHAR(1000),
    snapshots               JSONB NOT NULL DEFAULT '{}',
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage8_lineage_survivor FOREIGN KEY (survivor_target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_lineage_origin FOREIGN KEY (origin_target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_lineage_config FOREIGN KEY (config_version) REFERENCES fusion_config (config_version) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_lineage_operator FOREIGN KEY (operator_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage8_lineage_op CHECK (op IN ('CREATE', 'MERGE', 'SPLIT', 'SWITCH', 'CLASS_REVISION', 'STATUS')),
    CONSTRAINT ck_stage8_lineage_operator_kind CHECK (operator_kind IN ('SYSTEM', 'USER')),
    CONSTRAINT ck_stage8_lineage_operator_pair CHECK (operator_kind = 'SYSTEM' OR operator_id IS NOT NULL),
    CONSTRAINT ck_stage8_lineage_note CHECK (note IS NULL OR TRIM(note) <> '')
);

CREATE INDEX idx_stage8_lineage_survivor_time ON target_lineage (survivor_target_id, occurred_at ASC, lineage_id ASC);
CREATE INDEX idx_stage8_lineage_origin_time ON target_lineage (origin_target_id, occurred_at ASC, lineage_id ASC);
CREATE INDEX idx_stage8_lineage_time ON target_lineage (occurred_at ASC, lineage_id ASC);

-- 当前别名：被并目标 → 幸存目标。historical_target_id 不得等于 current_target_id（PostgreSQL CHECK 放 R__，H2 这里也写一份）。
CREATE TABLE target_current_alias (
    historical_target_id    VARCHAR(36) PRIMARY KEY,
    current_target_id       VARCHAR(36) NOT NULL,
    lineage_id              VARCHAR(36) NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage8_alias_historical FOREIGN KEY (historical_target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_alias_current FOREIGN KEY (current_target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage8_alias_lineage FOREIGN KEY (lineage_id) REFERENCES target_lineage (lineage_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage8_alias_distinct CHECK (historical_target_id <> current_target_id)
);

CREATE INDEX idx_stage8_alias_current ON target_current_alias (current_target_id);

-- 由融合引擎统一编号并维护的目标标 unified=TRUE；种子/人工直接写入的阶段 2 目标保持 FALSE。
ALTER TABLE target ADD COLUMN unified BOOLEAN NOT NULL DEFAULT FALSE;
