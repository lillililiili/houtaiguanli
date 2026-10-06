-- 2026-10-06 告警升级（验收问题 BUG-11 偏航后告警不变、BUG-16 同一架无人机出两条告警）。
-- 同一目标在合并窗口内再次违规，等级更高或出现新的违规原因时，不再另建一条告警、另起一次核实，
-- 而是把原告警升级：每次升级追加一行 alarm_escalation，记下升级前后的等级、新增与累计的违规原因，
-- 以及由谁（系统研判 / 人工转告警）在什么时候、因为什么升级。
-- alarm 行仍然只增不改（证据链按告警原始等级做指纹）；页面上的当前等级与违规原因取最近一次升级，
-- 没有升级时就是告警原值。PostgreSQL 的只增触发器与实时刷新触发器在 db/postgresql/V202610069132（H2 不加载）。

CREATE TABLE alarm_escalation (
    escalation_id       VARCHAR(36) PRIMARY KEY,
    alarm_id            VARCHAR(36) NOT NULL REFERENCES alarm (alarm_id) ON DELETE RESTRICT,
    -- 同一告警的第几次升级（从 1 起连续）；最近一次即当前状态。
    seq                 INTEGER NOT NULL,
    group_id            VARCHAR(36) REFERENCES alarm_merge_group (group_id) ON DELETE RESTRICT,
    evaluation_id       VARCHAR(36) NOT NULL REFERENCES rule_evaluation (evaluation_id) ON DELETE RESTRICT,
    trigger_kind        VARCHAR(16) NOT NULL,
    severity_before     VARCHAR(16) NOT NULL,
    severity_after      VARCHAR(16) NOT NULL,
    reasons_added       JSONB NOT NULL,
    reasons_after       JSONB NOT NULL,
    note                VARCHAR(1000),
    actor_id            VARCHAR(36) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    owner_org_id        VARCHAR(36) NOT NULL REFERENCES app_org (org_id) ON DELETE RESTRICT,
    district_id         VARCHAR(36) NOT NULL REFERENCES app_district (district_id) ON DELETE RESTRICT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_alarm_escalation_seq UNIQUE (alarm_id, seq),
    -- 一条研判最多让告警升级一次：同一研判重复进入只能回放既有结果。
    CONSTRAINT uk_alarm_escalation_evaluation UNIQUE (evaluation_id),
    CONSTRAINT ck_alarm_escalation_seq CHECK (seq >= 1),
    CONSTRAINT ck_alarm_escalation_trigger CHECK (trigger_kind IN ('ENGINE', 'MANUAL')),
    CONSTRAINT ck_alarm_escalation_severity CHECK (
        severity_before IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN')
        AND severity_after IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN')
    ),
    -- 人工转告警必须留下操作人；系统研判升级没有操作人。
    CONSTRAINT ck_alarm_escalation_actor CHECK (
        (trigger_kind = 'MANUAL' AND actor_id IS NOT NULL)
        OR (trigger_kind = 'ENGINE' AND actor_id IS NULL)
    )
);

CREATE INDEX idx_alarm_escalation_group ON alarm_escalation (group_id);

-- 合并成员新增 ESCALATED：命中并入原告警并使其升级，成员 alarm_id 指向被升级的原告警（不是新建的告警）。
ALTER TABLE alarm_merge_member DROP CONSTRAINT ck_stage7_merge_member_kind;
ALTER TABLE alarm_merge_member ADD CONSTRAINT ck_stage7_merge_member_kind CHECK (
    member_kind IN ('CREATED', 'MERGED', 'UPGRADED', 'DOWNGRADED', 'MANUAL_ESCALATION', 'ESCALATED')
);
ALTER TABLE alarm_merge_member DROP CONSTRAINT ck_stage7_merge_member_alarm;
ALTER TABLE alarm_merge_member ADD CONSTRAINT ck_stage7_merge_member_alarm CHECK (
    (member_kind IN ('CREATED', 'UPGRADED', 'MANUAL_ESCALATION', 'ESCALATED') AND alarm_id IS NOT NULL)
    OR (member_kind IN ('MERGED', 'DOWNGRADED') AND alarm_id IS NULL)
);
