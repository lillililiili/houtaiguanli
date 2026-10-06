package com.uav.lowaltitude.modules.alarm.domain;

import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Facts;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Evaluation;

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
    private static boolean fresh(Long at, long now, long window) {
        return at != null && at <= now && now - at <= window;
    }
}
