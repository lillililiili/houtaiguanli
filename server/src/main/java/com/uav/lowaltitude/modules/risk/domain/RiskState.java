package com.uav.lowaltitude.modules.risk.domain;

import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

/** 风险核验只推进到待通知；NOTIFIED 属于后续通知切片，本模块永不写入。 */
public final class RiskState {
    private RiskState() { }

    public static String next(String current, String conclusion) {
        if (!"PENDING_VERIFICATION".equals(current)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "当前风险状态不允许核验");
        }
        return switch (conclusion) {
            case "CONFIRMED" -> "PENDING_NOTIFICATION";
            case "EXCLUDED" -> "EXCLUDED";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONCLUSION", "核验结论无效");
        };
    }

    public static boolean verifiable(String state) {
        return "PENDING_VERIFICATION".equals(state);
    }
}
