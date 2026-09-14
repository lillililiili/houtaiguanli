-- 阶段 7 人工复核与 C06 告警合并表（执行者 2）。
-- legality_review 是每条 ACTIVE 研判的复核头行（版本号做条件更新）；legality_review_history 只增，
-- PostgreSQL 的只增触发器放在 db/postgresql/R__stage7_rule_engine_constraints.sql（H2 不加载）。
-- alarm_merge_group / alarm_merge_member 承载同目标同类型告警的去重/升级窗口状态：
-- alarm 行永不 UPDATE，窗口状态全部落在合并组上，成员行记录每条研判在组内的角色。

CREATE TABLE legality_review (
    evaluation_id   VARCHAR(36) PRIMARY KEY REFERENCES rule_evaluation (evaluation_id) ON DELETE RESTRICT,
    review_state    VARCHAR(16) NOT NULL,
    manual_status   VARCHAR(16),
    version         BIGINT NOT NULL DEFAULT 0,
    owner_org_id    VARCHAR(36) NOT NULL REFERENCES app_org (org_id) ON DELETE RESTRICT,
    district_id     VARCHAR(36) NOT NULL REFERENCES app_district (district_id) ON DELETE RESTRICT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stage7_review_state CHECK (
        review_state IN ('PENDING_REVIEW', 'CONFIRMED', 'REJECTED', 'OVERRIDDEN', 'SUPERSEDED')
    ),
    -- 人工结论只能是四态之一；NOT_APPLICABLE 不是人可以“改判”出来的结论。
    CONSTRAINT ck_stage7_review_manual_status CHECK (
        manual_status IS NULL OR manual_status IN ('LEGAL', 'ABNORMAL', 'ILLEGAL', 'UNDETERMINED')
    ),
    CONSTRAINT ck_stage7_review_version CHECK (version >= 0)
);

CREATE TABLE legality_review_history (
    history_id              VARCHAR(36) PRIMARY KEY,
    evaluation_id           VARCHAR(36) NOT NULL REFERENCES legality_review (evaluation_id) ON DELETE RESTRICT,
    version                 BIGINT NOT NULL,
    previous_state          VARCHAR(16) NOT NULL,
    resulting_state         VARCHAR(16) NOT NULL,
    conclusion              VARCHAR(16) NOT NULL,
    status_before           VARCHAR(16) NOT NULL,
    status_after            VARCHAR(16),
    note                    VARCHAR(1000) NOT NULL,
    actor_id                VARCHAR(36) NOT NULL REFERENCES app_user (user_id) ON DELETE RESTRICT,
    related_evaluation_id   VARCHAR(36) REFERENCES rule_evaluation (evaluation_id) ON DELETE RESTRICT,
    related_alarm_id        VARCHAR(36) REFERENCES alarm (alarm_id) ON DELETE RESTRICT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    -- 同一复核版本只能有一条历史：并发写穿透时由数据库拒绝，而不是 Java 先查后写。
    CONSTRAINT uk_stage7_review_history_version UNIQUE (evaluation_id, version),
    CONSTRAINT ck_stage7_review_history_version CHECK (version > 0),
    CONSTRAINT ck_stage7_review_history_conclusion CHECK (
        conclusion IN ('CONFIRM', 'REJECT', 'OVERRIDE', 'RECOMPUTE', 'ESCALATE')
    ),
    CONSTRAINT ck_stage7_review_history_states CHECK (
        previous_state IN ('PENDING_REVIEW', 'CONFIRMED', 'REJECTED', 'OVERRIDDEN', 'SUPERSEDED')
        AND resulting_state IN ('PENDING_REVIEW', 'CONFIRMED', 'REJECTED', 'OVERRIDDEN', 'SUPERSEDED')
    ),
    CONSTRAINT ck_stage7_review_history_note CHECK (TRIM(note) <> '')
);

