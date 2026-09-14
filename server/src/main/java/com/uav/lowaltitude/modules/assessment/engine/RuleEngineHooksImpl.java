package com.uav.lowaltitude.modules.assessment.engine;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeInput;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeOutcome;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.LegalStatus;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityReviewRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 执行者 2 的钩子实现：ACTIVE 研判落库后同事务建 legality_review(PENDING_REVIEW)；仅当引擎声明可告警
 * （ACTIVE 且 ABNORMAL/ILLEGAL）时走 C06（{@link AlarmMergePolicy}）并把结果交给引擎一次性回填。
 * SHADOW 研判既不建复核行也不告警：影子结论没有人工复核的语义，也不能进入核实流程。
 * NOT_APPLICABLE（STALE/NO_STATE）也不建复核行：它不是结论而是"这一 tick 没有可判事实"，调度每 5 s 都会产生，
 * 建行只会把复核队列灌满；等目标再次新鲜时会有新的可判研判。审查第 1 轮 P2-7 的决定。
 * 不标 @Primary：E1 的 LegalityEvaluationServiceTest 以 @Primary 桩替换钩子，两处 @Primary 会让容器无法启动；
 * 实现 Bean 必须存在（RuleEngineHooksImpl）；没有实现时容器启动失败，而不是静默无操作。
 * 重算取代旧复核（SUPERSEDED + RECOMPUTE 历史）由 LegalityReviewService.recompute 在同一事务内完成——
 * 只有它持有操作者、说明与 expected_version，钩子里没有这些事实，不能编造历史行。
 */
@Component
public class RuleEngineHooksImpl implements RuleEngineHooks {
    private final LegalityReviewRepository reviews;
    private final AlarmMergePolicy merge;
    private final AppClock clock;

    public RuleEngineHooksImpl(LegalityReviewRepository reviews, AlarmMergePolicy merge, AppClock clock) {
        this.reviews = reviews; this.merge = merge; this.clock = clock;
    }

    @Override
    @Transactional
    public HookResult afterEvaluation(EvaluationOutcome outcome) {
        OffsetDateTime now = outcome.evaluatedAt() != null ? outcome.evaluatedAt() : clock.now().atOffset(ZoneOffset.UTC);
        boolean reviewable = outcome.mode() == RunMode.ACTIVE && outcome.legalStatus() != null && outcome.legalStatus() != LegalStatus.NOT_APPLICABLE;
        if (reviewable && !reviews.exists(outcome.evaluationId())) {
            reviews.insertPending(outcome.evaluationId(), outcome.ownerOrgId(), outcome.districtId(), now);
        }
        if (!outcome.alarmEligible() || outcome.mode() != RunMode.ACTIVE || outcome.targetId() == null) return HookResult.none();
        // occurredAt = as_of（业务时刻）；mergedAt = evaluated_at（评估时钟 now）——窗口算术与 received_at 都以后者为准。
        MergeInput input = new MergeInput(outcome.evaluationId(), outcome.targetId(), outcome.ownerOrgId(), outcome.districtId(), outcome.sourceMode(),
                outcome.ruleSetId(), outcome.ruleSetVersionId(), outcome.legalStatus().name(),
                outcome.planMatchCode() == null ? null : outcome.planMatchCode().name(), outcome.grade(), outcome.score(), outcome.violationReasons(),
                outcome.asOf() != null ? outcome.asOf() : now, now);
        MergeOutcome result = merge.apply(input, outcome.params());
        // alarm_outcome 只放安全摘要：kind/告警/组/等级/被阻断原因，不带任何输入快照。
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kind", result.kind());
        if (result.alarmId() != null) summary.put("alarm_id", result.alarmId());
        if (result.eventId() != null) summary.put("event_id", result.eventId());
        if (result.groupId() != null) summary.put("group_id", result.groupId());
        if (result.severity() != null) summary.put("severity", result.severity());
        if (result.reason() != null) summary.put("reason", result.reason());
        boolean created = AlarmMergePolicy.KIND_CREATED.equals(result.kind()) || AlarmMergePolicy.KIND_UPGRADED.equals(result.kind());
        boolean merged = AlarmMergePolicy.KIND_MERGED.equals(result.kind()) || AlarmMergePolicy.KIND_DOWNGRADED.equals(result.kind());
        return new HookResult(result.alarmId(), summary, created, merged);
    }

    /** 运行收尾：只有 ACTIVE 运行才自动关闭到期合并组；窗口参数来自本次运行版本的 C06.*。 */
    @Override
    @Transactional
    public void afterRun(RunContext run, RuleParams params, OffsetDateTime now) {
        if (run == null || run.mode() != RunMode.ACTIVE) return;
        merge.autoCloseExpired(now, params);
    }
}
