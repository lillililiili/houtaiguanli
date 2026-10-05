-- Keep replay-only fusion tuning separate from the active production fusion config.
-- This is configuration for the explicitly enabled simulator profile, not demo data.
CREATE TABLE simulator_fusion_parameters (
    source_type      VARCHAR(32) PRIMARY KEY,
    accuracy_m       NUMERIC(12, 4) NOT NULL CHECK (accuracy_m > 0),
    position_weight  NUMERIC(12, 8) NOT NULL CHECK (position_weight >= 0),
    motion_weight    NUMERIC(12, 8) NOT NULL CHECK (motion_weight >= 0),
    class_weight     NUMERIC(12, 8) NOT NULL CHECK (class_weight >= 0),
    identity_weight  NUMERIC(12, 8) NOT NULL CHECK (identity_weight >= 0),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO simulator_fusion_parameters (
    source_type, accuracy_m, position_weight, motion_weight, class_weight, identity_weight, updated_at
) VALUES (
    'SIM_NORMALIZED', 60.0000, 0.25000000, 0.20000000, 0.15000000, 0.40000000, CURRENT_TIMESTAMP
);
