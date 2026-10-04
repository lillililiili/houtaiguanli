-- 2026-10-04 实时刷新：业务表写入提交后通过 pg_notify 发出“某类数据已变化”信号，
-- 后端监听后经 SSE 通知前端重读。信号只含主题名，不含业务数据；读取仍走原有授权接口。
-- 语句级触发器：一条语句只发一次，同一事务内相同主题由 PostgreSQL 合并，提交后才送达，回滚不发。
-- 后台任务常有影响 0 行的 UPDATE，语句级触发器仍会触发；借助转换表只在确有行变化时发信号。
-- 转换表要求每个触发器只对应一种事件，因此每张表建 INSERT/UPDATE/DELETE 三个触发器。
-- 覆盖所有写入方（应用、模拟器受控 seed、手工 SQL），因此放在库级触发器而不是 Java 写入点。

CREATE OR REPLACE FUNCTION app_notify_data_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM app_changed_rows) THEN
        PERFORM pg_notify('app_data_change', TG_ARGV[0]);
    END IF;
    RETURN NULL;
END
$$;

DO $$
DECLARE
    binding text[];
    bindings text[][] := ARRAY[
        -- 告警与无人机事件
        ARRAY['alarm', 'alarm'],
        ARRAY['alarm_merge_group', 'alarm'],
        ARRAY['alarm_merge_member', 'alarm'],
        ARRAY['uav_event', 'alarm'],
        ARRAY['uav_event_verification', 'alarm'],
        ARRAY['uav_event_advisory', 'alarm'],
        ARRAY['uav_event_voice_advisory', 'alarm'],
        -- uav_auto_sms_task / uav_auto_voice_task 是后台任务的租约与轮询记账，每秒都有更新且页面不显示，
        -- 不纳入信号；通知结果落在 uav_event_advisory / uav_event_voice_advisory。
        -- 目标与航迹
        ARRAY['target', 'target'],
        ARRAY['target_latest_state', 'target'],
        ARRAY['target_source_link', 'target'],
        ARRAY['target_track_status', 'target'],
        ARRAY['track_point', 'target'],
        ARRAY['ops_target_latest_state', 'target'],
        ARRAY['ops_track_point', 'target'],
        ARRAY['sensing_target', 'target'],
        -- 合法性研判
        ARRAY['legality_review', 'legality'],
        ARRAY['legality_review_history', 'legality'],
        ARRAY['assessment_result', 'legality'],
        -- 风险
        ARRAY['flight_risk', 'risk'],
        ARRAY['flight_risk_verification', 'risk'],
        ARRAY['space_risk_fact', 'risk'],
        ARRAY['weather_risk_fact', 'risk'],
        ARRAY['risk_clearance_evidence', 'risk'],
        -- 飞行计划与航线
        ARRAY['flight_plan', 'plan'],
        ARRAY['flight_plan_verification', 'plan'],
        ARRAY['flight_plan_feedback', 'plan'],
        ARRAY['route', 'plan'],
        ARRAY['route_version', 'plan'],
        -- 空域
        ARRAY['airspace', 'airspace'],
        ARRAY['airspace_version', 'airspace'],
        ARRAY['airspace_delivery', 'airspace'],
        -- 设备与运维
        ARRAY['device', 'device'],
        ARRAY['device_state', 'device'],
        ARRAY['device_incident', 'device'],
        ARRAY['device_event_log', 'device'],
        ARRAY['ops_device', 'device'],
        ARRAY['ops_device_state', 'device'],
        ARRAY['ops_device_maintenance_task', 'device'],
        ARRAY['commission_task', 'device'],
        ARRAY['commission_task_event', 'device'],
        -- 处置、反制与光电控制
        ARRAY['disposal_authorization', 'disposal'],
        ARRAY['disposal_authorization_event', 'disposal'],
        ARRAY['disposal_emergency_stop', 'disposal'],
        ARRAY['device_command', 'disposal'],
        ARRAY['eo_tracking_task', 'disposal'],
        ARRAY['eo_target_control', 'disposal'],
        -- 证据
        ARRAY['evidence_file', 'evidence'],
        ARRAY['evidence_link', 'evidence'],
        -- 处罚与移送
        ARRAY['punishment_case', 'punishment'],
        ARRAY['punishment_case_event', 'punishment'],
        ARRAY['handoff', 'punishment'],
        ARRAY['handoff_delivery', 'punishment']
    ];
BEGIN
    FOREACH binding SLICE 1 IN ARRAY bindings LOOP
        IF to_regclass('public.' || binding[1]) IS NULL THEN
            RAISE NOTICE 'realtime notify: table % not found, skipped', binding[1];
            CONTINUE;
        END IF;
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
