package com.uav.lowaltitude.modules.risk.application.spacerisk;

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
 * C04 定时评估：默认关闭（app.rule-engine.c04.enabled=false），不接入阶段 7 的 RuleEngineWorker——
 * 两者的窗口语义与产出对象不同，共用一个 Worker 会让任一方的失败拖住另一方。
 * 单实例保护用最朴素的进程内标记 + 条件更新式的"上一次窗口"推进：本期只有单节点部署，
 * 多节点时需要换成数据库租约（已在报告的未接入项里说明）。
 */
@Component
@ConditionalOnProperty(prefix = "app.rule-engine.c04", name = "enabled", havingValue = "true")
public class SpaceRiskEvaluationJob {
    private static final Logger log = LoggerFactory.getLogger(SpaceRiskEvaluationJob.class);
    private final SpaceRiskEvaluationService service;
    private final SpaceRiskRepository repository;
    private final RuleEngineProperties ruleEngine;
    private final AppClock clock;
    /** 调度回看窗口（分钟），不是规则阈值：与 rule_param 的 C04.trend_window_min 分开配置，避免改一处漏一处。 */
    private final int windowMinutes;
    private volatile OffsetDateTime lastWindowTo;

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
            // 不写 flight_risk，也不留运行记录：定时任务每分钟一次，留痕只会刷屏；原因写日志即可。
            log.debug("space risk scheduled evaluation skipped: {}", unavailable);
            return;
        }
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime from = lastWindowTo == null ? now.minusMinutes(windowMinutes) : lastWindowTo;
        if (!from.isBefore(now)) return;
        try {
            service.evaluate(C04DecisionTable.RULE_CODE, from, now, "SCHEDULED", null);
            lastWindowTo = now;
        } catch (RuntimeException ex) {
            // 失败不推进窗口：下一轮重算同一段，避免这段时间的异物被静默跳过。
            log.warn("space risk scheduled evaluation failed: {}", ex.toString());
        }
    }
}
