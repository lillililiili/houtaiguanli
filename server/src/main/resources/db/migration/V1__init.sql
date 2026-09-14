CREATE TABLE app_user (
    user_id         VARCHAR(36) PRIMARY KEY,
    account         VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(64) NOT NULL,
    role_code       VARCHAR(32) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT '正常',
    password_hash   VARCHAR(128) NOT NULL,
    fail_count      INTEGER NOT NULL DEFAULT 0,
    locked_until    BIGINT,
    org_id          VARCHAR(36)
);

CREATE TABLE app_session (
    session_id      VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(36) NOT NULL REFERENCES app_user (user_id),
    expire_at       BIGINT NOT NULL,
    ip              VARCHAR(64) NOT NULL DEFAULT '',
    shift_note      VARCHAR(64)
);

CREATE INDEX idx_app_session_user ON app_session (user_id);
CREATE INDEX idx_app_session_expire ON app_session (expire_at);

CREATE TABLE audit_log (
    audit_id        VARCHAR(36) PRIMARY KEY,
    user_id         VARCHAR(36),
    account         VARCHAR(64),
    action          VARCHAR(64) NOT NULL,
    object_type     VARCHAR(64),
    object_id       VARCHAR(64),
    detail          TEXT,
    occurred_at     BIGINT NOT NULL,
    ip              VARCHAR(64)
);

CREATE INDEX idx_audit_log_occurred ON audit_log (occurred_at);
CREATE INDEX idx_audit_log_user ON audit_log (user_id);
