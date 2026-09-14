package com.uav.lowaltitude.modules.handoff.domain;

import java.util.Set;

import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 交接规则：提交成功推进风险为已通知，可信确认回执推进已回执；投递事实独立记录，不表示处罚办结。
 *
 * 阶段 13（决策 13-6）起，UAV_PUNISHMENT 不再一律阻断：处置授权域上线后，"该事件已被反制/干扰且完成"
 * 成了库里可查的事实，处罚交接因此有了可信前提。没有完成授权时仍然阻断——阻断的理由从
 * "本期没有这种事实"变成了"这一件事上还没有这个事实"。
 */
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

    /**
     * 处罚交接的前提：该无人机事件存在已完成的处置授权（决策 13-6）。
     * 前提不成立时仍然 409 —— 没有"确实处置过"的事实就发起处罚，处罚本身站不住。
     */
    public static void requirePrerequisite(String handoffType, String sourceKind, String sourceId,
                                           DisposalCompletionPort disposals) {
        if (!TYPE_UAV_PUNISHMENT.equals(handoffType)) return;
        if (!KIND_UAV_EVENT.equals(sourceKind)) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOFF_PREREQUISITE_UNAVAILABLE",
                    "只有无人机事件可以发起处罚交接");
        }
        if (disposals == null || !disposals.completedExists(KIND_UAV_EVENT, sourceId)) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOFF_PREREQUISITE_UNAVAILABLE",
                    "该事件尚无已完成的处置授权，不能发起处罚交接");
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
