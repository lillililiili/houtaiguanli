-- Transport metadata only; no sample events, devices or successful receipts.
CREATE TABLE simulator_notification_receiver (
 receiver_id VARCHAR(36) PRIMARY KEY,
 actor_id VARCHAR(36) REFERENCES app_user(user_id),
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 expires_at BIGINT NOT NULL DEFAULT 0
);
INSERT INTO simulator_notification_receiver(receiver_id,enabled,expires_at) VALUES('receiver',FALSE,0);
CREATE TABLE simulator_notification_message (
 message_id VARCHAR(48) PRIMARY KEY,
 attempt_key VARCHAR(256) NOT NULL UNIQUE,
 actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
 kind VARCHAR(32) NOT NULL,
 subject_id VARCHAR(64) NOT NULL,
 payload TEXT NOT NULL,
 state VARCHAR(24) NOT NULL CHECK(state IN('SUBMITTED','DELIVERED','ANSWERED','PLAYED','ACKNOWLEDGED','FAILED','TIMEOUT')),
 created_at BIGINT NOT NULL,
 delivered_at BIGINT,
 answered_at BIGINT,
 completed_at BIGINT,
 acknowledged_at BIGINT,
 version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_simulator_notification_pending ON simulator_notification_message(actor_id,state,created_at);
