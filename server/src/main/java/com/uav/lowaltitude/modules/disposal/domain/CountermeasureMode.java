package com.uav.lowaltitude.modules.disposal.domain;

import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.integration.device.countermeasure.Countermeasure4ChCodec;

/** 厂家协议的反制方式；不改变 COUNTERMEASURE 的权限、流程和统计归属。 */
public enum CountermeasureMode {
    FORCE_LAND(Countermeasure4ChCodec.MASK_FORCE_LAND, 60003),
    DRIVE_AWAY(Countermeasure4ChCodec.MASK_DRIVE_AWAY, 60002);

    private final int mask;
    private final int operationCmd;

    CountermeasureMode(int mask, int operationCmd) {
        this.mask = mask;
        this.operationCmd = operationCmd;
    }

    public int mask() { return mask; }
    public DisposalPolicy.Command command() { return new DisposalPolicy.Command(1, operationCmd); }

    /** NULL 只用于兼容旧调用和旧授权，显式传入的空值/未知值不可静默降级。 */
    public static CountermeasureMode parse(String value, String actionType) {
        if (value == null) return null;
        if (!DisposalRules.COUNTERMEASURE.equals(actionType))
            throw invalid("countermeasure_mode 仅适用于反制动作");
        try { return valueOf(value); }
        catch (IllegalArgumentException ex) { throw invalid("反制方式必须为 FORCE_LAND（迫降）或 DRIVE_AWAY（驱离）"); }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
