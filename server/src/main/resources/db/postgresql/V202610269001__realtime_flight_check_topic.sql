-- 反制前、反制中的航班检查记录写入后，告警页面按处置主题重读。
DO $$
BEGIN
    EXECUTE 'CREATE TRIGGER trg_app_data_change_ins AFTER INSERT ON public.counter_flight_check '
        'REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change(''disposal'')';
END
$$;
