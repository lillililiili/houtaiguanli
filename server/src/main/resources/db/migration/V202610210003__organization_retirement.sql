-- Retire directory entries without deleting historical ownership, users, profiles or business records.
ALTER TABLE app_org ADD COLUMN deleted_at BIGINT;
ALTER TABLE app_org ADD COLUMN deleted_by VARCHAR(36);
ALTER TABLE app_org ADD CONSTRAINT ck_org_retirement CHECK (
    (deleted_at IS NULL AND deleted_by IS NULL)
    OR (deleted_at IS NOT NULL AND deleted_at>=0 AND deleted_by IS NOT NULL AND enabled=FALSE));
CREATE INDEX idx_org_current_parent ON app_org(deleted_at,parent_id,org_id);
