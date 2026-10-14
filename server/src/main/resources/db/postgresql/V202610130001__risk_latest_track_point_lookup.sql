-- Current risk reads need the latest measured/retained point and same-time peers.
-- Preserve observed_at NULLS LAST and the existing received_at/point_id tie-break.
CREATE INDEX idx_risk_track_point_latest
    ON track_point (track_id, observed_at DESC NULLS LAST, received_at DESC, point_id);
