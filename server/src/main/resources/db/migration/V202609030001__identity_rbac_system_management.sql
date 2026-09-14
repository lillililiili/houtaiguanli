CREATE TABLE app_org (
    org_id          VARCHAR(36) PRIMARY KEY,
    parent_id       VARCHAR(36) REFERENCES app_org (org_id),
    org_code        VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_app_org_parent ON app_org (parent_id);

CREATE TABLE app_district (
    district_id     VARCHAR(36) PRIMARY KEY,
    district_code   VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE app_role (
    role_code       VARCHAR(32) PRIMARY KEY,
    name            VARCHAR(64) NOT NULL UNIQUE,
    description     VARCHAR(512) NOT NULL DEFAULT '',
    builtin         BOOLEAN NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      BIGINT NOT NULL,
    updated_at      BIGINT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE app_permission (
    permission_code VARCHAR(64) PRIMARY KEY,
    module_name     VARCHAR(64) NOT NULL,
    route_key       VARCHAR(32),
    sort_order      INTEGER NOT NULL
);

CREATE TABLE app_role_permission (
    role_code       VARCHAR(32) NOT NULL REFERENCES app_role (role_code),
    permission_code VARCHAR(64) NOT NULL REFERENCES app_permission (permission_code),
    permission_level VARCHAR(8) NOT NULL,
    menu_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (role_code, permission_code),
    CONSTRAINT ck_role_permission_level CHECK (permission_level IN ('NONE', 'READ', 'OP', 'AUTH'))
);

ALTER TABLE app_user ADD COLUMN phone VARCHAR(32);
ALTER TABLE app_user ADD COLUMN scope_mode VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN permission_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN last_login_at BIGINT;
ALTER TABLE app_user ADD COLUMN last_login_ip VARCHAR(64);
ALTER TABLE app_user ADD COLUMN created_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN updated_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

UPDATE app_user SET status = 'ACTIVE' WHERE status = '正常';
UPDATE app_user SET status = 'DISABLED' WHERE status = '已停用';

ALTER TABLE app_user ADD CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'));
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_scope_mode CHECK (scope_mode IN ('ALL', 'NONE', 'ASSIGNED'));

ALTER TABLE app_session ADD COLUMN permission_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE audit_log ADD COLUMN module_code VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN role_code VARCHAR(32);
ALTER TABLE audit_log ADD COLUMN result VARCHAR(16) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE audit_log ADD COLUMN user_agent VARCHAR(512);

CREATE INDEX idx_audit_log_module ON audit_log (module_code);
CREATE INDEX idx_audit_log_result ON audit_log (result);
CREATE INDEX idx_audit_log_object ON audit_log (object_type, object_id);

CREATE TABLE app_user_data_scope (
    user_id         VARCHAR(36) NOT NULL REFERENCES app_user (user_id),
    org_id          VARCHAR(36) NOT NULL REFERENCES app_org (org_id),
    district_id     VARCHAR(36) NOT NULL REFERENCES app_district (district_id),
    PRIMARY KEY (user_id, org_id, district_id)
);

CREATE INDEX idx_user_scope_org_district ON app_user_data_scope (org_id, district_id);

CREATE TABLE access_change_request (
    change_id       VARCHAR(36) PRIMARY KEY,
    change_type     VARCHAR(32) NOT NULL,
    subject_type    VARCHAR(16) NOT NULL,
    subject_id      VARCHAR(64) NOT NULL,
    requester_id    VARCHAR(36) NOT NULL REFERENCES app_user (user_id),
    before_snapshot TEXT NOT NULL,
    after_snapshot  TEXT NOT NULL,
    reason          VARCHAR(1000) NOT NULL,
    subject_version INTEGER NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reviewer_id     VARCHAR(36) REFERENCES app_user (user_id),
    review_comment  VARCHAR(1000),
    requested_at    BIGINT NOT NULL,
    reviewed_at     BIGINT,
    version         INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_access_change_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_access_change_type CHECK (change_type IN ('USER_CREATE', 'USER_ACCESS', 'ROLE_ACCESS', 'ROLE_DELETE'))
);

CREATE INDEX idx_access_change_status_time ON access_change_request (status, requested_at);
CREATE INDEX idx_access_change_subject ON access_change_request (subject_type, subject_id);

CREATE TABLE access_change_record (
    record_id       VARCHAR(36) PRIMARY KEY,
    change_id       VARCHAR(36) NOT NULL UNIQUE REFERENCES access_change_request (change_id),
    actor_id        VARCHAR(36) NOT NULL REFERENCES app_user (user_id),
    reviewer_id     VARCHAR(36) NOT NULL REFERENCES app_user (user_id),
    subject_type    VARCHAR(16) NOT NULL,
    subject_id      VARCHAR(64) NOT NULL,
    before_snapshot TEXT NOT NULL,
    after_snapshot  TEXT NOT NULL,
    reason          VARCHAR(1000) NOT NULL,
    review_basis    VARCHAR(1000),
    created_at      BIGINT NOT NULL
);

CREATE TABLE pending_user_registration (
    change_id       VARCHAR(36) PRIMARY KEY REFERENCES access_change_request (change_id),
    account         VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(64) NOT NULL,
    phone           VARCHAR(32),
    org_id          VARCHAR(36) NOT NULL REFERENCES app_org (org_id),
    role_code       VARCHAR(32) NOT NULL REFERENCES app_role (role_code),
    scope_mode      VARCHAR(16) NOT NULL,
    scope_grants    TEXT NOT NULL,
    password_hash   VARCHAR(128) NOT NULL
);

INSERT INTO app_role (role_code, name, description, builtin, enabled, created_at, updated_at, version) VALUES
('ROLE-ADMIN', '系统管理员', '全部功能与系统配置', TRUE, TRUE, 0, 0, 0),
('ROLE-AUTH', '处置授权人', '反制、干扰授权及处置审批', TRUE, TRUE, 0, 0, 0),
('ROLE-DUTY', '值班员', '态势监视、告警核实与派发', TRUE, TRUE, 0, 0, 0),
('ROLE-JUDGE', '研判员', '飞行计划、合法性、空域与风险研判', TRUE, TRUE, 0, 0, 0),
('ROLE-OPS', '设备运维', '设备接入、调测与监测', TRUE, TRUE, 0, 0, 0),
('ROLE-AUDIT', '审计员', '业务只读与审计日志导出', TRUE, TRUE, 0, 0, 0);

INSERT INTO app_permission (permission_code, module_name, route_key, sort_order) VALUES
('dashboard', '综合态势总览', 'bigscreen', 10),
('sensing', '融合感知中心', 'situation', 20),
('statistics', '统计分析', 'stats', 30),
('flights', '飞行活动管理', 'flights', 40),
('legality', '合法性判定', 'legality', 50),
('airspace', '空域与航线', NULL, 60),
('alarms', '异常告警中心', 'alarms', 70),
('risk', '空间安全风险', NULL, 80),
('punishment', '处置处罚管理', 'punish', 90),
('countermeasure', '反制/干扰授权', NULL, 100),
('devices', '设备管理', 'devices', 110),
('commissioning', '设备接入调测', 'commission', 120),
('monitoring', '设备实时监测', 'monitor', 130),
('interfaces', '接口管理', NULL, 140),
('audit', '审计日志', 'archive', 150),
('evidence', '证据管理', 'evidence', 160),
('users', '用户管理', 'users', 170),
('roles', '角色管理', 'roles', 180);

INSERT INTO app_role_permission (role_code, permission_code, permission_level, menu_enabled)
SELECT r.role_code, p.permission_code,
       CASE
           WHEN r.role_code = 'ROLE-ADMIN' THEN 'AUTH'
           WHEN r.role_code = 'ROLE-AUTH' AND p.permission_code IN ('users', 'roles') THEN 'NONE'
           WHEN r.role_code = 'ROLE-AUTH' AND p.permission_code IN ('punishment', 'countermeasure') THEN 'AUTH'
           WHEN r.role_code = 'ROLE-AUTH' THEN 'OP'
           WHEN r.role_code = 'ROLE-DUTY' AND p.permission_code IN ('countermeasure', 'users', 'roles', 'interfaces') THEN 'NONE'
           WHEN r.role_code = 'ROLE-DUTY' AND p.permission_code IN ('sensing', 'alarms', 'risk') THEN 'OP'
           WHEN r.role_code = 'ROLE-DUTY' THEN 'READ'
           WHEN r.role_code = 'ROLE-JUDGE' AND p.permission_code IN ('countermeasure', 'users', 'roles', 'interfaces') THEN 'NONE'
           WHEN r.role_code = 'ROLE-JUDGE' AND p.permission_code IN ('flights', 'legality', 'airspace', 'risk') THEN 'OP'
           WHEN r.role_code = 'ROLE-JUDGE' THEN 'READ'
           WHEN r.role_code = 'ROLE-OPS' AND p.permission_code IN ('countermeasure', 'punishment', 'users', 'roles') THEN 'NONE'
           WHEN r.role_code = 'ROLE-OPS' AND p.permission_code IN ('devices', 'commissioning', 'monitoring', 'interfaces') THEN 'OP'
           WHEN r.role_code = 'ROLE-OPS' THEN 'READ'
           WHEN r.role_code = 'ROLE-AUDIT' AND p.permission_code IN ('countermeasure', 'users', 'roles') THEN 'NONE'
           WHEN r.role_code = 'ROLE-AUDIT' AND p.permission_code = 'audit' THEN 'OP'
           ELSE 'READ'
       END,
       CASE
           WHEN p.route_key IS NULL THEN FALSE
           WHEN r.role_code = 'ROLE-ADMIN' THEN TRUE
           WHEN r.role_code = 'ROLE-AUTH' AND p.permission_code IN ('users', 'roles') THEN FALSE
           WHEN r.role_code IN ('ROLE-DUTY', 'ROLE-JUDGE') AND p.permission_code IN ('users', 'roles', 'interfaces', 'countermeasure') THEN FALSE
           WHEN r.role_code = 'ROLE-OPS' AND p.permission_code IN ('punishment', 'users', 'roles', 'countermeasure') THEN FALSE
           WHEN r.role_code = 'ROLE-AUDIT' AND p.permission_code IN ('users', 'roles', 'countermeasure') THEN FALSE
           ELSE TRUE
       END
FROM app_role r CROSS JOIN app_permission p;

UPDATE app_user SET scope_mode = 'ALL' WHERE role_code = 'ROLE-ADMIN';

INSERT INTO app_role (role_code, name, description, builtin, enabled, created_at, updated_at, version)
SELECT DISTINCT u.role_code, u.role_code, '由历史用户数据回填，默认禁用且无权限', FALSE, FALSE, 0, 0, 0
FROM app_user u
WHERE NOT EXISTS (SELECT 1 FROM app_role r WHERE r.role_code = u.role_code);

INSERT INTO app_org (org_id, parent_id, org_code, name, enabled, created_at, updated_at, version)
SELECT DISTINCT u.org_id, NULL, 'LEGACY-' || u.org_id, '待核实历史组织 ' || u.org_id, FALSE, 0, 0, 0
FROM app_user u
WHERE u.org_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM app_org o WHERE o.org_id = u.org_id);

ALTER TABLE app_user ADD CONSTRAINT fk_app_user_role FOREIGN KEY (role_code) REFERENCES app_role (role_code);
ALTER TABLE app_user ADD CONSTRAINT fk_app_user_org FOREIGN KEY (org_id) REFERENCES app_org (org_id);
