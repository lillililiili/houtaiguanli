package com.uav.lowaltitude.modules.alarm.domain;

import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Facts;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Evaluation;

/** 当前系统依据仅决定反制资格；不授予权限、不创建授权、不替代设备回执。 */
public final class UavAdvisoryRules {
    private UavAdvisoryRules() { }
    public static String counterBlockReason(String state, String alarmId, Facts facts, Evaluation evaluation,
            boolean sufficient, boolean noUnknowns, Integer freshSeconds, long now) {
        if (!"CONFIRMED".equals(state)) return "事件尚未核实属实";
        if (freshSeconds == null || freshSeconds <= 0) return "缺少有效的目标观测时效配置，不能确认当前反制依据";
        long window = freshSeconds * 1000L;
        if (facts == null || !"UAV".equals(facts.objectType()) || !fresh(facts.observedAt(), now, window))
            return "缺少当前有效无人机观测，不能确认反制依据";
        if (evaluation == null || !"ILLEGAL".equals(evaluation.legalStatus())
                || !"FRESH".equals(evaluation.freshness()) || !sufficient || !noUnknowns
                || !alarmId.equals(evaluation.alarmId())
                || !fresh(evaluation.evaluatedAt(), now, window) || !fresh(evaluation.observedAt(), now, window))
            return "缺少关联本事件且证据充分的当前违规研判，暂不能申请或执行反制";
        return "";
    }
    private static boolean fresh(Long at, long now, long window) {
        return at != null && at <= now && now - at <= window;
    }
}
