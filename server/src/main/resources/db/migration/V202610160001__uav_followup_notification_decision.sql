-- Internal follow-up starts only with events created after deployment; no historical backfill.
CREATE TABLE handoff_followup_policy (
    policy_id INTEGER PRIMARY KEY CHECK (policy_id = 1),
    eligible_from TIMESTAMP WITH TIME ZONE NOT NULL
);
INSERT INTO handoff_followup_policy(policy_id,eligible_from) VALUES(1,CURRENT_TIMESTAMP);

ALTER TABLE handoff ALTER COLUMN recipient_id DROP NOT NULL;
ALTER TABLE handoff ALTER COLUMN submitted_by DROP NOT NULL;
ALTER TABLE handoff DROP CONSTRAINT ck_handoff_trigger_source;
ALTER TABLE handoff ADD CONSTRAINT ck_handoff_trigger_source CHECK
    (trigger_source IS NULL OR trigger_source IN ('JAMMING_COMPLETED','COUNTERMEASURE_COMPLETED','MANUAL','FIELD_HANDLING_ENDED'));
ALTER TABLE handoff ADD CONSTRAINT ck_handoff_internal_recipient CHECK
    ((recipient_id IS NOT NULL AND submitted_by IS NOT NULL)
     OR (trigger_source='FIELD_HANDLING_ENDED' AND source_kind='UAV_EVENT' AND handoff_type='UAV_PUNISHMENT'));

CREATE TABLE handoff_notification_decision (
    handoff_id VARCHAR(36) PRIMARY KEY REFERENCES handoff(handoff_id),
    event_id VARCHAR(36) NOT NULL UNIQUE REFERENCES uav_event(event_id),
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING_DECISION','NOTIFY','DO_NOT_NOTIFY')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    reason VARCHAR(1000),
    decided_by VARCHAR(36) REFERENCES app_user(user_id),
    decided_by_name VARCHAR(128),
    decided_at BIGINT,
    field_handling TEXT NOT NULL,
    CONSTRAINT ck_followup_decision_fact CHECK (
        (state='PENDING_DECISION' AND version=0 AND decided_by IS NULL AND decided_at IS NULL)
        OR (state IN ('NOTIFY','DO_NOT_NOTIFY') AND version=1 AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_followup_decline_reason CHECK (state<>'DO_NOT_NOTIFY' OR (reason IS NOT NULL AND TRIM(reason)<>''))
);
