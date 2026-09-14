package com.uav.lowaltitude.modules.assessment.engine;

import java.util.List;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanFact;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.PlanMatch;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.engine.checks.PlanMatchCheck;

/** 生产 C01：走廊距离与 C02-3 同源（同一个 SpatialFactPort），避免两处距离口径不一致。 */
@Component
public class DefaultPlanMatcher implements PlanMatcher {
    private final PlanMatchCheck check;
    private final SpatialFactPort spatial;

    public DefaultPlanMatcher(PlanMatchCheck check, SpatialFactPort spatial) {
        this.check = check;
        this.spatial = spatial;
    }

    @Override
    public PlanMatch match(EvaluationContext context, List<PlanFact> candidates, RuleParams params) {
        return check.match(context.state(), candidates, routeVersionId -> spatial.distanceToRoute(context.state(), routeVersionId),
                context.asOf(), params);
    }
}
