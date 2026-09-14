package com.uav.lowaltitude.modules.punishment.domain;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.platform.api.ApiException;

/**
 * 处罚案件状态机与派生规则（纯函数，不碰数据库）。
 *
 * 办案过程有法律后果：每一步都要能说清"当时处在哪个环节、凭什么往下走"。
 * 因此状态迁移写成显式白名单——没列出的组合一律 409，而不是落进 else 里先放过去。
 */
public final class PunishmentRules {
    public static final String FILED = "FILED", INVESTIGATING = "INVESTIGATING", UNDER_REVIEW = "UNDER_REVIEW",
            DECIDED = "DECIDED", CLOSED = "CLOSED", WITHDRAWN = "WITHDRAWN";
    public static final Set<String> STATUSES = Set.of(FILED, INVESTIGATING, UNDER_REVIEW, DECIDED, CLOSED, WITHDRAWN);

    public static final Set<String> PARTY_TYPES = Set.of("PERSON", "ORG", "UNKNOWN");
    public static final String PARTY_UNKNOWN = "UNKNOWN";

    public static final String WARNING = "WARNING", FINE = "FINE", WARNING_AND_FINE = "WARNING_AND_FINE";
    public static final Set<String> PENALTY_TYPES = Set.of(WARNING, FINE, WARNING_AND_FINE);

    public static final String DRAFT = "DRAFT", CONFIRMED = "CONFIRMED", SUPERSEDED = "SUPERSEDED";

    public static final String ISSUED = "ISSUED", REVOKED = "REVOKED";

    public static final String UPHELD = "UPHELD", REVISED = "REVISED", INSUFFICIENT = "INSUFFICIENT";
    public static final Set<String> REVIEW_CONCLUSIONS = Set.of(UPHELD, REVISED, INSUFFICIENT);

    /** 待补线索词表 = 事件驱动流程 4.7 的四个条件，外加兜底（决策 14-11）。 */
    public static final Set<String> LEAD_KINDS = Set.of("PARTY_IDENTITY", "EVIDENCE", "JURISDICTION", "FACT", "OTHER");

    /** 案件事件种类；与迁移 0102 的 ck_stage14_case_event_kind 白名单一一对应。 */
    public static final Set<String> EVENT_KINDS = Set.of("FILE", "ASSIGN", "LEAD_ADDED", "LEAD_RESOLVED",
            "DISCRETION_DRAFTED", "DISCRETION_CONFIRMED", "DOCUMENT_ISSUED", "DOCUMENT_REVOKED",
            "REVIEW_REQUESTED", "REVIEW_CONCLUDED", "CLOSE", "WITHDRAW");

    /** 十项动作与写接口一一对应（契约 v1.1 / 决策 14-19）：确认裁量即提请复核，没有单独的"提交复核"。 */
    public static final String ASSIGN = "ASSIGN", ADD_LEAD = "ADD_LEAD", RESOLVE_LEAD = "RESOLVE_LEAD",
            DRAFT_DISCRETION = "DRAFT_DISCRETION", CONFIRM_DISCRETION = "CONFIRM_DISCRETION", REVIEW = "REVIEW",
            ISSUE_DOCUMENT = "ISSUE_DOCUMENT", REVOKE_DOCUMENT = "REVOKE_DOCUMENT",
            CLOSE = "CLOSE", WITHDRAW = "WITHDRAW";

    /** 动作 → 允许发起该动作的来源状态。没列出的组合就是不允许。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry(ASSIGN, Set.of(FILED, INVESTIGATING)),
            Map.entry(ADD_LEAD, Set.of(INVESTIGATING)),
            Map.entry(RESOLVE_LEAD, Set.of(INVESTIGATING)),
            Map.entry(DRAFT_DISCRETION, Set.of(INVESTIGATING)),
            Map.entry(CONFIRM_DISCRETION, Set.of(INVESTIGATING)),
            Map.entry(REVIEW, Set.of(UNDER_REVIEW)),
            Map.entry(ISSUE_DOCUMENT, Set.of(DECIDED)),
            Map.entry(REVOKE_DOCUMENT, Set.of(DECIDED, CLOSED)),
            Map.entry(CLOSE, Set.of(DECIDED)),
            Map.entry(WITHDRAW, Set.of(FILED, INVESTIGATING)));

    /** 动作 → 所需权限码。allowed_actions 与实际写接口用同一张表，避免"按钮能点但一点就 403"。 */
    private static final Map<String, String> REQUIRED_PERMISSIONS = Map.ofEntries(
            Map.entry(ASSIGN, "punishment:file"),
            Map.entry(ADD_LEAD, "punishment:file"),
            Map.entry(RESOLVE_LEAD, "punishment:file"),
            Map.entry(DRAFT_DISCRETION, "punishment:decide"),
            Map.entry(CONFIRM_DISCRETION, "punishment:decide"),
            Map.entry(REVIEW, "punishment:review"),
            Map.entry(ISSUE_DOCUMENT, "punishment:decide"),
            Map.entry(REVOKE_DOCUMENT, "punishment:decide"),
            Map.entry(CLOSE, "punishment:close"),
            Map.entry(WITHDRAW, "punishment:close"));

    private PunishmentRules() { }

