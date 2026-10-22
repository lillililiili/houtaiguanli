-- Prediction evidence is append-only. Lifecycle changes have their own immutable history.
CREATE UNIQUE INDEX prediction_event_single_origin ON prediction_event_link(event_id) WHERE role='PREDICTED';
CREATE FUNCTION prediction_immutable_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Prediction evidence is immutable';
END $$;
DO $$
DECLARE item text;
BEGIN
    FOREACH item IN ARRAY ARRAY['prediction_parameter_version','prediction_snapshot','prediction_episode_snapshot',
        'prediction_episode_history','prediction_verification_basis','prediction_actual_entry_link','prediction_event_link']
    LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prediction_immutable_evidence()',item || '_immutable',item);
    END LOOP;
END $$;
CREATE TRIGGER prediction_current_insert_notify AFTER INSERT ON prediction_current
    REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('prediction');
CREATE TRIGGER prediction_current_update_notify AFTER UPDATE ON prediction_current
    REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('prediction');
CREATE TRIGGER prediction_episode_insert_notify AFTER INSERT ON prediction_episode
    REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('alarm');
CREATE TRIGGER prediction_episode_update_notify AFTER UPDATE ON prediction_episode
    REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('alarm');
