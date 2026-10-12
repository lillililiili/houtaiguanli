-- Freeze the first confirmed departure for this notification cycle. Old rows stay unknown.
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_channel VARCHAR(16);
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_started_at BIGINT;
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_deadline_at BIGINT;
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_evaluated_at BIGINT;
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_observed_at BIGINT;
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_longitude DECIMAL(18,12);
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_latitude DECIMAL(18,12);
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_basis VARCHAR(64);
ALTER TABLE uav_auto_voice_task ADD COLUMN departure_checked_at BIGINT;
