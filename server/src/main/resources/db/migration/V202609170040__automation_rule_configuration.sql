-- Configuration management only. No seeded thresholds, rules, authority or live dispatch.
CREATE TABLE automation_rule_group (
 category VARCHAR(16) PRIMARY KEY CHECK(category IN('verify','counter','dispose')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),
 scope_mode VARCHAR(16) NOT NULL DEFAULT 'ALL' CHECK(scope_mode IN('ALL','AIRSPACES')),
 schedule_mode VARCHAR(16) NOT NULL DEFAULT 'ALL_DAY' CHECK(schedule_mode IN('ALL_DAY','DAILY')),
 start_time VARCHAR(5) NOT NULL DEFAULT '08:00',
 end_time VARCHAR(5) NOT NULL DEFAULT '20:00',
 wait_seconds INTEGER NOT NULL DEFAULT 15 CHECK(wait_seconds IN(0,5,15,30)),
 actions_json TEXT NOT NULL DEFAULT '[]',
 updated_at BIGINT NOT NULL DEFAULT 0
);
INSERT INTO automation_rule_group(category) VALUES('verify'),('counter'),('dispose');
CREATE TABLE automation_rule_scope (
 category VARCHAR(16) NOT NULL REFERENCES automation_rule_group(category),
 airspace_id VARCHAR(36) NOT NULL REFERENCES airspace(airspace_id),
 ordinal INTEGER NOT NULL CHECK(ordinal>=0),
 PRIMARY KEY(category,airspace_id), UNIQUE(category,ordinal)
);
CREATE TABLE automation_rule_condition (
 rule_id VARCHAR(36) PRIMARY KEY,
 category VARCHAR(16) NOT NULL REFERENCES automation_rule_group(category),
 name VARCHAR(64) NOT NULL,
 item_code VARCHAR(64) NOT NULL,
 value_text VARCHAR(200) NOT NULL,
 hold_seconds INTEGER NOT NULL CHECK(hold_seconds BETWEEN 0 AND 60),
 enabled BOOLEAN NOT NULL,
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 updated_by VARCHAR(128) NOT NULL,
 UNIQUE(category,item_code), UNIQUE(category,name)
);
CREATE TABLE automation_rule_change (
 change_id VARCHAR(36) PRIMARY KEY,
 category VARCHAR(16) NOT NULL REFERENCES automation_rule_group(category),
 version BIGINT NOT NULL CHECK(version>0),
 action VARCHAR(200) NOT NULL,
 actor_id VARCHAR(36) NOT NULL REFERENCES app_user(user_id),
 actor VARCHAR(128) NOT NULL,
 created_at BIGINT NOT NULL,
 details_json TEXT NOT NULL,
 before_json TEXT NOT NULL,
 after_json TEXT NOT NULL,
 UNIQUE(category,version)
);
CREATE INDEX idx_automation_rule_changes ON automation_rule_change(category,version DESC);
UPDATE app_permission SET name='规则管理' WHERE permission_code='responsePlans';
