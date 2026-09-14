package com.uav.lowaltitude.modules.assessment.engine;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RuleSetRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunHandle;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunSummary;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 定时评估 Worker（app.rule-engine.enabled=true 才装配，默认关闭）。每个 tick：单行租约条件更新 → 对每个有生效/影子版本的规则集，
 * 取"最新状态在最近同模式研判之后更新且仍新鲜"的目标（批 50）→ 起一次 SCHEDULED 运行 → 每主体独立事务 → 收尾并计数。
 * 没有待评估主体就不留运行记录，避免每 5 秒写一行空运行。独立于 OutboxWorker：设备指令与研判是两条互不干扰的后台链路。
 */
@Component
@ConditionalOnProperty(prefix = "app.rule-engine", name = "enabled", havingValue = "true")
public class RuleEngineWorker {
    static final String LEASE_NAME = "rule-engine";
    private static final Logger log = LoggerFactory.getLogger(RuleEngineWorker.class);
    private final RuleEngineRepository repository;
    private final RuleRunService runs;
    private final RuleParamLoader params;
    private final RuleEngineProperties properties;
    private final AppClock clock;
    private final String holder;

    public RuleEngineWorker(RuleEngineRepository repository, RuleRunService runs, RuleParamLoader params, RuleEngineProperties properties, AppClock clock) {
        this.repository = repository; this.runs = runs; this.params = params; this.properties = properties; this.clock = clock;
        this.holder = properties.getInstanceId().isBlank() ? defaultHolder() : properties.getInstanceId();
    }

    @Scheduled(fixedDelayString = "${app.rule-engine.poll-millis:5000}")
    public void tick() {
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        if (!repository.acquireLease(LEASE_NAME, holder, now, now.plusSeconds(properties.getLeaseSeconds()))) return;
        // 上一进程崩溃留下的 RUNNING 运行永远无法收尾：超过租约四倍仍未结束的按 FAILED 回收，只增触发器允许这一次终结。
        int reclaimed = repository.failStaleRuns(now.minusSeconds(4L * properties.getLeaseSeconds()), now);
        if (reclaimed > 0) log.warn("rule engine reclaimed stale runs: count={}", reclaimed);
        for (RuleSetRow set : repository.ruleSetsWithVersions()) {
            if (set.activeVersionId() != null) runFor(set, RunMode.ACTIVE, set.activeVersionId(), now);
            if (set.shadowVersionId() != null) runFor(set, RunMode.SHADOW, set.shadowVersionId(), now);
        }
    }

    private void runFor(RuleSetRow set, RunMode mode, String versionId, OffsetDateTime now) {
        try {
            RuleParams ruleParams = params.load(versionId);
            int freshSeconds = ruleParams.integer(RuleCodes.C03, LegalityEvaluationService.PARAM_FRESH_SECONDS);
            List<Subject> subjects = repository.pendingSubjects(mode, versionId, now.minusSeconds(freshSeconds), properties.getBatchSize());
            if (subjects.isEmpty()) return;
            RunHandle run = runs.start(set.ruleSetCode(), mode, LegalityEvaluationService.TRIGGER_SCHEDULED, null, null, now);
            RunSummary summary = runs.runBatch(run, subjects, now);
            log.info("rule engine run finished: set={}, mode={}, run={}, subjects={}, evaluated={}, alarms={}/{}, errors={}", set.ruleSetCode(), mode,
                    run.runId(), summary.subjectCount(), summary.evaluatedCount(), summary.alarmCreatedCount(), summary.alarmMergedCount(), summary.errors().size());
        } catch (RuntimeException ex) {
            // 参数缺失等部署错误不能让调度线程死掉；记日志并等下一个 tick，其他规则集照常运行。
            log.error("rule engine run failed: set={}, mode={}, error={}", set.ruleSetCode(), mode, ex.toString());
        }
    }

    private static String defaultHolder() {
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); }
        catch (Exception ex) { host = "unknown-host"; }
        return host + ":" + ProcessHandle.current().pid();
    }
}
