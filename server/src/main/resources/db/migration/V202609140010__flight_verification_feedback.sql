-- 计划核实与告警、风险通知独立存储；处理结果不由提交动作生成。
CREATE TABLE flight_plan_verification (
    verification_id VARCHAR(36) PRIMARY KEY,
    plan_id VARCHAR(36) NOT NULL REFERENCES flight_plan(plan_id),
    revision_no BIGINT NOT NULL,
    conclusion VARCHAR(32) NOT NULL CHECK (conclusion IN ('NOT_TAKEN_OFF','DEVICE_ABNORMAL')),
    takeoff_status VARCHAR(32) NOT NULL CHECK (takeoff_status IN ('NOT_TAKEN_OFF','UNKNOWN')),
    evidence TEXT NOT NULL CHECK (TRIM(evidence) <> ''),
    note TEXT NOT NULL CHECK (TRIM(note) <> ''),
    handled_by VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    handled_by_name VARCHAR(128) NOT NULL,
    handled_at BIGINT NOT NULL,
    CONSTRAINT uk_flight_verification_revision UNIQUE(plan_id,revision_no),
    CONSTRAINT ck_flight_verification_takeoff CHECK (
        (conclusion='DEVICE_ABNORMAL' AND takeoff_status='UNKNOWN') OR
        (conclusion='NOT_TAKEN_OFF' AND takeoff_status='NOT_TAKEN_OFF'))
);
CREATE TABLE flight_plan_feedback (
    feedback_id VARCHAR(36) PRIMARY KEY,
    verification_id VARCHAR(36) NOT NULL UNIQUE REFERENCES flight_plan_verification(verification_id),
    plan_id VARCHAR(36) NOT NULL REFERENCES flight_plan(plan_id),
    recipient_id VARCHAR(36) NOT NULL REFERENCES integration_source(source_id),
    recipient_name VARCHAR(128) NOT NULL,
    material_snapshot TEXT NOT NULL,
    delivery_status VARCHAR(32) NOT NULL CHECK (delivery_status IN ('PENDING_DELIVERY','SUBMITTED','DELIVERED','FAILED')),
    receipt_status VARCHAR(32) NOT NULL CHECK (receipt_status IN ('NOT_EXPECTED','PENDING','ACKNOWLEDGED','TIMEOUT')),
    processing_result VARCHAR(128),
    blocked_reason VARCHAR(64),
    submitted_by VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    created_at BIGINT NOT NULL,
    submitted_at BIGINT,
    delivered_at BIGINT,
    acknowledged_at BIGINT
);
CREATE INDEX idx_flight_verification_plan ON flight_plan_verification(plan_id,revision_no);
CREATE INDEX idx_flight_feedback_plan ON flight_plan_feedback(plan_id,created_at);
-- 只登记独立动作，不给已有只读角色自动增加写权限。
INSERT INTO app_permission(permission_code,module_name,route_key,sort_order,module_code,permission_kind,action_code,name)
VALUES ('flight:verify','飞行计划',NULL,990,'flights','ACTION','verify','核实计划执行');
