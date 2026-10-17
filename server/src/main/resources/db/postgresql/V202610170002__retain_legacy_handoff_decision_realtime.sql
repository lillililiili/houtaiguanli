-- Existing decisions retain their refresh signals; current new events still use rule-gated automatic handoff.
DO $$
DECLARE
    topic_name TEXT;
    operation_name TEXT;
    trigger_name TEXT;
    transition_name TEXT;
BEGIN
    FOREACH topic_name IN ARRAY ARRAY['punishment','alarm'] LOOP
        FOREACH operation_name IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
            trigger_name := 'trg_handoff_decision_' || topic_name || '_' ||
                CASE operation_name WHEN 'INSERT' THEN 'ins' WHEN 'UPDATE' THEN 'upd' ELSE 'del' END;
            transition_name := CASE operation_name WHEN 'DELETE' THEN 'OLD' ELSE 'NEW' END;
            IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='handoff_notification_decision'::regclass AND tgname=trigger_name) THEN
                EXECUTE format('CREATE TRIGGER %I AFTER %s ON handoff_notification_decision REFERENCING %s TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)',
                    trigger_name, operation_name, transition_name, topic_name);
            END IF;
        END LOOP;
    END LOOP;
END $$;
