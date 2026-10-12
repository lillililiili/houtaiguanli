-- Preserve explicit tracking failures separately from successful reports and command history.
-- Historical absence is unknown; do not manufacture past report times.
ALTER TABLE eo_tracking_task ADD COLUMN last_failure_observed_at BIGINT;
ALTER TABLE eo_tracking_task ADD COLUMN last_failure_received_at BIGINT;
