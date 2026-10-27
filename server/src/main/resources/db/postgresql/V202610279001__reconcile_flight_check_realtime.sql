-- A voice-order lineage has no flight table when the original 202610269001 runs.
-- Attach the same disposal signal after both branches exist, scoped to this migration's schema.
-- Original flight-check records, trigger function and Flyway history are preserved.
DO $$
DECLARE
    migration_schema TEXT := current_schema();
BEGIN
    IF to_regclass(format('%I.counter_flight_check', migration_schema)) IS NULL THEN
        RAISE EXCEPTION 'Missing counter_flight_check after migration lineage reconciliation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                   WHERE tgrelid = to_regclass(format('%I.counter_flight_check', migration_schema))
                     AND tgname = 'trg_app_data_change_ins') THEN
        EXECUTE format('CREATE TRIGGER trg_app_data_change_ins AFTER INSERT ON %I.counter_flight_check '
            'REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT '
            'EXECUTE FUNCTION %I.app_notify_data_change(''disposal'')', migration_schema, migration_schema);
    END IF;
END
$$;
