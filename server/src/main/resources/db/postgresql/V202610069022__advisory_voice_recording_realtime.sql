-- 2026-10-06 电话通知录音的实时刷新信号（接 V202610069021，写法同 V202610059001）。
-- 录音清单和当前选用变化发 voice_recording：后台“接口配置 → 电话通知录音”收到后重读。
-- 当前选用变化另发 alarm：告警详情里的飞手电话状态取决于有没有可播放的录音，业务前台随之重读。
-- 可重复执行：建触发器前先删同名触发器。

DO $$
DECLARE
    binding text[];
    bindings text[][] := ARRAY[
        ARRAY['advisory_voice_recording', 'voice_recording', 'trg_app_data_change'],
        ARRAY['advisory_voice_recording_setting', 'voice_recording', 'trg_app_data_change'],
        ARRAY['advisory_voice_recording_setting', 'alarm', 'trg_app_data_change_alarm']
    ];
BEGIN
    FOREACH binding SLICE 1 IN ARRAY bindings LOOP
        IF to_regclass('public.' || binding[1]) IS NULL THEN
            RAISE NOTICE 'realtime notify: table % not found, skipped', binding[1];
            CONTINUE;
        END IF;
        EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', binding[3] || '_ins', binding[1]);
        EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', binding[3] || '_upd', binding[1]);
        EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', binding[3] || '_del', binding[1]);
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT ON public.%I '
            'REFERENCING NEW TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)',
            binding[3] || '_ins', binding[1], binding[2]);
        EXECUTE format(
            'CREATE TRIGGER %I AFTER UPDATE ON public.%I '
            'REFERENCING NEW TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)',
            binding[3] || '_upd', binding[1], binding[2]);
        EXECUTE format(
            'CREATE TRIGGER %I AFTER DELETE ON public.%I '
            'REFERENCING OLD TABLE AS app_changed_rows '
            'FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(%L)',
            binding[3] || '_del', binding[1], binding[2]);
    END LOOP;
END
$$;
