package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.annotation.Value;

import com.uav.lowaltitude.modules.assessment.engine.RuleEngineProperties;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.SpaceRiskRepository.RuleVersionRow;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * C04 定时评估：2026-10-06 产品确认默认打开（OBS-06，app.rule-engine.c04.enabled 缺省 true，环境变量可关）；
 * 是否真正产出风险仍由规则集守卫决定（见 {@link #unavailableReason()}）。不接入阶段 7 的 RuleEngineWorker——
 * 两者的窗口语义与产出对象不同，共用一个 Worker 会让任一方的失败拖住另一方。
 * 单实例保护用最朴素的进程内标记 + 条件更新式的"上一次窗口"推进：本期只有单节点部署，
 * 多节点时需要换成数据库租约（已在报告的未接入项里说明）。
 *
 * 窗口按服务器处理时间推进（BUG-17）：每轮评估"上一轮之后最新状态被写入过"的目标，并回叠 {@link #REFRESH_OVERLAP}，
 * 盖住跨轮才提交的写入；同一异物重复命中由"同计划同目标已有未解除风险"去重，不会每轮多造一条。
 *
 * 同一轮里接着评估 C05（机场区域异物，2026-10-08 确认书 4-3，新-27）：以前只能手动触发，机场附近的异物没人点就不出风险。
 * C05 有自己的窗口，和 C04 互不拖累；系统里没有启用的机场时不跑，也不留空的运行记录。
 */
@Component
@ConditionalOnProperty(prefix = "app.rule-engine.c04", name = "enabled", havingValue = "true")
public class SpaceRiskEvaluationJob {
    private static final Logger log = LoggerFactory.getLogger(SpaceRiskEvaluationJob.class);
    /** 处理时间窗口的回叠：融合事务先取写入时刻、稍后才提交，回叠一段才不会漏掉恰好跨轮提交的那次写入。 */
    static final Duration REFRESH_OVERLAP = Duration.ofSeconds(30);
    private final SpaceRiskEvaluationService service;
    private final SpaceRiskRepository repository;
    private final RuleEngineProperties ruleEngine;
    private final AppClock clock;
    /**
     * 调度回看窗口（分钟），不是规则阈值：与 rule_param 的 C04.trend_window_min 分开配置，避免改一处漏一处。
     * 首轮（或连续失败后）最多回看这么久；观测时刻早于"现在 - 回看"的最新状态不再当作当前异物。
     */
    private final int windowMinutes;
    private volatile OffsetDateTime lastWindowTo;
    private volatile OffsetDateTime lastAirportWindowTo;
    private volatile String lastSkipReason;

    public SpaceRiskEvaluationJob(SpaceRiskEvaluationService service, SpaceRiskRepository repository,
            RuleEngineProperties ruleEngine, AppClock clock,
            @Value("${app.rule-engine.c04.window-minutes:30}") int windowMinutes) {
        this.service = service; this.repository = repository; this.ruleEngine = ruleEngine;
        this.clock = clock; this.windowMinutes = windowMinutes;
    }

    /**
     * 决策 9-16：定时评估前先看规则集是否可用。
     * 没有生效版本，或生效版本是 DEMO 参数而部署未打开 allow-demo-active 时不评估——
     * 演示阈值算出来的风险在生产里没有依据，宁可不产出也不能让它进风险队列。
     * 返回不可用的原因；可用时返回 null。
     */
    String unavailableReason() {
        RuleVersionRow version = repository.activeRuleSetVersion(SpaceRiskEvaluationService.RULE_SET_CODE);
        if (version == null) return "NO_ACTIVE_RULE_SET";
        if ("DEMO".equals(version.paramStatus()) && !ruleEngine.isAllowDemoActive()) return "DEMO_PARAMS_NOT_ALLOWED";
        return null;
    }

    @Scheduled(fixedDelayString = "${app.rule-engine.c04.poll-millis:60000}")
    public synchronized void tick() {
        String unavailable = unavailableReason();
        if (unavailable != null) {
            // 不写 flight_risk，也不留运行记录：定时任务每分钟一次，留痕只会刷屏。
            // 原因变化时记一条 INFO：默认打开后，部署方要能从日志看出"为什么没有鸟群风险"（没激活规则集 / DEMO 参数未放行）。
            if (!unavailable.equals(lastSkipReason)) log.info("space risk scheduled evaluation skipped: {}", unavailable);
            else log.debug("space risk scheduled evaluation skipped: {}", unavailable);
            lastSkipReason = unavailable;
            return;
        }
        lastSkipReason = null;
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime from = windowFrom(now);
        if (from.isBefore(now)) {
            try {
                var run = service.evaluateScheduled(from, now, now.minusMinutes(windowMinutes));
                if (run == null || SpaceRiskEvaluationService.STATUS_SUCCESS.equals(run.status())) lastWindowTo = now;
            } catch (RuntimeException ex) {
                // 整轮失败不推进窗口：下一轮重算同一段（最多回看 windowMinutes），避免这段时间的异物被静默跳过。
                log.warn("space risk scheduled evaluation failed: {}", ex.toString());
            }
        }
        tickAirport(now);
    }

    /** C05 一轮：与 C04 同样的窗口推进与失败重算，各记各的窗口。 */
    private void tickAirport(OffsetDateTime now) {
        try {
            if (!repository.anyEnabledAirport()) return;
            OffsetDateTime from = windowFrom(now, lastAirportWindowTo);
            if (!from.isBefore(now)) return;
            var run = service.evaluateScheduledAirport(from, now, now.minusMinutes(windowMinutes));
            if (run == null || SpaceRiskEvaluationService.STATUS_SUCCESS.equals(run.status())) lastAirportWindowTo = now;
        } catch (RuntimeException ex) {
            log.warn("airport-zone (C05) scheduled evaluation failed: {}", ex.toString());
        }
    }

    /** 本轮处理时间窗口的起点：上一轮终点回叠 {@link #REFRESH_OVERLAP}，但不早于回看下限。 */
    OffsetDateTime windowFrom(OffsetDateTime now) {
        return windowFrom(now, lastWindowTo);
    }

    /** C05 本轮窗口的起点，口径同 {@link #windowFrom(OffsetDateTime)}。 */
    OffsetDateTime airportWindowFrom(OffsetDateTime now) {
        return windowFrom(now, lastAirportWindowTo);
    }

    private OffsetDateTime windowFrom(OffsetDateTime now, OffsetDateTime last) {
        OffsetDateTime earliest = now.minusMinutes(windowMinutes);
        if (last == null) return earliest;
        OffsetDateTime overlapped = last.minus(REFRESH_OVERLAP);
        return overlapped.isBefore(earliest) ? earliest : overlapped;
    }
}
