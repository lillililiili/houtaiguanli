CREATE TABLE local_interface_message (
    message_id VARCHAR(36) PRIMARY KEY,
    external_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    state VARCHAR(24) NOT NULL,
    payload TEXT NOT NULL,
    result TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_local_interface_input UNIQUE(created_by,kind,external_id),
    CONSTRAINT ck_local_interface_direction CHECK(direction IN ('IN','OUT')),
    CONSTRAINT ck_local_interface_version CHECK(version >= 0)
);
CREATE INDEX idx_local_interface_message_actor ON local_interface_message(created_by,created_at DESC);
CREATE INDEX idx_local_interface_forecast ON local_interface_message(kind,subject_id,created_at DESC);
CREATE TABLE local_interface_binding (
    source_kind VARCHAR(16) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    enabled BOOLEAN NOT NULL,
    expires_at BIGINT NOT NULL,
    PRIMARY KEY(source_kind,source_id),
    CONSTRAINT ck_local_interface_source CHECK(source_kind IN ('RISK','UAV_EVENT'))
);
