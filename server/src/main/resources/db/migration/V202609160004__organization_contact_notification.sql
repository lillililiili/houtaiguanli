-- 增量补齐单位业务资料、独立联系人及通知用途配置。既有业务归属和历史材料不回写。
CREATE TABLE organization_profile (
 org_id VARCHAR(36) PRIMARY KEY REFERENCES app_org(org_id),
 organization_type VARCHAR(24) NOT NULL DEFAULT 'OTHER' CHECK(organization_type IN ('REGULATOR','OPERATOR','SERVICE','OTHER')),
 address VARCHAR(500), credit_code VARCHAR(64), remarks VARCHAR(1000)
);
CREATE TABLE business_contact (
 contact_id VARCHAR(36) PRIMARY KEY, org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
 name VARCHAR(128) NOT NULL CHECK(TRIM(name)<>''), roles JSONB NOT NULL,
 phone VARCHAR(64), email VARCHAR(256), user_id VARCHAR(36) REFERENCES app_user(user_id),
 enabled BOOLEAN NOT NULL DEFAULT TRUE, valid_until BIGINT, verified_at BIGINT, verification_basis VARCHAR(1000),
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT ck_contact_version CHECK(version>=0), CONSTRAINT uk_contact_org UNIQUE(contact_id,org_id)
);
CREATE INDEX idx_business_contact_org ON business_contact(org_id,name,contact_id);
CREATE TABLE plan_source_binding (
 binding_id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL REFERENCES integration_source(source_id),
 external_org_code VARCHAR(128) NOT NULL CHECK(TRIM(external_org_code)<>''), org_id VARCHAR(36) NOT NULL REFERENCES app_org(org_id),
 enabled BOOLEAN NOT NULL DEFAULT TRUE, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uk_plan_source_external_org UNIQUE(source_id,external_org_code), CONSTRAINT ck_binding_version CHECK(version>=0)
);
ALTER TABLE flight_plan ADD COLUMN source_binding_id VARCHAR(36) REFERENCES plan_source_binding(binding_id);
ALTER TABLE flight_plan ADD COLUMN operator_org_id VARCHAR(36) REFERENCES app_org(org_id);
ALTER TABLE flight_plan ADD COLUMN pilot_contact_id VARCHAR(36) REFERENCES business_contact(contact_id);
ALTER TABLE flight_plan ADD CONSTRAINT fk_plan_pilot_org FOREIGN KEY(pilot_contact_id,operator_org_id) REFERENCES business_contact(contact_id,org_id);
ALTER TABLE flight_plan ADD CONSTRAINT ck_plan_pilot_org CHECK(pilot_contact_id IS NULL OR operator_org_id IS NOT NULL);
-- 上级是统一逻辑接收方；旧接收方仍供旧交接引用，绝不改名或删历史。
INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,is_default,created_at,updated_at)
VALUES('fixed-superior-recipient','上级','RISK_NOTICE',TRUE,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
CREATE TABLE notification_setting (
 setting_id VARCHAR(36) PRIMARY KEY,
 purpose VARCHAR(32) NOT NULL CHECK(purpose IN('RISK_NOTICE','PLAN_FEEDBACK','ADVISORY_SMS','ADVISORY_VOICE','UAV_PUNISHMENT','DEVICE_MAINTENANCE')),
 routing_key VARCHAR(128) NOT NULL UNIQUE,
 recipient_org_id VARCHAR(36) REFERENCES app_org(org_id), contact_id VARCHAR(36) REFERENCES business_contact(contact_id),
 source_binding_id VARCHAR(36) REFERENCES plan_source_binding(binding_id), recipient_id VARCHAR(36) REFERENCES handoff_recipient(recipient_id),
 channel_type VARCHAR(16) NOT NULL CHECK(channel_type IN('NONE','MOCK','API','SMS','VOICE')),
 endpoint_ref VARCHAR(256), enabled BOOLEAN NOT NULL DEFAULT FALSE, valid_until BIGINT,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_notice_contact_org FOREIGN KEY(contact_id,recipient_org_id) REFERENCES business_contact(contact_id,org_id),
 CONSTRAINT ck_notice_contact_org CHECK(contact_id IS NULL OR recipient_org_id IS NOT NULL),
 CONSTRAINT ck_notice_global CHECK(purpose NOT IN('RISK_NOTICE','ADVISORY_SMS','ADVISORY_VOICE') OR (recipient_org_id IS NULL AND contact_id IS NULL AND source_binding_id IS NULL)),
 CONSTRAINT ck_notice_superior CHECK(purpose<>'RISK_NOTICE' OR (setting_id='risk-superior' AND routing_key='RISK_NOTICE' AND recipient_id='fixed-superior-recipient')),
 CONSTRAINT ck_notice_version CHECK(version>=0)
);
INSERT INTO notification_setting(setting_id,purpose,routing_key,recipient_id,channel_type,enabled,created_at,updated_at)
VALUES('risk-superior','RISK_NOTICE','RISK_NOTICE','fixed-superior-recipient','NONE',FALSE,0,0);
INSERT INTO notification_setting(setting_id,purpose,routing_key,channel_type,enabled,created_at,updated_at)
VALUES('advisory-sms','ADVISORY_SMS','ADVISORY_SMS','NONE',FALSE,0,0),('advisory-voice','ADVISORY_VOICE','ADVISORY_VOICE','NONE',FALSE,0,0);
-- 新提交才冻结对象；旧行保留 NULL，表示当时未采集该快照。
ALTER TABLE handoff ADD COLUMN recipient_snapshot TEXT;
ALTER TABLE handoff ADD COLUMN recipient_name_snapshot VARCHAR(128);
ALTER TABLE handoff ADD COLUMN notification_setting_id VARCHAR(36) REFERENCES notification_setting(setting_id);
ALTER TABLE flight_plan_feedback ADD COLUMN recipient_snapshot TEXT;
ALTER TABLE flight_plan_feedback ADD COLUMN notification_setting_id VARCHAR(36) REFERENCES notification_setting(setting_id);
INSERT INTO app_permission(permission_code,module_name,route_key,sort_order,module_code,permission_kind,action_code,name)
VALUES('organizations','系统管理','organizations',121,'organizations','MODULE','access','单位档案'),
('notificationSettings','系统管理','notificationSettings',122,'notificationSettings','MODULE','access','通知对象配置');
