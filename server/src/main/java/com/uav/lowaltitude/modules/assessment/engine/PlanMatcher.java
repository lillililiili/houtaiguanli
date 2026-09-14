package com.uav.lowaltitude.modules.assessment.engine;

import java.util.List;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;

/**
 * C01 计划匹配的引擎入口：输入是收集阶段的上下文（此时 planMatch 尚为空）与同元组候选计划，输出 {@link PlanMatch}。
 * 生产实现 {@link DefaultPlanMatcher} 委托执行者 2 的 PlanMatchCheck；H2 测试可用 @Primary 桩替换以固定匹配结果。
 */
@FunctionalInterface
public interface PlanMatcher {
    PlanMatch match(EvaluationContext context, List<PlanFact> candidates, RuleParams params);
}
