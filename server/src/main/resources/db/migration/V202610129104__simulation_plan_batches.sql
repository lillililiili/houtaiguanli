CREATE TABLE simulation_plan_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    owner_org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
    district_id VARCHAR(36) NOT NULL REFERENCES app_district(district_id),
    started_at BIGINT NOT NULL,
    registered_at BIGINT NOT NULL,
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
    request_body TEXT NOT NULL
);
CREATE TABLE simulation_plan_batch_member (
    batch_id VARCHAR(64) NOT NULL REFERENCES simulation_plan_batch(batch_id),
    plan_id VARCHAR(36) NOT NULL REFERENCES flight_plan(plan_id),
    current_member BOOLEAN NOT NULL,
    PRIMARY KEY(batch_id,plan_id)
);
CREATE INDEX idx_simulation_plan_batch_plan ON simulation_plan_batch_member(plan_id);
CREATE TABLE simulation_plan_batch_current (
    owner_org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
    district_id VARCHAR(36) NOT NULL REFERENCES app_district(district_id),
    batch_id VARCHAR(64) NOT NULL REFERENCES simulation_plan_batch(batch_id),
    PRIMARY KEY(owner_org_id,district_id)
);