CREATE TABLE alarm_merge_group (
    group_id            VARCHAR(36) PRIMARY KEY,
    target_id           VARCHAR(36) NOT NULL REFERENCES target (target_id) ON DELETE RESTRICT,
    alarm_type          VARCHAR(64) NOT NULL,
    rule_set_id         VARCHAR(36) NOT NULL REFERENCES rule_set (rule_set_id) ON DELETE RESTRICT,
    state               VARCHAR(16) NOT NULL,
    current_severity    VARCHAR(16) NOT NULL,
    first_alarm_id      VARCHAR(36) NOT NULL REFERENCES alarm (alarm_id) ON DELETE RESTRICT,
    latest_alarm_id     VARCHAR(36) NOT NULL REFERENCES alarm (alarm_id) ON DELETE RESTRICT,
    hit_count           INTEGER NOT NULL,
    window_opened_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    window_expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_hit_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at           TIMESTAMP WITH TIME ZONE,
    closed_reason       VARCHAR(64),
    owner_org_id        VARCHAR(36) NOT NULL REFERENCES app_org (org_id) ON DELETE RESTRICT,
    district_id         VARCHAR(36) NOT NULL REFERENCES app_district (district_id) ON DELETE RESTRICT,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stage7_merge_group_state CHECK (state IN ('OPEN', 'AUTO_CLOSED')),
    CONSTRAINT ck_stage7_merge_group_severity CHECK (
        current_severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN')
    ),
    CONSTRAINT ck_stage7_merge_group_hits CHECK (hit_count >= 1),
    CONSTRAINT ck_stage7_merge_group_window CHECK (window_opened_at <= window_expires_at),
    -- 关闭时必须同时留下关闭时刻与原因；OPEN 组不能带关闭信息。
    CONSTRAINT ck_stage7_merge_group_closed CHECK (
        (state = 'OPEN' AND closed_at IS NULL AND closed_reason IS NULL)
        OR (state = 'AUTO_CLOSED' AND closed_at IS NOT NULL AND closed_reason IS NOT NULL)
    ),
    CONSTRAINT ck_stage7_merge_group_version CHECK (version >= 0)
);

CREATE TABLE alarm_merge_member (
    member_id       VARCHAR(36) PRIMARY KEY,
    group_id        VARCHAR(36) NOT NULL REFERENCES alarm_merge_group (group_id) ON DELETE RESTRICT,
    evaluation_id   VARCHAR(36) NOT NULL REFERENCES rule_evaluation (evaluation_id) ON DELETE RESTRICT,
    alarm_id        VARCHAR(36) REFERENCES alarm (alarm_id) ON DELETE RESTRICT,
    member_kind     VARCHAR(24) NOT NULL,
    severity_before VARCHAR(16),
    severity_after  VARCHAR(16) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    -- 一条研判在合并链里只出现一次：同一研判重复进入 C06 只能回放既有成员。
    CONSTRAINT uk_stage7_merge_member_evaluation UNIQUE (evaluation_id),
    CONSTRAINT ck_stage7_merge_member_kind CHECK (
        member_kind IN ('CREATED', 'MERGED', 'UPGRADED', 'DOWNGRADED', 'MANUAL_ESCALATION')
    ),
    -- 建了告警的成员必须带 alarm_id；只计数的成员不能伪造告警引用。
    CONSTRAINT ck_stage7_merge_member_alarm CHECK (
        (member_kind IN ('CREATED', 'UPGRADED', 'MANUAL_ESCALATION') AND alarm_id IS NOT NULL)
        OR (member_kind IN ('MERGED', 'DOWNGRADED') AND alarm_id IS NULL)
    ),
    CONSTRAINT ck_stage7_merge_member_severity CHECK (
        severity_after IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN')
        AND (severity_before IS NULL OR severity_before IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN'))
    )
);

CREATE INDEX idx_stage7_review_scope_state ON legality_review (owner_org_id, district_id, review_state);
CREATE INDEX idx_stage7_review_history_evaluation ON legality_review_history (evaluation_id, version ASC);
CREATE INDEX idx_stage7_merge_group_target_state ON alarm_merge_group (target_id, alarm_type, state, window_opened_at DESC);
CREATE INDEX idx_stage7_merge_group_expiry ON alarm_merge_group (state, window_expires_at ASC);
CREATE INDEX idx_stage7_merge_member_group ON alarm_merge_member (group_id, created_at ASC);
CREATE INDEX idx_stage7_merge_member_alarm ON alarm_merge_member (alarm_id);

-- 规则效果事实：一条研判一行，拼上复核结论与告警合并结果，供 /rule-effects/facts|summary 读取。
-- alarm_id 优先取引擎回填的 rule_evaluation.alarm_id，其次取合并成员的 alarm_id（人工转告警的记录在复核历史）。
CREATE VIEW v_rule_effect_fact AS
SELECT e.evaluation_id,
       e.evaluated_at,
       e.mode,
       e.subject_kind,
       e.target_id,
       e.plan_id,
       e.rule_set_version_id,
       v.param_status,
       e.legal_status,
       r.manual_status,
       r.review_state,
       (COALESCE(e.alarm_id, m.alarm_id) IS NOT NULL) AS has_alarm,
       m.member_kind AS merge_kind,
       COALESCE(e.alarm_id, m.alarm_id) AS alarm_id,
       m.group_id,
       e.supersedes_evaluation_id,
       e.source_mode,
       e.owner_org_id,
       e.district_id
FROM rule_evaluation e
JOIN rule_set_version v ON v.rule_set_version_id = e.rule_set_version_id
LEFT JOIN legality_review r ON r.evaluation_id = e.evaluation_id
LEFT JOIN alarm_merge_member m ON m.evaluation_id = e.evaluation_id;
