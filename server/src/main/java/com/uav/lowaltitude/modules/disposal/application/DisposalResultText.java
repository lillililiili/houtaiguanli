package com.uav.lowaltitude.modules.disposal.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 四通道反制设备回码说明的通俗写法（问题清单 #21）。适配器写下的原文（继电器、低四位）是给排查用的，
 * 报表等操作员页面按它的真实含义改写：低四位为 0 是四路全部关闭，非 0 是有通道开启（只代表开关动作，不代表已发射信号）。
 * 认不出的说明原样返回，不猜。
 */
public final class DisposalResultText {
    private static final Pattern CONFIRMED = Pattern.compile("继电器设置回码已确认，低四位=(?:0x)?([0-9a-fA-F]+)");
    private static final Pattern MISMATCH = Pattern.compile("回码继电器状态与请求不一致（低四位=(?:0x)?[0-9a-fA-F]+）");

    private DisposalResultText() { }

    public static String forOperator(String detail) {
        if (detail == null || detail.isBlank()) return detail;
        Matcher confirmed = CONFIRMED.matcher(detail);
        if (confirmed.find()) {
            int mask = Integer.parseInt(confirmed.group(1), 16) & 0x0F;
            return mask == 0 ? "设备已确认全部关闭。" : "设备已确认开启（只代表设备开关已动作，不代表已发射信号）。";
        }
        if (MISMATCH.matcher(detail).find()) return "设备回报的状态和指令不一致，本次设置没有确认，请核查设备实际状态。";
        return detail;
    }
}
