-- 阶段 7 规则引擎核心表。所有阈值/权重/容差放在 rule_param 并带 DEMO/CONFIRMED 状态，代码中不出现裸阈值；
-- 生产默认没有 ACTIVE 版本，引擎空转。rule_evaluation 与 rule_run 只增（PostgreSQL 触发器见 R__stage7_rule_engine_constraints.sql）。

CREATE TABLE rule_set (
    rule_set_id                 VARCHAR(36) PRIMARY KEY,
    rule_set_code               VARCHAR(64) NOT NULL,
    name                        VARCHAR(128) NOT NULL,
    active_version_id           VARCHAR(36),
    shadow_version_id           VARCHAR(36),
    previous_active_version_id  VARCHAR(36),
    version                     BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_stage7_rule_set_code UNIQUE (rule_set_code),
    CONSTRAINT ck_stage7_rule_set_code_nonblank CHECK (TRIM(rule_set_code) <> ''),
    CONSTRAINT ck_stage7_rule_set_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage7_rule_set_version CHECK (version >= 0)
);

CREATE TABLE rule_set_version (
    rule_set_version_id VARCHAR(36) PRIMARY KEY,
    rule_set_id         VARCHAR(36) NOT NULL REFERENCES rule_set (rule_set_id) ON DELETE RESTRICT,
    version_no          INTEGER NOT NULL,
    status_code         VARCHAR(16) NOT NULL,
    param_status        VARCHAR(16) NOT NULL,
    valid_from          TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to            TIMESTAMP WITH TIME ZONE,
    description         VARCHAR(512),
    source_mode         VARCHAR(8) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_stage7_rule_set_version_number UNIQUE (rule_set_id, version_no),
    CONSTRAINT ck_stage7_rule_set_version_number CHECK (version_no > 0),
    CONSTRAINT ck_stage7_rule_set_version_status CHECK (status_code IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    -- 参数状态是版本级事实：DEMO 版本在 allow-demo-active=false 的环境不能被激活。
    CONSTRAINT ck_stage7_rule_set_version_param_status CHECK (param_status IN ('DEMO', 'CONFIRMED')),
    CONSTRAINT ck_stage7_rule_set_version_validity CHECK (valid_to IS NULL OR valid_from < valid_to),
    CONSTRAINT ck_stage7_rule_set_version_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage7_rule_set_version_published CHECK (status_code = 'DRAFT' OR published_at IS NOT NULL)
);

-- 头行的三个版本指针在建表后补外键，避免循环建表顺序。
ALTER TABLE rule_set ADD CONSTRAINT fk_stage7_rule_set_active_version
    FOREIGN KEY (active_version_id) REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT;
ALTER TABLE rule_set ADD CONSTRAINT fk_stage7_rule_set_shadow_version
    FOREIGN KEY (shadow_version_id) REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT;
ALTER TABLE rule_set ADD CONSTRAINT fk_stage7_rule_set_previous_version
    FOREIGN KEY (previous_active_version_id) REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT;

CREATE TABLE rule_set_member (
    rule_set_version_id VARCHAR(36) NOT NULL REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT,
    rule_version_id     VARCHAR(36) NOT NULL REFERENCES rule_version (rule_version_id) ON DELETE RESTRICT,
    priority            INTEGER NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (rule_set_version_id, rule_version_id)
);

CREATE TABLE rule_param (
    -- 参数行没有外键引用它，允许种子用 "版本:规则:键" 形式的可读确定性 ID，因此比其他表的 36 位宽。
    rule_param_id       VARCHAR(128) PRIMARY KEY,
    rule_set_version_id VARCHAR(36) NOT NULL REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT,
    rule_code           VARCHAR(64) NOT NULL,
    param_key           VARCHAR(64) NOT NULL,
    value_text          VARCHAR(512) NOT NULL,
    value_type          VARCHAR(16) NOT NULL,
    unit                VARCHAR(16),
    param_status        VARCHAR(16) NOT NULL,
    note                VARCHAR(512),
    CONSTRAINT uk_stage7_rule_param_key UNIQUE (rule_set_version_id, rule_code, param_key),
    CONSTRAINT ck_stage7_rule_param_code_nonblank CHECK (TRIM(rule_code) <> ''),
    CONSTRAINT ck_stage7_rule_param_key_nonblank CHECK (TRIM(param_key) <> ''),
    CONSTRAINT ck_stage7_rule_param_value_nonblank CHECK (TRIM(value_text) <> ''),
    CONSTRAINT ck_stage7_rule_param_type CHECK (value_type IN ('NUMBER', 'INTEGER', 'BOOLEAN', 'STRING', 'LIST')),
    CONSTRAINT ck_stage7_rule_param_status CHECK (param_status IN ('DEMO', 'CONFIRMED'))
);

CREATE TABLE rule_set_activation (
    activation_id       VARCHAR(36) PRIMARY KEY,
    rule_set_id         VARCHAR(36) NOT NULL REFERENCES rule_set (rule_set_id) ON DELETE RESTRICT,
    kind                VARCHAR(16) NOT NULL,
    from_version_id     VARCHAR(36) REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT,
    to_version_id       VARCHAR(36) REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT,
    actor_id            VARCHAR(36) NOT NULL REFERENCES app_user (user_id) ON DELETE RESTRICT,
    note                VARCHAR(1000) NOT NULL,
    resulting_version   BIGINT NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_stage7_rule_set_activation_version UNIQUE (rule_set_id, resulting_version),
    CONSTRAINT ck_stage7_rule_set_activation_kind CHECK (kind IN ('ACTIVATE', 'ROLLBACK', 'SHADOW_SET', 'SHADOW_CLEAR')),
    CONSTRAINT ck_stage7_rule_set_activation_note CHECK (TRIM(note) <> '')
);

CREATE TABLE rule_run (
    run_id              VARCHAR(36) PRIMARY KEY,
    rule_set_id         VARCHAR(36) NOT NULL REFERENCES rule_set (rule_set_id) ON DELETE RESTRICT,
    rule_set_version_id VARCHAR(36) NOT NULL REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT,
    mode                VARCHAR(8) NOT NULL,
    trigger_kind        VARCHAR(16) NOT NULL,
    replay_dataset_code VARCHAR(64),
    triggered_by        VARCHAR(36),
    as_of               TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at         TIMESTAMP WITH TIME ZONE,
    status              VARCHAR(16) NOT NULL,
    subject_count       INTEGER NOT NULL DEFAULT 0,
    evaluated_count     INTEGER NOT NULL DEFAULT 0,
    alarm_created_count INTEGER NOT NULL DEFAULT 0,
    alarm_merged_count  INTEGER NOT NULL DEFAULT 0,
    error_summary       VARCHAR(2000),
    source_mode         VARCHAR(8) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stage7_rule_run_mode CHECK (mode IN ('ACTIVE', 'SHADOW')),
    CONSTRAINT ck_stage7_rule_run_trigger CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'RECOMPUTE', 'REPLAY')),
    CONSTRAINT ck_stage7_rule_run_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'SKIPPED')),
    -- 回放必须声明数据集；非回放不得带数据集，否则 R__ 中的部分唯一索引失去意义。
    CONSTRAINT ck_stage7_rule_run_replay_dataset CHECK (
        (trigger_kind = 'REPLAY' AND replay_dataset_code IS NOT NULL AND TRIM(replay_dataset_code) <> '')
        OR (trigger_kind <> 'REPLAY' AND replay_dataset_code IS NULL)
    ),
    CONSTRAINT ck_stage7_rule_run_counts CHECK (
        subject_count >= 0 AND evaluated_count >= 0 AND alarm_created_count >= 0 AND alarm_merged_count >= 0
    ),
    CONSTRAINT ck_stage7_rule_run_source_mode CHECK (source_mode IN ('mock', 'replay', 'live'))
);

