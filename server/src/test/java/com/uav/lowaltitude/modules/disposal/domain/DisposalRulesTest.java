package com.uav.lowaltitude.modules.disposal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.platform.api.ApiException;

/** 处置授权状态机的纯单元测试：不启 Spring、不碰库。 */
class DisposalRulesTest {

    private static final Set<String> ALL_PERMS = Set.of("disposal:read", "disposal:request",
            "disposal:approve", "disposal:execute", "disposal:stop");

    private static DisposalPolicy policy(Map<String, Object> overrides) {
        Map<String, Object> params = new java.util.LinkedHashMap<>(Map.of(
                "approval_required", true, "two_person_rule", true, "max_active_per_subject", 1,
                "time_limit_min", Map.of("COUNTERMEASURE", 30, "JAMMING", 30, "DISPERSAL", 15, "DECOY", 30),
                "requires_confirmed_event", Map.of("COUNTERMEASURE", true, "JAMMING", true,
                        "DISPERSAL", false, "DECOY", true),
                "command_map", Map.of("COUNTERMEASURE", Map.of("operation_type", 1, "operation_cmd", 60003))));
        params.putAll(overrides);
        return new DisposalPolicy("demo-v1", "DEMO", params);
    }

    // ---- 状态迁移 ----

    @Test
    void approveAndRejectOnlyFromRequested() {
        DisposalRules.requireTransition(DisposalRules.APPROVE, DisposalRules.REQUESTED);
        DisposalRules.requireTransition(DisposalRules.REJECT, DisposalRules.REQUESTED);
        for (String from : List.of(DisposalRules.APPROVED, DisposalRules.EXECUTING, DisposalRules.COMPLETED,
                DisposalRules.REJECTED, DisposalRules.CANCELLED, DisposalRules.EXPIRED, DisposalRules.STOPPED)) {
            assertThatThrownBy(() -> DisposalRules.requireTransition(DisposalRules.APPROVE, from))
                    .as(from).isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_TRANSITION");
        }
    }

