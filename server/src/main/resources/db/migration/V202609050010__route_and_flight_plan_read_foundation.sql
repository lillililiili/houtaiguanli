CREATE TABLE route (
    route_id        VARCHAR(36) PRIMARY KEY,
    route_no        VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    source_id       VARCHAR(36),
    source_mode     VARCHAR(8) NOT NULL,
    owner_org_id    VARCHAR(36),
    district_id     VARCHAR(36),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stage3_route_source FOREIGN KEY (source_id)
        REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_route_owner_org FOREIGN KEY (owner_org_id)
        REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_route_district FOREIGN KEY (district_id)
        REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage3_route_no_nonblank CHECK (TRIM(route_no) <> ''),
    CONSTRAINT ck_stage3_route_name_nonblank CHECK (TRIM(name) <> ''),
    CONSTRAINT ck_stage3_route_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage3_route_ownership_pair CHECK (
        (owner_org_id IS NULL AND district_id IS NULL)
        OR (owner_org_id IS NOT NULL AND district_id IS NOT NULL)
    ),
    CONSTRAINT ck_stage3_route_version CHECK (version >= 0)
);

CREATE TABLE route_version (
    route_version_id    VARCHAR(36) PRIMARY KEY,
    route_id            VARCHAR(36) NOT NULL,
    version_no          INTEGER NOT NULL,
    centerline          GEOMETRY(LINESTRING, 4326) NOT NULL,
    corridor_width_m    NUMERIC(10, 2),
    min_altitude_m      NUMERIC(10, 2),
    max_altitude_m      NUMERIC(10, 2),
    altitude_datum      VARCHAR(16),
    valid_from          TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to            TIMESTAMP WITH TIME ZONE,
    change_reason       VARCHAR(256),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage3_route_version_route FOREIGN KEY (route_id)
        REFERENCES route (route_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage3_route_version_number UNIQUE (route_id, version_no),
    CONSTRAINT ck_stage3_route_version_number CHECK (version_no > 0),
    CONSTRAINT ck_stage3_route_width CHECK (corridor_width_m IS NULL OR corridor_width_m > 0),
    CONSTRAINT ck_stage3_route_altitude_pair CHECK (
        (min_altitude_m IS NULL AND max_altitude_m IS NULL AND altitude_datum IS NULL)
        OR (min_altitude_m IS NOT NULL AND max_altitude_m IS NOT NULL
            AND altitude_datum IN ('AGL', 'AMSL') AND min_altitude_m <= max_altitude_m)
    ),
    CONSTRAINT ck_stage3_route_version_validity CHECK (valid_to IS NULL OR valid_from < valid_to),
    CONSTRAINT ck_stage3_route_change_reason_nonblank CHECK (
        change_reason IS NULL OR TRIM(change_reason) <> ''
    )
);

CREATE TABLE flight_plan (
    plan_id             VARCHAR(36) PRIMARY KEY,
    plan_no             VARCHAR(64) NOT NULL UNIQUE,
    status_code         VARCHAR(32) NOT NULL,
    source_id           VARCHAR(36),
    source_mode         VARCHAR(8) NOT NULL,
    uav_sn              VARCHAR(128),
    start_at            TIMESTAMP WITH TIME ZONE,
    end_at              TIMESTAMP WITH TIME ZONE,
    route_version_id    VARCHAR(36) NOT NULL,
    owner_org_id        VARCHAR(36),
    district_id         VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stage3_flight_plan_source FOREIGN KEY (source_id)
        REFERENCES integration_source (source_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_flight_plan_route_version FOREIGN KEY (route_version_id)
        REFERENCES route_version (route_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_flight_plan_owner_org FOREIGN KEY (owner_org_id)
        REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage3_flight_plan_district FOREIGN KEY (district_id)
        REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage3_flight_plan_no_nonblank CHECK (TRIM(plan_no) <> ''),
    CONSTRAINT ck_stage3_flight_plan_status_nonblank CHECK (TRIM(status_code) <> ''),
    CONSTRAINT ck_stage3_flight_plan_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_stage3_flight_plan_time_range CHECK (
        (start_at IS NULL AND end_at IS NULL)
        OR (start_at IS NOT NULL AND end_at IS NOT NULL AND start_at <= end_at)
    ),
    CONSTRAINT ck_stage3_flight_plan_ownership_pair CHECK (
        (owner_org_id IS NULL AND district_id IS NULL)
        OR (owner_org_id IS NOT NULL AND district_id IS NOT NULL)
    ),
    CONSTRAINT ck_stage3_flight_plan_version CHECK (version >= 0)
);

CREATE INDEX idx_stage3_route_scope_updated
    ON route (owner_org_id, district_id, updated_at DESC, route_id ASC);
CREATE INDEX idx_stage3_route_source_enabled
    ON route (source_id, source_mode, enabled, route_id);
CREATE INDEX idx_stage3_route_version_route_validity
    ON route_version (route_id, version_no DESC, route_version_id ASC);
CREATE INDEX idx_stage3_flight_plan_scope_start
    ON flight_plan (owner_org_id, district_id, start_at DESC, plan_id ASC);
CREATE INDEX idx_stage3_flight_plan_route_version
    ON flight_plan (route_version_id, plan_id);
CREATE INDEX idx_stage3_flight_plan_source_status
    ON flight_plan (source_id, source_mode, status_code, plan_id);
