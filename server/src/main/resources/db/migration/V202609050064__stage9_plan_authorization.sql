-- 阶段 9：飞行计划的外部授权登记。
-- 这是"登记别处已经批下来的授权文号"，不是本平台的审批流：因此它只增不改不删，也不改变
-- flight_plan.status_code（决策 9-10 的边界——平台不冒充审批机关，状态仍由计划来源系统给出）。
-- 只增触发器放 db/postgresql/R__stage9_airspace_succession.sql（H2 测试不加载该目录）。

CREATE TABLE flight_plan_authorization (
    authorization_id    VARCHAR(36) PRIMARY KEY,
    plan_id             VARCHAR(36) NOT NULL REFERENCES flight_plan (plan_id) ON DELETE RESTRICT,
    -- 文号是这份授权在签发单位那边的身份：同一计划同一文号只能登记一次，重复提交不是新事实。
    document_no         VARCHAR(128) NOT NULL,
    issuer              VARCHAR(128) NOT NULL,
    granted_from        TIMESTAMP WITH TIME ZONE NOT NULL,
    granted_to          TIMESTAMP WITH TIME ZONE NOT NULL,
    scope_note          VARCHAR(500),
    recorded_by         VARCHAR(36) NOT NULL REFERENCES app_user (user_id) ON DELETE RESTRICT,
    recorded_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    source_kind         VARCHAR(16) NOT NULL,
    CONSTRAINT uk_stage9_plan_authorization_document UNIQUE (plan_id, document_no),
    CONSTRAINT ck_stage9_plan_authorization_source CHECK (source_kind IN ('MANUAL', 'IMPORT')),
    CONSTRAINT ck_stage9_plan_authorization_text CHECK (TRIM(document_no) <> '' AND TRIM(issuer) <> ''),
    -- 零长度或倒挂的授权区间说不清"哪段时间是被授权的"，一律拒绝入库。
    CONSTRAINT ck_stage9_plan_authorization_window CHECK (granted_to > granted_from)
);

CREATE INDEX idx_stage9_plan_authorization_plan ON flight_plan_authorization (plan_id, granted_from DESC);
