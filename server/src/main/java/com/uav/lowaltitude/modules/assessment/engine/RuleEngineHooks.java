package com.uav.lowaltitude.modules.assessment.engine;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.LegalStatus;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatchCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;

/**
 * 引擎核心（执行者 1）与 C06/复核（执行者 2）之间的钩子。{@link LegalityEvaluationService} 在 rule_evaluation
 * 与 assessment_result 投影写入之后、同一事务内调用 {@link #afterEvaluation}；实现方负责：
 * <ul>
 *   <li>写 legality_review(PENDING_REVIEW)；重算时（{@link EvaluationOutcome#supersedesEvaluationId()} 非空）把旧复核置为 SUPERSEDED 并追加 RECOMPUTE 历史；</li>
 *   <li>仅当 {@link EvaluationOutcome#alarmEligible()} 为 true（ACTIVE 且 ABNORMAL/ILLEGAL）时执行 C06 告警生成与合并，并把结果放进 {@link HookResult}；</li>
 *   <li>不得修改 rule_evaluation / assessment_result：告警关联由引擎凭 HookResult 一次性回填。</li>
 * </ul>
 * 实现 Bean 必须存在（RuleEngineHooksImpl）；没有实现时容器启动失败，而不是静默无操作。
 */
public interface RuleEngineHooks {

    HookResult afterEvaluation(EvaluationOutcome outcome);

    /**
     * 一次运行收尾后调用（SCHEDULED/MANUAL 均会触发），供 C06 自动关闭到期合并组；params 为本次运行版本的参数。
     * 默认无操作。
     */
    default void afterRun(RunContext run, RuleParams params, OffsetDateTime now) {
    }

    /**
     * 一条研判的结果快照。alarmEligible 只在 ACTIVE 且 legalStatus ∈ {ABNORMAL, ILLEGAL} 时为 true；
     * SHADOW 模式永远为 false（影子研判既不投影也不告警）。
     */
    record EvaluationOutcome(String evaluationId, String runId, String ruleSetId, String ruleSetVersionId, RunMode mode,
            String triggerKind, Subject subject, String targetId, String trackId, String planId, String routeVersionId,
            String ownerOrgId, String districtId, String sourceMode, LegalStatus legalStatus, PlanMatchCode planMatchCode,
            BigDecimal score, String grade, List<String> violationReasons, List<String> unknownReasons,
            OffsetDateTime asOf, OffsetDateTime observedAt, OffsetDateTime receivedAt, OffsetDateTime evaluatedAt,
            String assessmentId, String supersedesEvaluationId, boolean alarmEligible, RuleParams params) { }

    /**
     * 钩子回填：alarmOutcome 写入 rule_evaluation.alarm_outcome（kind ∈ CREATED|MERGED|UPGRADED|DOWNGRADED|BLOCKED），
     * alarmId 写入 rule_evaluation.alarm_id；alarmCreated/alarmMerged 计入 rule_run 计数。
     */
    record HookResult(String alarmId, Map<String, Object> alarmOutcome, boolean alarmCreated, boolean alarmMerged) {
        public static HookResult none() { return new HookResult(null, null, false, false); }
    }

    /** 运行上下文（供 afterRun）。 */
    record RunContext(String runId, String ruleSetId, String ruleSetVersionId, RunMode mode, String triggerKind, OffsetDateTime asOf) { }
}
