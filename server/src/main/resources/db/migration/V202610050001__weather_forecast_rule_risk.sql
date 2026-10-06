-- 天气预报只负责提出待核验风险；人工确认后才允许进入通知/处置。
INSERT INTO integration_source (
    source_id, source_code, name, protocol_code, protocol_version, enabled, credential_ref,
    source_mode, created_at, updated_at, version
)
VALUES
    ('weather-forecast-rule-mock', 'WEATHER-FORECAST-RULE-MOCK', '天气预报规则（模拟）', 'RULE_ENGINE', '1', TRUE, NULL, 'mock', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('weather-forecast-rule-replay', 'WEATHER-FORECAST-RULE-REPLAY', '天气预报规则（回放）', 'RULE_ENGINE', '1', TRUE, NULL, 'replay', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

CREATE TABLE weather_forecast_risk_fact (
    risk_id                 VARCHAR(36) PRIMARY KEY REFERENCES flight_risk(risk_id) ON DELETE RESTRICT,
    forecast_message_id     VARCHAR(36) NOT NULL REFERENCES local_interface_message(message_id) ON DELETE RESTRICT,
    area_name               VARCHAR(128) NOT NULL,
    published_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_from              TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to                TIMESTAMP WITH TIME ZONE NOT NULL,
    summary                 VARCHAR(128) NOT NULL,
    wind_speed_mps          NUMERIC(8,2),
    gust_mps                NUMERIC(8,2),
    precipitation_probability_pct INTEGER,
    rule_code               VARCHAR(64) NOT NULL,
    rule_version            VARCHAR(32) NOT NULL,
    source_mode             VARCHAR(8) NOT NULL,
    CONSTRAINT weather_forecast_risk_window CHECK (valid_to > valid_from),
    CONSTRAINT weather_forecast_risk_wind CHECK (wind_speed_mps IS NULL OR wind_speed_mps >= 0),
    CONSTRAINT weather_forecast_risk_gust CHECK (gust_mps IS NULL OR gust_mps >= 0),
    CONSTRAINT weather_forecast_risk_pop CHECK (precipitation_probability_pct IS NULL OR (precipitation_probability_pct BETWEEN 0 AND 100)),
    CONSTRAINT weather_forecast_risk_mode CHECK (source_mode IN ('mock','replay'))
);

CREATE INDEX idx_weather_forecast_risk_fact_message ON weather_forecast_risk_fact (forecast_message_id);
