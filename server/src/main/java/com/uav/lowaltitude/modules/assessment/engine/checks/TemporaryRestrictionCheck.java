package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;

/** C02-8 临时限制：kind ∈ C02-8.kinds 且 as_of 在版本生效窗口 [valid_from, valid_to) 内的覆盖才算命中；过期版本与本规则无关。 */
@Component
public class TemporaryRestrictionCheck extends AirspaceCoverageCheck {
    @Override public String ruleCode() { return RuleCodes.C02_8; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_8; }
    @Override protected String failReason() { return RuleCodes.TEMPORARY_RESTRICTION_ACTIVE; }
    @Override protected String failMessage(AirspaceHit hit) { return "目标进入生效中的临时限制空域 " + hit.airspaceId(); }
    @Override protected String passMessage() { return "未进入生效中的临时限制空域"; }

    @Override
    protected boolean applies(AirspaceHit hit, EvaluationContext context) {
        OffsetDateTime asOf = context.asOf();
        if (asOf == null || hit.validFrom() == null) return false;
        return !asOf.isBefore(hit.validFrom()) && (hit.validTo() == null || asOf.isBefore(hit.validTo()));
    }
}
