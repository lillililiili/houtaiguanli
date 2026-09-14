-- 阶段 9 证据关联服务：文件元数据、业务关联、访问留痕与冻结。不默认授权任何生产角色。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('evidence:read', 'Stage 9 evidence read', NULL, 960, 'evidence', 'ACTION', 'read', 'Read evidence files'),
    ('evidence:ingest', 'Stage 9 evidence ingest', NULL, 961, 'evidence', 'ACTION', 'ingest', 'Ingest evidence files'),
    ('evidence:download', 'Stage 9 evidence download', NULL, 962, 'evidence', 'ACTION', 'download', 'Download evidence files'),
    ('evidence:link', 'Stage 9 evidence link', NULL, 963, 'evidence', 'ACTION', 'link', 'Link evidence to subjects'),
    ('evidence:hold', 'Stage 9 evidence hold', NULL, 964, 'evidence', 'ACTION', 'hold', 'Hold or release evidence');

CREATE TABLE evidence_file (
    evidence_id         VARCHAR(36) PRIMARY KEY,
    evidence_no         VARCHAR(64) NOT NULL,
    kind_code           VARCHAR(32) NOT NULL,
    original_name       VARCHAR(256) NOT NULL,
    content_type        VARCHAR(128) NOT NULL,
    storage_backend     VARCHAR(32) NOT NULL,
    object_key          VARCHAR(512) NOT NULL,
    object_version      VARCHAR(128),
    size_bytes          BIGINT,
    sha256              VARCHAR(64),
    captured_at         TIMESTAMP WITH TIME ZONE,
    stored_at           TIMESTAMP WITH TIME ZONE,
    status              VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    retain_until        TIMESTAMP WITH TIME ZONE,
    source_mode         VARCHAR(8) NOT NULL,
    owner_org_id        VARCHAR(36),
    district_id         VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_evidence_file_no UNIQUE (evidence_no),
    CONSTRAINT fk_evidence_file_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_file_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT ck_evidence_file_kind CHECK (kind_code IN (
        'EO_VIDEO','EO_STILL','TRACK_SNAPSHOT','NOTICE_RECEIPT','COMMISSION_REPORT',
        'COMMAND_LOG','SCENE_PHOTO','PENALTY_DOCUMENT')),
    CONSTRAINT ck_evidence_file_status CHECK (status IN ('PENDING','AVAILABLE','MISSING','CORRUPT','DESTROYED')),
    CONSTRAINT ck_evidence_file_source_mode CHECK (source_mode IN ('mock','replay','live')),
    CONSTRAINT ck_evidence_file_storage CHECK (storage_backend IN ('local')),
    CONSTRAINT ck_evidence_file_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_evidence_file_sha256 CHECK (sha256 IS NULL OR LENGTH(sha256) = 64),
    CONSTRAINT ck_evidence_file_available CHECK (
        status <> 'AVAILABLE'
        OR (size_bytes IS NOT NULL AND sha256 IS NOT NULL AND stored_at IS NOT NULL)),
    CONSTRAINT ck_evidence_file_ownership_pair CHECK (
        (owner_org_id IS NULL AND district_id IS NULL)
        OR (owner_org_id IS NOT NULL AND district_id IS NOT NULL)),
    CONSTRAINT ck_evidence_file_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_evidence_file_object
    ON evidence_file (storage_backend, object_key);
CREATE INDEX idx_evidence_file_scope_captured
    ON evidence_file (owner_org_id, district_id, captured_at DESC NULLS LAST, evidence_id DESC);
CREATE INDEX idx_evidence_file_status ON evidence_file (status, stored_at DESC);

CREATE TABLE evidence_link (
    link_id             VARCHAR(36) PRIMARY KEY,
    evidence_id         VARCHAR(36) NOT NULL,
    subject_kind        VARCHAR(16) NOT NULL,
    subject_id          VARCHAR(36) NOT NULL,
    event_id            VARCHAR(36),
    device_id           VARCHAR(36),
    target_id           VARCHAR(36),
    plan_id             VARCHAR(36),
    command_id          VARCHAR(36),
    commission_id       VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_evidence_link_file FOREIGN KEY (evidence_id) REFERENCES evidence_file (evidence_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_link_event FOREIGN KEY (event_id) REFERENCES uav_event (event_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_link_device FOREIGN KEY (device_id) REFERENCES ops_device (device_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_link_target FOREIGN KEY (target_id) REFERENCES target (target_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_link_plan FOREIGN KEY (plan_id) REFERENCES flight_plan (plan_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_link_command FOREIGN KEY (command_id) REFERENCES device_command (command_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_link_commission FOREIGN KEY (commission_id) REFERENCES commission_task (commission_id) ON DELETE RESTRICT,
    CONSTRAINT uk_evidence_link_subject UNIQUE (evidence_id, subject_kind, subject_id),
    CONSTRAINT ck_evidence_link_kind CHECK (subject_kind IN ('EVENT','DEVICE','TARGET','PLAN','COMMAND','COMMISSION')),
    CONSTRAINT ck_evidence_link_one_subject CHECK (
        ((CASE WHEN event_id IS NOT NULL THEN 1 ELSE 0 END)
       + (CASE WHEN device_id IS NOT NULL THEN 1 ELSE 0 END)
       + (CASE WHEN target_id IS NOT NULL THEN 1 ELSE 0 END)
       + (CASE WHEN plan_id IS NOT NULL THEN 1 ELSE 0 END)
       + (CASE WHEN command_id IS NOT NULL THEN 1 ELSE 0 END)
       + (CASE WHEN commission_id IS NOT NULL THEN 1 ELSE 0 END)) = 1),
    CONSTRAINT ck_evidence_link_match CHECK (
        (subject_kind = 'EVENT' AND event_id = subject_id)
        OR (subject_kind = 'DEVICE' AND device_id = subject_id)
        OR (subject_kind = 'TARGET' AND target_id = subject_id)
        OR (subject_kind = 'PLAN' AND plan_id = subject_id)
        OR (subject_kind = 'COMMAND' AND command_id = subject_id)
        OR (subject_kind = 'COMMISSION' AND commission_id = subject_id))
);

CREATE INDEX idx_evidence_link_event ON evidence_link (event_id);
CREATE INDEX idx_evidence_link_device ON evidence_link (device_id);
CREATE INDEX idx_evidence_link_target ON evidence_link (target_id);
CREATE INDEX idx_evidence_link_plan ON evidence_link (plan_id);
CREATE INDEX idx_evidence_link_command ON evidence_link (command_id);
CREATE INDEX idx_evidence_link_commission ON evidence_link (commission_id);
CREATE INDEX idx_evidence_link_subject ON evidence_link (subject_kind, subject_id);

CREATE TABLE evidence_access_log (
    access_id           VARCHAR(36) PRIMARY KEY,
    evidence_id         VARCHAR(36) NOT NULL,
    user_id             VARCHAR(36),
    action              VARCHAR(24) NOT NULL,
    result              VARCHAR(16) NOT NULL,
    reason_code         VARCHAR(64),
    trace_id            VARCHAR(64),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_evidence_access_file FOREIGN KEY (evidence_id) REFERENCES evidence_file (evidence_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_access_user FOREIGN KEY (user_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_evidence_access_action CHECK (action IN ('VIEW','DOWNLOAD','VERIFY','EXPORT')),
    CONSTRAINT ck_evidence_access_result CHECK (result IN ('GRANTED','DENIED'))
);

CREATE INDEX idx_evidence_access_file_created ON evidence_access_log (evidence_id, created_at DESC);
CREATE INDEX idx_evidence_access_user_created ON evidence_access_log (user_id, created_at DESC);

CREATE TABLE evidence_hold (
    hold_id             VARCHAR(36) PRIMARY KEY,
    evidence_id         VARCHAR(36) NOT NULL,
    event_id            VARCHAR(36),
    held_by             VARCHAR(36) NOT NULL,
    reason              TEXT NOT NULL,
    released_at         TIMESTAMP WITH TIME ZONE,
    released_by         VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_evidence_hold_file FOREIGN KEY (evidence_id) REFERENCES evidence_file (evidence_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_hold_event FOREIGN KEY (event_id) REFERENCES uav_event (event_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_hold_holder FOREIGN KEY (held_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evidence_hold_releaser FOREIGN KEY (released_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_evidence_hold_reason CHECK (LENGTH(TRIM(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_evidence_hold_release CHECK (
        (released_at IS NULL AND released_by IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL))
);

CREATE INDEX idx_evidence_hold_active ON evidence_hold (evidence_id);
CREATE INDEX idx_evidence_hold_event ON evidence_hold (event_id);
