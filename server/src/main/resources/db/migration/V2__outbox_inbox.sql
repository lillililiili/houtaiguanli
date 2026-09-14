CREATE TABLE outbox_event (
    outbox_id       VARCHAR(36) PRIMARY KEY,
    topic           VARCHAR(128) NOT NULL,
    payload         TEXT NOT NULL,
    created_at      BIGINT NOT NULL,
    available_at    BIGINT NOT NULL,
    processed_at    BIGINT,
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT
);

CREATE INDEX idx_outbox_available ON outbox_event (processed_at, available_at);

CREATE TABLE inbox_message (
    inbox_id        VARCHAR(36) PRIMARY KEY,
    source          VARCHAR(128) NOT NULL,
    source_msg_id   VARCHAR(128) NOT NULL,
    received_at     BIGINT NOT NULL,
    UNIQUE (source, source_msg_id)
);

CREATE TABLE idempotency_request (
    idem_key        VARCHAR(128) PRIMARY KEY,
    user_id         VARCHAR(36),
    request_hash    VARCHAR(64) NOT NULL,
    response_body   TEXT,
    created_at      BIGINT NOT NULL
);