    public static void requireTransition(String action, String currentStatus) {
        Set<String> allowed = TRANSITIONS.get(action);
        if (allowed == null || !allowed.contains(currentStatus))
            throw conflict("INVALID_TRANSITION", "当前案件状态不允许该操作");
    }

    /** 复核结论决定案件去向（决策 14-7 / 14-19）：维持即定案，其余两种都退回调查。 */
    public static String statusAfterReview(String conclusion) {
        return UPHELD.equals(conclusion) ? DECIDED : INVESTIGATING;
    }

    /**
     * 复核人不能是承办人（决策 14-11）。
     * 这是"自己办的案不能自己复核"的制度约束——复核的意义就在于换一双眼睛。
     */
    public static void requireDifferentReviewer(String officerId, String reviewerId) {
        // 还没指派承办人就复核，等于复核一件没人负责调查的案子——复核不是补办案的手段（决策 14-27 ③）。
        // 消息文本是契约的一部分：前端按这句话兜底显示，改字面会让页面提示落空。
        if (officerId == null) throw conflict("INVALID_TRANSITION", "未指派承办人不能复核");
        if (officerId.equals(reviewerId))
            throw conflict("REVIEW_SELF_NOT_ALLOWED", "承办人不能复核自己承办的案件");
    }

    /**
     * 罚款金额必须落在规则区间内（决策 14-9）。区间来自 penalty_rule，代码里不写任何金额。
     * 只警告时金额必须为 0：一份写着"警告"却带着罚款数额的裁量，落到文书上是自相矛盾的。
     */
    public static void requireFineWithin(String penaltyType, long fineAmount, long fineMin, long fineMax) {
        if (fineAmount < 0) throw badRequest("VALIDATION_ERROR", "罚款金额不能为负");
        if (WARNING.equals(penaltyType)) {
            if (fineAmount != 0) throw badRequest("VALIDATION_ERROR", "处罚种类为警告时罚款金额必须为 0");
            return;
        }
        if (fineAmount < fineMin || fineAmount > fineMax)
            throw badRequest("FINE_OUT_OF_RANGE", "罚款金额不在该档位的区间内");
    }

    /**
     * 详情页能点哪些动作：状态允许 + 调用者有权限 + 该动作的前置事实成立，三者都满足才给。
     * 少任何一条都会让前端把按钮画成可点，用户点了才吃 403/409。
     */
    public static Set<String> allowedActions(String status, Set<String> permissions, String callerId, CaseFacts facts) {
        Set<String> actions = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : TRANSITIONS.entrySet()) {
            String action = entry.getKey();
            if (!entry.getValue().contains(status)) continue;
            if (!permissions.contains(REQUIRED_PERMISSIONS.get(action))) continue;
            if (!factsAllow(action, facts)) continue;
            // 复核还要看**是谁在看**（决策 14-32）：承办人复核自己的案子会被服务端 409 挡下，
            // 未指派承办人时同样被挡。把按钮画出来再让人点一次吃 409，等于把规则藏到点击之后才告诉他。
            if (REVIEW.equals(action) && !mayReview(callerId, facts.officerId())) continue;
            actions.add(action);
        }
        return actions;
    }

    /** 与 requireDifferentReviewer 同一口径：这两处一旦分叉，页面给的按钮就会和服务端的答复对不上。 */
    private static boolean mayReview(String callerId, String officerId) {
        return officerId != null && !officerId.equals(callerId);
    }

    private static boolean factsAllow(String action, CaseFacts facts) {
        return switch (action) {
            // 没有未解决线索就没有"解决线索"可点。
            case RESOLVE_LEAD -> facts.openLeads() > 0;
            // 没有草稿就无从确认。
            case CONFIRM_DISCRETION -> facts.hasDraftDiscretion();
            // 决定书只能基于冻结的裁量（决策 14-10）。
            case ISSUE_DOCUMENT -> facts.hasConfirmedDiscretion();
            case REVOKE_DOCUMENT -> facts.issuedDocuments() > 0;
            // 结案是有文书的结案（决策 14-14）。
            case CLOSE -> facts.issuedDocuments() > 0;
            default -> true;
        };
    }

    /** 派生 allowed_actions 需要的案件事实。officerId 参与复核的可见性判断（决策 14-32）。 */
    public record CaseFacts(int openLeads, boolean hasDraftDiscretion, boolean hasConfirmedDiscretion,
            int issuedDocuments, String officerId) { }

    /** CASE-YYYYMMDD-NNNN（决策 14-6）。四位不够时不截断——宁可号变长，也不能两个案件共用一个案号。 */
    public static String caseNo(String dayKey, int sequence) {
        if (dayKey == null || dayKey.length() != 8) throw new IllegalArgumentException("day key must be yyyyMMdd");
        return "CASE-" + dayKey + "-" + (sequence < 10000 ? String.format("%04d", sequence) : String.valueOf(sequence));
    }

    /** 文书号 `<案件号>-DEC-NN`（决策 14-10）；同一案件内递增。 */
    public static String documentNo(String caseNo, int sequence) {
        return caseNo + "-DEC-" + (sequence < 100 ? String.format("%02d", sequence) : String.valueOf(sequence));
    }

    public static void requireKnown(String value, Set<String> domain, String message) {
        if (value == null || !domain.contains(value)) throw badRequest("VALIDATION_ERROR", message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
