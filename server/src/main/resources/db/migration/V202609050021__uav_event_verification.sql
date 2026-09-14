-- 来源 alarm 是设备/外部系统不可变事实；核实状态只写入独立 UAV 事件，不回写来源告警。
CREATE TABLE uav_event (
    event_id            VARCHAR(36) PRIMARY KEY,
    alarm_id            VARCHAR(36) NOT NULL UNIQUE,
    state_code          VARCHAR(32) NOT NULL,
    owner_org_id        VARCHAR(36) NOT NULL,
    district_id         VARCHAR(36) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stage4_uav_event_alarm FOREIGN KEY (alarm_id) REFERENCES alarm (alarm_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage4_uav_event_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage4_uav_event_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage4_uav_event_state CHECK (state_code IN ('PENDING_VERIFICATION','EVIDENCE_REQUIRED','CONFIRMED','FALSE_POSITIVE')),
    CONSTRAINT ck_stage4_uav_event_version CHECK (version >= 0)
);

CREATE INDEX idx_stage4_uav_event_scope_updated ON uav_event (owner_org_id, district_id, updated_at DESC, event_id DESC);

CREATE TABLE uav_event_verification (
    history_id          VARCHAR(36) PRIMARY KEY,
    event_id            VARCHAR(36) NOT NULL,
    version             BIGINT NOT NULL,
    previous_state      VARCHAR(32) NOT NULL,
    resulting_state     VARCHAR(32) NOT NULL,
    conclusion          VARCHAR(32) NOT NULL,
    note                VARCHAR(1000) NOT NULL,
    actor_id            VARCHAR(36) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage4_uav_verification_event FOREIGN KEY (event_id) REFERENCES uav_event (event_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage4_uav_verification_actor FOREIGN KEY (actor_id) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage4_uav_verification_event_version UNIQUE (event_id, version),
    CONSTRAINT ck_stage4_uav_verification_conclusion CHECK (conclusion IN ('CONFIRMED','FALSE_POSITIVE','EVIDENCE_REQUIRED')),
    CONSTRAINT ck_stage4_uav_verification_version CHECK (version >= 1),
    CONSTRAINT ck_stage4_uav_verification_note CHECK (LENGTH(TRIM(note)) BETWEEN 1 AND 1000)
);

CREATE INDEX idx_stage4_uav_verification_event_version ON uav_event_verification (event_id, version ASC, history_id ASC);
