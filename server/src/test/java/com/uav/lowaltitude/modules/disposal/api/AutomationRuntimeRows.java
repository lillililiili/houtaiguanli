package com.uav.lowaltitude.modules.disposal.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 自动规则引擎默认打开（application.yml），后台每 2 秒会给观测新鲜的测试事件写判定记录。
 * 这些记录引用 uav_event，删测试事件之前要先清掉；机器忙时引擎可能刚好在两步之间又写一条，所以删不掉就重来几次。
 */
public final class AutomationRuntimeRows {
    private static final String EVENTS = "select event_id from uav_event where event_id like ?";

    private AutomationRuntimeRows() {}

    /** 删除 event_id 符合 {@code eventIdLike} 的测试事件，连同自动规则引擎给它们写的判定记录。 */
    public static void deleteEvents(JdbcTemplate jdbc, String eventIdLike) {
        for (int attempt = 1; ; attempt++) {
            forget(jdbc, eventIdLike);
            try {
                jdbc.update("delete from uav_event where event_id like ?", eventIdLike);
                return;
            } catch (DataIntegrityViolationException raced) {
                if (attempt >= 5) throw raced;
            }
        }
    }

    private static void forget(JdbcTemplate jdbc, String eventIdLike) {
        jdbc.update("delete from automation_runtime_state where event_id in (" + EVENTS + ")", eventIdLike);
        jdbc.update("delete from automation_runtime_run_action where run_id in"
                + " (select run_id from automation_runtime_run where event_id in (" + EVENTS + "))", eventIdLike);
        jdbc.update("delete from automation_runtime_action where event_id in (" + EVENTS + ")", eventIdLike);
        jdbc.update("delete from automation_runtime_run where event_id in (" + EVENTS + ")", eventIdLike);
    }
}
