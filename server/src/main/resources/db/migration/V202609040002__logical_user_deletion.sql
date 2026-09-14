ALTER TABLE app_user DROP CONSTRAINT ck_app_user_status;
ALTER TABLE app_user ADD COLUMN deleted_account VARCHAR(64);
ALTER TABLE app_user ADD COLUMN deleted_role_code VARCHAR(32);
ALTER TABLE app_user ADD COLUMN deleted_at BIGINT;
ALTER TABLE app_user ADD COLUMN deleted_by VARCHAR(36);
ALTER TABLE app_user ALTER COLUMN role_code DROP NOT NULL;

ALTER TABLE app_user ADD CONSTRAINT ck_app_user_status
    CHECK (
        (status IN ('ACTIVE', 'DISABLED') AND role_code IS NOT NULL AND deleted_at IS NULL)
        OR
        (status = 'DELETED' AND role_code IS NULL AND deleted_role_code IS NOT NULL AND deleted_at IS NOT NULL)
    );

CREATE INDEX idx_app_user_status ON app_user (status);
