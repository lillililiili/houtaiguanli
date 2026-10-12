-- Preserve the optional caller-selected plan in resend idempotency; do not infer one for historical requests.
ALTER TABLE ops_device_maintenance_notice_attempt
    ADD COLUMN request_plan_id VARCHAR(36) REFERENCES flight_plan(plan_id);
