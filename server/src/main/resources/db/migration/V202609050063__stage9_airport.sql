-- 阶段 9（E2）：机场基础数据。五张表只增不改不删——机场跑道与进离场程序是台账事实，
-- 纠错以新增记录表达；通报对象只存逻辑名与角色，绝不存号码、邮箱或任何凭据（决策 9-9）。

CREATE TABLE airport (
    airport_id          VARCHAR(36) PRIMARY KEY,
    icao_code           VARCHAR(8) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    reference_point     GEOMETRY(POINT, 4326) NOT NULL,
    elevation_amsl_m    NUMERIC(10, 2),
    owner_org_id        VARCHAR(36) NOT NULL,
    district_id         VARCHAR(36) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    note                VARCHAR(500),
    -- 可空：种子与系统建档的机场没有自然人建档人，写 NULL 而不是硬塞一个管理员账号（那是伪造归属）。
    -- 接口建档路径一律写当前登录用户，见 AirportService；与迁移 061 的 airspace_version_origin.actor_id 同一口径。
    created_by          VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_stage9_airport_icao UNIQUE (icao_code),
    CONSTRAINT fk_stage9_airport_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_airport_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_airport_creator FOREIGN KEY (created_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage9_airport_icao_nonblank CHECK (TRIM(icao_code) <> ''),
    CONSTRAINT ck_stage9_airport_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage9_airport_version CHECK (version >= 0)
);

CREATE INDEX idx_stage9_airport_scope ON airport (owner_org_id, district_id, enabled);

CREATE TABLE airport_runway (
    runway_id           VARCHAR(36) PRIMARY KEY,
    airport_id          VARCHAR(36) NOT NULL,
    designator          VARCHAR(16) NOT NULL,
    heading_deg         NUMERIC(6, 2) NOT NULL,
    length_m            NUMERIC(10, 2),
    centerline          GEOMETRY(LINESTRING, 4326),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage9_runway_airport FOREIGN KEY (airport_id) REFERENCES airport (airport_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage9_runway_designator UNIQUE (airport_id, designator),
    CONSTRAINT ck_stage9_runway_designator_nonblank CHECK (TRIM(designator) <> ''),
    CONSTRAINT ck_stage9_runway_heading CHECK (heading_deg >= 0 AND heading_deg < 360),
    CONSTRAINT ck_stage9_runway_length CHECK (length_m IS NULL OR length_m > 0)
);

-- 进离场程序航线：C05 用它的中心线加缓冲判定异物是否落在进近/离场通道内。
CREATE TABLE airport_procedure_route (
    route_id            VARCHAR(36) PRIMARY KEY,
    airport_id          VARCHAR(36) NOT NULL,
    kind                VARCHAR(16) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    centerline          GEOMETRY(LINESTRING, 4326) NOT NULL,
    protect_width_m     NUMERIC(10, 2) NOT NULL,
    min_altitude_m      NUMERIC(10, 2),
    max_altitude_m      NUMERIC(10, 2),
    altitude_datum      VARCHAR(16),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage9_procedure_airport FOREIGN KEY (airport_id) REFERENCES airport (airport_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage9_procedure_name UNIQUE (airport_id, name),
    CONSTRAINT ck_stage9_procedure_kind CHECK (kind IN ('APPROACH', 'DEPARTURE')),
    CONSTRAINT ck_stage9_procedure_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage9_procedure_width CHECK (protect_width_m > 0),
    -- 高度与基准必须同时给出：只有数值没有基准无法与目标高度比较（AGL/AMSL 不互比）。
    CONSTRAINT ck_stage9_procedure_datum CHECK (
        (min_altitude_m IS NULL AND max_altitude_m IS NULL AND altitude_datum IS NULL)
        OR (altitude_datum IN ('AGL', 'AMSL'))
    ),
    CONSTRAINT ck_stage9_procedure_altitude_range CHECK (
        min_altitude_m IS NULL OR max_altitude_m IS NULL OR min_altitude_m <= max_altitude_m
    )
);

CREATE TABLE airport_protected_target (
    protected_target_id VARCHAR(36) PRIMARY KEY,
    airport_id          VARCHAR(36) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    kind                VARCHAR(32) NOT NULL,
    location            GEOMETRY(POINT, 4326) NOT NULL,
    radius_m            NUMERIC(10, 2) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage9_protected_airport FOREIGN KEY (airport_id) REFERENCES airport (airport_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage9_protected_name UNIQUE (airport_id, name),
    CONSTRAINT ck_stage9_protected_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage9_protected_kind_nonblank CHECK (TRIM(kind) <> ''),
    CONSTRAINT ck_stage9_protected_radius CHECK (radius_m > 0)
);

-- 通报对象只有逻辑名与角色：本期不接真实通报通道，存号码或邮箱会让页面看起来"能发出去"。
CREATE TABLE airport_notification_target (
    notification_target_id VARCHAR(36) PRIMARY KEY,
    airport_id          VARCHAR(36) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    role                VARCHAR(64) NOT NULL,
    channel_kind        VARCHAR(32) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage9_notification_airport FOREIGN KEY (airport_id) REFERENCES airport (airport_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage9_notification_name UNIQUE (airport_id, name),
    CONSTRAINT ck_stage9_notification_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage9_notification_role_nonblank CHECK (TRIM(role) <> ''),
    CONSTRAINT ck_stage9_notification_channel CHECK (channel_kind IN ('PHONE', 'RADIO', 'SYSTEM', 'OTHER'))
);
