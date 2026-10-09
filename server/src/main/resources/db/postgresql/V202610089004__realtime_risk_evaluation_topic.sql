-- 2026-10-08 P03 实时刷新信号（接 V202610059001 / V202610069041）。
-- 空中异物风险的评估历史（space_risk_evaluation_segment）每轮定时评估（约每分钟）都会更新；发 risk 会让风险列表、空域页跟着每分钟全量重读。
-- 这张表只有风险详情里的“评估历史”一栏读取，改发单独的 risk_evaluation 信号，由那一栏自己订阅、只重读这一栏。

DO $$
DECLARE
    binding text[];
    bindings text[][] := ARRAY[
        ARRAY['space_risk_evaluation_segment', 'risk_evaluation']
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
