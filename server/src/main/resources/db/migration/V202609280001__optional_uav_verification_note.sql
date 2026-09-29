ALTER TABLE uav_event_verification DROP CONSTRAINT ck_stage4_uav_verification_note;
ALTER TABLE uav_event_verification ADD CONSTRAINT ck_stage4_uav_verification_note
    CHECK (LENGTH(TRIM(note)) BETWEEN 0 AND 1000);
