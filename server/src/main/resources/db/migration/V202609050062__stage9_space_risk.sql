-- 阶段 9（E2）：空间安全风险。C04/C05 的产出仍是阶段 4 的 flight_risk（决策 9-5），
-- 本迁移只补细类字典、规则参数、事实明细与评估运行记录，不新建第二套风险表。

-- 异物细类字典：按 target.subtype 精确或别名匹配（决策 9-6）；匹配不到不进 C04，不归类到 OTHER，
-- 因为"未识别"和"识别为其他异物"是两个不同事实。
CREATE TABLE space_object_subtype (
    subtype_code    VARCHAR(32) PRIMARY KEY,
    display_name    VARCHAR(64) NOT NULL,
    aliases         JSON NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_stage9_subtype_name_nonblank CHECK (TRIM(display_name) <> '')
);

INSERT INTO space_object_subtype (subtype_code, display_name, aliases, enabled, sort_order, created_at) VALUES
    ('BIRD_FLOCK',  '鸟群',   '["BIRD_FLOCK","BIRD","MIGRATORY_BIRD","RAPTOR","鸟群","鸟","候鸟"]', TRUE, 10, CURRENT_TIMESTAMP),
    ('BALLOON',     '气球',   '["BALLOON","气球"]',                                                 TRUE, 20, CURRENT_TIMESTAMP),
    ('KITE',        '风筝',   '["KITE","风筝"]',                                                    TRUE, 30, CURRENT_TIMESTAMP),
    ('SKY_LANTERN', '孔明灯', '["SKY_LANTERN","LANTERN","孔明灯"]',                                 TRUE, 40, CURRENT_TIMESTAMP),
    ('OTHER_OBJECT','其他异物','["OTHER_OBJECT","其他异物"]',                                        TRUE, 50, CURRENT_TIMESTAMP);

