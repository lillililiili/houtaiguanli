-- 2026-10-04 实时刷新信号细分（接 V202610040001）。
-- 实测模拟场景下设备每次上报都写 ops_device_state / device_event_log，每分钟约 40 次 device 信号，
-- 让只关心设备资料的页面（融合感知资料组、运维消息）跟着全量重读，请求量涨了十倍。
-- 1. 设备在线状态与设备事件改发 device_state；device 只保留设备资料、维护、故障与调测。
-- 2. alarm_merge_group 只是告警合并引擎内部的窗口状态，没有页面读取，不再发信号；
--    alarm_merge_member 只被合法性研判列表读取，改发 legality，不再带动告警页重读。

DO $$
DECLARE
    binding text[];
    bindings text[][] := ARRAY[
        ARRAY['ops_device_state', 'device_state'],
        ARRAY['device_state', 'device_state'],
        ARRAY['device_event_log', 'device_state'],
        ARRAY['alarm_merge_member', 'legality'],
        ARRAY['alarm_merge_group', '']
    ];
BEGIN
    FOREACH binding SLICE 1 IN ARRAY bindings LOOP
        IF to_regclass('public.' || binding[1]) IS NULL THEN
            RAISE NOTICE 'realtime notify: table % not found, skipped', binding[1];
            CONTINUE;
        END IF;
        EXECUTE format('DROP TRIGGER IF EXISTS trg_app_data_change_ins ON public.%I', binding[1]);
        EXECUTE format('DROP TRIGGER IF EXISTS trg_app_data_change_upd ON public.%I', binding[1]);
        EXECUTE format('DROP TRIGGER IF EXISTS trg_app_data_change_del ON public.%I', binding[1]);
        CONTINUE WHEN binding[2] = '';
        EXECUTE format(
            'CREATE TRIGGER trg_app_data_change_ins AFTER INSERT ON public.%I '
            'REFERENCING NEW TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)',
            binding[1], binding[2]);
        EXECUTE format(
            'CREATE TRIGGER trg_app_data_change_upd AFTER UPDATE ON public.%I '
            'REFERENCING NEW TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)',
            binding[1], binding[2]);
        EXECUTE format(
            'CREATE TRIGGER trg_app_data_change_del AFTER DELETE ON public.%I '
            'REFERENCING OLD TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)',
            binding[1], binding[2]);
    END LOOP;
END
$$;
