package com.uav.lowaltitude.modules.assessment.engine.checks;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;

/** C02-1 禁飞/限制空域：kind ∈ C02-1.kinds，水平覆盖且（无高度带 或 同基准高度在带内）即进入。 */
@Component
public class RestrictedAirspaceCheck extends AirspaceCoverageCheck {
    @Override public String ruleCode() { return RuleCodes.C02_1; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_1; }
    @Override protected String failReason() { return RuleCodes.INSIDE_RESTRICTED_AIRSPACE; }
    @Override protected String failMessage(AirspaceHit hit) { return "目标进入" + hit.kindCode() + "空域 " + hit.airspaceId(); }
    @Override protected String passMessage() { return "未进入任何禁飞/限制空域"; }
}
