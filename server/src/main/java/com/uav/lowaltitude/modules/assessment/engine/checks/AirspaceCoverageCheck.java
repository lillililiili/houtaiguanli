package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.AirspaceHit;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvaluationContext;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvidenceRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleCheck;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;

/**
 * 空域覆盖类检查（C02-1 禁飞、C02-2 限高、C02-8 临时限制）的公共骨架。空间关系只来自 SpatialFactPort：
 * <ul>
 *   <li>版本歧义（同一空域两个版本同时生效）先于一切：不能用其中一个版本的几何得出结论；</li>
 *   <li>ST_Touches 是未知而不是命中或未命中：点落在边界上时"以内/以外"没有既定业务政策，PostGIS 的拓扑判断也不能替监管方做这个决定，所以记 BOUNDARY_POLICY_UNKNOWN；</li>
 *   <li>水平覆盖后再比高度：空域高度带与目标高度必须同基准（AGL/AMSL），缺一即未知。</li>
 * </ul>
 */
abstract class AirspaceCoverageCheck implements RuleCheck {
    static final String PARAM_KINDS = "kinds";

    @Override
    public HitDetail evaluate(EvaluationContext context, RuleParams params) {
        List<String> kinds = params.list(ruleCode(), PARAM_KINDS);
        List<ParamRef> refs = new ArrayList<>(List.of(CheckSupport.list(params, ruleCode(), PARAM_KINDS)));
        refs.addAll(extraParams(params));
        TargetState state = context.state();
        Map<String, Object> facts = CheckSupport.facts();
        if (!CheckSupport.positionKnown(state)) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.POSITION_UNKNOWN, facts, refs, List.of(), "目标位置缺失，无法判断与空域的关系");
        }
        List<AirspaceHit> hits = context.airspaces() == null ? List.of() : context.airspaces();
        for (AirspaceHit hit : hits) {
            if (RuleCodes.RELATION_UNKNOWN.equals(hit.relation()) && RuleCodes.VERSION_AMBIGUOUS.equals(hit.unknownReason())) {
                return CheckSupport.undetermined(ruleCode(), RuleCodes.VERSION_AMBIGUOUS, facts, refs, List.of(), "同一空域存在多个同时生效的版本，无法确定适用边界");
            }
        }
        List<AirspaceHit> relevant = hits.stream().filter(hit -> hit.kindCode() != null && kinds.contains(hit.kindCode()) && applies(hit, context)).toList();
        facts.put("candidate_count", relevant.size());
        // 未知优先：任一相关空域只是边界接触或几何缺失，就不能声称目标"在外面"。
        for (AirspaceHit hit : relevant) {
            if (RuleCodes.RELATION_TOUCHES.equals(hit.relation())) {
                return CheckSupport.undetermined(ruleCode(), RuleCodes.BOUNDARY_POLICY_UNKNOWN, describe(facts, hit, state), refs,
                        CheckSupport.evidence(CheckSupport.EVIDENCE_AIRSPACE_VERSION, hit.airspaceVersionId()), "目标位于空域边界上，边界归属尚无业务政策");
            }
            if (RuleCodes.RELATION_UNKNOWN.equals(hit.relation())) {
                String reason = hit.unknownReason() == null || hit.unknownReason().isBlank() ? RuleCodes.AIRSPACE_BOUNDARY_UNKNOWN : hit.unknownReason();
                return CheckSupport.undetermined(ruleCode(), reason, describe(facts, hit, state), refs,
                        CheckSupport.evidence(CheckSupport.EVIDENCE_AIRSPACE_VERSION, hit.airspaceVersionId()), "空域几何或版本事实缺失，无法判断覆盖关系");
            }
        }
        AirspaceHit unknownAltitude = null;
        for (AirspaceHit hit : relevant) {
            if (!RuleCodes.RELATION_COVERS.equals(hit.relation())) continue;
            Verdict verdict = verdict(hit, state);
            if (verdict == Verdict.HIT) {
                return CheckSupport.fail(ruleCode(), failReason(), describe(facts, hit, state), refs,
                        CheckSupport.evidence(CheckSupport.EVIDENCE_AIRSPACE_VERSION, hit.airspaceVersionId()), failMessage(hit));
            }
            if (verdict == Verdict.UNKNOWN && unknownAltitude == null) unknownAltitude = hit;
        }
        if (unknownAltitude != null) {
            return CheckSupport.undetermined(ruleCode(), RuleCodes.ALTITUDE_DATUM_OR_RANGE_UNKNOWN, describe(facts, unknownAltitude, state), refs,
                    CheckSupport.evidence(CheckSupport.EVIDENCE_AIRSPACE_VERSION, unknownAltitude.airspaceVersionId()),
                    "空域高度带与目标高度基准不一致或缺失，AGL 与 AMSL 不互比");
        }
        return CheckSupport.pass(ruleCode(), facts, refs, List.<EvidenceRef>of(), passMessage());
    }

    /** 命中即 FAIL 的原因码。 */
    protected abstract String failReason();

    protected abstract String failMessage(AirspaceHit hit);

    protected abstract String passMessage();

    /** 除 kinds 外的参数引用（默认无）。 */
    protected List<ParamRef> extraParams(RuleParams params) { return List.of(); }

    /** 相关性附加条件（C02-8 用于生效窗口）。 */
    protected boolean applies(AirspaceHit hit, EvaluationContext context) { return true; }

    /** 水平覆盖已成立时的高度判定：默认"无高度带 或 目标同基准高度在带内"即命中。 */
    protected Verdict verdict(AirspaceHit hit, TargetState state) {
        if (hit.altitudeDatum() == null && hit.minAltitudeM() == null && hit.maxAltitudeM() == null) return Verdict.HIT;
        if (hit.altitudeDatum() == null || hit.minAltitudeM() == null || hit.maxAltitudeM() == null) return Verdict.UNKNOWN;
        BigDecimal altitude = CheckSupport.altitudeOn(state, hit.altitudeDatum());
        if (altitude == null) return Verdict.UNKNOWN;
        boolean inside = altitude.compareTo(hit.minAltitudeM()) >= 0 && altitude.compareTo(hit.maxAltitudeM()) <= 0;
        return inside ? Verdict.HIT : Verdict.MISS;
    }

    protected Map<String, Object> describe(Map<String, Object> facts, AirspaceHit hit, TargetState state) {
        facts.put("airspace_id", hit.airspaceId());
        facts.put("airspace_version_id", hit.airspaceVersionId());
        facts.put("kind_code", hit.kindCode());
        facts.put("relation", hit.relation());
        facts.put("altitude_datum", hit.altitudeDatum());
        facts.put("min_altitude_m", hit.minAltitudeM());
        facts.put("max_altitude_m", hit.maxAltitudeM());
        facts.put("target_altitude_amsl_m", state.altitudeAmslM());
        facts.put("target_height_agl_m", state.heightAglM());
        return facts;
    }

    enum Verdict { HIT, MISS, UNKNOWN }
}