CREATE TABLE rule_evaluation (
    evaluation_id           VARCHAR(36) PRIMARY KEY,
    run_id                  VARCHAR(36) NOT NULL REFERENCES rule_run (run_id) ON DELETE RESTRICT,
    rule_set_version_id     VARCHAR(36) NOT NULL REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT,
    mode                    VARCHAR(8) NOT NULL,
    subject_kind            VARCHAR(8) NOT NULL,
    target_id               VARCHAR(36) REFERENCES target (target_id) ON DELETE RESTRICT,
    track_id                VARCHAR(36) REFERENCES track (track_id) ON DELETE RESTRICT,
    plan_id                 VARCHAR(36) REFERENCES flight_plan (plan_id) ON DELETE RESTRICT,
    route_version_id        VARCHAR(36) REFERENCES route_version (route_version_id) ON DELETE RESTRICT,
    observed_at             TIMESTAMP WITH TIME ZONE,
    as_of                   TIMESTAMP WITH TIME ZONE NOT NULL,
    evaluated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    freshness_code          VARCHAR(16) NOT NULL,
    plan_match_code         VARCHAR(16) NOT NULL,
    legal_status            VARCHAR(16) NOT NULL,
    score                   NUMERIC(6, 2),
    grade                   VARCHAR(8),
    violation_reasons       JSONB NOT NULL,
    hit_details             JSONB NOT NULL,
    unknown_reasons         JSONB NOT NULL,
    evidence_references     JSONB NOT NULL,
    input_snapshot          JSONB NOT NULL,
    supersedes_evaluation_id VARCHAR(36) REFERENCES rule_evaluation (evaluation_id) ON DELETE RESTRICT,
    assessment_id           VARCHAR(36) REFERENCES assessment_result (assessment_id) ON DELETE RESTRICT,
    alarm_outcome           JSONB,
    alarm_id                VARCHAR(36) REFERENCES alarm (alarm_id) ON DELETE RESTRICT,
    owner_org_id            VARCHAR(36) NOT NULL REFERENCES app_org (org_id) ON DELETE RESTRICT,
    district_id             VARCHAR(36) NOT NULL REFERENCES app_district (district_id) ON DELETE RESTRICT,
    source_mode             VARCHAR(8) NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stage7_rule_evaluation_mode CHECK (mode IN ('ACTIVE', 'SHADOW')),
    CONSTRAINT ck_stage7_rule_evaluation_subject CHECK (subject_kind IN ('TARGET', 'PLAN')),
    CONSTRAINT ck_stage7_rule_evaluation_freshness CHECK (freshness_code IN ('FRESH', 'STALE', 'NO_STATE', 'REPLAY')),
    CONSTRAINT ck_stage7_rule_evaluation_plan_match CHECK (
        plan_match_code IN ('FULL', 'PARTIAL', 'NONE', 'UNDETERMINED', 'NOT_APPLICABLE')
    ),
    CONSTRAINT ck_stage7_rule_evaluation_legal_status CHECK (
        legal_status IN ('LEGAL', 'ABNORMAL', 'ILLEGAL', 'UNDETERMINED', 'NOT_APPLICABLE')
    ),
    -- 评分只在 ILLEGAL/ABNORMAL 出现且必须成对；等级不能脱离分数单独存在。
    CONSTRAINT ck_stage7_rule_evaluation_score_grade CHECK (
        (score IS NULL AND grade IS NULL)
        OR (score IS NOT NULL AND grade IN ('HIGH', 'MEDIUM', 'LOW') AND legal_status IN ('ILLEGAL', 'ABNORMAL'))
    ),
    CONSTRAINT ck_stage7_rule_evaluation_source_mode CHECK (source_mode IN ('mock', 'replay', 'live'))
);

