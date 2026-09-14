package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;

/**
 * C02-2 空域限高：kind ∈ C02-2.kinds 且水平覆盖时，目标按 airspace_version.altitude_datum 取同基准高度，
 * 高于 max_altitude_m 即超限。限高空域没有高度带等于没有限值，只能未知。
 */
@Component
public class AirspaceAltitudeCheck extends AirspaceCoverageCheck {
    @Override public String ruleCode() { return RuleCodes.C02_2; }
    @Override public int defaultPriority() { return RuleCodes.PRIORITY_C02_2; }
    @Override protected String failReason() { return RuleCodes.AIRSPACE_ALTITUDE_EXCEEDED; }
    @Override protected String failMessage(AirspaceHit hit) {
        return "目标高度超过空域 " + hit.airspaceId() + " 的限高 " + hit.maxAltitudeM() + " m（" + hit.altitudeDatum() + "）";
    }
    @Override protected String passMessage() { return "未超过所在空域限高"; }

    @Override
    protected Verdict verdict(AirspaceHit hit, TargetState state) {
        if (hit.altitudeDatum() == null || hit.maxAltitudeM() == null) return Verdict.UNKNOWN;
        BigDecimal altitude = CheckSupport.altitudeOn(state, hit.altitudeDatum());
        if (altitude == null) return Verdict.UNKNOWN;
        return altitude.compareTo(hit.maxAltitudeM()) > 0 ? Verdict.HIT : Verdict.MISS;
    }
}
