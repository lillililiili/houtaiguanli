package com.uav.lowaltitude.modules.alarm.domain;

import java.util.Set;

import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

/** 无人机核实仅改变事件事实；CONFIRMED 绝不代表反制、干扰或交接已执行。 */
public final class UavEventState {
    private static final Set<String> OPEN = Set.of("PENDING_VERIFICATION");
    private static final Set<String> CONCLUSIONS = Set.of("CONFIRMED", "FALSE_POSITIVE");

    private UavEventState() { }

    public static String next(String current, String conclusion) {
        if (!OPEN.contains(current)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "当前状态不允许核实");
        }
        if (!CONCLUSIONS.contains(conclusion)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONCLUSION", "核实结论无效");
        }
        return conclusion;
    }

    public static boolean verifiable(String state) {
        return OPEN.contains(state);
    }
}
