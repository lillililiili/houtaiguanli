-- Versioned operational guidance; publication does not authorize or dispatch actions.
CREATE TABLE response_plan (
 plan_id VARCHAR(36) PRIMARY KEY,
 anchor_airspace_id VARCHAR(36) NOT NULL REFERENCES airspace(airspace_id),
 created_at BIGINT NOT NULL
);
CREATE TABLE response_plan_version (
 version_id VARCHAR(36) PRIMARY KEY,
 plan_id VARCHAR(36) NOT NULL REFERENCES response_plan(plan_id),
 revision INTEGER NOT NULL CHECK(revision>0),
 name VARCHAR(128) NOT NULL,
 trigger_basis VARCHAR(4000) NOT NULL,
 action_steps VARCHAR(4000) NOT NULL,
 manual_conditions VARCHAR(4000) NOT NULL,
 failure_handling VARCHAR(4000) NOT NULL,
 source_mode VARCHAR(8) NOT NULL CHECK(source_mode IN('live','mock','replay')),
 valid_from BIGINT NOT NULL,
 valid_to BIGINT,
 status VARCHAR(16) NOT NULL CHECK(status IN('DRAFT','PUBLISHED','WITHDRAWN')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 published_at BIGINT,
 published_by VARCHAR(128),
 withdrawn_at BIGINT,
 withdrawn_reason VARCHAR(1000),
 UNIQUE(plan_id,revision),
 CHECK(valid_to IS NULL OR valid_to>valid_from)
);
CREATE TABLE airspace_response_plan_binding (
 binding_id VARCHAR(36) PRIMARY KEY,
 airspace_id VARCHAR(36) NOT NULL REFERENCES airspace(airspace_id),
 version_id VARCHAR(36) NOT NULL REFERENCES response_plan_version(version_id),
 bound_at BIGINT NOT NULL,
 bound_by VARCHAR(128) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 ended_at BIGINT,
 ended_by VARCHAR(128),
 end_reason VARCHAR(1000),
 CHECK(ended_at IS NULL OR ended_at>=bound_at)
);
CREATE INDEX idx_response_plan_version ON response_plan_version(plan_id,revision DESC);
CREATE INDEX idx_response_plan_binding ON airspace_response_plan_binding(airspace_id,bound_at DESC,binding_id);
INSERT INTO app_permission(permission_code,module_name,route_key,sort_order,module_code,permission_kind,action_code,name)
VALUES('responsePlans','系统管理','responsePlans',123,'responsePlans','MODULE','access','处置预案');
