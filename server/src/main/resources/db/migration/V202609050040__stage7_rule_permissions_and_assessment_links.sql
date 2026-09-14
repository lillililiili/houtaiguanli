-- 阶段 7 只登记动作目录，不给任何角色默认授权；研判读权限 assessment:read 沿用阶段 3。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('rule:read', 'Stage 7 rule read', NULL, 940, 'rule', 'ACTION', 'read', 'Read rule sets and runs'),
    ('rule:manage', 'Stage 7 rule manage', NULL, 941, 'rule', 'ACTION', 'manage', 'Activate, roll back or shadow rule sets'),
    ('assessment:evaluate', 'Stage 7 legality evaluate', NULL, 942, 'assessment', 'ACTION', 'evaluate', 'Trigger or recompute legality evaluations'),
    ('assessment:revise', 'Stage 7 legality revise', NULL, 943, 'assessment', 'ACTION', 'revise', 'Review legality evaluations'),
    ('assessment:escalate', 'Stage 7 legality escalate', NULL, 944, 'assessment', 'ACTION', 'escalate', 'Escalate evaluations to source alarms');

-- 规则引擎生成的来源告警必须挂在真实的 integration_source 上（alarm.source_id 非空外键）。
-- 三种模式各一行，跟随目标的 source_mode 选用，避免把回放/模拟研判产生的告警混入实测来源。
INSERT INTO integration_source (
    source_id, source_code, name, protocol_code, protocol_version, enabled, credential_ref,
    source_mode, created_at, updated_at, version
)
VALUES
    ('rule-engine-legality-mock', 'RULE-ENGINE-LEGALITY-MOCK', '平台规则引擎（模拟来源）', 'RULE_ENGINE', '1', TRUE, NULL, 'mock', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('rule-engine-legality-replay', 'RULE-ENGINE-LEGALITY-REPLAY', '平台规则引擎（回放来源）', 'RULE_ENGINE', '1', TRUE, NULL, 'replay', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('rule-engine-legality-live', 'RULE-ENGINE-LEGALITY-LIVE', '平台规则引擎（实测来源）', 'RULE_ENGINE', '1', TRUE, NULL, 'live', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- 研判结果保持只追加：重算不改旧行，而是新行通过 supersedes_assessment_id 指向被取代的研判。
-- evaluation_id / rule_set_version_id 把阶段 3 的计划维度投影与阶段 7 引擎输出关联起来（FK 由迁移 041 在建表后补齐）。
ALTER TABLE assessment_result ADD COLUMN evaluation_id VARCHAR(36);
ALTER TABLE assessment_result ADD COLUMN rule_set_version_id VARCHAR(36);
ALTER TABLE assessment_result ADD COLUMN supersedes_assessment_id VARCHAR(36);
ALTER TABLE assessment_result ADD CONSTRAINT fk_stage7_assessment_supersedes
    FOREIGN KEY (supersedes_assessment_id) REFERENCES assessment_result (assessment_id) ON DELETE RESTRICT;

-- 四态判定需要 ABNORMAL（偏航、超计划高度等"异常但非违法"）；ILLEGAL 口径不变，A 的 illegal_assessments 指标不受影响。
ALTER TABLE assessment_result DROP CONSTRAINT ck_stage3_assessment_conclusion;
ALTER TABLE assessment_result ADD CONSTRAINT ck_stage7_assessment_conclusion CHECK (
    conclusion_code IN ('LEGAL', 'ABNORMAL', 'ILLEGAL', 'UNDETERMINED', 'NOT_APPLICABLE')
);

CREATE INDEX idx_stage7_assessment_evaluation ON assessment_result (evaluation_id);
