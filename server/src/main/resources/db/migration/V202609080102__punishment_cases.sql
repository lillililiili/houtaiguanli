-- 阶段 14：处罚案件域。权限目录五行在 V202609080101（领导）里，本文件不重复插入。
-- PG 专属的只增触发器与列级约束放 db/postgresql/V202609080103__stage14_punishment_pg.sql
-- （版本化而不是 R__：R__ 在"先停在中间版本再前进"的库上会被记为已应用而不再重跑，约束会静默缺失——决策 13-32 修订）。

-- 罚则档位：条例设定的是**区间**，legacy 的十项定额只能作参考值（决策 14-8）。
--
-- 关于 legal_basis：本仓库里没有任何权威条文出处，因此这里**只写条例名称，不编造条款号**。
-- 这张表最终会被渲染进《行政处罚决定书》，编一个"第某条"出来即使标了 DEMO 也是伪造法律依据；
-- 条款号与金额档位一并列入待确认（决策 14-8 / 契约 §7 Q 罚则），由法制岗核定后另发版本。
-- fine_min/fine_max/fine_reference 单位为**分**，与 penalty_discretion.fine_amount 一致。
CREATE TABLE penalty_rule (
    rule_code           VARCHAR(32) PRIMARY KEY,
    violation_code      VARCHAR(48) NOT NULL,
    title               VARCHAR(128) NOT NULL,
    legal_basis         VARCHAR(255) NOT NULL,
    fine_min            BIGINT NOT NULL,
    fine_max            BIGINT NOT NULL,
    -- legacy 定额：只作对照，不参与区间校验（决策 14-8）。
    fine_reference      BIGINT,
    -- 该档位**允许**的处罚种类，存 JSON 数组文本（如 ["WARNING","FINE"]）：
    -- 同一违法事由按情节可以只警告、也可以并处罚款，写死一种会让裁量无从选择。
    -- 用 JSON 数组而不是逗号分隔，免得日后有人往里塞带逗号的值再来一轮解析歧义（决策 14-24）。
    penalty_types       VARCHAR(128) NOT NULL,
    schema_status       VARCHAR(16) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stage14_rule_schema CHECK (schema_status IN ('DEMO', 'CONFIRMED')),
    CONSTRAINT ck_stage14_rule_amounts CHECK (fine_min >= 0 AND fine_max >= fine_min),
    CONSTRAINT ck_stage14_rule_reference CHECK (fine_reference IS NULL OR fine_reference >= 0),
    CONSTRAINT ck_stage14_rule_text CHECK (TRIM(title) <> '' AND TRIM(legal_basis) <> '')
);

