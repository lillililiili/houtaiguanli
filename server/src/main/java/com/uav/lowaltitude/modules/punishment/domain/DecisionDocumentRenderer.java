package com.uav.lowaltitude.modules.punishment.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 《行政处罚决定书》DEMO 模板渲染（决策 14-10 / 14-20）。
 *
 * 平台**未获授权出具**处罚文书，所以首行是水印，而不是把它做得像一份真文书。
 * 这里先把"可追溯、不可篡改"做对：结构化字段 + 确定性渲染 + sha256 落库；PDF、盖章、送达都不做。
 *
 * 法律依据原样带出 penalty_rule.legal_basis，**不补任何条款号**（决策 14-20）：
 * 仓库里没有权威条文出处，编一个"第某条"出来即使有水印也是伪造法律依据——
 * 水印能说明"这份文书未获授权出具"，说明不了"这条法条是编的"。
 */
public final class DecisionDocumentRenderer {
    public static final String TEMPLATE_VERSION = "demo-v1";
    /** 首行水印：三件事一次说清——是演示、未获授权、金额档位未确认。 */
    public static final String WATERMARK = "演示模板 · 未经授权出具 · 金额档位未确认";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日").withZone(ZoneOffset.UTC);

    private DecisionDocumentRenderer() { }

    /**
     * 渲染纯文本。字段顺序固定、时间用固定时区格式化，因此**同一份 fields 两次渲染的 sha256 必然相同**——
     * 文书的可追溯性靠的就是这个：事后能用哈希证明"当时出具的正是这份文本"。
     */
    public static String render(Map<String, Object> fields) {
        StringBuilder text = new StringBuilder();
        text.append(WATERMARK).append('\n').append('\n');
        text.append("行政处罚决定书").append('\n');
        line(text, "文书编号", fields.get("document_no"));
        line(text, "案件编号", fields.get("case_no"));
        text.append('\n');
        line(text, "当事人", party(fields));
        line(text, "违法事由", fields.get("violation_title"));
        line(text, "法律依据", fields.get("legal_basis"));
        text.append('\n');
        line(text, "处罚种类", fields.get("penalty_type_text"));
        line(text, "罚款金额", fields.get("fine_text"));
        Object basis = fields.get("basis_text");
        if (basis != null && !String.valueOf(basis).isBlank()) line(text, "裁量说明", basis);
        text.append('\n');
        line(text, "出具单位", fields.get("issuer_org"));
        line(text, "出具人", fields.get("issued_by_name"));
        line(text, "出具时间", fields.get("issued_at_text"));
        return text.toString();
    }

    /** 当事人不详时如实写"不详"，不留空行也不编一个名字（决策 14-12）。 */
    private static String party(Map<String, Object> fields) {
        Object name = fields.get("party_name");
        if (name == null || String.valueOf(name).isBlank()) return "不详";
        return String.valueOf(name);
    }

    private static void line(StringBuilder text, String label, Object value) {
        text.append(label).append('：').append(value == null ? "" : String.valueOf(value)).append('\n');
    }

    /**
     * 组装结构化字段。金额以分存储，这里换算成元展示；
     * 换算只发生在展示层，库里始终是分——避免小数在存储层滚雪球。
     */
    public static Map<String, Object> fields(String documentNo, String caseNo, String partyName, String violationTitle,
            String legalBasis, String penaltyType, long fineAmountCents, String basisText, String issuerOrg,
            String issuedByName, Instant issuedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("document_no", documentNo);
        fields.put("case_no", caseNo);
        fields.put("party_name", partyName);
        fields.put("violation_title", violationTitle);
        fields.put("legal_basis", legalBasis);
        fields.put("penalty_type", penaltyType);
        fields.put("penalty_type_text", penaltyTypeText(penaltyType));
        fields.put("fine_amount", fineAmountCents);
        fields.put("fine_text", fineText(penaltyType, fineAmountCents));
        fields.put("basis_text", basisText);
        fields.put("issuer_org", issuerOrg);
        fields.put("issued_by_name", issuedByName);
        fields.put("issued_at", issuedAt.toEpochMilli());
        fields.put("issued_at_text", DAY.format(issuedAt));
        fields.put("template_version", TEMPLATE_VERSION);
        fields.put("watermark", WATERMARK);
        return fields;
    }

    private static String penaltyTypeText(String penaltyType) {
        return switch (penaltyType) {
            case PunishmentRules.WARNING -> "警告";
            case PunishmentRules.FINE -> "罚款";
            case PunishmentRules.WARNING_AND_FINE -> "警告并处罚款";
            default -> penaltyType;
        };
    }

    /** 只警告时如实写"无"，不写"0.00 元"——那会让人以为罚了款只是金额为零。 */
    private static String fineText(String penaltyType, long cents) {
        if (PunishmentRules.WARNING.equals(penaltyType) || cents == 0) return "无";
        return String.format("%d.%02d 元", cents / 100, Math.abs(cents % 100));
    }

    public static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot hash decision document", ex);
        }
    }
}
