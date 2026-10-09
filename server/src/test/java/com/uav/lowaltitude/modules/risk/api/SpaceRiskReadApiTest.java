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
    void summaryCanExcludePresetSamplesWithoutDroppingRuleEvaluatedMockRisks() throws Exception {
        String samplePlan = fixture.planWithRoute(org, district, suffix + "demo");
        String sample = fixture.risk(samplePlan, fixture.routeVersionOf(samplePlan), source, null,
                "SPACE_OBJECT", "HIGH", "PENDING_NOTIFICATION", org, district, "demo-" + suffix);
        jdbc.update("update flight_risk set source_risk_id=? where risk_id=?", "pending-plan-notice-demo-" + suffix, sample);
        fixture.spaceFact(sample, "BIRD_FLOCK", "INSIDE", "UNKNOWN", 20);
        assertThat(data("/api/v1/space-risks/summary", reader).path("routes_involved").path("value").asLong()).isEqualTo(2);
        assertThat(data("/api/v1/space-risks/summary?exclude_demo_samples=false", reader).path("total").path("value").asLong()).isEqualTo(2);
        JsonNode filtered = data("/api/v1/space-risks/summary?exclude_demo_samples=true", reader);
        assertThat(filtered.path("total").path("value").asLong()).isEqualTo(1);
        assertThat(filtered.path("routes_involved").path("value").asLong()).isEqualTo(1);
        assertThat(filtered.path("by_subtype").get(0).path("count").asLong()).isEqualTo(1);
        assertThat(filtered.path("bird_events").path("value").asLong()).isEqualTo(1);
        assertThat(filtered.path("pending_verification").path("value").asLong()).isEqualTo(1);
        for (String query : new String[]{"exclude_demo_samples=1", "exclude_demo_samples=", "exclude_demo_samples=true&exclude_demo_samples=false"}) {
            error("/api/v1/space-risks/summary?" + query, reader, 400, "VALIDATION_ERROR");
        }
    }

    /** P03：鸟群风险的评估历史按时间先后分页，合计次数和最早、最近时刻覆盖全部段；不是 C04 风险的不显示这一栏。 */
    @Test
    void evaluationHistoryListsSegmentsInTimeOrderOnlyForBirdRisksInScope() throws Exception {
        fixture.segment(spaceRisk, 1, SpaceRiskFixture.T0, SpaceRiskFixture.T0.plusMinutes(4), 5, "120.40", "141.00", "NEAR", true, "MEDIUM", true);
        fixture.segment(spaceRisk, 2, SpaceRiskFixture.T0.plusMinutes(5), SpaceRiskFixture.T0.plusMinutes(6), 2, "18.20", "24.00", "INSIDE", true, "HIGH", false);
        fixture.segment(spaceRisk, 3, SpaceRiskFixture.T0.plusMinutes(7), SpaceRiskFixture.T0.plusMinutes(7), 1, null, null, "UNKNOWN", false, null, false);

        JsonNode all = data("/api/v1/risks/" + spaceRisk + "/evaluation-history", reader);
        assertThat(all.path("applicable").asBoolean()).isTrue();
        assertThat(all.path("evaluation_count").asLong()).isEqualTo(8);
        assertThat(all.path("first_evaluated_at").asLong()).isEqualTo(SpaceRiskFixture.T0.toInstant().toEpochMilli());
        assertThat(all.path("last_evaluated_at").asLong()).isEqualTo(SpaceRiskFixture.T0.plusMinutes(7).toInstant().toEpochMilli());
        assertThat(all.path("from_detection").asBoolean()).isTrue();
        assertThat(all.path("total").asLong()).isEqualTo(3);
        assertThat(all.path("page").asInt()).isEqualTo(1);
        assertThat(all.path("size").asInt()).isEqualTo(20);
        assertThat(all.path("items")).extracting(node -> node.path("segment_no").asInt()).containsExactly(1, 2, 3);
        JsonNode near = all.path("items").get(0);
        assertThat(near.path("evaluation_count").asInt()).isEqualTo(5);
        assertThat(near.path("distance_band_m").asInt()).isEqualTo(100);
        assertThat(near.path("min_distance_m").decimalValue()).isEqualByComparingTo("120.40");
        assertThat(near.path("max_distance_m").decimalValue()).isEqualByComparingTo("141.00");
        assertThat(near.path("corridor_relation").asText()).isEqualTo("NEAR");
        assertThat(near.path("altitude_band").asText()).isEqualTo("CLIMB");
        assertThat(near.path("risk_present").asBoolean()).isTrue();
        assertThat(near.path("severity").asText()).isEqualTo("MEDIUM");
        assertThat(near.path("first_evaluated_at").asLong()).isEqualTo(SpaceRiskFixture.T0.toInstant().toEpochMilli());
        // 没有距离、不构成风险的那段：距离和等级都省略，不用 0 或空串占位。
        JsonNode unknown = all.path("items").get(2);
        assertThat(unknown.path("risk_present").asBoolean()).isFalse();
        assertThat(unknown.has("severity")).isFalse();
        assertThat(unknown.has("distance_band_m")).isFalse();
        assertThat(unknown.has("min_distance_m")).isFalse();

        JsonNode secondPage = data("/api/v1/risks/" + spaceRisk + "/evaluation-history?page=2&size=2", reader);
        assertThat(secondPage.path("items")).extracting(node -> node.path("segment_no").asInt()).containsExactly(3);
        assertThat(secondPage.path("total").asLong()).isEqualTo(3);
        assertThat(secondPage.path("evaluation_count").asLong()).as("合计不随分页变").isEqualTo(8);

        // 作业风险、机场区域风险不记评估历史：applicable=false，页面不显示这一栏。
        JsonNode plain = data("/api/v1/risks/" + plainRisk + "/evaluation-history", reader);
        assertThat(plain.path("applicable").asBoolean()).isFalse();
        assertThat(plain.path("items")).isEmpty();
        assertThat(plain.path("total").asLong()).isZero();
        String airportPlan = fixture.planWithRoute(org, district, suffix + "apt");
        String airportRisk = fixture.risk(airportPlan, fixture.routeVersionOf(airportPlan), source, null, "SPACE_OBJECT", "MEDIUM",
                "PENDING_VERIFICATION", org, district, "apt-" + suffix);
        fixture.spaceFact(airportRisk, "BALLOON", "UNKNOWN", "UNKNOWN", null, "[]", null, null, "space-risk-c05-v1");
        assertThat(data("/api/v1/risks/" + airportRisk + "/evaluation-history", reader).path("applicable").asBoolean()).isFalse();

        // 看不到的风险一律 404，不能借评估历史确认它存在；参数只认 page、size。
        error("/api/v1/risks/" + crossRisk + "/evaluation-history", reader, 404, "RISK_NOT_FOUND");
        error("/api/v1/risks/no-such-risk/evaluation-history", reader, 404, "RISK_NOT_FOUND");
        error("/api/v1/risks/" + spaceRisk + "/evaluation-history?sort=asc", reader, 400, "VALIDATION_ERROR");
        error("/api/v1/risks/" + spaceRisk + "/evaluation-history?size=101", reader, 400, "VALIDATION_ERROR");
        error("/api/v1/risks/" + spaceRisk + "/evaluation-history?page=0", reader, 400, "VALIDATION_ERROR");
    }

    /** 改动以前发现的风险：发现时那次没有记录（from_detection=false）；一次也没记时合计为 0，没有时刻。 */
    @Test
    void evaluationHistoryOfARiskFoundBeforeTheChangeSaysTheDetectionIsMissing() throws Exception {
        JsonNode empty = data("/api/v1/risks/" + spaceRisk + "/evaluation-history", reader);
        assertThat(empty.path("applicable").asBoolean()).isTrue();
        assertThat(empty.path("evaluation_count").asLong()).isZero();
        assertThat(empty.has("first_evaluated_at")).isFalse();
        assertThat(empty.has("last_evaluated_at")).isFalse();
        assertThat(empty.path("from_detection").asBoolean()).isFalse();
        assertThat(empty.path("items")).isEmpty();

        fixture.segment(spaceRisk, 1, SpaceRiskFixture.T0.plusMinutes(30), SpaceRiskFixture.T0.plusMinutes(32), 3, "60.00", "70.00", "NEAR", true, "MEDIUM", false);
        JsonNode later = data("/api/v1/risks/" + spaceRisk + "/evaluation-history", reader);
        assertThat(later.path("evaluation_count").asLong()).isEqualTo(3);
        assertThat(later.path("from_detection").asBoolean()).isFalse();
    }

    @Test
    void readingNeedsRiskReadPermission() throws Exception {
        String denied = fixture.session(fixture.role("D-" + suffix), org, district, "ASSIGNED");
        error("/api/v1/space-object-subtypes", denied, 403, "FORBIDDEN");
        error("/api/v1/space-risks/summary", denied, 403, "FORBIDDEN");
        error("/api/v1/risks/" + spaceRisk + "/space-fact", denied, 403, "FORBIDDEN");
        error("/api/v1/risks/" + spaceRisk + "/evaluation-history", denied, 403, "FORBIDDEN");
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
