-- 前台异常通知形成后台待办，不修改设备异常/重启/恢复核验状态。
CREATE TABLE ops_device_maintenance_task (
    task_id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36) NOT NULL REFERENCES flight_plan(plan_id),
    device_id VARCHAR(36) NOT NULL REFERENCES ops_device(device_id),
    owner_org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
    district_id VARCHAR(36) NOT NULL REFERENCES app_district(district_id),
    plan_no VARCHAR(128),
    device_no VARCHAR(128),
    device_name VARCHAR(256) NOT NULL,
    reason TEXT NOT NULL,
    connectivity VARCHAR(32),
    health_code VARCHAR(32),
    observed_at BIGINT,
    last_heartbeat_at BIGINT,
    simulated BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','HANDLED')),
    active_key VARCHAR(80) UNIQUE,
    reported_by VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    reported_by_name VARCHAR(128) NOT NULL,
    reported_at BIGINT NOT NULL,
    handled_by VARCHAR(36) REFERENCES app_user(user_id),
    handled_by_name VARCHAR(128),
    handled_at BIGINT,
    handling_note TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    CHECK ((status='PENDING' AND active_key IS NOT NULL AND handled_at IS NULL)
        OR (status='HANDLED' AND active_key IS NULL AND handled_by IS NOT NULL
            AND handled_at IS NOT NULL AND handling_note IS NOT NULL AND TRIM(handling_note)<>''))
);
CREATE INDEX idx_device_maintenance_status ON ops_device_maintenance_task(status,reported_at,task_id);
CREATE TABLE ops_device_maintenance_submission (
    actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    request_key VARCHAR(128) NOT NULL,
    task_id VARCHAR(36) NOT NULL REFERENCES ops_device_maintenance_task(task_id),
    PRIMARY KEY(actor_id,request_key)
);
