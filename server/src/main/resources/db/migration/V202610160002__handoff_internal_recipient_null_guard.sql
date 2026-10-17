-- Preserve the already-applied initial workflow migration. Reject NULL legacy trigger sources explicitly.
ALTER TABLE handoff DROP CONSTRAINT ck_handoff_internal_recipient;
ALTER TABLE handoff ADD CONSTRAINT ck_handoff_internal_recipient CHECK
    ((recipient_id IS NOT NULL AND submitted_by IS NOT NULL)
     OR (COALESCE(trigger_source,'')='FIELD_HANDLING_ENDED' AND source_kind='UAV_EVENT' AND handoff_type='UAV_PUNISHMENT'));
