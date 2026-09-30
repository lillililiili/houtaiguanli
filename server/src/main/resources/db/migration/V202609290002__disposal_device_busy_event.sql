-- Keep device occupancy distinct from a fault and preserve the blocked authorization history.
ALTER TABLE disposal_authorization_event DROP CONSTRAINT ck_stage13_event_kind;
ALTER TABLE disposal_authorization_event ADD CONSTRAINT ck_stage13_event_kind CHECK (event_kind IN (
    'REQUEST','DIRECT_AUTHORIZE','APPROVE','REJECT','EXECUTE','RECEIPT','STOP','COMPLETE','FAIL','EXPIRE','CANCEL','MANUAL_RESULT',
    'DEVICE_STOP_UNAVAILABLE','DEVICE_CONTROL_UNAVAILABLE','DEVICE_NOT_BOUND','PROTOCOL_NOT_OPENED','DEVICE_OFFLINE',
    'DEVICE_ALL_OFF_ISSUED','DEVICE_FAULT','DEVICE_BUSY'));
