-- Extend append-only protection to the newly captured recognition fields.
CREATE OR REPLACE FUNCTION prevent_legality_recognition_snapshot_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.recognition_class_code IS DISTINCT FROM OLD.recognition_class_code
       OR NEW.recognition_revision IS DISTINCT FROM OLD.recognition_revision THEN
        RAISE EXCEPTION 'legality recognition snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_legality_recognition_snapshot_immutable
BEFORE UPDATE ON rule_evaluation
FOR EACH ROW EXECUTE FUNCTION prevent_legality_recognition_snapshot_mutation();
