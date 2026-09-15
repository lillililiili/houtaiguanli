CREATE TABLE weather_risk_fact (
    risk_id VARCHAR(36) PRIMARY KEY REFERENCES flight_risk(risk_id),
    polygon_json TEXT NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to TIMESTAMP WITH TIME ZONE NOT NULL,
    wind_speed_mps NUMERIC(8,2),
    wind_from_degrees NUMERIC(6,2),
    visibility_m NUMERIC(10,2),
    CONSTRAINT weather_risk_window CHECK (valid_to > valid_from),
    CONSTRAINT weather_risk_wind CHECK (wind_speed_mps IS NULL OR wind_speed_mps >= 0),
    CONSTRAINT weather_risk_direction CHECK (wind_from_degrees IS NULL OR (wind_from_degrees >= 0 AND wind_from_degrees < 360)),
    CONSTRAINT weather_risk_visibility CHECK (visibility_m IS NULL OR visibility_m >= 0)
);
