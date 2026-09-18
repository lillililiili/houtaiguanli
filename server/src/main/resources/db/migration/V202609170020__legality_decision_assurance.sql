-- 算法结论充分性独立于风险评分与人工复核历史；旧记录保持 NULL，不补造历史判定。
ALTER TABLE rule_evaluation ADD COLUMN decision_algorithm_version VARCHAR(64);
ALTER TABLE rule_evaluation ADD COLUMN decision_assurance_code VARCHAR(32);
ALTER TABLE rule_evaluation ADD COLUMN decision_assurance_reasons JSON;
ALTER TABLE rule_evaluation ADD CONSTRAINT ck_evaluation_decision_assurance CHECK (
    (decision_algorithm_version IS NULL AND decision_assurance_code IS NULL AND decision_assurance_reasons IS NULL)
    OR (decision_algorithm_version IS NOT NULL AND TRIM(decision_algorithm_version) <> ''
        AND decision_assurance_code IS NOT NULL AND decision_assurance_code IN ('SUFFICIENT', 'INSUFFICIENT', 'NOT_APPLICABLE')
        AND decision_assurance_reasons IS NOT NULL)
);
CREATE INDEX ix_evaluation_decision_assurance ON rule_evaluation (decision_assurance_code, mode, evaluated_at);
