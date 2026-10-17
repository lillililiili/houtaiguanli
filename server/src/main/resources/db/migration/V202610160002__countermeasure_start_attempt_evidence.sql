-- A start attempt must survive rollback of the command/receipt transaction.
-- No FK deliberately: the caller holds the command lock while this evidence commits
-- in REQUIRES_NEW; a FK would wait for that same outer transaction and deadlock.
-- Application code only inserts/reads these retained safety facts, never edits them.
CREATE TABLE countermeasure_start_attempt (
    command_id VARCHAR(36) PRIMARY KEY,
    authorization_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(36) NOT NULL,
    attempted_at BIGINT NOT NULL
);
CREATE INDEX idx_countermeasure_start_attempt_authorization ON countermeasure_start_attempt (authorization_id);
