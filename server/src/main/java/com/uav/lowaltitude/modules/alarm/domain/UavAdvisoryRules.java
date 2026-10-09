package com.uav.lowaltitude.modules.alarm.domain;

import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Facts;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Evaluation;
import com.uav.lowaltitude.modules.assessment.engine.RuleCodes;

/** 当前系统依据仅决定反制资格；不授予权限、不创建授权、不替代设备回执。 */
public final class UavAdvisoryRules {
    private UavAdvisoryRules() { }
    private static final String NO_OBSERVATION = "缺少当前有效无人机观测",
            NO_EVALUATION = "缺少关联本事件且证据充分的当前违规研判";

    public static String counterBlockReason(String state, String alarmId, Facts facts, Evaluation evaluation,
            boolean sufficient, boolean noUnknowns, Integer freshSeconds, long now) {
        if (freshSeconds == null || freshSeconds <= 0) return "缺少有效的目标观测时效配置，不能确认当前反制依据";
        String gap = evidenceGap(alarmId, facts, evaluation, sufficient, noUnknowns, freshSeconds * 1000L, now);
        // 没核实又缺依据时先说缺依据（ZT-18）：只说“先核实”，会让人以为核实了就能反制。
        if (!"CONFIRMED".equals(state))
            return gap == null ? "事件尚未核实属实" : "证据不足：" + gap + "；事件也尚未核实属实，暂不能反制";
        if (gap == null) return "";
        return NO_OBSERVATION.equals(gap) ? gap + "，不能确认反制依据" : gap + "，暂不能申请或执行反制";
    }
    private static String evidenceGap(String alarmId, Facts facts, Evaluation evaluation, boolean sufficient,
            boolean noUnknowns, long window, long now) {
        if (facts == null || !"UAV".equals(facts.objectType()) || !fresh(facts.observedAt(), now, window))
            return NO_OBSERVATION;
        if (evaluation == null || !"ILLEGAL".equals(evaluation.legalStatus())
                || !"FRESH".equals(evaluation.freshness()) || !sufficient || !noUnknowns
                || alarmId == null || !alarmId.equals(evaluation.alarmId())
                || !fresh(evaluation.evaluatedAt(), now, window) || !fresh(evaluation.observedAt(), now, window))
            return NO_EVALUATION;
        return null;
    }
    /**
     * 研判的未知原因都不挡反制和暂不反制时为 true。只有“没有飞手位置”不挡：它只让 C02-6 飞手距离算不出，
     * C03 本来就忽略它、不因此不可判定（2026-10-07 业务决定）；黑飞常常测不到遥控器位置，不能因此连人工反制都申请不了
     * （2026-10-08 验收预跑 3-4 / 8-8，新-19）。新-29 起这一项不判、不再记进未知原因，这里照旧放行以前的研判。
     * 其他未知照样挡，读不出的也挡。
     */
    public static boolean noBlockingUnknowns(java.util.List<String> reasons) {
        return reasons != null && reasons.stream().allMatch(RuleCodes.PILOT_POSITION_UNAVAILABLE::equals);
    }
    public static boolean noBlockingUnknowns(com.fasterxml.jackson.databind.JsonNode reasons) {
        if (reasons == null || !reasons.isArray()) return false;
        for (var reason : reasons) if (!reason.isTextual() || !RuleCodes.PILOT_POSITION_UNAVAILABLE.equals(reason.textValue())) return false;
        return true;
    }
    private static boolean fresh(Long at, long now, long window) {
        return at != null && at <= now && now - at <= window;
    }
}
