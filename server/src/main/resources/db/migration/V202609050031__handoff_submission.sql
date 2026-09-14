-- 交接接收方是逻辑部门目录，只有展示名与类型；不存凭据、地址或网络信息。
-- 生产不由迁移或启动器自动插入任何接收方，目录为空时提交返回 RECIPIENT_NOT_CONFIGURED。
CREATE TABLE handoff_recipient (
    recipient_id        VARCHAR(36) PRIMARY KEY,
    display_name        VARCHAR(128) NOT NULL,
    handoff_type        VARCHAR(32) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stage5_recipient_type CHECK (handoff_type IN ('RISK_NOTICE','UAV_PUNISHMENT')),
    CONSTRAINT ck_stage5_recipient_name CHECK (TRIM(display_name) <> '')
);

CREATE INDEX idx_stage5_recipient_type_enabled ON handoff_recipient (handoff_type, enabled, recipient_id);

-- 交接头只记录“把哪个源事项的第几版材料提交给谁”，不拥有源事项状态；源风险仍由 flight_risk 自己的状态机推进。
-- source_kind 决定唯一一个非空的源外键，且 source_id 必须与该外键相等，避免同一 source_id 在不同 kind 下被误当同一事项。
CREATE TABLE handoff (
    handoff_id          VARCHAR(36) PRIMARY KEY,
    source_kind         VARCHAR(32) NOT NULL,
    source_id           VARCHAR(36) NOT NULL,
    risk_id             VARCHAR(36),
    event_id            VARCHAR(36),
    handoff_type        VARCHAR(32) NOT NULL,
    recipient_id        VARCHAR(36) NOT NULL,
    source_version      BIGINT NOT NULL,
    owner_org_id        VARCHAR(36) NOT NULL,
    district_id         VARCHAR(36) NOT NULL,
    source_mode         VARCHAR(8) NOT NULL,
    submitted_by        VARCHAR(36) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage5_handoff_risk FOREIGN KEY (risk_id) REFERENCES flight_risk (risk_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage5_handoff_event FOREIGN KEY (event_id) REFERENCES uav_event (event_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage5_handoff_recipient FOREIGN KEY (recipient_id) REFERENCES handoff_recipient (recipient_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage5_handoff_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage5_handoff_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage5_handoff_submitter FOREIGN KEY (submitted_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    -- 逻辑唯一：同一事项、同一类型、同一接收方只允许一份交接；两人不同幂等键提交也只能落一份。
    CONSTRAINT uk_stage5_handoff_logical UNIQUE (source_kind, source_id, handoff_type, recipient_id),
    CONSTRAINT ck_stage5_handoff_kind CHECK (source_kind IN ('RISK','UAV_EVENT')),
    CONSTRAINT ck_stage5_handoff_type CHECK (handoff_type IN ('RISK_NOTICE','UAV_PUNISHMENT')),
    CONSTRAINT ck_stage5_handoff_source_ref CHECK (
        (source_kind = 'RISK' AND risk_id IS NOT NULL AND risk_id = source_id AND event_id IS NULL)
        OR (source_kind = 'UAV_EVENT' AND event_id IS NOT NULL AND event_id = source_id AND risk_id IS NULL)
    ),
    CONSTRAINT ck_stage5_handoff_source_mode CHECK (source_mode IN ('mock','replay','live')),
    CONSTRAINT ck_stage5_handoff_version CHECK (source_version >= 0)
);

CREATE INDEX idx_stage5_handoff_scope_created ON handoff (owner_org_id, district_id, created_at DESC, handoff_id DESC);
CREATE INDEX idx_stage5_handoff_source_created ON handoff (source_kind, source_id, created_at DESC, handoff_id DESC);

-- 材料快照只增不改：冻结提交当时的白名单结构化材料；没有文件就不存在文件名、哈希或下载链接。
-- JSONB 与阶段 3 研判表写法一致，H2 PostgreSQL 模式同样接受；PostgreSQL 专用约束本期不需要。
CREATE TABLE handoff_material_snapshot (
    handoff_id          VARCHAR(36) PRIMARY KEY,
    schema_version      INTEGER NOT NULL,
    snapshot            JSONB NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage5_snapshot_handoff FOREIGN KEY (handoff_id) REFERENCES handoff (handoff_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage5_snapshot_schema CHECK (schema_version >= 1)
);

-- 投递尝试与交接头分开：提交成功只生成 attempt_no=1 的待投递记录；送达和回执是后续外部事实，本期没有写入口。
CREATE TABLE handoff_delivery (
    delivery_id         VARCHAR(36) PRIMARY KEY,
    handoff_id          VARCHAR(36) NOT NULL,
    attempt_no          INTEGER NOT NULL,
    delivery_status     VARCHAR(32) NOT NULL,
    receipt_status      VARCHAR(32) NOT NULL,
    blocked_reason      VARCHAR(64),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at        TIMESTAMP WITH TIME ZONE,
    delivered_at        TIMESTAMP WITH TIME ZONE,
    acknowledged_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_stage5_delivery_handoff FOREIGN KEY (handoff_id) REFERENCES handoff (handoff_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage5_delivery_attempt UNIQUE (handoff_id, attempt_no),
    CONSTRAINT ck_stage5_delivery_attempt CHECK (attempt_no >= 1),
    CONSTRAINT ck_stage5_delivery_status CHECK (delivery_status IN ('PENDING_DELIVERY','SUBMITTED','DELIVERED','FAILED')),
    CONSTRAINT ck_stage5_receipt_status CHECK (receipt_status IN ('NOT_EXPECTED','PENDING','ACKNOWLEDGED','TIMEOUT'))
);

CREATE INDEX idx_stage5_delivery_handoff_attempt ON handoff_delivery (handoff_id, attempt_no ASC);
