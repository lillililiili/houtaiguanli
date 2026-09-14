package com.uav.lowaltitude.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 契约 §2 的来源类型目录（`source_type_catalog`）的唯一权威口径，H2 与 PostgreSQL 共用。
 *
 * 为什么抽成夹具：阶段 8.5 迁移 070 把目录从五行扩到八行时，六个测试各自写死了同一份列表，
 * 一处漏改就得靠失败去发现；下次目录再变（联调后某路转 CONFIRMED、或再增来源）仍会重演。
 * 断言集中在这里，测试只引用——但**语义不放宽**：仍是"恰好这八个、按字典序、只有雷达 CONFIRMED、其余 DEMO"。
 */
public final class SourceTypeCatalogFixture {

    /** 迁移 050 的五个 + 迁移 070 按凌云协议 A v8.6 补的三个，按 `order by source_type` 的字典序排列。 */
    public static final List<String> EXPECTED_TYPES = List.of(
            "AOA", "DCD", "EO", "FIVE_G_A", "FUSION_BOX", "RADAR", "RID", "TDOA");

    /** 只有雷达有协议资料（T02 v3.0.0）；其余七种字段为 Demo（凌云三路已有出处但未联调），页面与文档必须标注待确认。 */
    public static final List<String> CONFIRMED_TYPES = List.of("RADAR");

    /** 迁移 070 新增的三行：凌云协议给了字段但未联调，必须仍标 DEMO。 */
    public static final List<String> STAGE85_TYPES = List.of("AOA", "DCD", "RID");

    private SourceTypeCatalogFixture() {
    }

    /**
     * 目录的完整断言：行集恰好是 {@link #EXPECTED_TYPES}（含顺序与行数）、CONFIRMED 恰好是 {@link #CONFIRMED_TYPES}、
     * 其余全部 DEMO、没有第三种状态、每行都有 `spec_ref`。
     */
    public static void assertCatalog(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select source_type, schema_status, spec_ref from source_type_catalog order by source_type");
        assertThat(rows).extracting(row -> row.get("source_type"))
                .as("契约 §2 的来源类型目录必须恰好是这八种（字典序）")
                .containsExactlyElementsOf(EXPECTED_TYPES);
        assertThat(rows).filteredOn(row -> "CONFIRMED".equals(row.get("schema_status")))
                .extracting(row -> row.get("source_type"))
                .as("只有雷达有协议资料，其余未联调不得标 CONFIRMED")
                .containsExactlyElementsOf(CONFIRMED_TYPES);
        assertThat(rows).filteredOn(row -> !CONFIRMED_TYPES.contains(row.get("source_type")))
                .as("非 CONFIRMED 的来源必须自述为 DEMO，不允许第三种状态")
                .allSatisfy(row -> assertThat(row.get("schema_status")).isEqualTo("DEMO"));
        assertThat(rows).as("每种来源都要能追到协议出处")
                .allSatisfy(row -> assertThat(String.valueOf(row.get("spec_ref"))).isNotBlank().isNotEqualTo("null"));
    }
}