-- 单行租约：多实例只允许一个 Worker 领取；条件更新在 RuleEngineWorker 中执行。
CREATE TABLE rule_engine_lease (
    lease_name  VARCHAR(64) PRIMARY KEY,
    holder      VARCHAR(128),
    lease_until TIMESTAMP WITH TIME ZONE,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO rule_engine_lease (lease_name, holder, lease_until, updated_at)
VALUES ('rule-engine', NULL, NULL, CURRENT_TIMESTAMP);

-- 迁移 040 已加列；引擎投影行必须钉住产生它的规则集版本。
ALTER TABLE assessment_result ADD CONSTRAINT fk_stage7_assessment_rule_set_version
    FOREIGN KEY (rule_set_version_id) REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT;

CREATE INDEX idx_stage7_rule_set_version_set ON rule_set_version (rule_set_id, version_no DESC);
CREATE INDEX idx_stage7_rule_param_version ON rule_param (rule_set_version_id, rule_code, param_key);
CREATE INDEX idx_stage7_rule_set_activation_set ON rule_set_activation (rule_set_id, resulting_version ASC);
CREATE INDEX idx_stage7_rule_run_started ON rule_run (started_at DESC, run_id DESC);
CREATE INDEX idx_stage7_rule_run_mode_trigger ON rule_run (mode, trigger_kind, started_at DESC);
CREATE INDEX idx_stage7_rule_evaluation_target_mode ON rule_evaluation (target_id, mode, observed_at DESC, evaluation_id DESC);
CREATE INDEX idx_stage7_rule_evaluation_plan ON rule_evaluation (plan_id, evaluated_at DESC);
CREATE INDEX idx_stage7_rule_evaluation_run ON rule_evaluation (run_id);
CREATE INDEX idx_stage7_rule_evaluation_scope_evaluated ON rule_evaluation (owner_org_id, district_id, evaluated_at DESC, evaluation_id DESC);
CREATE INDEX idx_stage7_rule_evaluation_supersedes ON rule_evaluation (supersedes_evaluation_id);
