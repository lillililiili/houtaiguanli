-- Bearer session IDs are credentials and must never remain in audit records.
-- Earlier development builds used the session ID as the audited object ID for
-- login/logout events. Preserve the actor relationship while removing that
-- credential-shaped value during upgrades as well as fresh installations.
UPDATE audit_log
SET object_type = 'user',
    object_id = user_id,
    detail = NULL
WHERE LOWER(COALESCE(object_type, '')) = 'session';
