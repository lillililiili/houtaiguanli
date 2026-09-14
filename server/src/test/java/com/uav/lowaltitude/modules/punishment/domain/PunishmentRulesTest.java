package com.uav.lowaltitude.modules.punishment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.punishment.domain.PunishmentRules.CaseFacts;
import com.uav.lowaltitude.platform.api.ApiException;

/** 处罚案件状态机的纯单元测试：不启 Spring、不碰库。 */
class PunishmentRulesTest {

    private static final Set<String> ALL = Set.of("punishment:read", "punishment:file", "punishment:decide",
            "punishment:review", "punishment:close");
    private static final String OFFICER = "u-officer", OTHER = "u-other";
    private static final CaseFacts RICH = new CaseFacts(1, true, true, 1, OFFICER);
    private static final CaseFacts BARE = new CaseFacts(0, false, false, 0, OFFICER);

    @Test
    void terminalStatusesAcceptNothingButDocumentRevocation() {
        // 结案后仍要能作废文书：文书出错与案件是否办完是两件事，堵死会让错误的文书永远挂在那里。
        assertThatCode(() -> PunishmentRules.requireTransition(PunishmentRules.REVOKE_DOCUMENT, PunishmentRules.CLOSED))
                .doesNotThrowAnyException();
        for (String action : List.of(PunishmentRules.ASSIGN, PunishmentRules.ADD_LEAD, PunishmentRules.DRAFT_DISCRETION,
                PunishmentRules.CONFIRM_DISCRETION, PunishmentRules.REVIEW, PunishmentRules.ISSUE_DOCUMENT,
                PunishmentRules.CLOSE, PunishmentRules.WITHDRAW)) {
            for (String terminal : List.of(PunishmentRules.CLOSED, PunishmentRules.WITHDRAWN)) {
                assertThatThrownBy(() -> PunishmentRules.requireTransition(action, terminal))
                        .as(terminal + "/" + action).isInstanceOf(ApiException.class)
                        .hasFieldOrPropertyWithValue("code", "INVALID_TRANSITION");
            }
        }
    }

