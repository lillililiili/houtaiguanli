-- Serialize new current work with unit retirement, including ownership columns without an FK.
-- Capture the migration schema so isolated schemas and the packaged application use the same tables.
CREATE FUNCTION require_current_organization(p_org TEXT) RETURNS VOID
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE retired BIGINT;
BEGIN
    IF p_org IS NULL OR p_org='' THEN RETURN; END IF;
    BEGIN
        SELECT deleted_at INTO retired FROM app_org WHERE org_id=p_org FOR KEY SHARE NOWAIT;
        IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P7103', MESSAGE='Organization does not exist'; END IF;
        IF retired IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P7101', MESSAGE='Organization is deleted'; END IF;
    EXCEPTION WHEN lock_not_available THEN
        RAISE EXCEPTION USING ERRCODE='P7102', MESSAGE='Organization is being changed';
    END;
END;
$$;

CREATE FUNCTION guard_current_organization_write() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    n JSONB := to_jsonb(NEW);
    orgs TEXT[] := ARRAY[]::TEXT[];
    item TEXT;
    command_device TEXT;
    ownership_changed BOOLEAN := FALSE;
BEGIN
    IF TG_OP='UPDATE' THEN
        FOREACH item IN ARRAY ARRAY['org_id','owner_org_id','operator_org_id','management_org_id','recipient_org_id',
            'device_id','ops_device_id','target_id','event_id','alarm_id','plan_id','source_binding_id','handoff_id','task_id','authorization_id','role_code'] LOOP
            IF n->item IS DISTINCT FROM to_jsonb(OLD)->item THEN ownership_changed := TRUE; EXIT; END IF;
        END LOOP;
    END IF;
    -- Terminal transitions, frozen history and safety stops are deliberately not new activity.
    IF TG_TABLE_NAME='app_org' THEN
        IF n->>'deleted_at' IS NOT NULL THEN RETURN NEW; END IF;
        PERFORM require_current_organization(n->>'parent_id');
        RETURN NEW;
    END IF;
    -- A terminal label must not allow a record to be moved into/out of a retired unit.
    IF NOT ownership_changed THEN
    IF TG_TABLE_NAME='app_user' AND n->>'status'='DELETED' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='app_role' AND TG_OP='UPDATE' AND n->>'enabled'='false' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='app_org_delegation' AND TG_OP='UPDATE' AND n->>'administrator_user_id' IS NULL THEN RETURN NEW;
    ELSIF TG_TABLE_NAME IN ('business_contact','plan_source_binding','notification_setting','mqtt_broker')
        AND TG_OP='UPDATE' AND n->>'enabled'='false' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='flight_plan' AND n->>'status_code' IN ('COMPLETED','CANCELLED') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='flight_risk' AND n->>'state_code' IN ('ACKNOWLEDGED','EXCLUDED') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='prediction_episode' AND n->>'status'='CLEARED' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='disposal_authorization' AND n->>'status' NOT IN ('REQUESTED','APPROVED','EXECUTING') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='eo_tracking_task' AND n->>'status'<>'OPEN' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='eo_dispatch_request' AND n->>'status' NOT IN ('WAITING','STOPPING') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='commission_task' AND n->>'status' NOT IN ('CREATED','CONNECTING','CONNECTED','READY','RUNNING') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='ops_device_maintenance_task' AND n->>'status'='HANDLED' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='ops_device_maintenance_notice_attempt' AND TG_OP='UPDATE'
        AND n->>'task_id' IS NOT DISTINCT FROM to_jsonb(OLD)->>'task_id' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='ops_device_maintenance_notice_attempt' AND n->>'outcome_state' IN ('COMPLETED','NOT_SENT') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='uav_event' AND n->>'state_code'='FALSE_POSITIVE' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='uav_auto_sms_task' AND n->>'status' NOT IN ('WAITING','SENDING') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='uav_auto_voice_task' AND n->>'status' NOT IN ('WAITING','CALLING','UNKNOWN') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='handoff_delivery' AND TG_OP='UPDATE'
        AND n->>'handoff_id' IS NOT DISTINCT FROM to_jsonb(OLD)->>'handoff_id' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='handoff_delivery' AND (n->>'receipt_status'='ACKNOWLEDGED'
          OR (n->>'delivery_status'='DELIVERED' AND n->>'receipt_status'='NOT_EXPECTED')) THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='handoff_notification_decision' AND n->>'state'='DO_NOT_NOTIFY' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='punishment_case' AND n->>'status' IN ('CLOSED','WITHDRAWN') THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='rule_evaluation' AND n->>'mode'<>'ACTIVE' THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='device_command' THEN
        IF n->>'status' NOT IN ('QUEUED','SENT','ACCEPTED') OR n->>'command_type' IN ('EO_END_TRACK','COUNTERMEASURE_4CH','LINGYUN_CONTROL') THEN
            -- The two control types insert their detail after the envelope. Their detail guards
            -- distinguish start from stop; send-state changes still check the stored detail.
            IF TG_OP='INSERT' OR n->>'status' NOT IN ('QUEUED','SENT','ACCEPTED') OR n->>'command_type'='EO_END_TRACK' THEN RETURN NEW; END IF;
            IF n->>'command_type'='COUNTERMEASURE_4CH' AND NOT EXISTS (SELECT 1 FROM countermeasure_4ch_command x
                WHERE x.command_id=n->>'command_id' AND (x.action='CHANNEL_ON' OR (x.action='SET_MASK' AND x.mask<>0))) THEN RETURN NEW; END IF;
            IF n->>'command_type'='LINGYUN_CONTROL' AND NOT EXISTS (SELECT 1 FROM lingyun_control_command x
                WHERE x.command_id=n->>'command_id' AND x.operation_type<>0) THEN RETURN NEW; END IF;
        END IF;
    ELSIF TG_TABLE_NAME='countermeasure_4ch_command' AND (n->>'action'='CHANNEL_OFF'
          OR (n->>'action'='SET_MASK' AND (n->>'mask')::INTEGER=0)) THEN RETURN NEW;
    ELSIF TG_TABLE_NAME='lingyun_control_command' AND (n->>'operation_type')::INTEGER=0 THEN RETURN NEW;
    END IF;
    END IF;

    FOR n IN SELECT to_jsonb(NEW) UNION ALL SELECT to_jsonb(OLD) WHERE ownership_changed LOOP
    command_device := NULL;
    orgs := orgs || ARRAY[n->>'org_id',n->>'owner_org_id',n->>'operator_org_id',n->>'management_org_id',n->>'recipient_org_id'];
    IF n->>'role_code' IS NOT NULL THEN
        orgs := orgs || ARRAY(SELECT owner_org_id::TEXT FROM app_role WHERE role_code=n->>'role_code');
    END IF;
    IF n->>'target_id' IS NOT NULL THEN
        orgs := orgs || ARRAY(SELECT owner_org_id::TEXT FROM target WHERE target_id=n->>'target_id');
    END IF;
    IF n->>'event_id' IS NOT NULL THEN
        orgs := orgs || ARRAY(SELECT owner_org_id::TEXT FROM uav_event WHERE event_id=n->>'event_id');
    END IF;
    IF n->>'alarm_id' IS NOT NULL THEN
        orgs := orgs || ARRAY(SELECT owner_org_id::TEXT FROM alarm WHERE alarm_id=n->>'alarm_id');
    END IF;
    IF n->>'plan_id' IS NOT NULL THEN
        orgs := orgs || ARRAY(SELECT owner_org_id::TEXT FROM flight_plan WHERE plan_id=n->>'plan_id')
                     || ARRAY(SELECT operator_org_id::TEXT FROM flight_plan WHERE plan_id=n->>'plan_id');
    END IF;
    IF n->>'source_binding_id' IS NOT NULL THEN
        orgs := orgs || ARRAY(SELECT org_id::TEXT FROM plan_source_binding WHERE binding_id=n->>'source_binding_id');
    END IF;
    IF n->>'handoff_id' IS NOT NULL THEN
        orgs := orgs || ARRAY(SELECT owner_org_id::TEXT FROM handoff WHERE handoff_id=n->>'handoff_id');
    END IF;
    IF TG_TABLE_NAME='ops_device_maintenance_notice_attempt' THEN
        orgs := orgs || ARRAY(SELECT m.owner_org_id::TEXT FROM ops_device_maintenance_task m WHERE m.task_id=n->>'task_id')
                     || ARRAY(SELECT s.owner_org_id::TEXT FROM ops_device_maintenance_task m JOIN device_business_scope s
                              ON s.ops_device_id=m.device_id WHERE m.task_id=n->>'task_id');
    END IF;
    IF n->>'authorization_id' IS NOT NULL THEN
        orgs := orgs || ARRAY(SELECT owner_org_id::TEXT FROM disposal_authorization WHERE authorization_id=n->>'authorization_id');
    END IF;
    IF n->>'command_id' IS NOT NULL THEN
        SELECT device_id INTO command_device FROM device_command WHERE command_id=n->>'command_id';
        orgs := orgs || ARRAY(SELECT a.owner_org_id::TEXT FROM disposal_authorization a
            WHERE EXISTS (SELECT 1 FROM countermeasure_4ch_command x WHERE x.command_id=n->>'command_id' AND x.authorization_id=a.authorization_id)
               OR EXISTS (SELECT 1 FROM lingyun_control_command x WHERE x.command_id=n->>'command_id' AND x.authorization_id=a.authorization_id));
    END IF;
    FOR item IN SELECT DISTINCT d FROM unnest(ARRAY[n->>'device_id',n->>'ops_device_id',command_device]) d WHERE d IS NOT NULL LOOP
        orgs := orgs || ARRAY(SELECT owner_org_id::TEXT FROM device_business_scope WHERE ops_device_id=item)
                     || ARRAY(SELECT owner_org_id::TEXT FROM device WHERE device_id=item);
    END LOOP;
    END LOOP;
    -- Deterministic order, fail immediately instead of waiting with a business row lock held.
    FOR item IN SELECT DISTINCT value FROM unnest(orgs) value WHERE value IS NOT NULL AND value<>'' ORDER BY value LOOP
        PERFORM require_current_organization(item);
    END LOOP;
    RETURN NEW;
