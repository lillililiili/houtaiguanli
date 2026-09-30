-- Verified departure is independent of verification and notification history; reads never create it.
CREATE TABLE risk_clearance_evidence (
    risk_id VARCHAR(36) PRIMARY KEY REFERENCES flight_risk(risk_id),
    target_id VARCHAR(36) NOT NULL REFERENCES target(target_id),
    route_version_id VARCHAR(36) NOT NULL REFERENCES route_version(route_version_id),
    point_id VARCHAR(36) NOT NULL REFERENCES track_point(point_id),
    rule_set_version_id VARCHAR(36) NOT NULL REFERENCES rule_set_version(rule_set_version_id),
    freshness_rule_set_version_id VARCHAR(36) NOT NULL REFERENCES rule_set_version(rule_set_version_id),
    source_mode VARCHAR(8) NOT NULL CHECK (source_mode IN ('live','mock','replay')),
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    distance_m NUMERIC(16,4) NOT NULL,
    accuracy_m NUMERIC(12,4) NOT NULL CHECK (accuracy_m > 0),
    boundary_m NUMERIC(12,4) NOT NULL CHECK (boundary_m >= 0),
    freshness_seconds INTEGER NOT NULL CHECK (freshness_seconds > 0),
    CONSTRAINT ck_risk_clearance_outside CHECK (distance_m - accuracy_m > boundary_m),
    CONSTRAINT ck_risk_clearance_time CHECK (observed_at <= received_at AND received_at <= recorded_at)
);
