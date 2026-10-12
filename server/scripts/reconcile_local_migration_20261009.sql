-- Reviewed exception required: reconcile this local database's version collision.
-- Keep migration SQL and checksum unchanged; do not run against an unrecognized history.
BEGIN;
LOCK TABLE flyway_schema_history IN EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='202610090002') THEN
    RAISE EXCEPTION 'Destination migration version already exists; aborting';
  END IF;
  IF (SELECT count(*) FROM flyway_schema_history WHERE version='202610090001'
      AND script='V202610090001__confirm_current_project_parameters.sql'
      AND description='confirm current project parameters'
      AND checksum=627355451 AND success=true) <> 1 THEN
    RAISE EXCEPTION 'Local migration history differs from reviewed state; aborting';
  END IF;
  UPDATE flyway_schema_history SET version='202610090002',
    script='V202610090002__confirm_current_project_parameters.sql'
    WHERE version='202610090001'
      AND script='V202610090001__confirm_current_project_parameters.sql'
      AND checksum=627355451 AND success=true;
END $$;
COMMIT;
