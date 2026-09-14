package com.uav.lowaltitude.modules.disposal.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 处置授权状态机与派生规则（纯函数，不碰数据库）。
 *
 * 这条链的每一步都可能成为处罚案件的证据：谁批的、几点执行的、设备回了什么。
 * 因此状态迁移写成显式白名单——没列出的组合一律 409，而不是"落到 else 里先放过去"。
 */
public final class DisposalRules {
    public static final String COUNTERMEASURE = "COUNTERMEASURE", JAMMING = "JAMMING",
            DISPERSAL = "DISPERSAL", DECOY = "DECOY";
    public static final Set<String> ACTION_TYPES = Set.of(COUNTERMEASURE, JAMMING, DISPERSAL, DECOY);
    // 决策 18-14：飞行风险的流程到"通知上级"为止，回执"已驱离"即闭环，风险不进处置授权。
    // 所以名单里没有 RISK——名单是这个接口对外的自我说明，挂着一个永远走不通的类型只会让人以为它能用。
    public static final Set<String> SUBJECT_KINDS = Set.of("UAV_EVENT", "TARGET");

    public static final String LINGYUN_B = "LINGYUN_B", COUNTERMEASURE_4CH = "COUNTERMEASURE_4CH", MANUAL = "MANUAL";
    public static final Set<String> CHANNELS = Set.of(LINGYUN_B, COUNTERMEASURE_4CH, MANUAL);

    public static final String REQUESTED = "REQUESTED", APPROVED = "APPROVED", REJECTED = "REJECTED",
            EXECUTING = "EXECUTING", COMPLETED = "COMPLETED", FAILED = "FAILED", STOPPED = "STOPPED",
            EXPIRED = "EXPIRED", CANCELLED = "CANCELLED";
    public static final Set<String> STATUSES = Set.of(REQUESTED, APPROVED, REJECTED, EXECUTING,
            COMPLETED, FAILED, STOPPED, EXPIRED, CANCELLED);

    /** 仍然"活着"的授权：占用同主体并发额度的就是这三种。终态不占额度。 */
    public static final Set<String> ACTIVE_STATUSES = Set.of(REQUESTED, APPROVED, EXECUTING);

    public static final String APPROVE = "APPROVE", REJECT = "REJECT", EXECUTE = "EXECUTE",
            STOP = "STOP", CANCEL = "CANCEL", MANUAL_RESULT = "MANUAL_RESULT";

    /** 事件种类；与迁移 0102 的 ck_stage13_event_kind 白名单一一对应。 */
    public static final Set<String> EVENT_KINDS = Set.of("REQUEST", "APPROVE", "REJECT", "EXECUTE", "RECEIPT",
            "STOP", "COMPLETE", "FAIL", "EXPIRE", "CANCEL", "MANUAL_RESULT",
            "DEVICE_STOP_UNAVAILABLE", "DEVICE_CONTROL_UNAVAILABLE", "DEVICE_NOT_BOUND",
            "PROTOCOL_NOT_OPENED", "DEVICE_OFFLINE", "DEVICE_ALL_OFF_ISSUED");

    /** 执行受阻原因（决策 13-14 / 13-22）。四值各自对应一个不同的补救方，页面要分开说。 */
    public static final String BLOCK_DEVICE_CAPABILITY = "DEVICE_CAPABILITY",
            BLOCK_PROTOCOL_NOT_OPENED = "PROTOCOL_NOT_OPENED",
            BLOCK_NOT_BOUND = "NOT_BOUND", BLOCK_DEVICE_OFFLINE = "DEVICE_OFFLINE";

    /** 阻塞事件 → 受阻原因。一一对应，因此推导只看 event_kind，不解析 note（13-22）。 */
    private static final Map<String, String> BLOCK_REASONS = Map.of(
            "DEVICE_CONTROL_UNAVAILABLE", BLOCK_DEVICE_CAPABILITY,
            "PROTOCOL_NOT_OPENED", BLOCK_PROTOCOL_NOT_OPENED,
            "DEVICE_NOT_BOUND", BLOCK_NOT_BOUND,
            "DEVICE_OFFLINE", BLOCK_DEVICE_OFFLINE);

    /** 决策 13-10 / 13-11：停止时设备侧到底怎么了，由事件流推导，不加列。 */
    public static final String STOP_NOT_ATTEMPTED = "NOT_ATTEMPTED", STOP_EXECUTED = "EXECUTED",
            STOP_UNAVAILABLE = "UNAVAILABLE", STOP_NOT_BOUND = "NOT_BOUND",
            STOP_ALL_OFF_ISSUED = "ALL_OFF_ISSUED";

    /** 动作 → 允许发起该动作的来源状态。没列出的组合就是不允许。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            APPROVE, Set.of(REQUESTED),
            REJECT, Set.of(REQUESTED),
            EXECUTE, Set.of(APPROVED),
            STOP, Set.of(APPROVED, EXECUTING),
            CANCEL, Set.of(REQUESTED, APPROVED),
            MANUAL_RESULT, Set.of(EXECUTING));

    private DisposalRules() { }

    /** 动作完成后授权应处的状态。执行的落点由通道决定，因此不在这里给。 */
    public static String outcomeOf(String action) {
        return switch (action) {
            case APPROVE -> APPROVED;
            case REJECT -> REJECTED;
            case STOP -> STOPPED;
            case CANCEL -> CANCELLED;
            default -> throw new IllegalArgumentException("no fixed outcome for " + action);
        };
    }

