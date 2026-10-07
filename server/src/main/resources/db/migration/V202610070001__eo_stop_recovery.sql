ALTER TABLE eo_tracking_task ADD COLUMN stop_retry_count INTEGER NOT NULL DEFAULT 0
    CHECK (stop_retry_count >= 0);
ALTER TABLE eo_tracking_task ADD COLUMN stop_retry_at BIGINT;
