-- 2026-10-06 电话通知录音改由后台“接口配置 → 电话通知录音”上传和选用（BLOCK-04）。
-- 文件字节与证据文件一样存放在 app.evidence-dir 下（advisory-voice-recordings/ 目录），这里只保存元数据和真实内容哈希。
-- 选用的上传录音优先于启动参数 app.advisory.auto-voice.recording-*；没有选用时仍按启动参数，迁移不预置任何录音。
CREATE TABLE advisory_voice_recording (
    recording_id      VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(120) NOT NULL,
    transcript        VARCHAR(1000) NOT NULL,
    original_name     VARCHAR(256) NOT NULL,
    content_type      VARCHAR(64) NOT NULL,
    object_key        VARCHAR(256) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    sha256            VARCHAR(64) NOT NULL,
    duration_millis   BIGINT NOT NULL,
    sample_rate       INTEGER NOT NULL,
    channels          INTEGER NOT NULL,
    uploaded_by       VARCHAR(36) NOT NULL REFERENCES app_user (user_id) ON DELETE RESTRICT,
    uploaded_by_name  VARCHAR(64) NOT NULL,
    uploaded_at       BIGINT NOT NULL,
    CONSTRAINT uk_advisory_voice_recording_object UNIQUE (object_key),
    CONSTRAINT uk_advisory_voice_recording_sha256 UNIQUE (sha256),
    CONSTRAINT ck_advisory_voice_recording_text CHECK (TRIM(name) <> '' AND TRIM(transcript) <> ''),
    CONSTRAINT ck_advisory_voice_recording_audio CHECK (size_bytes > 44 AND duration_millis > 0 AND sample_rate > 0 AND channels > 0)
);
CREATE INDEX idx_advisory_voice_recording_uploaded ON advisory_voice_recording (uploaded_at);

-- 全局唯一一行；正在选用的录音受外键保护，不能被删除。
CREATE TABLE advisory_voice_recording_setting (
    setting_id           VARCHAR(16) PRIMARY KEY,
    active_recording_id  VARCHAR(36) REFERENCES advisory_voice_recording (recording_id) ON DELETE RESTRICT,
    updated_by           VARCHAR(36) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    updated_at           BIGINT,
    version              INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_advisory_voice_recording_setting_singleton CHECK (setting_id = 'global'),
    CONSTRAINT ck_advisory_voice_recording_setting_version CHECK (version >= 0)
);
INSERT INTO advisory_voice_recording_setting (setting_id, version) VALUES ('global', 0);

-- 删除录音前要查它是否用于过电话通知（任务和成功记录都冻结了录音编号）。
CREATE INDEX idx_auto_voice_task_recording ON uav_auto_voice_task (recording_id);
CREATE INDEX idx_voice_advisory_recording ON uav_event_voice_advisory (recording_id);
