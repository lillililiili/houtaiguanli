-- Separate unit management grants from front-office role and action permissions.
CREATE TABLE app_org_management_permission (
    org_id VARCHAR(36) NOT NULL REFERENCES app_org_delegation(org_id),
    module_key VARCHAR(32) NOT NULL,
    permission_level VARCHAR(8) NOT NULL,
    PRIMARY KEY (org_id,module_key),
    CONSTRAINT ck_unit_management_module CHECK (module_key IN ('devices','monitoring','maintenance','commissioning','statistics')),
    CONSTRAINT ck_unit_management_level CHECK (permission_level IN ('NONE','READ','OP')),
    CONSTRAINT ck_unit_monitoring_read CHECK (module_key<>'monitoring' OR permission_level IN ('NONE','READ'))
);

-- Freeze unit-management context and the actual person queuing each execution.
-- Existing platform tasks retain NULL context; historical ownership is not inferred.
ALTER TABLE commission_task ADD COLUMN management_org_id VARCHAR(36) REFERENCES app_org(org_id);
ALTER TABLE commission_task ADD COLUMN execution_requested_by VARCHAR(36) REFERENCES app_user(user_id);
