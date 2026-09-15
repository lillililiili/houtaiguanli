-- Explicit versioned protection: installation after the stop tables is mandatory.
CREATE TRIGGER trg_emergency_stop_event_append_only
    BEFORE UPDATE OR DELETE ON disposal_emergency_stop_event
    FOR EACH ROW EXECUTE FUNCTION prevent_stage13_disposal_event_mutation();

ALTER TABLE disposal_emergency_stop_device ADD CONSTRAINT ck_emergency_stop_status
    CHECK (stop_status IN ('QUEUED','UNSUPPORTED','OFFLINE','NOT_REQUIRED'));
ALTER TABLE disposal_emergency_stop_device ADD CONSTRAINT ck_emergency_stop_attempt
    CHECK (attempt >= 0);
