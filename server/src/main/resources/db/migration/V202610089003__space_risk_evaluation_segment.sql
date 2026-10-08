-- 2026-10-08 P03（用户确认）：空中异物（C04）风险的评估历史。
-- 风险发现后，每分钟一轮的评估原来只在 rule_evaluation_run 里计数，看不出这条风险被判了几次、每次离航线多远、什么时候不再构成风险。
-- 按“段”记：相邻几次评估的事实（离航线中心线的距离按 50 米一档、走廊关系、高度档、是否构成风险、等级、规则集版本）都没变，
-- 且这一段开始不到 5 分钟，就并进这一段（次数加一、最近时间后移、距离范围放宽）；有一项变了或满 5 分钟，另起一段。
-- 评估只记事实，不改风险本身的等级。只建表，不放任何数据：改动以前产生的风险没有发现时的那段记录。

CREATE TABLE space_risk_evaluation_segment (
    segment_id          VARCHAR(36) PRIMARY KEY,
    risk_id             VARCHAR(36) NOT NULL REFERENCES flight_risk(risk_id) ON DELETE RESTRICT,
    segment_no          INTEGER NOT NULL,
    first_evaluated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_evaluated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    evaluation_count    INTEGER NOT NULL,
    first_observed_at   TIMESTAMP WITH TIME ZONE,
    last_observed_at    TIMESTAMP WITH TIME ZONE,
    -- 距离档的下沿（米）；没有距离时三项都为空，不用 0 代替。
    distance_band_m     INTEGER,
    min_distance_m      NUMERIC(12, 2),
    max_distance_m      NUMERIC(12, 2),
    corridor_relation   VARCHAR(16) NOT NULL,
    altitude_band       VARCHAR(16) NOT NULL,
    risk_present        BOOLEAN NOT NULL,
    severity            VARCHAR(16),
    rule_set_version_id VARCHAR(36) NOT NULL REFERENCES rule_set_version(rule_set_version_id) ON DELETE RESTRICT,
    -- 这一段从发现这条风险的那次评估开始（风险是这次评估新建的）。
    from_detection      BOOLEAN NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_space_risk_evaluation_segment_no UNIQUE (risk_id, segment_no),
    CONSTRAINT ck_space_risk_evaluation_segment_no CHECK (segment_no >= 1),
    CONSTRAINT ck_space_risk_evaluation_segment_count CHECK (evaluation_count >= 1),
    CONSTRAINT ck_space_risk_evaluation_segment_time CHECK (last_evaluated_at >= first_evaluated_at),
    CONSTRAINT ck_space_risk_evaluation_segment_relation CHECK (corridor_relation IN ('INSIDE', 'NEAR', 'OUTSIDE', 'UNKNOWN')),
    CONSTRAINT ck_space_risk_evaluation_segment_band CHECK (altitude_band IN ('CLIMB', 'APPROACH', 'CRUISE', 'UNKNOWN')),
    CONSTRAINT ck_space_risk_evaluation_segment_severity CHECK (
        (risk_present = TRUE AND severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
        OR (risk_present = FALSE AND severity IS NULL)),
    CONSTRAINT ck_space_risk_evaluation_segment_distance CHECK (
        (distance_band_m IS NULL AND min_distance_m IS NULL AND max_distance_m IS NULL)
        OR (distance_band_m >= 0 AND min_distance_m >= 0 AND max_distance_m >= min_distance_m))
);
