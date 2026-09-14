-- 阶段 13：处置授权域（申请 → 审批 → 执行 → 回执 → 完成/失败/停止/过期）。
-- 权限目录五行在 V202609070101（领导）里，本文件不重复插入。
-- 只增触发器与 valid_until > valid_from 的 PG 约束放 db/postgresql/R__stage13_disposal.sql（H2 测试不加载该目录）。

-- 授权策略：客户 Q5（授权条件/审批层级/时限/可自动执行的设备）未答复，因此 demo-v1 全部参数 schema_status=DEMO。
-- 代码里不许出现裸阈值：两人规则、时限、是否要求已核实事件、并发上限、指令码映射一律从 params 读（决策 13-2）。
CREATE TABLE disposal_policy (
    policy_code         VARCHAR(32) PRIMARY KEY,
    status              VARCHAR(16) NOT NULL,
    schema_status       VARCHAR(16) NOT NULL,
    params              JSON NOT NULL,
    note                VARCHAR(500),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_stage13_policy_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_stage13_policy_schema CHECK (schema_status IN ('DEMO', 'CONFIRMED')),
    CONSTRAINT ck_stage13_policy_version CHECK (version >= 0)
);

-- command_map 是"处置动作 → 协议 B 指令码"的演示映射（码表见 docs/backend-stage8/target-schema-v1-alignment.md §5.3）。
-- 如实记录：这四个码在协作者 A 的 LingyunControlEnvelope.family() 里都没有设备类型缩写映射，
-- 现阶段调 enqueue 一律被 A 以 PROTOCOL_UNSUPPORTED 拒绝（A 的 LingyunControlMqttTest 对 50002 就是这么断言的）。
-- 映射先按纪要落在这里，等厂家确认设备类型缩写后由 A 开通；平台侧不绕开 A 另开通道，也不伪造回执（决策 13-3）。
INSERT INTO disposal_policy (policy_code, status, schema_status, params, note, created_at, updated_at, version) VALUES (
    'demo-v1', 'ACTIVE', 'DEMO',
    CAST('{"approval_required":true,"two_person_rule":true,"max_active_per_subject":1,"time_limit_min":{"COUNTERMEASURE":30,"JAMMING":30,"DISPERSAL":15,"DECOY":30},"requires_confirmed_event":{"COUNTERMEASURE":true,"JAMMING":true,"DISPERSAL":false,"DECOY":true},"command_map":{"COUNTERMEASURE":{"operation_type":1,"operation_cmd":60003},"JAMMING":{"operation_type":1,"operation_cmd":60002},"DISPERSAL":{"operation_type":1,"operation_cmd":70001},"DECOY":{"operation_type":1,"operation_cmd":50002}}}' AS JSON),
    'DEMO：授权条件、审批层级、时限与指令码映射均未经客户与厂家确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
);

