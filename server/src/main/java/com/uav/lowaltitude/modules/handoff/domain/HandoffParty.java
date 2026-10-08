package com.uav.lowaltitude.modules.handoff.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 处罚交接里的当事人认定（2026-10-06）。
 *
 * 只有关联本事件的报备计划（来自上游计划接口，平台没有飞手录入页）写明了飞手或运营单位，才算当事人已明确。
 * 否则按“当事人不明，待补线索”移送：写明原因，并列出已有线索（无人机序列号、设备测算的遥控器位置）。
 * 当事人不明时目前不拦截移送——处罚部门要凭这些线索去找人；人工移送时页面要求提交人先确认。
 * 是否改为不允许正式移送，需产品负责人确认。
 */
public final class HandoffParty {
    public static final String IDENTIFIED = "IDENTIFIED";
    public static final String UNIDENTIFIED = "UNIDENTIFIED";
    public static final String UNIDENTIFIED_LABEL = "当事人不明，按待补线索移送";

    private HandoffParty() { }

    public record Assessment(String status, String label, List<String> reasons, String uavSn) {
        public boolean identified() { return IDENTIFIED.equals(status); }
    }

    /**
     * @param pilotLocation 是否有设备测算的遥控器位置；null 表示这次没有查，不写进原因。
     */
    public static Assessment assess(String planId, String pilotName, String operatorName, String planSerial, String targetSerial,
            Boolean pilotLocation) {
        String serial = text(targetSerial) != null ? text(targetSerial) : text(planSerial);
        if (planId != null && (text(pilotName) != null || text(operatorName) != null))
            return new Assessment(IDENTIFIED, null, List.of(), serial);
        List<String> reasons = new ArrayList<>();
        reasons.add(planId == null ? "没有匹配到本次飞行的报备任务，找不到飞手和运营单位"
                : "匹配到的报备任务没有写明飞手或运营单位");
        if (serial == null) reasons.add("没有获取到无人机序列号");
        if (Boolean.FALSE.equals(pilotLocation)) reasons.add("没有设备测算的遥控器位置");
        return new Assessment(UNIDENTIFIED, UNIDENTIFIED_LABEL, List.copyOf(reasons), serial);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
