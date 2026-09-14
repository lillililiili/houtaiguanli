package com.uav.lowaltitude.modules.disposal.domain;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 处置策略：授权条件、审批规则、时限与指令码映射全部来自 disposal_policy.params，代码里不写任何阈值（决策 13-2）。
 *
 * 缺参数时抛错而不是取默认值。默认值就是藏起来的裸阈值：策略表少一项，系统照常放行 30 分钟，
 * 而没有人知道这 30 分钟是谁定的——对一个"允许对空中目标动手"的授权，这种含糊不能有。
 */
public record DisposalPolicy(String policyCode, String schemaStatus, Map<String, Object> params) {

    public boolean approvalRequired() { return bool("approval_required"); }

    public boolean twoPersonRule() { return bool("two_person_rule"); }

    public int maxActivePerSubject() { return number("max_active_per_subject").intValue(); }

    /** 该动作的授权时限（分钟）：批准时刻 + 本值 = valid_until。 */
    public int timeLimitMinutes(String actionType) {
        return nested("time_limit_min", actionType) instanceof Number n ? n.intValue()
                : fail("time_limit_min." + actionType);
    }

    /** 该动作是否要求主体事件已核实。 */
    public boolean requiresConfirmedEvent(String actionType) {
        return nested("requires_confirmed_event", actionType) instanceof Boolean b ? b
                : fail("requires_confirmed_event." + actionType);
    }

    /** 协议 B 指令码映射；DEMO 码表见 docs/backend-stage8/target-schema-v1-alignment.md §5.3。 */
    public Command command(String actionType) {
        Object entry = nested("command_map", actionType);
        if (!(entry instanceof Map<?, ?> map)) return fail("command_map." + actionType);
        Object type = map.get("operation_type"), cmd = map.get("operation_cmd");
        if (!(type instanceof Number t) || !(cmd instanceof Number c)) return fail("command_map." + actionType);
        return new Command(t.intValue(), c.intValue());
    }

    public boolean demo() { return "DEMO".equals(schemaStatus); }

    public record Command(int operationType, int operationCmd) { }

    private boolean bool(String key) {
        return params.get(key) instanceof Boolean b ? b : fail(key);
    }

    private Number number(String key) {
        return params.get(key) instanceof Number n ? n : fail(key);
    }

    private Object nested(String group, String key) {
        return params.get(group) instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private <T> T fail(String key) {
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "POLICY_PARAM_MISSING",
                "处置策略 " + policyCode + " 缺少参数 " + key + "，不能凭默认值放行");
    }
}
