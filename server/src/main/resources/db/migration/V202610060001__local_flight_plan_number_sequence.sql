-- Human-readable local simulator numbers; flight_plan.plan_id remains the UUID identity.
-- Sequence gaps after a rollback are intentional: allocated numbers are never reused.
CREATE SEQUENCE local_flight_plan_no_seq START WITH 1 INCREMENT BY 1;