    @Test
    void withdrawOnlyBeforeReview() {
        PunishmentRules.requireTransition(PunishmentRules.WITHDRAW, PunishmentRules.FILED);
        PunishmentRules.requireTransition(PunishmentRules.WITHDRAW, PunishmentRules.INVESTIGATING);
        // 已经进入复核或已定案就不能一撤了之：那时已经有了裁量甚至文书，撤案会让它们失去归属。
        for (String late : List.of(PunishmentRules.UNDER_REVIEW, PunishmentRules.DECIDED)) {
            assertThatThrownBy(() -> PunishmentRules.requireTransition(PunishmentRules.WITHDRAW, late))
                    .as(late).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void reviewOutcomeDecidesWhereTheCaseGoes() {
        assertThat(PunishmentRules.statusAfterReview(PunishmentRules.UPHELD)).isEqualTo(PunishmentRules.DECIDED);
        // 维持之外的两种结论都退回调查（决策 14-19 ③）：需要改的和证据不足的，都还没到能定案的程度。
        assertThat(PunishmentRules.statusAfterReview(PunishmentRules.REVISED)).isEqualTo(PunishmentRules.INVESTIGATING);
        assertThat(PunishmentRules.statusAfterReview(PunishmentRules.INSUFFICIENT)).isEqualTo(PunishmentRules.INVESTIGATING);
    }

    @Test
    void reviewerMustNotBeTheOfficer() {
        assertThatThrownBy(() -> PunishmentRules.requireDifferentReviewer("u-1", "u-1"))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "REVIEW_SELF_NOT_ALLOWED");
        assertThatCode(() -> PunishmentRules.requireDifferentReviewer("u-1", "u-2")).doesNotThrowAnyException();
        // 还没指派承办人就复核，等于复核一件没人负责调查的案子（决策 14-27 ③）。
        // 消息文本是契约的一部分：前端按这句话兜底显示。
        assertThatThrownBy(() -> PunishmentRules.requireDifferentReviewer(null, "u-2"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TRANSITION")
                .hasMessageContaining("未指派承办人不能复核");
    }

    @Test
    void fineMustFallInsideTheRuleRange() {
        PunishmentRules.requireFineWithin(PunishmentRules.FINE, 50000, 20000, 200000);
        PunishmentRules.requireFineWithin(PunishmentRules.FINE, 20000, 20000, 200000);
        PunishmentRules.requireFineWithin(PunishmentRules.FINE, 200000, 20000, 200000);
        for (long outside : new long[]{19999, 200001}) {
            assertThatThrownBy(() -> PunishmentRules.requireFineWithin(PunishmentRules.FINE, outside, 20000, 200000))
                    .as(String.valueOf(outside)).isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("code", "FINE_OUT_OF_RANGE");
        }
    }

    @Test
    void warningCarriesNoFine() {
        PunishmentRules.requireFineWithin(PunishmentRules.WARNING, 0, 20000, 200000);
        // 写着"警告"却带罚款数额的裁量，落到文书上是自相矛盾的。
        assertThatThrownBy(() -> PunishmentRules.requireFineWithin(PunishmentRules.WARNING, 1, 20000, 200000))
                .isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("code", "VALIDATION_ERROR");
    }

    @Test
    void allowedActionsCoverTheTenWriteEndpoints() {
        // 契约 v1.1：十项动作与写接口一一对应，没有 SUBMIT_REVIEW（确认裁量即提请复核）。
        Set<String> everything = new java.util.LinkedHashSet<>();
        for (String status : PunishmentRules.STATUSES) {
            everything.addAll(PunishmentRules.allowedActions(status, ALL, OTHER, RICH));
        }
        assertThat(everything).containsExactlyInAnyOrder("ASSIGN", "ADD_LEAD", "RESOLVE_LEAD", "DRAFT_DISCRETION",
                "CONFIRM_DISCRETION", "REVIEW", "ISSUE_DOCUMENT", "REVOKE_DOCUMENT", "CLOSE", "WITHDRAW");
        assertThat(everything).doesNotContain("SUBMIT_REVIEW");
    }

    @Test
    void allowedActionsNeedStatusPermissionAndFacts() {
        assertThat(PunishmentRules.allowedActions(PunishmentRules.FILED, ALL, OTHER, BARE))
                .containsExactlyInAnyOrder("ASSIGN", "WITHDRAW");
        // 只读用户在每个状态下都拿不到动作，否则前端会把按钮画成可点。
        for (String status : PunishmentRules.STATUSES) {
            assertThat(PunishmentRules.allowedActions(status, Set.of("punishment:read"), OTHER, RICH)).as(status).isEmpty();
        }
        // 有权限、状态也对，但前置事实不成立时同样不给：没有草稿就无从确认，没有文书就不能结案。
        assertThat(PunishmentRules.allowedActions(PunishmentRules.INVESTIGATING, ALL, OTHER, BARE))
                .doesNotContain("CONFIRM_DISCRETION", "RESOLVE_LEAD");
        assertThat(PunishmentRules.allowedActions(PunishmentRules.DECIDED, ALL, OTHER, BARE))
                .doesNotContain("CLOSE", "REVOKE_DOCUMENT");
        // 决定书只能基于冻结的裁量。
        assertThat(PunishmentRules.allowedActions(PunishmentRules.DECIDED, ALL, OTHER, new CaseFacts(0, true, false, 0, OFFICER)))
                .doesNotContain("ISSUE_DOCUMENT");
    }

    @Test
    void reviewIsNotOfferedToTheOfficerThemselves() {
        // 决策 14-32：承办人复核自己的案子会被服务端 409 挡（REVIEW_SELF_NOT_ALLOWED）。
        // 把按钮画出来再让人点一次吃 409，等于把规则藏到点击之后才告诉他——E2 联调就是这么撞上的。
        assertThat(PunishmentRules.allowedActions(PunishmentRules.UNDER_REVIEW, ALL, OFFICER, RICH))
                .doesNotContain("REVIEW");
        assertThat(PunishmentRules.allowedActions(PunishmentRules.UNDER_REVIEW, ALL, OTHER, RICH))
                .contains("REVIEW");
    }

    @Test
    void reviewIsNotOfferedWhenNoOfficerIsAssigned() {
        // 未指派承办人时服务端同样拒绝（"未指派承办人不能复核"），按钮也就不该出现。
        CaseFacts unassigned = new CaseFacts(0, false, true, 0, null);
        assertThat(PunishmentRules.allowedActions(PunishmentRules.UNDER_REVIEW, ALL, OTHER, unassigned))
                .doesNotContain("REVIEW");
    }

    @Test
    void closeNeedsAnIssuedDocument() {
        assertThat(PunishmentRules.allowedActions(PunishmentRules.DECIDED, ALL, OTHER, new CaseFacts(0, false, true, 0, OFFICER)))
                .doesNotContain("CLOSE");
        assertThat(PunishmentRules.allowedActions(PunishmentRules.DECIDED, ALL, OTHER, new CaseFacts(0, false, true, 1, OFFICER)))
                .contains("CLOSE");
    }

    @Test
    void numbersAreZeroPaddedAndNeverTruncated() {
        assertThat(PunishmentRules.caseNo("20260908", 1)).isEqualTo("CASE-20260908-0001");
        // 第 10000 件宁可号变长也不能回绕：两个案件共用一个案号，卷宗就对不上了。
        assertThat(PunishmentRules.caseNo("20260908", 10000)).isEqualTo("CASE-20260908-10000");
        assertThat(PunishmentRules.documentNo("CASE-20260908-0001", 1)).isEqualTo("CASE-20260908-0001-DEC-01");
        assertThat(PunishmentRules.documentNo("CASE-20260908-0001", 100)).isEqualTo("CASE-20260908-0001-DEC-100");
    }

    @Test
    void eventKindsMatchTheMigrationWhitelist() {
        assertThat(PunishmentRules.EVENT_KINDS).containsExactlyInAnyOrder("FILE", "ASSIGN", "LEAD_ADDED",
                "LEAD_RESOLVED", "DISCRETION_DRAFTED", "DISCRETION_CONFIRMED", "DOCUMENT_ISSUED", "DOCUMENT_REVOKED",
                "REVIEW_REQUESTED", "REVIEW_CONCLUDED", "CLOSE", "WITHDRAW");
    }
}
