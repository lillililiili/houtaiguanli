-- 驳回保留真实审批人和审批时间，但不产生可执行的授权窗口。
-- 其余 REVIEW 和 DIRECT 约束保持原样，不回写旧审批记录。
ALTER TABLE disposal_authorization DROP CONSTRAINT ck_stage13_authorization_approval;
ALTER TABLE disposal_authorization ADD CONSTRAINT ck_stage13_authorization_approval CHECK (
    (authorization_mode='REVIEW' AND (
        (approved_by IS NULL AND approved_at IS NULL AND valid_from IS NULL AND valid_until IS NULL)
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND valid_from IS NOT NULL AND valid_until IS NOT NULL)
        OR (status='REJECTED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND valid_from IS NULL AND valid_until IS NULL)))
    OR (authorization_mode='DIRECT' AND approved_by IS NULL AND approved_at IS NULL
        AND valid_from IS NOT NULL AND valid_until IS NOT NULL AND valid_until > valid_from)
);
