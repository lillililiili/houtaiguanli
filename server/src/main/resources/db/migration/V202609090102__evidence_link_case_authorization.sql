-- evidence_link 主体扩到处罚案件与处置授权（阶段 15 契约 §6 / 决策 15-11）。
-- AUTHORIZATION 只作关联主体，不作证据链根；链根仅增 CASE。
ALTER TABLE evidence_link ADD COLUMN case_id VARCHAR(36);
ALTER TABLE evidence_link ADD COLUMN authorization_id VARCHAR(36);

ALTER TABLE evidence_link DROP CONSTRAINT ck_evidence_link_kind;
ALTER TABLE evidence_link ADD CONSTRAINT ck_evidence_link_kind CHECK (
    subject_kind IN ('EVENT','DEVICE','TARGET','PLAN','COMMAND','COMMISSION','CASE','AUTHORIZATION'));

ALTER TABLE evidence_link DROP CONSTRAINT ck_evidence_link_one_subject;
ALTER TABLE evidence_link ADD CONSTRAINT ck_evidence_link_one_subject CHECK (
    ((CASE WHEN event_id IS NOT NULL THEN 1 ELSE 0 END)
   + (CASE WHEN device_id IS NOT NULL THEN 1 ELSE 0 END)
   + (CASE WHEN target_id IS NOT NULL THEN 1 ELSE 0 END)
   + (CASE WHEN plan_id IS NOT NULL THEN 1 ELSE 0 END)
   + (CASE WHEN command_id IS NOT NULL THEN 1 ELSE 0 END)
   + (CASE WHEN commission_id IS NOT NULL THEN 1 ELSE 0 END)
   + (CASE WHEN case_id IS NOT NULL THEN 1 ELSE 0 END)
   + (CASE WHEN authorization_id IS NOT NULL THEN 1 ELSE 0 END)) = 1);

ALTER TABLE evidence_link DROP CONSTRAINT ck_evidence_link_match;
ALTER TABLE evidence_link ADD CONSTRAINT ck_evidence_link_match CHECK (
    (subject_kind = 'EVENT' AND event_id = subject_id)
    OR (subject_kind = 'DEVICE' AND device_id = subject_id)
    OR (subject_kind = 'TARGET' AND target_id = subject_id)
    OR (subject_kind = 'PLAN' AND plan_id = subject_id)
    OR (subject_kind = 'COMMAND' AND command_id = subject_id)
    OR (subject_kind = 'COMMISSION' AND commission_id = subject_id)
    OR (subject_kind = 'CASE' AND case_id = subject_id)
    OR (subject_kind = 'AUTHORIZATION' AND authorization_id = subject_id));

ALTER TABLE evidence_link ADD CONSTRAINT fk_evidence_link_case
    FOREIGN KEY (case_id) REFERENCES punishment_case (case_id) ON DELETE RESTRICT;
ALTER TABLE evidence_link ADD CONSTRAINT fk_evidence_link_authorization
    FOREIGN KEY (authorization_id) REFERENCES disposal_authorization (authorization_id) ON DELETE RESTRICT;

CREATE INDEX idx_evidence_link_case ON evidence_link (case_id);
CREATE INDEX idx_evidence_link_authorization ON evidence_link (authorization_id);
