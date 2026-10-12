-- Reviewed exception for the notification branch's migration version collision only.
-- Stop the application and back up the database before an explicitly approved execution.
-- SQL contents/checksum, execution facts and business data remain unchanged.
-- The subsequent upgrade requires Flyway out-of-order=true for pending main migrations.
-- Only local enables this by default; other environments need a controlled one-time override.
BEGIN;
LOCK TABLE flyway_schema_history IN EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='202610120001') THEN
    RAISE EXCEPTION 'Destination migration version already exists; aborting';
  END IF;
  IF (SELECT count(*) FROM flyway_schema_history WHERE version='202610110001') <> 1
     OR (SELECT count(*) FROM flyway_schema_history WHERE version='202610110001'
       AND script='V202610110001__notification_departure_snapshot.sql'
       AND description='notification departure snapshot'
       AND type='SQL' AND checksum=-1287981834 AND success=true) <> 1 THEN
    RAISE EXCEPTION 'Notification migration history differs from reviewed state; aborting';
  END IF;
  IF (SELECT count(*) FROM information_schema.columns
      WHERE table_schema=current_schema() AND table_name='uav_auto_voice_task'
        AND column_name IN ('departure_channel','departure_started_at','departure_deadline_at',
          'departure_evaluated_at','departure_observed_at','departure_longitude',
          'departure_latitude','departure_basis','departure_checked_at')) <> 9 THEN
    RAISE EXCEPTION 'Notification departure snapshot columns are incomplete; aborting';
  END IF;
  UPDATE flyway_schema_history
     SET version='202610120001', script='V202610120001__notification_departure_snapshot.sql'
   WHERE version='202610110001'
     AND script='V202610110001__notification_departure_snapshot.sql'
     AND checksum=-1287981834 AND success=true;
END $$;
COMMIT;