END;
$$;

DO $$
DECLARE guarded TEXT;
BEGIN
    FOREACH guarded IN ARRAY ARRAY[
        'app_org','app_user','app_user_data_scope','app_role','app_org_delegation',
        'app_org_permission','app_org_action','app_org_management_permission','pending_user_registration',
        'business_contact','plan_source_binding','notification_setting','mqtt_broker','device_business_scope','device',
        'target','target_latest_state','flight_plan','flight_risk','prediction_episode',
        'rule_evaluation','alarm','alarm_escalation','uav_event','disposal_authorization',
        'device_command','countermeasure_4ch_command','lingyun_control_command',
        'eo_tracking_task','eo_dispatch_request','commission_task','ops_device_maintenance_task',
        'ops_device_maintenance_notice_attempt','uav_auto_sms_task','uav_auto_voice_task',
        'handoff','handoff_delivery','handoff_notification_decision','punishment_case'
    ] LOOP
        -- These tables are required by the completed lineage. A typo/missing table must fail
        -- migration instead of silently omitting a tenant isolation guard.
        EXECUTE format('CREATE TRIGGER guard_current_organization BEFORE INSERT OR UPDATE ON %I.%I '
                     || 'FOR EACH ROW EXECUTE FUNCTION guard_current_organization_write()',current_schema(),guarded);
    END LOOP;
END;
$$;
