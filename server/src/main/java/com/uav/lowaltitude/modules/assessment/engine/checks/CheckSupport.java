package com.uav.lowaltitude.modules.assessment.engine.checks;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.EvidenceRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.HitDetail;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ParamRef;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.ResultCode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RuleParams;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;

/** C02 检查的公共拼装：参数引用（含 DEMO 标注）、同基准高度取值、明细构造。rule_version_id 由引擎按规则集成员回填。 */
final class CheckSupport {
    static final String EVIDENCE_AIRSPACE_VERSION = "airspace_version";
    static final String EVIDENCE_ROUTE_VERSION = "route_version";
    static final String EVIDENCE_FLIGHT_PLAN = "flight_plan";
    static final String EVIDENCE_TARGET = "target";
    private static final String DEMO_SUFFIX = "；参数为 DEMO 演示值，尚未确认";

    private CheckSupport() { }

    static ParamRef number(RuleParams params, String ruleCode, String key) {
        return new ParamRef(key, params.number(ruleCode, key).toPlainString(), params.paramStatus(ruleCode, key));
    }

    static ParamRef integer(RuleParams params, String ruleCode, String key) {
        return new ParamRef(key, Integer.toString(params.integer(ruleCode, key)), params.paramStatus(ruleCode, key));
    }

    static ParamRef string(RuleParams params, String ruleCode, String key) {
        return new ParamRef(key, params.string(ruleCode, key), params.paramStatus(ruleCode, key));
    }

    static ParamRef list(RuleParams params, String ruleCode, String key) {
        return new ParamRef(key, String.join(",", params.list(ruleCode, key)), params.paramStatus(ruleCode, key));
    }

    /** 有任一参数仍是 DEMO 时，可读解释必须带演示标注，避免页面把演示阈值当作已确认的监管口径。 */
    static String message(String base, List<ParamRef> params) {
        boolean demo = params.stream().anyMatch(ref -> RuleCodes.PARAM_STATUS_DEMO.equals(ref.status()));
        return demo ? base + DEMO_SUFFIX : base;
    }

    static boolean positionKnown(TargetState state) {
        return state != null && state.longitude() != null && state.latitude() != null;
    }

    /**
     * 目标在指定基准下的高度：AMSL 取 altitude_amsl_m，AGL 取 height_agl_m；基准缺失或该基准的值缺失即 null。
     * 永远不用一种基准的数值去推另一种，缺换算依据就是未知。
     */
    static BigDecimal altitudeOn(TargetState state, String datum) {
        if (state == null || datum == null) return null;
        if (RuleCodes.DATUM_AMSL.equals(datum)) return state.altitudeAmslM();
        if (RuleCodes.DATUM_AGL.equals(datum)) return state.heightAglM();
        return null;
    }

    static Map<String, Object> facts() { return new LinkedHashMap<>(); }

    static List<EvidenceRef> evidence(String kind, String id) {
        List<EvidenceRef> refs = new ArrayList<>();
        if (id != null) refs.add(new EvidenceRef(kind, id));
        return refs;
    }

    static HitDetail detail(String ruleCode, ResultCode result, String reason, BigDecimal severity, Map<String, Object> facts,
            List<ParamRef> params, List<EvidenceRef> evidence, String message) {
        return new HitDetail(ruleCode, null, result, reason, severity, Map.copyOf(nullSafe(facts)), List.copyOf(params), List.copyOf(evidence),
                message(message, params));
    }

    static HitDetail pass(String ruleCode, Map<String, Object> facts, List<ParamRef> params, List<EvidenceRef> evidence, String message) {
        return detail(ruleCode, ResultCode.PASS, null, null, facts, params, evidence, message);
    }

    static HitDetail fail(String ruleCode, String reason, Map<String, Object> facts, List<ParamRef> params, List<EvidenceRef> evidence, String message) {
        return detail(ruleCode, ResultCode.FAIL, reason, null, facts, params, evidence, message);
    }

    static HitDetail undetermined(String ruleCode, String reason, Map<String, Object> facts, List<ParamRef> params, List<EvidenceRef> evidence, String message) {
        return detail(ruleCode, ResultCode.UNDETERMINED, reason, null, facts, params, evidence, message);
    }

    static HitDetail notApplicable(String ruleCode, String reason, List<ParamRef> params, String message) {
        return detail(ruleCode, ResultCode.NOT_APPLICABLE, reason, null, facts(), params, List.of(), message);
    }

    /** Map.copyOf 不接受 null 值；facts 中缺失的事实用空缺表达（不放入），而不是放 null。 */
    private static Map<String, Object> nullSafe(Map<String, Object> facts) {
        Map<String, Object> safe = new LinkedHashMap<>();
        facts.forEach((key, value) -> { if (value != null) safe.put(key, value); });
        return safe;
    }
}
