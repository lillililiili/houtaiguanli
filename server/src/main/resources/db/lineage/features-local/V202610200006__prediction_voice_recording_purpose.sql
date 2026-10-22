-- Existing audio keeps its original general-alarm purpose. Predictive warnings never reuse it.
ALTER TABLE advisory_voice_recording ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE advisory_voice_recording ADD CONSTRAINT ck_advisory_voice_recording_purpose
    CHECK (purpose IN ('GENERAL','PREDICTED_AIRSPACE_ENTRY'));
ALTER TABLE advisory_voice_recording_setting ADD COLUMN predicted_active_recording_id VARCHAR(36)
    REFERENCES advisory_voice_recording(recording_id) ON DELETE RESTRICT;
