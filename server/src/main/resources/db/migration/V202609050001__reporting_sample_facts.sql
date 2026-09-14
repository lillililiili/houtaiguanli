-- 运行统计切片的样本事实表。正式目标/案件域表落地前，统计接口从本表聚合，
-- 不另建可改写的 KPI 真值表。演示数据由 LocalReportingSeeder 写入，不进本脚本。

CREATE TABLE report_airborne_target (
    target_id           VARCHAR(36) PRIMARY KEY,
    target_no           VARCHAR(64) NOT NULL UNIQUE,
    occurred_on         DATE NOT NULL,
    district_name       VARCHAR(64) NOT NULL,
    object_type         VARCHAR(32) NOT NULL,
    legal_status        VARCHAR(16) NOT NULL,
    risk_level          VARCHAR(16),
    duration_min        INTEGER NOT NULL,
    track_km            DECIMAL(10, 1) NOT NULL,
    altitude_amsl_m     DECIMAL(10, 2) NOT NULL,
    owner_org_id        VARCHAR(36) REFERENCES app_org (org_id),
    district_id         VARCHAR(36) REFERENCES app_district (district_id),
    source_mode         VARCHAR(16) NOT NULL,
    simulated           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          BIGINT NOT NULL,
    CONSTRAINT ck_report_target_legal CHECK (legal_status IN ('合法', '非法', '异常', '待确认', '不适用')),
    CONSTRAINT ck_report_target_risk CHECK (risk_level IS NULL OR risk_level IN ('超高风险', '高风险', '中风险', '低风险')),
    CONSTRAINT ck_report_target_duration CHECK (duration_min >= 0),
    CONSTRAINT ck_report_target_track CHECK (track_km >= 0),
    CONSTRAINT ck_report_target_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_report_target_scope_pair CHECK (
        (owner_org_id IS NULL AND district_id IS NULL)
        OR (owner_org_id IS NOT NULL AND district_id IS NOT NULL)
    )
);

CREATE INDEX idx_report_target_day ON report_airborne_target (occurred_on, district_name);
CREATE INDEX idx_report_target_scope ON report_airborne_target (owner_org_id, district_id, occurred_on);

CREATE TABLE report_penalty_case (
    case_id             VARCHAR(36) PRIMARY KEY,
    case_no             VARCHAR(64) NOT NULL UNIQUE,
    occurred_on         DATE NOT NULL,
    district_name       VARCHAR(64) NOT NULL,
    partner_name        VARCHAR(128) NOT NULL,
    penalty_type        VARCHAR(16) NOT NULL,
    fine_amount         INTEGER NOT NULL,
    owner_org_id        VARCHAR(36) REFERENCES app_org (org_id),
    district_id         VARCHAR(36) REFERENCES app_district (district_id),
    source_mode         VARCHAR(16) NOT NULL,
    simulated           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          BIGINT NOT NULL,
    CONSTRAINT ck_report_case_penalty CHECK (penalty_type IN ('警告', '罚款', '驱离')),
    CONSTRAINT ck_report_case_fine CHECK (fine_amount >= 0),
    CONSTRAINT ck_report_case_fine_pair CHECK (
        (penalty_type = '罚款' AND fine_amount > 0)
        OR (penalty_type <> '罚款' AND fine_amount = 0)
    ),
    CONSTRAINT ck_report_case_source_mode CHECK (source_mode IN ('mock', 'replay', 'live')),
    CONSTRAINT ck_report_case_scope_pair CHECK (
        (owner_org_id IS NULL AND district_id IS NULL)
        OR (owner_org_id IS NOT NULL AND district_id IS NOT NULL)
    )
);

CREATE INDEX idx_report_case_day ON report_penalty_case (occurred_on, district_name);
CREATE INDEX idx_report_case_scope ON report_penalty_case (owner_org_id, district_id, occurred_on);
CREATE INDEX idx_report_case_partner ON report_penalty_case (partner_name, occurred_on);
