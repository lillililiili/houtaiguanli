package com.uav.lowaltitude.modules.alarm.domain;

import java.util.List;
import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Record;

/** 现场核查依据决定是否可以申请升级；本规则不替代审批、目标校验或设备授权。 */
public final class UavAdvisoryRules {
    private UavAdvisoryRules() { }
    public static String counterBlockReason(String state,List<Record> records) {
        if (!"CONFIRMED".equals(state)) return "请先人工核实事件属实";
        if (records.isEmpty()) return "请先短信劝离或记录联系情况，再核查目标是否飞离及当前危险度";
        int observation=-1,contact=-1;
        for(int i=0;i<records.size();i++) {
            if("OBSERVATION".equals(records.get(i).kind())) observation=i; else contact=i;
        }
        if(observation<0 || observation<contact) return "请记录本次联系后的现场核查结果";
        Record last=records.get(observation);
        if(!"STILL_INSIDE".equals(last.outcome())) return "DEPARTED".equals(last.outcome())?"目标已飞离，无需升级反制":"目标情况不明，请继续核查，不能据此申请反制";
        if(!"HIGH".equals(last.danger())) return "当前未确认高危险度，请继续观察核查";
        if(!last.urgent() && contact<0) return "请先短信劝离或记录联系情况，再补充联系后的核查结果";
        return "";
    }
}
