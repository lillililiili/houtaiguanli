-- 页面改用 SSE 后，配置、处置单关联及自动设备检查的变化也要发出通知。
-- 只发送主题；数据仍经原有鉴权接口读取。后台租约、重试记账不作为页面事实。
DO $$
DECLARE
    binding text[];
    bindings text[][] := ARRAY[
        ARRAY['device_sensing_profile', 'device'],
        ARRAY['device_connection_profile', 'device'],
        ARRAY['ops_maintenance_workflow_event', 'device'],
        ARRAY['ops_maintenance_commission', 'device'],
        ARRAY['ops_maintenance_message_read', 'device'],
        ARRAY['ops_device_maintenance_notice_attempt', 'device'],
        ARRAY['ops_maintenance_plan', 'device'],
        ARRAY['ops_maintenance_report', 'device'],
        ARRAY['ops_maintenance_incident', 'device'],
        ARRAY['flight_device_check_schedule', 'plan_check'],
        ARRAY['fusion_config', 'fusion']
    ];
BEGIN
    FOREACH binding SLICE 1 IN ARRAY bindings LOOP
        IF to_regclass('public.' || binding[1]) IS NULL THEN
            RAISE NOTICE 'realtime notify: table % not found, skipped', binding[1];
            CONTINUE;
        END IF;
        EXECUTE format('CREATE TRIGGER trg_app_data_change_ins AFTER INSERT ON public.%I '
            'REFERENCING NEW TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)', binding[1], binding[2]);
        EXECUTE format('CREATE TRIGGER trg_app_data_change_upd AFTER UPDATE ON public.%I '
            'REFERENCING NEW TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)', binding[1], binding[2]);
        EXECUTE format('CREATE TRIGGER trg_app_data_change_del AFTER DELETE ON public.%I '
            'REFERENCING OLD TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)', binding[1], binding[2]);
    END LOOP;
END
$$;
