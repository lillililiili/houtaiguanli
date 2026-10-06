-- 2026-10-06 告警升级记录（接 db/migration/V202610069131）的 PostgreSQL 专属部分（H2 不加载）。
-- 1. alarm_escalation 只增：升级记录是“谁、什么时候、为什么升级”的审计事实，纠错只能追加新行。
-- 2. 告警页读取当前等级、违规原因与升级记录，升级写入后发 alarm 信号让告警页重读（同 V202610059001）。

CREATE OR REPLACE FUNCTION prevent_alarm_escalation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'alarm escalations are append-only' USING ERRCODE = '23514';
END
$$;

DROP TRIGGER IF EXISTS trg_alarm_escalation_append_only ON alarm_escalation;
CREATE TRIGGER trg_alarm_escalation_append_only
BEFORE UPDATE OR DELETE ON alarm_escalation
FOR EACH ROW EXECUTE FUNCTION prevent_alarm_escalation_mutation();

DO $$
DECLARE
    binding text[];
    bindings text[][] := ARRAY[
        ARRAY['alarm_escalation', 'alarm']
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
