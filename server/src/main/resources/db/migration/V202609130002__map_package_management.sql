-- 离线底图包管理：文件字节仍在共享文件系统，数据库只保存版本、校验与启用事实。
INSERT INTO app_permission (
    permission_code, module_name, route_key, sort_order,
    module_code, permission_kind, action_code, name
)
VALUES
    ('maps', '运维管理', 'maps', 115, 'maps', 'MODULE', 'access', '地图资源管理'),
    ('map:read', '地图资源管理', NULL, 980, 'maps', 'ACTION', 'read', '查看地图资源'),
    ('map:upload', '地图资源管理', NULL, 981, 'maps', 'ACTION', 'upload', '上传并校验地图包'),
    ('map:activate', '地图资源管理', NULL, 982, 'maps', 'ACTION', 'activate', '启用或回滚地图包'),
    ('map:delete', '地图资源管理', NULL, 983, 'maps', 'ACTION', 'delete', '删除未启用地图包');

INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled, created_at)
SELECT role_code, 'maps', CASE WHEN role_code = 'ROLE-ADMIN' THEN 'AUTH' ELSE 'OP' END, TRUE, CURRENT_TIMESTAMP
FROM app_role
WHERE role_code IN ('ROLE-ADMIN', 'ROLE-OPS')
  AND NOT EXISTS (
      SELECT 1 FROM app_role_permission rp
      WHERE rp.role_code = app_role.role_code AND rp.permission_code = 'maps'
  );

CREATE TABLE map_package (
    package_id          VARCHAR(36) PRIMARY KEY,
    package_name        VARCHAR(128) NOT NULL,
    city_code           VARCHAR(32) NOT NULL,
    city_name           VARCHAR(64) NOT NULL,
    data_version        VARCHAR(64) NOT NULL,
    coordinate_system   VARCHAR(16) NOT NULL,
    archive_name        VARCHAR(256) NOT NULL,
    package_path        VARCHAR(256) NOT NULL UNIQUE,
    archive_sha256      VARCHAR(64) NOT NULL UNIQUE,
    manifest_sha256     VARCHAR(64) NOT NULL,
    size_bytes          BIGINT NOT NULL,
    file_count          INTEGER NOT NULL,
    bounds_west         DECIMAL(12, 7) NOT NULL,
    bounds_south        DECIMAL(12, 7) NOT NULL,
    bounds_east         DECIMAL(12, 7) NOT NULL,
    bounds_north        DECIMAL(12, 7) NOT NULL,
    min_zoom            INTEGER NOT NULL,
    max_zoom            INTEGER NOT NULL,
    display_max_zoom    INTEGER NOT NULL,
    manifest_json       TEXT NOT NULL,
    status              VARCHAR(16) NOT NULL,
    validation_message  VARCHAR(1000) NOT NULL,
    uploaded_by         VARCHAR(36) NOT NULL REFERENCES app_user (user_id) ON DELETE RESTRICT,
    uploaded_by_name    VARCHAR(64) NOT NULL,
    uploaded_at         BIGINT NOT NULL,
    activated_by        VARCHAR(36) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    activated_by_name   VARCHAR(64),
    activated_at        BIGINT,
    deleted_by          VARCHAR(36) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    deleted_at          BIGINT,
    delete_reason       VARCHAR(500),
    version             INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_map_package_status CHECK (status IN ('VALIDATED', 'ACTIVE', 'RETIRED', 'DELETED')),
    CONSTRAINT ck_map_package_coordinate CHECK (coordinate_system = 'WGS84'),
    CONSTRAINT ck_map_package_bounds CHECK (
        bounds_west >= -180 AND bounds_east <= 180 AND bounds_west < bounds_east
        AND bounds_south >= -90 AND bounds_north <= 90 AND bounds_south < bounds_north
    ),
    CONSTRAINT ck_map_package_zoom CHECK (
        min_zoom >= 0 AND max_zoom >= min_zoom AND display_max_zoom >= max_zoom AND display_max_zoom <= 24
    ),
    CONSTRAINT ck_map_package_size CHECK (size_bytes > 0 AND file_count >= 3),
    CONSTRAINT ck_map_package_delete CHECK (
        (status = 'DELETED' AND deleted_by IS NOT NULL AND deleted_at IS NOT NULL AND delete_reason IS NOT NULL)
        OR (status <> 'DELETED' AND deleted_by IS NULL AND deleted_at IS NULL AND delete_reason IS NULL)
    )
);

CREATE INDEX idx_map_package_city_status ON map_package (city_code, status, uploaded_at);
CREATE INDEX idx_map_package_status_time ON map_package (status, uploaded_at);

CREATE TABLE map_runtime_config (
    config_id            VARCHAR(16) PRIMARY KEY,
    active_package_id    VARCHAR(36) REFERENCES map_package (package_id) ON DELETE RESTRICT,
    previous_package_id  VARCHAR(36) REFERENCES map_package (package_id) ON DELETE RESTRICT,
    revision             BIGINT NOT NULL DEFAULT 0,
    updated_by           VARCHAR(36) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    updated_at           BIGINT,
    version              INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_map_runtime_singleton CHECK (config_id = 'global'),
    CONSTRAINT ck_map_runtime_revision CHECK (revision >= 0),
    CONSTRAINT ck_map_runtime_distinct CHECK (
        active_package_id IS NULL OR previous_package_id IS NULL OR active_package_id <> previous_package_id
    )
);

INSERT INTO map_runtime_config (config_id, revision, version) VALUES ('global', 0, 0);
