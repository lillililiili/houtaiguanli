package com.uav.lowaltitude.modules.flight.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 飞行计划执行状态按计划时段推进（需求确认表增补四 F10 的默认口径，与原型一致）：
 * 未到起飞时刻为待执行；进入时段为执行中；时段结束为已完成。已取消的计划不动。
 *
 * <p>平台没有审批动作，也收不到来源方的实际起降回传，所以"执行中 / 已完成"只能按时段判定；
 * 计划时段过去了却从未匹配到目标，仍记为已完成，由"计划与实际对照"的引擎结论说明有没有飞——
 * 这与原型的做法相同。客户若要求以来源回传为准，关闭本任务即可，状态就停在来源写入的值。
 *
 * <p>条件更新只翻转 PENDING / EXECUTING，版本号随之递增；同一时刻多实例并发也只会有一个赢家。
 * 默认关闭（{@code app.flight.status-advance.enabled=false}），本地演示配置里打开。
 */
@Component
@ConditionalOnProperty(prefix = "app.flight.status-advance", name = "enabled", havingValue = "true")
public class FlightPlanStatusAdvanceJob {
    private static final Logger log = LoggerFactory.getLogger(FlightPlanStatusAdvanceJob.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final AppClock clock;

    public FlightPlanStatusAdvanceJob(JdbcTemplate jdbcTemplate, AppClock clock) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.flight.status-advance.poll-millis:60000}")
    public void poll() {
        int[] moved = advanceOnce();
        if (moved[0] > 0 || moved[1] > 0) log.info("flight plan status advanced: executing={}, completed={}", moved[0], moved[1]);
    }

    /** 按当前时刻推进一次；返回 [转为执行中的条数, 转为已完成的条数]。供测试与种子同步调用。 */
    @Transactional
    public int[] advanceOnce() {
        Instant now = clock.now();
        Map<String, Object> params = Map.of("now", Timestamp.from(now));
        int completed = jdbc.update("UPDATE flight_plan SET status_code='COMPLETED', updated_at=:now, version=version+1"
                + " WHERE status_code IN ('PENDING','EXECUTING') AND end_at IS NOT NULL AND end_at <= :now", params);
        int executing = jdbc.update("UPDATE flight_plan SET status_code='EXECUTING', updated_at=:now, version=version+1"
                + " WHERE status_code='PENDING' AND start_at IS NOT NULL AND start_at <= :now AND (end_at IS NULL OR end_at > :now)", params);
        return new int[] { executing, completed };
    }
}
