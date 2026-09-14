-- 人工销毁：登记动作权限与销毁留痕列。不默认授权生产值班角色；不自动按年限删除。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('evidence:destroy', 'Stage 9 evidence destroy', NULL, 965, 'evidence', 'ACTION', 'destroy', 'Destroy evidence file bytes');

ALTER TABLE evidence_file ADD COLUMN destroyed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE evidence_file ADD COLUMN destroyed_by VARCHAR(36);
ALTER TABLE evidence_file ADD COLUMN destroy_reason TEXT;
ALTER TABLE evidence_file ADD COLUMN destroy_approval VARCHAR(64);

ALTER TABLE evidence_file ADD CONSTRAINT fk_evidence_file_destroyed_by
    FOREIGN KEY (destroyed_by) REFERENCES app_user (user_id) ON DELETE RESTRICT;
ALTER TABLE evidence_file ADD CONSTRAINT ck_evidence_file_destroy_reason CHECK (
    destroy_reason IS NULL OR LENGTH(TRIM(destroy_reason)) BETWEEN 1 AND 500);
ALTER TABLE evidence_file ADD CONSTRAINT ck_evidence_file_destroy_approval CHECK (
    destroy_approval IS NULL OR LENGTH(TRIM(destroy_approval)) BETWEEN 1 AND 64);
ALTER TABLE evidence_file ADD CONSTRAINT ck_evidence_file_destroyed CHECK (
    (status = 'DESTROYED'
        AND destroyed_at IS NOT NULL
        AND destroyed_by IS NOT NULL
        AND destroy_reason IS NOT NULL)
    OR (status <> 'DESTROYED'
        AND destroyed_at IS NULL
        AND destroyed_by IS NULL
        AND destroy_reason IS NULL
        AND destroy_approval IS NULL));

ALTER TABLE evidence_access_log DROP CONSTRAINT ck_evidence_access_action;
ALTER TABLE evidence_access_log ADD CONSTRAINT ck_evidence_access_action
    CHECK (action IN ('VIEW','DOWNLOAD','VERIFY','EXPORT','DESTROY'));
