-- Only new evaluations capture recognition. Do not infer/backfill historical recognition.
ALTER TABLE rule_evaluation ADD COLUMN recognition_class_code VARCHAR(32);
ALTER TABLE rule_evaluation ADD COLUMN recognition_revision BIGINT;
