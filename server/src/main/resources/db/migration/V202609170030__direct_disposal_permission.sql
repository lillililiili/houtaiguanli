-- 独立的免逐次审批权限；仅登记目录，不授予任何角色。
INSERT INTO app_permission (permission_code,module_name,route_key,sort_order,module_code,permission_kind,action_code,name)
VALUES ('disposal:direct','处置授权',NULL,972,'disposal','ACTION','direct','直接反制（免逐次审批）');

ALTER TABLE disposal_authorization ADD COLUMN authorization_mode VARCHAR(16) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE disposal_authorization ADD CONSTRAINT ck_disposal_authorization_mode CHECK (authorization_mode IN ('REVIEW','DIRECT'));
ALTER TABLE disposal_authorization DROP CONSTRAINT ck_stage13_authorization_approval;
ALTER TABLE disposal_authorization ADD CONSTRAINT ck_stage13_authorization_approval CHECK (
    (authorization_mode='REVIEW' AND (
        (approved_by IS NULL AND approved_at IS NULL AND valid_from IS NULL AND valid_until IS NULL)
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND valid_from IS NOT NULL AND valid_until IS NOT NULL)))
    OR (authorization_mode='DIRECT' AND approved_by IS NULL AND approved_at IS NULL
        AND valid_from IS NOT NULL AND valid_until IS NOT NULL AND valid_until > valid_from)
);
ALTER TABLE disposal_authorization_event DROP CONSTRAINT ck_stage13_event_kind;
ALTER TABLE disposal_authorization_event ADD CONSTRAINT ck_stage13_event_kind CHECK (event_kind IN (
    'REQUEST','DIRECT_AUTHORIZE','APPROVE','REJECT','EXECUTE','RECEIPT','STOP','COMPLETE','FAIL','EXPIRE','CANCEL','MANUAL_RESULT',
    'DEVICE_STOP_UNAVAILABLE','DEVICE_CONTROL_UNAVAILABLE','DEVICE_NOT_BOUND','PROTOCOL_NOT_OPENED','DEVICE_OFFLINE','DEVICE_ALL_OFF_ISSUED'));
