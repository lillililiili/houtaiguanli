-- 2026-10-06 实时刷新信号细分（接 V202610059002）。
-- 光电跟踪每收到一次设备跟踪回报都会更新 eo_tracking_task（last_report_at），跟踪期间每秒多次发 disposal 信号，
-- 告警页、反制办理、证据页跟着反复重读处置进度，告警页因此不停重画（BUG-06）。
-- 这两张表只被光电跟踪面板读取（面板自己定时读），改发单独的 tracking 信号；
-- disposal 只保留处置授权、授权事件、急停和设备指令的变化。

DO $$
DECLARE
    binding text[];
    bindings text[][] := ARRAY[
        ARRAY['eo_tracking_task', 'tracking'],
        ARRAY['eo_target_control', 'tracking']
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