    @Test
    void executeOnlyFromApprovedAndStopFromApprovedOrExecuting() {
        DisposalRules.requireTransition(DisposalRules.EXECUTE, DisposalRules.APPROVED);
        DisposalRules.requireTransition(DisposalRules.STOP, DisposalRules.APPROVED);
        DisposalRules.requireTransition(DisposalRules.STOP, DisposalRules.EXECUTING);
        assertThatThrownBy(() -> DisposalRules.requireTransition(DisposalRules.EXECUTE, DisposalRules.EXECUTING))
                .isInstanceOf(ApiException.class);
        // 终态不能再停：已经完成的处置再"停止"一次，事实链上会多出一次不存在的动作。
        assertThatThrownBy(() -> DisposalRules.requireTransition(DisposalRules.STOP, DisposalRules.COMPLETED))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void terminalStatusesAcceptNoAction() {
        for (String terminal : List.of(DisposalRules.COMPLETED, DisposalRules.FAILED, DisposalRules.REJECTED,
                DisposalRules.STOPPED, DisposalRules.EXPIRED, DisposalRules.CANCELLED)) {
            for (String action : List.of(DisposalRules.APPROVE, DisposalRules.REJECT, DisposalRules.EXECUTE,
                    DisposalRules.STOP, DisposalRules.CANCEL, DisposalRules.MANUAL_RESULT)) {
                assertThatThrownBy(() -> DisposalRules.requireTransition(action, terminal))
                        .as(terminal + "/" + action).isInstanceOf(ApiException.class);
            }
        }
    }

    @Test
    void activeStatusesAreExactlyTheNonTerminalOnes() {
        assertThat(DisposalRules.ACTIVE_STATUSES)
                .containsExactlyInAnyOrder(DisposalRules.REQUESTED, DisposalRules.APPROVED, DisposalRules.EXECUTING);
    }

    // ---- 两人规则与时限 ----

    @Test
    void twoPersonRuleBlocksSelfApproval() {
        assertThatThrownBy(() -> DisposalRules.requireTwoPerson(policy(Map.of()), "u-1", "u-1"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "TWO_PERSON_RULE");
        DisposalRules.requireTwoPerson(policy(Map.of()), "u-1", "u-2");
        // 关闭两人规则必须是策略的明示决定，不是代码里的默认。
        DisposalRules.requireTwoPerson(policy(Map.of("two_person_rule", false)), "u-1", "u-1");
    }

    @Test
    void executionWindowIsHalfOpen() {
        DisposalRules.requireWithinWindow(1_000L, 1_000L, 2_000L);
        DisposalRules.requireWithinWindow(1_999L, 1_000L, 2_000L);
        // valid_until 当刻已失效：到点就是到点，不给"刚好卡在末尾"的模糊地带。
        assertThatThrownBy(() -> DisposalRules.requireWithinWindow(2_000L, 1_000L, 2_000L))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "AUTHORIZATION_EXPIRED");
        assertThatThrownBy(() -> DisposalRules.requireWithinWindow(999L, 1_000L, 2_000L))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "AUTHORIZATION_EXPIRED");
        assertThatThrownBy(() -> DisposalRules.requireWithinWindow(1_500L, null, null))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "AUTHORIZATION_NOT_APPROVED");
    }

    @Test
    void expiryOnlyAppliesToApproved() {
        assertThat(DisposalRules.expired(DisposalRules.APPROVED, 2_000L, 2_000L)).isTrue();
        assertThat(DisposalRules.expired(DisposalRules.APPROVED, 1_999L, 2_000L)).isFalse();
        // 已在执行的不算过期：设备已经动了，把它标成 EXPIRED 等于抹掉一次真实发生的处置。
        assertThat(DisposalRules.expired(DisposalRules.EXECUTING, 9_999L, 2_000L)).isFalse();
        assertThat(DisposalRules.expired(DisposalRules.REQUESTED, 9_999L, 2_000L)).isFalse();
    }

    // ---- allowed_actions ----

    @Test
    void allowedActionsRequireBothStatusAndPermission() {
        assertThat(DisposalRules.allowedActions(DisposalRules.REQUESTED, DisposalRules.LINGYUN_B, "u-1", "u-2", ALL_PERMS))
                .containsExactlyInAnyOrder("APPROVE", "REJECT", "CANCEL");
        assertThat(DisposalRules.allowedActions(DisposalRules.APPROVED, DisposalRules.LINGYUN_B, "u-1", "u-2", ALL_PERMS))
                .containsExactlyInAnyOrder("EXECUTE", "STOP", "CANCEL");
        // 只读用户在**每一个**状态下都不该拿到动作，否则前端会把按钮画成可点。
        // 只测 REQUESTED 是不够的：那个状态本来就到不了 EXECUTE/STOP，漏掉权限判断也照样是空集
        // ——逐状态断言才拦得住"状态对了就给按钮、不看权限"这类写法。
        for (String status : List.of(DisposalRules.REQUESTED, DisposalRules.APPROVED, DisposalRules.EXECUTING)) {
            assertThat(DisposalRules.allowedActions(status, DisposalRules.MANUAL, "u-1", "u-2",
                    Set.of("disposal:read"))).as(status).isEmpty();
        }
        // 只有执行权、没有审批权：APPROVED 下只能执行，不能顺手把停止或撤回也拿到。
        assertThat(DisposalRules.allowedActions(DisposalRules.APPROVED, DisposalRules.LINGYUN_B, "u-1", "u-2",
                Set.of("disposal:read", "disposal:execute"))).containsExactly("EXECUTE");
        assertThat(DisposalRules.allowedActions(DisposalRules.EXECUTING, DisposalRules.LINGYUN_B, "u-1", "u-2",
                Set.of("disposal:read", "disposal:stop"))).containsExactly("STOP");
    }

    @Test
    void requesterMayCancelOwnRequestWithoutApprovalPermission() {
        assertThat(DisposalRules.allowedActions(DisposalRules.REQUESTED, DisposalRules.MANUAL, "u-1", "u-1",
                Set.of("disposal:read", "disposal:request"))).containsExactly("CANCEL");
        // 别人的申请，没有审批权就不能替他撤回。
        assertThat(DisposalRules.allowedActions(DisposalRules.REQUESTED, DisposalRules.MANUAL, "u-1", "u-9",
                Set.of("disposal:read", "disposal:request"))).isEmpty();
    }

    @Test
    void manualResultOnlyOnManualChannelWhileExecuting() {
        assertThat(DisposalRules.allowedActions(DisposalRules.EXECUTING, DisposalRules.MANUAL, "u-1", "u-2", ALL_PERMS))
                .contains("MANUAL_RESULT");
        // 协议 B 的结果来自设备回执，人不能替设备说"我成功了"。
        assertThat(DisposalRules.allowedActions(DisposalRules.EXECUTING, DisposalRules.LINGYUN_B, "u-1", "u-2", ALL_PERMS))
                .doesNotContain("MANUAL_RESULT");
    }

    // ---- device_stop_result（决策 13-10 / 13-11）----

    @Test
    void deviceStopResultDistinguishesFourCases() {
        assertThat(DisposalRules.deviceStopResult(DisposalRules.LINGYUN_B, List.of("REQUEST", "APPROVE")))
                .isEqualTo(DisposalRules.STOP_NOT_ATTEMPTED);
        // 人工执行根本没有设备：停过也仍然是"未尝试"，不能说成设备已停。
        assertThat(DisposalRules.deviceStopResult(DisposalRules.MANUAL, List.of("REQUEST", "APPROVE", "STOP")))
                .isEqualTo(DisposalRules.STOP_NOT_ATTEMPTED);
        // 设备通道下没有任何设备侧证据时只能说"不知道"，不能从"没有失败记录"推出成功。
        assertThat(DisposalRules.deviceStopResult(DisposalRules.LINGYUN_B, List.of("REQUEST", "APPROVE", "STOP")))
                .isEqualTo(DisposalRules.STOP_UNAVAILABLE);
        assertThat(DisposalRules.deviceStopResult(DisposalRules.LINGYUN_B, List.of("STOP", "DEVICE_STOP_UNAVAILABLE")))
                .isEqualTo(DisposalRules.STOP_UNAVAILABLE);
        // 未登记 MQTT 是本平台可补救的配置遗漏，与"厂家没做急停"不是一回事（13-11）。
        assertThat(DisposalRules.deviceStopResult(DisposalRules.LINGYUN_B, List.of("STOP", "DEVICE_NOT_BOUND")))
                .isEqualTo(DisposalRules.STOP_NOT_BOUND);
        assertThat(DisposalRules.deviceStopResult(DisposalRules.COUNTERMEASURE_4CH,
                List.of("STOP", "DEVICE_ALL_OFF_ISSUED")))
                .isEqualTo(DisposalRules.STOP_ALL_OFF_ISSUED);
    }

    // ---- 编号 ----

    @Test
    void authorizationNumberIsZeroPaddedAndNeverTruncated() {
        assertThat(DisposalRules.authorizationNo("20260907", 1)).isEqualTo("AUTH-20260907-0001");
        assertThat(DisposalRules.authorizationNo("20260907", 9999)).isEqualTo("AUTH-20260907-9999");
        // 第 10000 号宁可变长也不能回绕成 0000 —— 两次处置共用一个编号，证据链就断了。
        assertThat(DisposalRules.authorizationNo("20260907", 10000)).isEqualTo("AUTH-20260907-10000");
    }

    // ---- 策略参数：缺参数必须炸，不能取默认 ----

    @Test
    void policyReadsParamsAndRefusesToInventDefaults() {
        DisposalPolicy p = policy(Map.of());
        assertThat(p.timeLimitMinutes("DISPERSAL")).isEqualTo(15);
        assertThat(p.requiresConfirmedEvent("DISPERSAL")).isFalse();
        assertThat(p.requiresConfirmedEvent("COUNTERMEASURE")).isTrue();
        assertThat(p.command("COUNTERMEASURE")).isEqualTo(new DisposalPolicy.Command(1, 60003));
        assertThat(p.demo()).isTrue();
        // 缺失的参数不能悄悄变成一个"看起来合理"的默认值。
        assertThatThrownBy(() -> p.timeLimitMinutes("NOT_A_TYPE"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "POLICY_PARAM_MISSING");
        assertThatThrownBy(() -> new DisposalPolicy("demo-v1", "DEMO", Map.of()).twoPersonRule())
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "POLICY_PARAM_MISSING");
    }

    // ---- execution_block_reason（决策 13-14 / 13-22）----

    @Test
    void executionBlockReasonMapsEachEventToItsOwnRemedy() {
        assertThat(block(List.of("REQUEST", "APPROVE"))).isNull();
        assertThat(block(List.of("APPROVE", "DEVICE_CONTROL_UNAVAILABLE")))
                .isEqualTo(DisposalRules.BLOCK_DEVICE_CAPABILITY);
        assertThat(block(List.of("APPROVE", "PROTOCOL_NOT_OPENED")))
                .isEqualTo(DisposalRules.BLOCK_PROTOCOL_NOT_OPENED);
        assertThat(block(List.of("APPROVE", "DEVICE_NOT_BOUND"))).isEqualTo(DisposalRules.BLOCK_NOT_BOUND);
        assertThat(block(List.of("APPROVE", "DEVICE_OFFLINE"))).isEqualTo(DisposalRules.BLOCK_DEVICE_OFFLINE);
    }

    @Test
    void executionBlockReasonTakesTheLatestObstacleAndClearsOnSuccess() {
        // 先没绑定、运维补了绑定又发现离线：显示的必须是当前这一个，否则运维会去重复修已经修好的那个。
        assertThat(block(List.of("APPROVE", "DEVICE_NOT_BOUND", "DEVICE_OFFLINE")))
                .isEqualTo(DisposalRules.BLOCK_DEVICE_OFFLINE);
        // 成功下发之后就不再是"受阻"。
        assertThat(block(List.of("APPROVE", "DEVICE_OFFLINE", "EXECUTE"))).isNull();
    }

    @Test
    void executionBlockReasonOnlyAppliesWhileApproved() {
        // 停止路径也会记 DEVICE_NOT_BOUND（13-11）。不按状态设限的话，
        // 一次"停止时发现设备没绑定"会被显示成"执行受阻"，而那条授权其实已经撤销了。
        assertThat(DisposalRules.executionBlockReason(DisposalRules.STOPPED,
                List.of("APPROVE", "STOP", "DEVICE_NOT_BOUND"))).isNull();
        assertThat(DisposalRules.executionBlockReason(DisposalRules.COMPLETED,
                List.of("APPROVE", "EXECUTE", "COMPLETE"))).isNull();
        assertThat(DisposalRules.executionBlockReason(DisposalRules.REQUESTED,
                List.of("REQUEST"))).isNull();
    }

    private static String block(List<String> kinds) {
        return DisposalRules.executionBlockReason(DisposalRules.APPROVED, kinds);
    }

    /**
     * 决策 18-14：风险的流程到"通知上级"为止，不进处置授权。
     * 接口层那条反面用例只钉答复，名单里挂着 RISK 时它照样绿——名单本身要单独钉。
     */
    @Test
    void riskIsNotADisposalSubject() {
        assertThat(DisposalRules.SUBJECT_KINDS).containsExactlyInAnyOrder("UAV_EVENT", "TARGET");
    }

    @Test
    void eventKindsMatchTheMigrationWhitelist() {
        assertThat(DisposalRules.EVENT_KINDS).containsExactlyInAnyOrder("REQUEST", "APPROVE", "REJECT", "EXECUTE",
                "RECEIPT", "STOP", "COMPLETE", "FAIL", "EXPIRE", "CANCEL", "MANUAL_RESULT",
                "DEVICE_STOP_UNAVAILABLE", "DEVICE_CONTROL_UNAVAILABLE", "DEVICE_NOT_BOUND",
                "PROTOCOL_NOT_OPENED", "DEVICE_OFFLINE", "DEVICE_ALL_OFF_ISSUED");
    }
}
