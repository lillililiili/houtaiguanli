package com.uav.lowaltitude.modules.risk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 空间安全风险读侧：细类字典、可空空间事实、汇总口径与越权边界。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SpaceRiskReadApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private SpaceRiskFixture fixture;
    private String suffix, org, district, otherOrg, otherDistrict, source;
    private String spaceRisk, plainRisk, crossRisk;
    private String reader, otherReader;

    @BeforeEach
    void seed() {
        fixture = new SpaceRiskFixture(jdbc);
        suffix = SpaceRiskFixture.id().substring(0, 8);
        org = SpaceRiskFixture.id(); district = SpaceRiskFixture.id();
        otherOrg = SpaceRiskFixture.id(); otherDistrict = SpaceRiskFixture.id();
        fixture.org(org, "ORG-S9-" + suffix); fixture.district(district, "DIST-S9-" + suffix);
        fixture.org(otherOrg, "ORG-S9B-" + suffix); fixture.district(otherDistrict, "DIST-S9B-" + suffix);
        source = SpaceRiskFixture.id();
        fixture.source(source, "SRC-S9-" + suffix, "mock");

        String plan = fixture.planWithRoute(org, district, suffix);
        String routeVersion = fixture.routeVersionOf(plan);
        String otherPlan = fixture.planWithRoute(otherOrg, otherDistrict, suffix + "b");
        String otherRouteVersion = fixture.routeVersionOf(otherPlan);
        String target = fixture.target(org, district, "BIRD_FLOCK", suffix);

        spaceRisk = fixture.risk(plan, routeVersion, source, target, "SPACE_OBJECT", "HIGH", "PENDING_VERIFICATION", org, district, "space-" + suffix);
        fixture.spaceFact(spaceRisk, "BIRD_FLOCK", "INSIDE", "CLIMB", 30);
        plainRisk = fixture.risk(plan, routeVersion, source, null, "FLIGHT_OPERATION", "MEDIUM", "PENDING_VERIFICATION", org, district, "plain-" + suffix);
        crossRisk = fixture.risk(otherPlan, otherRouteVersion, source, null, "SPACE_OBJECT", "HIGH", "PENDING_VERIFICATION", otherOrg, otherDistrict, "cross-" + suffix);
        fixture.spaceFact(crossRisk, "BALLOON", "NEAR", "UNKNOWN", 1);

        reader = fixture.session(fixture.role("R-" + suffix, "risk:read"), org, district, "ASSIGNED");
        otherReader = fixture.session(fixture.role("RB-" + suffix, "risk:read"), otherOrg, otherDistrict, "ASSIGNED");
    }

    @Test
    void subtypeDictionaryHasFiveEnabledEntriesWithChineseAliases() throws Exception {
        JsonNode data = data("/api/v1/space-object-subtypes", reader);
        assertThat(data).hasSize(5);
        assertThat(data).extracting(node -> node.path("subtype_code").asText())
                .containsExactly("BIRD_FLOCK", "BALLOON", "KITE", "SKY_LANTERN", "OTHER_OBJECT");
        JsonNode flock = data.get(0);
        assertThat(flock.path("display_name").asText()).isEqualTo("鸟群");
        assertThat(flock.path("enabled").asBoolean()).isTrue();
        assertThat(flock.path("aliases")).extracting(JsonNode::asText).contains("鸟群", "鸟", "候鸟");
    }

    @Test
    void riskListFiltersByTypeAndSubtypeAndCarriesNullableSpaceFact() throws Exception {
        JsonNode spaceOnly = data("/api/v1/risks?risk_type=SPACE_OBJECT&size=100", reader);
        assertThat(ids(spaceOnly.path("items"))).containsExactly(spaceRisk);
        JsonNode item = spaceOnly.path("items").get(0);
        assertThat(item.path("space_fact").path("subtype_code").asText()).isEqualTo("BIRD_FLOCK");
        assertThat(item.path("space_fact").path("subtype_name").asText()).isEqualTo("鸟群");
        assertThat(item.path("space_fact").path("corridor_relation").asText()).isEqualTo("INSIDE");
        assertThat(item.path("space_fact").path("altitude_band").asText()).isEqualTo("CLIMB");
        assertThat(item.path("space_fact").path("object_count").asInt()).isEqualTo(30);
        assertThat(item.path("space_fact").path("rule_set_version_no").asInt()).isEqualTo(1);

        // 作业风险没有空间事实：字段整体缺省，不返回空对象。
        JsonNode plain = data("/api/v1/risks?risk_type=FLIGHT_OPERATION&size=100", reader);
        assertThat(ids(plain.path("items"))).containsExactly(plainRisk);
        assertThat(plain.path("items").get(0).has("space_fact")).isFalse();

        assertThat(ids(data("/api/v1/risks?object_subtype=BIRD_FLOCK&size=100", reader).path("items"))).containsExactly(spaceRisk);
        assertThat(data("/api/v1/risks?object_subtype=KITE&size=100", reader).path("items")).isEmpty();
        // risk_type 是自由文本：未出现过的取值筛不到结果，而不是参数错误。
        assertThat(data("/api/v1/risks?risk_type=NOPE&size=100", reader).path("items")).isEmpty();
    }

    @Test
    void spaceFactEndpointIsNotFoundForPlainRiskAndForCrossScopeRisk() throws Exception {
        JsonNode fact = data("/api/v1/risks/" + spaceRisk + "/space-fact", reader);
        assertThat(fact.path("risk_id").asText()).isEqualTo(spaceRisk);
        assertThat(fact.path("trend").asText()).isEqualTo("FLAT");
        // 决策 9-19：评估时刻的位置快照随事实一起返回，页面据此在地图上按等级标点。
        assertThat(fact.path("longitude").asDouble()).isEqualTo(118.021);
        assertThat(fact.path("latitude").asDouble()).isEqualTo(37.021);
        assertThat(fact.path("target_altitude_raw").asDouble()).isEqualTo(120.0);
        error("/api/v1/risks/" + plainRisk + "/space-fact", reader, 404, "SPACE_FACT_NOT_FOUND");
        // 越权风险一律 404 且用风险自身的错误码，不能因为"有没有空间事实"泄露它存在。
        error("/api/v1/risks/" + crossRisk + "/space-fact", reader, 404, "RISK_NOT_FOUND");
    }

    @Test
    void missingFactsAreReportedAndRisksWithoutCoordinatesOmitThePoint() throws Exception {
        // 决策 9-18：数量与趋势没有数据源时如实记录；决策 9-19：没有坐标就不给点，页面不画。
        String plan = fixture.planWithRoute(org, district, suffix + "c");
        String bare = fixture.risk(plan, fixture.routeVersionOf(plan), source, null, "SPACE_OBJECT", "MEDIUM",
                "PENDING_VERIFICATION", org, district, "bare-" + suffix);
        fixture.spaceFact(bare, "BALLOON", "NEAR", "UNKNOWN", null,
                "[\"OBJECT_COUNT_UNAVAILABLE\",\"TREND_UNAVAILABLE\"]", null, null);
        JsonNode fact = data("/api/v1/risks/" + bare + "/space-fact", reader);
        assertThat(fact.path("unknown_reasons")).extracting(JsonNode::asText)
                .containsExactly("OBJECT_COUNT_UNAVAILABLE", "TREND_UNAVAILABLE");
        assertThat(fact.has("longitude")).isFalse();
        assertThat(fact.has("latitude")).isFalse();
        assertThat(fact.has("object_count")).isFalse();
    }

    @Test
    void summaryCountsOnlyOwnScopeAndReportsNullWhenThereIsNoData() throws Exception {
        JsonNode mine = data("/api/v1/space-risks/summary", reader);
        assertThat(mine.path("total").path("value").asLong()).isEqualTo(1);
        assertThat(mine.path("high_severity").path("value").asLong()).isEqualTo(1);
        assertThat(mine.path("medium_severity").path("value").asLong()).isZero();
        assertThat(mine.path("bird_events").path("value").asLong()).isEqualTo(1);
        assertThat(mine.path("pending_verification").path("value").asLong()).isEqualTo(1);
        assertThat(mine.path("routes_involved").path("value").asLong()).isEqualTo(1);
        assertThat(mine.path("by_subtype").get(0).path("bucket").asText()).isEqualTo("BIRD_FLOCK");
        assertThat(mine.path("rule_version").path("rule_set_code").asText()).isEqualTo("SPACE-RISK-DEMO");
        assertThat(mine.path("rule_version").path("version_no").asInt()).isEqualTo(1);
        assertThat(mine.path("rule_version").path("param_status").asText()).isEqualTo("DEMO");
        assertThat(mine.path("as_of").asLong()).isPositive();

        // 另一范围只看得到自己的那条；两边的计数互不相加。
        JsonNode theirs = data("/api/v1/space-risks/summary", otherReader);
        assertThat(theirs.path("total").path("value").asLong()).isEqualTo(1);
        assertThat(theirs.path("by_subtype").get(0).path("bucket").asText()).isEqualTo("BALLOON");

        // 窗口内没有任何数据时给 null + availability，而不是 0：没统计到不等于没有风险。
        long farFrom = SpaceRiskFixture.T0.minusDays(30).toInstant().toEpochMilli();
        long farTo = SpaceRiskFixture.T0.minusDays(29).toInstant().toEpochMilli();
        JsonNode empty = data("/api/v1/space-risks/summary?from=" + farFrom + "&to=" + farTo, reader);
        assertThat(empty.path("total").has("value")).isFalse();
        assertThat(empty.path("total").path("availability").asText()).isEqualTo("NO_DATA");
        assertThat(empty.path("high_severity").has("value")).isFalse();
    }

    @Test
    void readingNeedsRiskReadPermission() throws Exception {
        String denied = fixture.session(fixture.role("D-" + suffix), org, district, "ASSIGNED");
        error("/api/v1/space-object-subtypes", denied, 403, "FORBIDDEN");
        error("/api/v1/space-risks/summary", denied, 403, "FORBIDDEN");
        error("/api/v1/risks/" + spaceRisk + "/space-fact", denied, 403, "FORBIDDEN");
    }

    private JsonNode data(String path, String session) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private void error(String path, String session, int expectedStatus, String code) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + session))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.error.code").value(code));
    }

    private static java.util.List<String> ids(JsonNode items) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        items.forEach(item -> ids.add(item.path("risk_id").asText()));
        return ids;
    }
}