CREATE TABLE disposal_authorization (
    authorization_id    VARCHAR(36) PRIMARY KEY,
    -- AUTH-YYYYMMDD-NNNN，按日递增（决策 13-5）；全局唯一，重号即入库失败而不是悄悄覆盖。
    authorization_no    VARCHAR(32) NOT NULL,
    action_type         VARCHAR(16) NOT NULL,
    subject_kind        VARCHAR(16) NOT NULL,
    subject_id          VARCHAR(36) NOT NULL,
    target_id           VARCHAR(36),
    device_id           VARCHAR(36),
    channel             VARCHAR(24) NOT NULL,
    reason              VARCHAR(500) NOT NULL,
    requested_by        VARCHAR(36) NOT NULL,
    requested_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_by         VARCHAR(36),
    approved_at         TIMESTAMP WITH TIME ZONE,
    decision_note       VARCHAR(500),
    valid_from          TIMESTAMP WITH TIME ZONE,
    valid_until         TIMESTAMP WITH TIME ZONE,
    status              VARCHAR(16) NOT NULL,
    execution_command_id VARCHAR(36),
    result_code         VARCHAR(48),
    result_detail       VARCHAR(500),
    policy_version      VARCHAR(32) NOT NULL,
    owner_org_id        VARCHAR(36) NOT NULL,
    district_id         VARCHAR(36) NOT NULL,
    source_mode         VARCHAR(8) NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_stage13_authorization_no UNIQUE (authorization_no),
    CONSTRAINT fk_stage13_authorization_policy FOREIGN KEY (policy_version) REFERENCES disposal_policy (policy_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stage13_authorization_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage13_authorization_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage13_authorization_requester FOREIGN KEY (requested_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage13_authorization_approver FOREIGN KEY (approved_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage13_authorization_action CHECK (action_type IN ('COUNTERMEASURE', 'JAMMING', 'DISPERSAL', 'DECOY')),
    CONSTRAINT ck_stage13_authorization_subject CHECK (subject_kind IN ('UAV_EVENT', 'RISK', 'TARGET')),
    CONSTRAINT ck_stage13_authorization_channel CHECK (channel IN ('LINGYUN_B', 'COUNTERMEASURE_4CH', 'MANUAL')),
    CONSTRAINT ck_stage13_authorization_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'EXECUTING',
        'COMPLETED', 'FAILED', 'STOPPED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_stage13_authorization_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage13_authorization_reason CHECK (TRIM(reason) <> ''),
    CONSTRAINT ck_stage13_authorization_no_text CHECK (TRIM(authorization_no) <> ''),
    CONSTRAINT ck_stage13_authorization_version CHECK (version >= 0),
    -- 批过就必须同时留下批的人、批的时刻和有效期：少任何一项都说不清"这次动手凭什么"。
    CONSTRAINT ck_stage13_authorization_approval CHECK (
        (approved_by IS NULL AND approved_at IS NULL AND valid_from IS NULL AND valid_until IS NULL)
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND valid_from IS NOT NULL AND valid_until IS NOT NULL)
    ),
    -- 经协议 B 自动执行的授权必须指明设备；人工执行不需要。
    CONSTRAINT ck_stage13_authorization_device CHECK (channel = 'MANUAL' OR device_id IS NOT NULL)
);

CREATE INDEX idx_stage13_authorization_subject ON disposal_authorization (subject_kind, subject_id);
CREATE INDEX idx_stage13_authorization_expiry ON disposal_authorization (status, valid_until);
CREATE INDEX idx_stage13_authorization_scope ON disposal_authorization (owner_org_id, district_id, requested_at DESC);

-- 事件流只增：一次处置的全过程是有法律后果的事实链，改一行就等于改口供。
-- H2 侧靠应用层不发 UPDATE/DELETE，PG 侧由 R__stage13_disposal.sql 的触发器强制。
CREATE TABLE disposal_authorization_event (
    event_id            VARCHAR(36) PRIMARY KEY,
    authorization_id    VARCHAR(36) NOT NULL,
    event_kind          VARCHAR(32) NOT NULL,
    actor_id            VARCHAR(36),
    note                VARCHAR(500),
    snapshot            JSON,
    occurred_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage13_event_authorization FOREIGN KEY (authorization_id) REFERENCES disposal_authorization (authorization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage13_event_actor FOREIGN KEY (actor_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    -- 执行受阻的四种原因各记各的事件（决策 13-11 / 13-14 / 13-22），因为**补救的人不同**：
    --   DEVICE_CONTROL_UNAVAILABLE 设备本身没有自动执行能力（如四通道反制）——要换设备；
    --   PROTOCOL_NOT_OPENED        指令码在 A 的协议面未开通——等厂家确认设备类型缩写；
    --   DEVICE_NOT_BOUND           设备没登记凌云 MQTT——运维补登记即可；
    --   DEVICE_OFFLINE             设备未启用或不在线——现场处理。
    -- 压成一个码会让运维以为"等厂家"的事自己能修，或让"自己五分钟能修"的事一直挂着等厂家。
    CONSTRAINT ck_stage13_event_kind CHECK (event_kind IN ('REQUEST', 'APPROVE', 'REJECT', 'EXECUTE', 'RECEIPT',
        'STOP', 'COMPLETE', 'FAIL', 'EXPIRE', 'CANCEL', 'MANUAL_RESULT',
        'DEVICE_STOP_UNAVAILABLE', 'DEVICE_CONTROL_UNAVAILABLE', 'DEVICE_NOT_BOUND',
        'PROTOCOL_NOT_OPENED', 'DEVICE_OFFLINE'))
);

CREATE INDEX idx_stage13_event_authorization ON disposal_authorization_event (authorization_id, occurred_at);

-- 编号计数表：按日一行，发号时对该行加锁再自增，PG 与 H2 都能保证并发下不重号（决策 13-5）。
CREATE TABLE disposal_no_counter (
    day_key             VARCHAR(8) PRIMARY KEY,
    next_no             INTEGER NOT NULL,
    CONSTRAINT ck_stage13_no_counter_next CHECK (next_no >= 1)
);
