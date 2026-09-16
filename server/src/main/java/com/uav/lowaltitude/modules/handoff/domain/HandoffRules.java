package com.uav.lowaltitude.modules.handoff.domain;

import java.util.Set;

import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

/** 处罚交接依据核实后的事件事实，不以完成反制为前置条件；交接不代表处罚办结。 */
public final class HandoffRules {
    public static final Set<String> SOURCE_KINDS = Set.of("RISK", "UAV_EVENT");
    public static final Set<String> HANDOFF_TYPES = Set.of("RISK_NOTICE", "UAV_PUNISHMENT");
    public static final Set<String> DELIVERY_STATUSES = Set.of("PENDING_DELIVERY", "SUBMITTED", "DELIVERED", "FAILED");
    public static final Set<String> RECEIPT_STATUSES = Set.of("NOT_EXPECTED", "PENDING", "ACKNOWLEDGED", "TIMEOUT");
    public static final Set<String> SOURCE_MODES = Set.of("mock", "replay", "live");
    public static final String KIND_RISK = "RISK";
    public static final String KIND_UAV_EVENT = "UAV_EVENT";
    public static final String TYPE_RISK_NOTICE = "RISK_NOTICE";
    public static final String TYPE_UAV_PUNISHMENT = "UAV_PUNISHMENT";
    public static final String PENDING_DELIVERY = "PENDING_DELIVERY";
    public static final String NOT_EXPECTED = "NOT_EXPECTED";
    public static final String CHANNEL_NOT_CONNECTED = "CHANNEL_NOT_CONNECTED";
    // 回执结果（决策 18-14）：风险的闭环判据是"已驱离"，不是"送到了"。未驱离说明事还没完，风险继续待通知。
    public static final String RECEIPT_DISPERSED = "DISPERSED";
    public static final String RECEIPT_NOT_DISPERSED = "NOT_DISPERSED";
    public static final int SNAPSHOT_SCHEMA_VERSION = 1;

    private HandoffRules() { }

    /** 类型守卫；核实状态、版本、接收方与范围仍由提交服务检查。 */
    public static void requirePrerequisite(String handoffType, String sourceKind, String sourceId,
                                           DisposalCompletionPort disposals) {
        if (!TYPE_UAV_PUNISHMENT.equals(handoffType)) return;
        if (!KIND_UAV_EVENT.equals(sourceKind)) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOFF_PREREQUISITE_UNAVAILABLE",
                    "只有无人机事件可以发起处罚交接");
        }

    }

    /**
     * 可提交的组合：风险 → 风险通知，无人机事件 → 处罚交接。其余组合在语义上不存在，按 400 拒绝。
     *
     * 阶段 13 必须把 (UAV_EVENT, UAV_PUNISHMENT) 放进来。只改 requirePrerequisite 是不够的：
     * 调用方紧接着就会走到这里，处罚交接照样被 400 挡死，前提放开了也永远走不到。
     */
    public static void requireKindSupportsType(String sourceKind, String handoffType) {
        boolean riskNotice = KIND_RISK.equals(sourceKind) && TYPE_RISK_NOTICE.equals(handoffType);
        boolean uavPunishment = KIND_UAV_EVENT.equals(sourceKind) && TYPE_UAV_PUNISHMENT.equals(handoffType);
        if (!riskNotice && !uavPunishment) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KIND", "该来源类型不支持此交接类型");
        }
    }

    /** 只有核验后的待通知风险可以交接；待核验、已排除都不是可通知状态。 */
    public static void requireNotifiable(String riskState) {
        if (!Set.of("PENDING_NOTIFICATION", "NOTIFIED", "ACKNOWLEDGED").contains(riskState)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "当前风险状态不允许提交通知交接");
        }
    }
}
