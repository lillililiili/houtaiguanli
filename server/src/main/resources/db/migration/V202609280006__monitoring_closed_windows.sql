ALTER TABLE device_report_cursor ADD COLUMN closed_before BIGINT NOT NULL DEFAULT 0;
ALTER TABLE device_report_window ADD COLUMN late_arrival BOOLEAN NOT NULL DEFAULT FALSE;
