package com.uav.lowaltitude.modules.punishment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 决定书渲染：确定性、水印、以及"不补条款号"这条纪律（决策 14-10 / 14-20）。 */
class DecisionDocumentTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-09-08T02:00:00Z");

    private static Map<String, Object> fields() {
        return DecisionDocumentRenderer.fields("CASE-20260908-0001-DEC-01", "CASE-20260908-0001", "张某",
                "未经批准擅自飞行", "《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）",
                PunishmentRules.WARNING_AND_FINE, 50000, "初次违法，未造成后果", "东营市低空安全管理平台",
                "值班员甲", ISSUED_AT);
    }

    @Test
    void sameFieldsRenderToTheSameHash() {
        String first = DecisionDocumentRenderer.render(fields());
        String second = DecisionDocumentRenderer.render(fields());
        // 文书的可追溯性靠这个：事后能用哈希证明"当时出具的正是这份文本"。
        assertThat(DecisionDocumentRenderer.sha256(first)).isEqualTo(DecisionDocumentRenderer.sha256(second));
        assertThat(DecisionDocumentRenderer.sha256(first)).hasSize(64);
    }

    @Test
    void watermarkIsTheFirstLine() {
        String text = DecisionDocumentRenderer.render(fields());
        // 水印必须是第一行：读到任何内容之前先看到"这不是一份可用的处罚文书"。
        assertThat(text.lines().findFirst().orElseThrow()).isEqualTo(DecisionDocumentRenderer.WATERMARK);
        assertThat(text).contains("演示模板", "未经授权出具", "金额档位未确认");
    }

    @Test
    void legalBasisIsCarriedThroughVerbatimWithoutInventingAnArticle() {
        String text = DecisionDocumentRenderer.render(fields());
        // 决策 14-20：原样带出，不补条款号。水印能说明"未获授权出具"，说明不了"这条法条是编的"。
        assertThat(text).contains("《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）");
        assertThat(text).doesNotContain("第一条", "第二条", "第十条");
    }

    @Test
    void amountsAreRenderedFromCentsAndWarningShowsNone() {
        assertThat(DecisionDocumentRenderer.render(fields())).contains("500.00 元");
        Map<String, Object> warning = DecisionDocumentRenderer.fields("D", "C", "张某", "实名登记信息不符",
                "《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）", PunishmentRules.WARNING, 0, null,
                "东营市低空安全管理平台", "值班员甲", ISSUED_AT);
        // 只警告时写"无"而不是"0.00 元"——后者会让人以为罚了款只是金额为零。
        assertThat(DecisionDocumentRenderer.render(warning)).contains("罚款金额：无").doesNotContain("0.00 元");
    }

    @Test
    void unknownPartyIsStatedRatherThanLeftBlank() {
        Map<String, Object> unknown = DecisionDocumentRenderer.fields("D", "C", null, "其他违反飞行管理规定的行为",
                "《无人驾驶航空器飞行管理暂行条例》（条款号待法制岗核定）", PunishmentRules.FINE, 20000, null,
                "东营市低空安全管理平台", "值班员甲", ISSUED_AT);
        // 当事人不详就写"不详"：留空会让人以为漏填，编一个名字更糟（决策 14-12）。
        assertThat(DecisionDocumentRenderer.render(unknown)).contains("当事人：不详");
    }
}