INSERT INTO penalty_rule (rule_code, violation_code, title, legal_basis, fine_min, fine_max, fine_reference,
    penalty_types, schema_status, enabled, created_at, updated_at) VALUES
    ('PR-01', 'NO_AUTHORIZATION', '未经批准擅自飞行', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        20000, 200000, 50000, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-02', 'PROHIBITED_AIRSPACE_OVERLAP', '进入禁飞空域飞行', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        50000, 500000, 100000, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-03', 'AIRSPACE_ALTITUDE_EXCEEDED', '超出空域限高飞行', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        20000, 200000, 30000, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-04', 'PLAN_ALTITUDE_EXCEEDED', '超出计划高度飞行', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        10000, 100000, 20000, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-05', 'TIME_WINDOW_EXCEEDED', '超出批准时段飞行', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        10000, 100000, 20000, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-06', 'ROUTE_DEVIATION', '偏离批准航线飞行', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        10000, 100000, 20000, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-07', 'BVLOS_EXCEEDED', '超视距飞行未符合要求', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        20000, 200000, 30000, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-08', 'NIGHT_FLIGHT', '夜间飞行未符合要求', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        10000, 100000, 20000, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-09', 'IDENTITY_MISMATCH', '实名登记信息不符', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        0, 0, NULL, '["WARNING"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PR-10', 'OTHER', '其他违反飞行管理规定的行为', '《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）',
        0, 200000, NULL, '["WARNING","FINE","WARNING_AND_FINE"]', 'DEMO', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 一事件一案：event_id 唯一（决策 14-5）。案件是"受理"的事实，交接是"移送"的事实，两者分开记。
CREATE TABLE punishment_case (
    case_id                 VARCHAR(36) PRIMARY KEY,
    case_no                 VARCHAR(32) NOT NULL,
    event_id                VARCHAR(36) NOT NULL,
    handoff_id              VARCHAR(36) NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    party_type              VARCHAR(8) NOT NULL,
    party_name              VARCHAR(128),
    officer_id              VARCHAR(36),
    officer_name            VARCHAR(128),
    primary_violation_code  VARCHAR(48),
    filed_by                VARCHAR(36) NOT NULL,
    filed_by_name           VARCHAR(128),
    filed_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at              TIMESTAMP WITH TIME ZONE,
    closed_at               TIMESTAMP WITH TIME ZONE,
    close_note              VARCHAR(500),
    withdraw_reason         VARCHAR(500),
    owner_org_id            VARCHAR(36) NOT NULL,
    district_id             VARCHAR(36) NOT NULL,
    source_mode             VARCHAR(8) NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_stage14_case_no UNIQUE (case_no),
    CONSTRAINT uk_stage14_case_event UNIQUE (event_id),
    CONSTRAINT fk_stage14_case_event FOREIGN KEY (event_id) REFERENCES uav_event (event_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_case_handoff FOREIGN KEY (handoff_id) REFERENCES handoff (handoff_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_case_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_case_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_case_filer FOREIGN KEY (filed_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_case_officer FOREIGN KEY (officer_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage14_case_status CHECK (status IN ('FILED', 'INVESTIGATING', 'UNDER_REVIEW', 'DECIDED', 'CLOSED', 'WITHDRAWN')),
    -- 当事人只存类型与名称，不存证件号/电话/住址（决策 14-12）；UNKNOWN 时名称必须为空，
    -- 否则"当事人不详"和"当事人叫某某"会同时成立，文书上说不清到底认定了谁。
    CONSTRAINT ck_stage14_case_party CHECK (party_type IN ('PERSON', 'ORG', 'UNKNOWN')),
    CONSTRAINT ck_stage14_case_party_name CHECK (
        (party_type = 'UNKNOWN' AND party_name IS NULL) OR (party_type <> 'UNKNOWN')),
    CONSTRAINT ck_stage14_case_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage14_case_no_text CHECK (TRIM(case_no) <> ''),
    CONSTRAINT ck_stage14_case_version CHECK (version >= 0),
    -- 撤案必须有理由：没有理由的撤案在事后无法交代（决策 14-14）。
    CONSTRAINT ck_stage14_case_withdraw CHECK (status <> 'WITHDRAWN' OR TRIM(COALESCE(withdraw_reason, '')) <> '')
);

CREATE INDEX idx_stage14_case_scope ON punishment_case (owner_org_id, district_id, filed_at DESC);
CREATE INDEX idx_stage14_case_status ON punishment_case (status, filed_at DESC);
CREATE INDEX idx_stage14_case_handoff ON punishment_case (handoff_id);

-- 案件事件流只增：办案过程是有法律后果的事实链，改一行等于改卷宗。
CREATE TABLE punishment_case_event (
    event_id            VARCHAR(36) PRIMARY KEY,
    case_id             VARCHAR(36) NOT NULL,
    event_kind          VARCHAR(32) NOT NULL,
    actor_id            VARCHAR(36),
    actor_name          VARCHAR(128),
    note                VARCHAR(500),
    snapshot            JSON,
    occurred_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage14_case_event_case FOREIGN KEY (case_id) REFERENCES punishment_case (case_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_case_event_actor FOREIGN KEY (actor_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage14_case_event_kind CHECK (event_kind IN ('FILE', 'ASSIGN', 'LEAD_ADDED', 'LEAD_RESOLVED',
        'DISCRETION_DRAFTED', 'DISCRETION_CONFIRMED', 'DOCUMENT_ISSUED', 'DOCUMENT_REVOKED',
        'REVIEW_REQUESTED', 'REVIEW_CONCLUDED', 'CLOSE', 'WITHDRAW'))
);

CREATE INDEX idx_stage14_case_event_case ON punishment_case_event (case_id, occurred_at);

-- 待补线索：复核说"证据不足"时挂在案件上，承办人补齐后逐条解决（决策 14-11）。
CREATE TABLE punishment_case_lead (
    lead_id             VARCHAR(36) PRIMARY KEY,
    case_id             VARCHAR(36) NOT NULL,
    kind                VARCHAR(24) NOT NULL,
    description         VARCHAR(500) NOT NULL,
    resolved            BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_note       VARCHAR(500),
    created_by          VARCHAR(36) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_by         VARCHAR(36),
    resolved_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_stage14_lead_case FOREIGN KEY (case_id) REFERENCES punishment_case (case_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_lead_creator FOREIGN KEY (created_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_lead_resolver FOREIGN KEY (resolved_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    -- 词表就是事件驱动流程 4.7 的四个条件（事实清楚、主体可认定、证据充分、管辖明确）。
    CONSTRAINT ck_stage14_lead_kind CHECK (kind IN ('PARTY_IDENTITY', 'EVIDENCE', 'JURISDICTION', 'FACT', 'OTHER')),
    CONSTRAINT ck_stage14_lead_text CHECK (TRIM(description) <> ''),
    -- 解决了就必须留下谁解决的、什么时候：否则"这条线索补齐了"没有任何人对它负责。
    -- 解决说明与"谁在何时解决的"同生同灭（决策 14-26）：只留说明而没有责任人，
    -- 事后无法追问这条线索到底是谁认定补齐的；只留责任人而没有说明，则看不出补了什么。
    CONSTRAINT ck_stage14_lead_resolution CHECK (
        (resolved = FALSE AND resolved_by IS NULL AND resolved_at IS NULL AND resolved_note IS NULL)
        OR (resolved = TRUE AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL
            AND TRIM(COALESCE(resolved_note, '')) <> ''))
);

CREATE INDEX idx_stage14_lead_case ON punishment_case_lead (case_id, resolved, created_at);

-- 裁量：一案多版，DRAFT 可改、CONFIRMED 冻结（决策 14-9）。决定书只能基于冻结的那一版。
CREATE TABLE penalty_discretion (
    discretion_id       VARCHAR(36) PRIMARY KEY,
    case_id             VARCHAR(36) NOT NULL,
    version_no          INTEGER NOT NULL,
    status              VARCHAR(16) NOT NULL,
    violation_code      VARCHAR(48) NOT NULL,
    rule_code           VARCHAR(32) NOT NULL,
    penalty_type        VARCHAR(24) NOT NULL,
    -- 单位：分。区间校验在应用层按 penalty_rule 做（400 FINE_OUT_OF_RANGE），库层只保证非负。
    fine_amount         BIGINT NOT NULL,
    factors             JSON,
    basis_text          VARCHAR(1000),
    drafted_by          VARCHAR(36) NOT NULL,
    drafted_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by          VARCHAR(36),
    decided_at          TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_stage14_discretion_version UNIQUE (case_id, version_no),
    CONSTRAINT fk_stage14_discretion_case FOREIGN KEY (case_id) REFERENCES punishment_case (case_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_discretion_rule FOREIGN KEY (rule_code) REFERENCES penalty_rule (rule_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_discretion_drafter FOREIGN KEY (drafted_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_discretion_decider FOREIGN KEY (decided_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage14_discretion_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'SUPERSEDED')),
    CONSTRAINT ck_stage14_discretion_type CHECK (penalty_type IN ('WARNING', 'FINE', 'WARNING_AND_FINE')),
    CONSTRAINT ck_stage14_discretion_amount CHECK (fine_amount >= 0),
    -- 只警告就不该有金额：一份写着"警告"却带着罚款数额的裁量，落到文书上是自相矛盾的（决策 14-9）。
    CONSTRAINT ck_stage14_discretion_warning_amount CHECK (penalty_type <> 'WARNING' OR fine_amount = 0),
    CONSTRAINT ck_stage14_discretion_version_no CHECK (version_no >= 1),
    -- 确认过就必须留下确认人与时刻：文书的效力来自"谁在何时定的"。
    CONSTRAINT ck_stage14_discretion_decision CHECK (
        (status <> 'CONFIRMED') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);

CREATE INDEX idx_stage14_discretion_case ON penalty_discretion (case_id, version_no DESC);

-- 决定书：ISSUED 之后内容不可改，作废走 REVOKED（决策 14-10）。
-- 平台未获授权出具文书，渲染文本首行带 DEMO 水印；这里先把"可追溯、不可篡改"做对。
CREATE TABLE penalty_decision_document (
    document_id         VARCHAR(36) PRIMARY KEY,
    document_no         VARCHAR(48) NOT NULL,
    case_id             VARCHAR(36) NOT NULL,
    discretion_id       VARCHAR(36) NOT NULL,
    template_version    VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    fields              JSON NOT NULL,
    rendered_sha256     VARCHAR(64) NOT NULL,
    issued_by           VARCHAR(36) NOT NULL,
    issued_by_name      VARCHAR(128),
    issued_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at          TIMESTAMP WITH TIME ZONE,
    revoke_reason       VARCHAR(500),
    version             BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_stage14_document_no UNIQUE (document_no),
    CONSTRAINT fk_stage14_document_case FOREIGN KEY (case_id) REFERENCES punishment_case (case_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_document_discretion FOREIGN KEY (discretion_id) REFERENCES penalty_discretion (discretion_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_document_issuer FOREIGN KEY (issued_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage14_document_status CHECK (status IN ('ISSUED', 'REVOKED')),
    CONSTRAINT ck_stage14_document_sha CHECK (LENGTH(rendered_sha256) = 64),
    CONSTRAINT ck_stage14_document_version CHECK (version >= 0),
    -- 作废必须有时刻与理由：一份被作废的处罚决定书，事后要能说清是谁在何时以什么理由作废的。
    CONSTRAINT ck_stage14_document_revocation CHECK (
        (status = 'ISSUED' AND revoked_at IS NULL AND revoke_reason IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND TRIM(COALESCE(revoke_reason, '')) <> ''))
);

CREATE INDEX idx_stage14_document_case ON penalty_decision_document (case_id, issued_at DESC);

-- 复核只增：复核意见是定性的依据，改一条等于改结论（决策 14-11）。
CREATE TABLE punishment_review (
    review_id           VARCHAR(36) PRIMARY KEY,
    case_id             VARCHAR(36) NOT NULL,
    reviewer_id         VARCHAR(36) NOT NULL,
    reviewer_name       VARCHAR(128),
    conclusion          VARCHAR(16) NOT NULL,
    note                VARCHAR(1000),
    missing_leads       JSON,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage14_review_case FOREIGN KEY (case_id) REFERENCES punishment_case (case_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage14_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage14_review_conclusion CHECK (conclusion IN ('UPHELD', 'REVISED', 'INSUFFICIENT'))
);

CREATE INDEX idx_stage14_review_case ON punishment_review (case_id, created_at);

-- 案件编号计数表：按日一行，发号时对该行加锁再自增（决策 14-6，写法同 13-5）。
CREATE TABLE punishment_no_counter (
    day_key             VARCHAR(8) PRIMARY KEY,
    next_no             INTEGER NOT NULL,
    CONSTRAINT ck_stage14_no_counter_next CHECK (next_no >= 1)
);
