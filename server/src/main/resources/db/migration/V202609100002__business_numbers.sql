-- 平台自己产生的告警、风险（规则引擎、空间安全评估）此前只有内部标识，页面上没有可读编号（需求确认表增补四 F11）。
-- 编号规则：告警-MMDD-NNN / 风险-MMDD-NNN，按北京时间当日自增；来源方自带可读编号的记录沿用来源编号，平台编号留空。
CREATE TABLE business_number_counter (
    kind        VARCHAR(16) NOT NULL,
    day_key     VARCHAR(8)  NOT NULL,
    next_value  INTEGER     NOT NULL,
    CONSTRAINT pk_business_number_counter PRIMARY KEY (kind, day_key)
);
ALTER TABLE alarm ADD COLUMN alarm_no VARCHAR(32);
ALTER TABLE flight_risk ADD COLUMN risk_no VARCHAR(32);
CREATE UNIQUE INDEX uk_alarm_no ON alarm (alarm_no);
CREATE UNIQUE INDEX uk_flight_risk_no ON flight_risk (risk_no);
