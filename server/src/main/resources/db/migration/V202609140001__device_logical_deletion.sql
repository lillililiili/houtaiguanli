-- Keep device identities and historical references after removal from the active catalog.
ALTER TABLE ops_device ADD COLUMN deleted_at BIGINT;
ALTER TABLE ops_device ADD CONSTRAINT ck_ops_device_deleted_disabled
    CHECK (deleted_at IS NULL OR enabled = FALSE);
