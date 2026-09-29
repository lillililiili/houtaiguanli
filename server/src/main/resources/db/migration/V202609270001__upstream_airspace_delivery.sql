-- 来源配置不含演示空域；业务数据仅由鉴权后的下发接口写入。
INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,created_at,updated_at,version)
VALUES('local-airspace-upstream','LOCAL-AIRSPACE-UPSTREAM','上级空域下发模拟','AIRSPACE_PUSH_V1','1',TRUE,'mock',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0);

ALTER TABLE airspace_version_origin DROP CONSTRAINT ck_stage9_origin_kind;
ALTER TABLE airspace_version_origin ADD CONSTRAINT ck_stage9_origin_kind
    CHECK(origin_kind IN ('MANUAL','GEOJSON_IMPORT','SEED','UPSTREAM'));

CREATE TABLE airspace_delivery (
    source_id VARCHAR(36) NOT NULL REFERENCES integration_source(source_id),
    message_id VARCHAR(64) NOT NULL,
    airspace_id VARCHAR(36) NOT NULL REFERENCES airspace(airspace_id),
    airspace_version_id VARCHAR(36) NOT NULL REFERENCES airspace_version(airspace_version_id),
    revision BIGINT NOT NULL CHECK(revision > 0),
    action VARCHAR(16) NOT NULL CHECK(action IN ('UPSERT','WITHDRAW')),
    effective_at BIGINT NOT NULL,
    payload TEXT NOT NULL,
    received_at BIGINT NOT NULL,
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    PRIMARY KEY(source_id,message_id),
    UNIQUE(source_id,airspace_id,revision)
);
CREATE INDEX idx_airspace_delivery_airspace_revision ON airspace_delivery(airspace_id,revision DESC);
