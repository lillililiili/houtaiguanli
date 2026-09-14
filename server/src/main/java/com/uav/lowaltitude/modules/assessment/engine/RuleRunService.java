package com.uav.lowaltitude.modules.assessment.engine;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.modules.assessment.engine.LegalityEvaluationService.EvaluationResult;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineHooks.RunContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RuleSetRow;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 运行记录：每次引擎运行（定时/手动/重算/回放）留一行 rule_run，起始 RUNNING，收尾时一次性写状态与计数。
 * 批次内每个主体独立事务（NESTED：无外层事务时即独立提交，有外层事务时为保存点），单个主体失败只记 error_summary，不影响其他主体。
 */
@Service
public class RuleRunService {
    public static final String STATUS_RUNNING = "RUNNING", STATUS_DONE = "DONE", STATUS_FAILED = "FAILED", STATUS_SKIPPED = "SKIPPED";
    private static final Set<String> TRIGGERS = Set.of("SCHEDULED", "MANUAL", "RECOMPUTE", "REPLAY");
    private static final int ERROR_SUMMARY_MAX = 2000;
    private static final Logger log = LoggerFactory.getLogger(RuleRunService.class);
    private final RuleEngineRepository repository;
    private final LegalityEvaluationService evaluation;
    private final RuleParamLoader params;
    private final RuleEngineHooks hooks;
    private final AppClock clock;
    private final AppProperties app;
    private final TransactionTemplate perSubject;
    /** finish 由本类内部调用，@Transactional 的代理不会生效；用显式事务模板保证收尾与钩子同一事务。 */
    private final TransactionTemplate required;

    public RuleRunService(RuleEngineRepository repository, LegalityEvaluationService evaluation, RuleParamLoader params, RuleEngineHooks hooks,
            AppClock clock, AppProperties app, PlatformTransactionManager transactionManager) {
        this.repository = repository; this.evaluation = evaluation; this.params = params; this.hooks = hooks; this.clock = clock; this.app = app;
        this.perSubject = new TransactionTemplate(transactionManager);
        this.perSubject.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** 起运行：ACTIVE 取生效版本（无则 NO_ACTIVE_RULE_SET），SHADOW 取影子版本（无则 SHADOW_VERSION_NOT_SET）；没有版本就不留运行记录。 */
    @Transactional
    public RunHandle start(String ruleSetCode, RunMode mode, String triggerKind, String replayDatasetCode, String triggeredBy, OffsetDateTime asOf) {
        if (mode == null) throw new IllegalArgumentException("运行模式不能为空");
        if (triggerKind == null || !TRIGGERS.contains(triggerKind)) throw new IllegalArgumentException("触发方式无效: " + triggerKind);
        boolean replay = "REPLAY".equals(triggerKind);
        if (replay == (replayDatasetCode == null || replayDatasetCode.isBlank())) throw new IllegalArgumentException("回放必须且只有回放才带数据集编码");
        RuleSetRow set = repository.findRuleSetByCode(ruleSetCode == null ? "" : ruleSetCode.trim());
        if (set == null) throw new ApiException(HttpStatus.NOT_FOUND, "RULE_SET_NOT_FOUND", "规则集不存在");
        String versionId = mode == RunMode.ACTIVE ? set.activeVersionId() : set.shadowVersionId();
        if (versionId == null) {
            throw mode == RunMode.ACTIVE
                    ? new ApiException(HttpStatus.CONFLICT, "NO_ACTIVE_RULE_SET", "规则集没有生效版本")
                    : new ApiException(HttpStatus.CONFLICT, "SHADOW_VERSION_NOT_SET", "规则集没有影子版本");
        }
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime effectiveAsOf = asOf == null ? now : asOf;
        String runId = UUID.randomUUID().toString();
        repository.insertRun(runId, set.ruleSetId(), versionId, mode, triggerKind, replay ? replayDatasetCode.trim() : null, triggeredBy, effectiveAsOf, now, app.getSourceMode());
        return new RunHandle(runId, set.ruleSetId(), set.ruleSetCode(), versionId, mode, triggerKind, effectiveAsOf);
    }

    /** 批量评估并收尾。调用方无需开启事务；有外层事务时每个主体退化为保存点。 */
    public RunSummary runBatch(RunHandle run, List<Subject> subjects, OffsetDateTime asOf) {
        int evaluated = 0, alarmsCreated = 0, alarmsMerged = 0;
        List<String> errors = new ArrayList<>();
        for (Subject subject : subjects) {
            try {
                EvaluationResult result = perSubject.execute(status -> evaluation.evaluateInCurrentTransaction(subject, run.mode(), asOf, run.runId(), null));
                evaluated++;
                if (result.alarmCreated()) alarmsCreated++;
                if (result.alarmMerged()) alarmsMerged++;
            } catch (RuntimeException ex) {
                // 失败主体只记摘要：异常消息可能含内部细节，不进 API，只进运行记录供排障。
                errors.add(subject.subjectId() + ": " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                log.warn("legality evaluation failed: run={}, subject={}, error={}", run.runId(), subject.subjectId(), ex.toString());
            }
        }
        RunSummary summary = new RunSummary(subjects.size(), evaluated, alarmsCreated, alarmsMerged, List.copyOf(errors));
        finish(run, summary);
        return summary;
    }

    /** 单主体（手动触发/重算）：异常向上抛，运行记录随调用方事务一起回滚。 */
    @Transactional
    public EvaluationResult evaluateOne(RunHandle run, Subject subject, OffsetDateTime asOf, String supersedesEvaluationId) {
        EvaluationResult result = evaluation.evaluateInCurrentTransaction(subject, run.mode(), asOf, run.runId(), supersedesEvaluationId);
        finish(run, new RunSummary(1, 1, result.alarmCreated() ? 1 : 0, result.alarmMerged() ? 1 : 0, List.of()));
        return result;
    }

    /** 收尾：全部主体失败记 FAILED，否则 DONE（部分失败的摘要保留在 error_summary）；随后通知钩子做运行级收尾（如自动关闭到期合并组）。 */
    public void finish(RunHandle run, RunSummary summary) {
        required.executeWithoutResult(tx -> finishInTransaction(run, summary));
    }

    private void finishInTransaction(RunHandle run, RunSummary summary) {
        String status = summary.subjectCount() > 0 && summary.evaluatedCount() == 0 && !summary.errors().isEmpty() ? STATUS_FAILED : STATUS_DONE;
        String errorSummary = summary.errors().isEmpty() ? null : String.join("; ", summary.errors());
        if (errorSummary != null && errorSummary.length() > ERROR_SUMMARY_MAX) errorSummary = errorSummary.substring(0, ERROR_SUMMARY_MAX);
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        if (repository.finishRun(run.runId(), status, summary.subjectCount(), summary.evaluatedCount(), summary.alarmCreatedCount(), summary.alarmMergedCount(), errorSummary, now) != 1) {
            throw new IllegalStateException("运行记录已收尾或不存在: " + run.runId());
        }
        if (run.mode() == RunMode.ACTIVE) {
            hooks.afterRun(new RunContext(run.runId(), run.ruleSetId(), run.ruleSetVersionId(), run.mode(), run.triggerKind(), run.asOf()), params.load(run.ruleSetVersionId()), now);
        }
    }

    public record RunHandle(String runId, String ruleSetId, String ruleSetCode, String ruleSetVersionId, RunMode mode, String triggerKind, OffsetDateTime asOf) { }

    public record RunSummary(int subjectCount, int evaluatedCount, int alarmCreatedCount, int alarmMergedCount, List<String> errors) { }
}