    public static void requireTransition(String action, String currentStatus) {
        Set<String> allowed = TRANSITIONS.get(action);
        if (allowed == null || !allowed.contains(currentStatus))
            throw conflict("INVALID_TRANSITION", "当前状态不允许该操作");
    }

    /**
     * 两人规则：批准人不能是申请人。
     * 这是"不能自己批准自己动手"的制度约束，不是数据校验——所以它在策略里可关，但关掉必须是策略的明示决定。
     */
    public static void requireTwoPerson(DisposalPolicy policy, String requestedBy, String approverId) {
        if (policy.twoPersonRule() && requestedBy != null && requestedBy.equals(approverId))
            throw conflict("TWO_PERSON_RULE", "批准人不能是申请人");
    }

    /** 执行必须落在授权时限内；过期的授权不是"晚一点也行"，是已经失效。 */
    public static void requireWithinWindow(long now, Long validFrom, Long validUntil) {
        if (validFrom == null || validUntil == null)
            throw conflict("AUTHORIZATION_NOT_APPROVED", "授权尚未批准，没有有效期");
        if (now < validFrom || now >= validUntil)
            throw conflict("AUTHORIZATION_EXPIRED", "授权已超出有效期");
    }

    public static boolean expired(String status, long now, Long validUntil) {
        return APPROVED.equals(status) && validUntil != null && now >= validUntil;
    }

    /**
     * 详情页能点哪些动作：状态允许 + 调用者有权限，两个条件都满足才给。
     * 只按状态给会让前端把没有权限的按钮画成可点，用户点了才吃 403。
     */
    public static Set<String> allowedActions(String status, String channel, String requestedBy,
                                             String callerId, Set<String> permissions) {
        Set<String> actions = new LinkedHashSet<>();
        boolean canApprove = permissions.contains("disposal:approve");
        if (TRANSITIONS.get(APPROVE).contains(status) && canApprove) { actions.add(APPROVE); actions.add(REJECT); }
        if (TRANSITIONS.get(EXECUTE).contains(status) && permissions.contains("disposal:execute")) actions.add(EXECUTE);
        if (TRANSITIONS.get(STOP).contains(status) && permissions.contains("disposal:stop")) actions.add(STOP);
        // 撤回是申请人自己收回申请，因此本人无需审批权；他人收回则需要审批权。
        if (TRANSITIONS.get(CANCEL).contains(status) && (canApprove || callerId != null && callerId.equals(requestedBy)))
            actions.add(CANCEL);
        if (MANUAL.equals(channel) && TRANSITIONS.get(MANUAL_RESULT).contains(status)
                && permissions.contains("disposal:execute")) actions.add(MANUAL_RESULT);
        return actions;
    }

    /**
     * 由通道与事件流推导设备急停结果（决策 13-10 / 13-11）。
     * "授权撤销了"和"设备真的停了"是两件事：前端在 STOPPED 旁必须带限定语，靠的就是这个值。
     *
     * 两条刻意的规则：
     * 1. 人工执行的授权根本没有设备，永远是"未尝试"——不能因为有 STOP 事件就说成设备已停。
     * 2. 设备通道下没有任何设备侧证据时给 UNAVAILABLE，**不给 EXECUTED**。
     *    从"没有失败记录"推出"成功了"，正是本决策要防的误读；缺证据只能说不知道它停没停。
     *    因此 EXECUTED 需要正面证据，本期设备协议没有急停、也就产生不了这种证据（见 stop 路径）。
     */
    public static String deviceStopResult(String channel, List<String> eventKinds) {
        if (!eventKinds.contains("STOP")) return STOP_NOT_ATTEMPTED;
        if (!LINGYUN_B.equals(channel) && !COUNTERMEASURE_4CH.equals(channel)) return STOP_NOT_ATTEMPTED;
        if (eventKinds.contains("DEVICE_ALL_OFF_ISSUED")) return STOP_ALL_OFF_ISSUED;
        if (eventKinds.contains("DEVICE_NOT_BOUND")) return STOP_NOT_BOUND;
        return STOP_UNAVAILABLE;
    }

    /**
     * 执行为何还没做成（决策 13-14 / 13-22）：由事件流推导，不加列。
     *
     * 只在 APPROVED 下给值。别的状态要么已经执行/了结（"受阻"已不成立），要么根本还没批。
     * 这条限制同时解掉一处歧义：DEVICE_NOT_BOUND 在停止路径上也会记（13-11），
     * 而停止后状态是 STOPPED——不按状态设限的话，一次"停止时发现设备没绑定"会被显示成"执行受阻"。
     * 同一授权可能被拦多次（补了绑定又发现离线），因此取**最后一条**阻塞事件；成功执行后清空。
     */
    public static String executionBlockReason(String status, List<String> eventKinds) {
        if (!APPROVED.equals(status)) return null;
        String reason = null;
        for (String kind : eventKinds) {
            if ("EXECUTE".equals(kind)) reason = null;
            else if (BLOCK_REASONS.containsKey(kind)) reason = BLOCK_REASONS.get(kind);
        }
        return reason;
    }

    /** AUTH-YYYYMMDD-NNNN（决策 13-5）。四位不够时不截断——宁可号变长，也不能两次处置共用一个编号。 */
    public static String authorizationNo(String dayKey, int sequence) {
        if (dayKey == null || dayKey.length() != 8)
            throw new IllegalArgumentException("day key must be yyyyMMdd");
        return "AUTH-" + dayKey + "-" + (sequence < 10000 ? String.format("%04d", sequence) : String.valueOf(sequence));
    }

    public static void requireKnown(String value, Set<String> domain, String message) {
        if (value == null || !domain.contains(value))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
