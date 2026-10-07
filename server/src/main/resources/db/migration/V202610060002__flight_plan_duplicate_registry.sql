-- Preserve duplicate rows and their original receipts; active readers use the canonical plan.
CREATE TABLE flight_plan_duplicate (
    duplicate_plan_id VARCHAR(36) PRIMARY KEY REFERENCES flight_plan(plan_id),
    canonical_plan_id VARCHAR(36) NOT NULL REFERENCES flight_plan(plan_id),
    reason VARCHAR(128) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_flight_plan_duplicate_distinct CHECK (duplicate_plan_id <> canonical_plan_id)
);
CREATE INDEX idx_flight_plan_duplicate_canonical ON flight_plan_duplicate(canonical_plan_id);