-- 规则引擎来源：与阶段 7 合法性来源分开（决策 9-8），统计口径可分。系统目录行，没有设备类型也没有凭据。
INSERT INTO integration_source (source_id, source_code, name, protocol_code, protocol_version, enabled, credential_ref, source_mode, created_at, updated_at, version)
VALUES
    ('rule-engine-space-risk-live', 'RULE-ENGINE-SPACE-RISK-LIVE', '空间安全风险规则引擎', NULL, NULL, TRUE, NULL, 'live', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('rule-engine-space-risk-mock', 'RULE-ENGINE-SPACE-RISK-MOCK', '空间安全风险规则引擎（模拟）', NULL, NULL, TRUE, NULL, 'mock', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- C04/C05 参数走阶段 7 的 rule_param 机制（决策 9-7）：规则集 SPACE-RISK-DEMO v1，全部 DEMO 值，未经业务方确认。
INSERT INTO rule_set (rule_set_id, rule_set_code, name, active_version_id, shadow_version_id, previous_active_version_id, version, created_at, updated_at)
VALUES ('space-risk-demo', 'SPACE-RISK-DEMO', '空间安全风险规则集', NULL, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO rule_set_version (rule_set_version_id, rule_set_id, version_no, status_code, param_status, valid_from, valid_to, description, source_mode, created_at, published_at)
VALUES ('space-risk-demo-v1', 'space-risk-demo', 1, 'PUBLISHED', 'DEMO', CURRENT_TIMESTAMP, NULL, 'C04/C05 演示阈值，未经业务方确认', 'mock', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 决策 9-16：迁移只建规则集与 PUBLISHED 的 DEMO 版本，**不设为 ACTIVE**。
-- 与阶段 7 同一原则：生产默认没有生效规则集，DEMO 参数只能在 allow-demo-active 打开时经
-- POST /rule-sets/{code}/activate 激活；local/test 的激活由 LocalStage9SpaceRiskSeeder 完成。

INSERT INTO rule_version (rule_version_id, rule_code, version_no, status_code, valid_from, source_mode, created_at) VALUES
    ('space-risk-c04-v1', 'C04', 1, 'ACTIVE', CURRENT_TIMESTAMP, 'mock', CURRENT_TIMESTAMP),
    ('space-risk-c05-v1', 'C05', 1, 'ACTIVE', CURRENT_TIMESTAMP, 'mock', CURRENT_TIMESTAMP);

INSERT INTO rule_set_member (rule_set_version_id, rule_version_id, priority, enabled) VALUES
    ('space-risk-demo-v1', 'space-risk-c04-v1', 10, TRUE),
    ('space-risk-demo-v1', 'space-risk-c05-v1', 20, TRUE);

INSERT INTO rule_param (rule_param_id, rule_set_version_id, rule_code, param_key, value_text, value_type, unit, param_status, note) VALUES
    ('space-risk-demo-v1:C04:corridor_near_m',       'space-risk-demo-v1', 'C04', 'corridor_near_m',       '300', 'NUMBER',  'm',   'DEMO', '演示值：邻近航线判定距离'),
    ('space-risk-demo-v1:C04:climb_band_agl_m',      'space-risk-demo-v1', 'C04', 'climb_band_agl_m',      '150', 'NUMBER',  'm',   'DEMO', '演示值：起降爬升段上界'),
    ('space-risk-demo-v1:C04:approach_band_agl_m',   'space-risk-demo-v1', 'C04', 'approach_band_agl_m',   '300', 'NUMBER',  'm',   'DEMO', '演示值：进近段上界'),
    ('space-risk-demo-v1:C04:flock_count_threshold', 'space-risk-demo-v1', 'C04', 'flock_count_threshold', '20',  'INTEGER', '只',  'DEMO', '演示值：成群数量阈值'),
    ('space-risk-demo-v1:C04:trend_window_min',      'space-risk-demo-v1', 'C04', 'trend_window_min',      '30',  'INTEGER', 'min', 'DEMO', '演示值：趋势观察窗口'),
    ('space-risk-demo-v1:C04:plan_window_pad_min',   'space-risk-demo-v1', 'C04', 'plan_window_pad_min',   '15',  'INTEGER', 'min', 'DEMO', '演示值：计划时间窗前后放宽'),
    ('space-risk-demo-v1:C05:procedure_buffer_m',    'space-risk-demo-v1', 'C05', 'procedure_buffer_m',    '500', 'NUMBER',  'm',   'DEMO', '演示值：进离场航线缓冲'),
    ('space-risk-demo-v1:C05:protected_target_pad_m','space-risk-demo-v1', 'C05', 'protected_target_pad_m','200', 'NUMBER',  'm',   'DEMO', '演示值：保护目标半径外扩');

-- 风险的空间事实明细：risk_id 与 flight_risk 一对一，只增。
-- 判定依据必须与风险同生共死地保存：参数会随版本变化，事后再算一遍得不到当时的结论。
CREATE TABLE space_risk_fact (
    risk_id                 VARCHAR(36) PRIMARY KEY,
    subtype_code            VARCHAR(32) NOT NULL,
    rule_version_id         VARCHAR(36) NOT NULL,
    rule_set_version_id     VARCHAR(36) NOT NULL,
    distance_to_route_m     NUMERIC(12, 2),
    corridor_relation       VARCHAR(16) NOT NULL,
    altitude_band           VARCHAR(16) NOT NULL,
    altitude_datum          VARCHAR(16),
    object_count            INTEGER,
    trend                   VARCHAR(16) NOT NULL,
    -- 决策 9-18：数量与趋势当前没有数据源，缺失时决策表不上调等级，并在这里如实记 OBJECT_COUNT_UNAVAILABLE /
    -- TREND_UNAVAILABLE。把"没有这项事实"写下来，页面才不会把"没上调"误读成"评估过且不严重"。
    unknown_reasons         JSON NOT NULL,
    -- 决策 9-19：评估时刻的目标位置快照。风险本身没有坐标，页面要按等级在地图上标点就只能靠它；
    -- 目标状态随后会移动，事后再查得到的已经不是判定当时的位置。为空表示当时没有可信坐标，页面不画点。
    target_location         GEOMETRY(POINT, 4326),
    target_altitude_raw     NUMERIC(10, 2),
    window_from             TIMESTAMP WITH TIME ZONE NOT NULL,
    window_to               TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage9_space_fact_risk FOREIGN KEY (risk_id) REFERENCES flight_risk (risk_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_space_fact_subtype FOREIGN KEY (subtype_code) REFERENCES space_object_subtype (subtype_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_space_fact_rule_version FOREIGN KEY (rule_version_id) REFERENCES rule_version (rule_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_space_fact_rule_set_version FOREIGN KEY (rule_set_version_id) REFERENCES rule_set_version (rule_set_version_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage9_space_fact_relation CHECK (corridor_relation IN ('INSIDE', 'NEAR', 'OUTSIDE', 'UNKNOWN')),
    CONSTRAINT ck_stage9_space_fact_band CHECK (altitude_band IN ('CLIMB', 'APPROACH', 'CRUISE', 'UNKNOWN')),
    CONSTRAINT ck_stage9_space_fact_datum CHECK (altitude_datum IS NULL OR altitude_datum IN ('AGL', 'AMSL')),
    CONSTRAINT ck_stage9_space_fact_trend CHECK (trend IN ('RISING', 'FLAT', 'FALLING', 'UNKNOWN')),
    CONSTRAINT ck_stage9_space_fact_count CHECK (object_count IS NULL OR object_count >= 0),
    CONSTRAINT ck_stage9_space_fact_window CHECK (window_from < window_to)
);

CREATE INDEX idx_stage9_space_fact_subtype ON space_risk_fact (subtype_code, created_at DESC);

-- 评估运行记录：一次手动或定时评估一行。UNAVAILABLE 表示空间后端不可用（H2），
-- 它是一次如实记录的运行结果，不是失败，也不能当成"没有风险"。
CREATE TABLE rule_evaluation_run (
    run_id                  VARCHAR(36) PRIMARY KEY,
    rule_code               VARCHAR(16) NOT NULL,
    trigger_kind            VARCHAR(16) NOT NULL,
    window_from             TIMESTAMP WITH TIME ZONE NOT NULL,
    window_to               TIMESTAMP WITH TIME ZONE NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    targets_seen            INTEGER NOT NULL DEFAULT 0,
    risks_created           INTEGER NOT NULL DEFAULT 0,
    risks_deduplicated      INTEGER NOT NULL DEFAULT 0,
    message                 VARCHAR(500),
    actor_id                VARCHAR(36),
    started_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at             TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_stage9_rule_run_actor FOREIGN KEY (actor_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage9_rule_run_code CHECK (rule_code IN ('C04', 'C05')),
    CONSTRAINT ck_stage9_rule_run_trigger CHECK (trigger_kind IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT ck_stage9_rule_run_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'UNAVAILABLE')),
    CONSTRAINT ck_stage9_rule_run_window CHECK (window_from < window_to),
    CONSTRAINT ck_stage9_rule_run_counts CHECK (targets_seen >= 0 AND risks_created >= 0 AND risks_deduplicated >= 0)
);

CREATE INDEX idx_stage9_rule_run_code_time ON rule_evaluation_run (rule_code, started_at DESC, run_id DESC);
