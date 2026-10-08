CREATE TABLE flight_execution_fact (
 fact_id VARCHAR(36) PRIMARY KEY,
 ingestion_seq BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
 source_id VARCHAR(36) NOT NULL REFERENCES integration_source(source_id),
 source_mode VARCHAR(8) NOT NULL CHECK(source_mode IN ('live','mock','replay')),
 message_id VARCHAR(128) NOT NULL,
 fact_version BIGINT NOT NULL CHECK(fact_version>0),
 target_id VARCHAR(36) NOT NULL REFERENCES target(target_id),
 track_id VARCHAR(36) NOT NULL REFERENCES track(track_id),
 execution_id VARCHAR(128) NOT NULL,
 owner_org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
 district_id VARCHAR(36) NOT NULL REFERENCES app_district(district_id),
 valid_from BIGINT NOT NULL,
 valid_to BIGINT NOT NULL CHECK(valid_to>valid_from),
 received_at BIGINT NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 payload_json TEXT NOT NULL,
 UNIQUE(source_id,message_id,fact_version)
);
CREATE INDEX ix_execution_target ON flight_execution_fact(target_id,track_id,received_at);
ALTER TABLE rule_evaluation ADD COLUMN execution_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE flight_execution_evidence (
 fact_id VARCHAR(36) NOT NULL REFERENCES flight_execution_fact(fact_id),
 evidence_ref VARCHAR(256) NOT NULL,
 PRIMARY KEY(fact_id,evidence_ref)
);
-- Catalog only: no active/shadow pointer or formal parameters are manufactured.
INSERT INTO rule_version(rule_version_id,rule_code,version_no,status_code,valid_from,source_mode,created_at)
 VALUES ('rule-flight-takeoff-v1','C02-9',1,'DRAFT',CURRENT_TIMESTAMP,'live',CURRENT_TIMESTAMP),
 ('rule-flight-landing-v1','C02-10',1,'DRAFT',CURRENT_TIMESTAMP,'live',CURRENT_TIMESTAMP),
 ('rule-flight-pilot-v1','C02-11',1,'DRAFT',CURRENT_TIMESTAMP,'live',CURRENT_TIMESTAMP),
 ('rule-flight-reporting-v1','C02-12',1,'DRAFT',CURRENT_TIMESTAMP,'live',CURRENT_TIMESTAMP);
