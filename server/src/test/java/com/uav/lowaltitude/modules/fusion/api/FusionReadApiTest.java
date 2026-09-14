package com.uav.lowaltitude.modules.fusion.api;

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

/** 阶段 8 读接口：目标详情追加的可空字段、分层/点种类过滤、被并目标别名、来源在线状态、越权与权限边界。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FusionReadApiTest {
    private static final String CONFIG = "demo-v1";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private FusionFixture fixture;
    private String suffix, org, district, otherOrg, otherDistrict;
    private String radar, tdoa, eo;
    private String fusedTarget, plainTarget, mergedTarget, crossTarget;
    private String fusedTrackId, rawTrackId;
    private String reader, readerWithFusion;

    @BeforeEach
    void seed() {
        fixture = new FusionFixture(jdbc);
        suffix = FusionFixture.id().substring(0, 8);
        org = FusionFixture.id(); district = FusionFixture.id(); otherOrg = FusionFixture.id(); otherDistrict = FusionFixture.id();
        fixture.org(org, "ORG-F-" + suffix); fixture.district(district, "DIST-F-" + suffix);
        fixture.org(otherOrg, "ORG-G-" + suffix); fixture.district(otherDistrict, "DIST-G-" + suffix);
        radar = FusionFixture.id(); tdoa = FusionFixture.id(); eo = FusionFixture.id();
        fixture.source(radar, "SRC-RADAR-" + suffix, "replay", "RADAR");
        fixture.source(tdoa, "SRC-TDOA-" + suffix, "replay", "TDOA");
        fixture.source(eo, "SRC-EO-" + suffix, "replay", "EO");

        fusedTarget = FusionFixture.id(); plainTarget = FusionFixture.id(); mergedTarget = FusionFixture.id(); crossTarget = FusionFixture.id();
        fixture.target(fusedTarget, "TGT-F-" + suffix, "UAV", "replay", org, district, 3);
        fixture.target(plainTarget, "TGT-P-" + suffix, null, "replay", org, district, 0);
        fixture.target(mergedTarget, "TGT-M-" + suffix, "UAV", "replay", org, district, 1);
        fixture.target(crossTarget, "TGT-X-" + suffix, "UAV", "replay", otherOrg, otherDistrict, 0);
        fixture.latestState(fusedTarget, 118.6, 37.4, 0.8, "[]");
        // 不可判定目标：fusion_confidence 为空并在 unknown_fields 里记 UNSUPPORTED。
        fixture.latestState(plainTarget, 118.61, 37.41, null, "[{\"field\":\"fusion_confidence\",\"reason_code\":\"UNSUPPORTED\"}]");

        String linkRadar = fixture.link(fusedTarget, radar, "session-" + suffix, "ext-radar");
        fixture.link(fusedTarget, tdoa, "session-" + suffix, "ext-tdoa");
        rawTrackId = fixture.rawTrack(fusedTarget, linkRadar, "raw-" + suffix);
        fixture.point(rawTrackId, 1, "MEAS", 118.6, 37.4, null, null, null);
        fusedTrackId = fixture.fusedTrack(fusedTarget, CONFIG);
        fixture.point(fusedTrackId, 1, "MEAS", 118.6, 37.4, "[{\"source_id\":\"" + radar + "\",\"weight\":0.7}]", radar, "THREE_SOURCE");
        fixture.point(fusedTrackId, 2, "BRIDGE", 118.601, 37.401, "[]", radar, "THREE_SOURCE");
        fixture.point(fusedTrackId, 3, "PRED", 118.602, 37.402, "[]", null, "NONE");

        fixture.trackStatus(fusedTarget, "STABLE");
        fixture.degradation(fusedTarget, "THREE_SOURCE", "[\"" + radar + "\",\"" + tdoa + "\"]", 0.0, true);
        fixture.selection(fusedTarget, radar, eo, tdoa, CONFIG, false);
        String lineageId = fixture.lineage("MERGE", fusedTarget, null, "[\"" + mergedTarget + "\"]", CONFIG, null);
        fixture.alias(mergedTarget, fusedTarget, lineageId);
        fixture.observation(radar, "RADAR", "session-" + suffix, "ext-radar", FusionFixture.T0.plusSeconds(5), "replay", org, district);

        reader = fixture.session(fixture.role("R-" + suffix, "target:read"), org, district, "ASSIGNED");
        readerWithFusion = fixture.session(fixture.role("RF-" + suffix, "target:read", "fusion:read"), org, district, "ASSIGNED");
    }

    @Test
    void targetDetailCarriesNullableFusionFieldsAndOmitsThemWhenEngineNeverRan() throws Exception {
        JsonNode fused = data("/api/v1/targets/" + fusedTarget, reader);
        assertThat(fused.path("track_status").path("status").asText()).isEqualTo("STABLE");
        assertThat(fused.path("track_status").path("since").asLong()).isEqualTo(FusionFixture.T0.toInstant().toEpochMilli());
        assertThat(fused.path("degradation").path("level").asText()).isEqualTo("THREE_SOURCE");
        assertThat(fused.path("degradation").path("determined").asBoolean()).isTrue();
        assertThat(fused.path("degradation").path("available_sources")).hasSize(2);
        assertThat(fused.path("degradation").path("available_sources").get(0).asText()).startsWith("SRC-");
        // 属性优选对外只给来源编码，不暴露内部来源 ID。
        assertThat(fused.path("attribute_selection").path("position_source_code").asText()).isEqualTo("SRC-RADAR-" + suffix);
        assertThat(fused.path("attribute_selection").path("class_source_code").asText()).isEqualTo("SRC-EO-" + suffix);
        assertThat(fused.path("attribute_selection").path("identity_source_code").asText()).isEqualTo("SRC-TDOA-" + suffix);
        assertThat(fused.path("attribute_selection").path("manual_class_override").asBoolean()).isFalse();
        // 阶段 8 的新块只给来源编码；source_links.source_id 是阶段 2 既有字段，不在本条约束内。
        assertThat(fused.path("attribute_selection").toString()).doesNotContain(radar);
        assertThat(fused.path("degradation").toString()).doesNotContain(radar);
        assertThat(fused.path("lineage_summary").path("current_target_id").asText()).isEqualTo(fusedTarget);
        assertThat(fused.path("source_links").get(0).path("source_type").asText()).isNotEmpty();
        assertThat(fused.path("source_links").get(0).path("schema_status").asText()).isIn("CONFIRMED", "DEMO");

        // 引擎没接管过的目标：阶段 8 字段一律缺省，不返回零值。
        JsonNode plain = data("/api/v1/targets/" + plainTarget, reader);
        assertThat(plain.has("track_status")).isFalse();
        assertThat(plain.has("degradation")).isFalse();
        assertThat(plain.has("attribute_selection")).isFalse();
        assertThat(plain.has("lineage_summary")).isFalse();
        assertThat(plain.path("latest_state").has("fusion_confidence")).isFalse();
        assertThat(plain.path("latest_state").path("field_issues").get(0).path("field").asText()).isEqualTo("fusion_confidence");
        assertThat(plain.path("latest_state").path("field_issues").get(0).path("reason_code").asText()).isEqualTo("UNSUPPORTED");
    }

    @Test
    void mergedTargetStillResolvesAndPointsToSurvivor() throws Exception {
        // 被并目标不删不改名：详情仍 200，但血缘摘要告诉调用方当前目标是谁。
        JsonNode merged = data("/api/v1/targets/" + mergedTarget, reader);
        assertThat(merged.path("target_id").asText()).isEqualTo(mergedTarget);
        assertThat(merged.path("lineage_summary").path("current_target_id").asText()).isEqualTo(fusedTarget);
        assertThat(merged.path("lineage_summary").path("current_target_id").asText()).isNotEqualTo(mergedTarget);
        assertThat(merged.path("lineage_summary").path("op_count").asLong()).isEqualTo(1);
        assertThat(merged.path("lineage_summary").path("last_op").asText()).isEqualTo("MERGE");
    }

    @Test
    void tracksExposeBothLayersAndFilterByLayer() throws Exception {
        JsonNode all = data("/api/v1/targets/" + fusedTarget + "/tracks?size=100", reader);
        assertThat(all.path("total").asLong()).isEqualTo(2);
        JsonNode fusedOnly = data("/api/v1/targets/" + fusedTarget + "/tracks?layer=FUSED", reader);
        assertThat(fusedOnly.path("total").asLong()).isEqualTo(1);
        JsonNode item = fusedOnly.path("items").get(0);
        assertThat(item.path("track_id").asText()).isEqualTo(fusedTrackId);
        assertThat(item.path("layer").asText()).isEqualTo("FUSED");
        assertThat(item.path("config_version").asText()).isEqualTo(CONFIG);
        // 融合层没有 link/source：这些字段必须缺省而不是空串。
        assertThat(item.has("link_id")).isFalse();
        assertThat(item.has("source_id")).isFalse();
        assertThat(item.has("source_code")).isFalse();
        JsonNode rawOnly = data("/api/v1/targets/" + fusedTarget + "/tracks?layer=RAW", reader);
        assertThat(rawOnly.path("total").asLong()).isEqualTo(1);
        assertThat(rawOnly.path("items").get(0).path("layer").asText()).isEqualTo("RAW");
        assertThat(rawOnly.path("items").get(0).path("link_id").asText()).isNotEmpty();
        error("/api/v1/targets/" + fusedTarget + "/tracks?layer=BOGUS", reader, 400, "VALIDATION_ERROR");
    }

    @Test
    void pointsDefaultToMeasuredAndBridgedAndIncludePredOnlyWhenRequested() throws Exception {
        JsonNode defaults = data("/api/v1/tracks/" + fusedTrackId + "/points?size=100", reader);
        assertThat(defaults.path("total").asLong()).isEqualTo(2);
        assertThat(defaults.path("items")).allSatisfy(p -> assertThat(p.path("point_kind").asText()).isIn("MEAS", "BRIDGE"));
        JsonNode measured = defaults.path("items").get(0);
        assertThat(measured.path("position_accuracy_m").asDouble()).isEqualTo(14.20);
        assertThat(measured.path("contributing").get(0).path("source_code").asText()).isEqualTo("SRC-RADAR-" + suffix);
        assertThat(measured.path("contributing").get(0).path("weight").asDouble()).isEqualTo(0.7);
        assertThat(measured.path("degradation_level").asText()).isEqualTo("THREE_SOURCE");
        assertThat(measured.path("contributing").toString()).doesNotContain(radar);

        JsonNode withPred = data("/api/v1/tracks/" + fusedTrackId + "/points?kind=MEAS,BRIDGE,PRED&size=100", reader);
        assertThat(withPred.path("total").asLong()).isEqualTo(3);
        JsonNode predOnly = data("/api/v1/tracks/" + fusedTrackId + "/points?kind=PRED", reader);
        assertThat(predOnly.path("total").asLong()).isEqualTo(1);
        assertThat(predOnly.path("items").get(0).path("point_kind").asText()).isEqualTo("PRED");
        error("/api/v1/tracks/" + fusedTrackId + "/points?kind=GUESS", reader, 400, "VALIDATION_ERROR");
        // 原始层点没有阶段 8 字段（迁移默认 MEAS，其余为空）。
        JsonNode rawPoint = data("/api/v1/tracks/" + rawTrackId + "/points", reader).path("items").get(0);
        assertThat(rawPoint.path("point_kind").asText()).isEqualTo("MEAS");
        assertThat(rawPoint.has("contributing")).isFalse();
    }

    @Test
    void lineageNeedsFusionReadAndObservationsNeedOnlyTargetRead() throws Exception {
        error("/api/v1/targets/" + fusedTarget + "/lineage", reader, 403, "FORBIDDEN");
        JsonNode lineage = data("/api/v1/targets/" + fusedTarget + "/lineage", readerWithFusion);
        assertThat(lineage.path("total").asLong()).isEqualTo(1);
        assertThat(lineage.path("items").get(0).path("op").asText()).isEqualTo("MERGE");
        assertThat(lineage.path("items").get(0).path("member_target_ids").get(0).asText()).isEqualTo(mergedTarget);

        JsonNode observations = data("/api/v1/targets/" + fusedTarget + "/observations", reader);
        assertThat(observations.path("total").asLong()).isEqualTo(1);
        assertThat(observations.path("items").get(0).path("source_code").asText()).isEqualTo("SRC-RADAR-" + suffix);
        assertThat(observations.path("items").get(0).path("source_type").asText()).isEqualTo("RADAR");
        assertThat(observations.path("items").get(0).path("position_accuracy_m").asDouble()).isEqualTo(15.0);
        // 跨范围目标一律 404，不区分“不存在”与“不可见”。
        error("/api/v1/targets/" + crossTarget + "/observations", reader, 404, "TARGET_NOT_FOUND");
        error("/api/v1/targets/" + crossTarget + "/lineage", readerWithFusion, 404, "TARGET_NOT_FOUND");
    }

    /**
     * 阶段 8.5：观测的飞手位置与类别来源要露给读侧——页面据此说明"这个类别是光电判的还是感知数据报的"。
     * 两列可空，缺失时整个字段不下发（不出现 null 占位）。
     */
    @Test
    void observationsExposePilotLocationAndClassSourceWhenPresent() throws Exception {
        JsonNode before = data("/api/v1/targets/" + fusedTarget + "/observations", reader).path("items").get(0);
        assertThat(before.has("pilot_location")).as("没有飞手位置就不下发该字段").isFalse();
        assertThat(before.has("class_source")).isFalse();

        // 观测经 target_source_link 挂到目标上，本身没有 target_id 列。
        jdbc.update("update source_observation set pilot_location=CAST('SRID=4326;POINT (118.5 37.4)' AS GEOMETRY), class_source='SENSE_DATA'"
                + " where observation_id in (select o.observation_id from source_observation o"
                + " join target_source_link l on l.source_id=o.source_id and l.source_session_key=o.source_session_key"
                + " and l.external_target_id=o.external_target_id where l.target_id=?)", fusedTarget);
        JsonNode after = data("/api/v1/targets/" + fusedTarget + "/observations", reader).path("items").get(0);
        assertThat(after.path("pilot_location").path("longitude").decimalValue()).isEqualByComparingTo("118.5");
        assertThat(after.path("pilot_location").path("latitude").decimalValue()).isEqualByComparingTo("37.4");
        assertThat(after.path("pilot_location").path("coordinate_system").asText()).isEqualTo("WGS84");
        assertThat(after.path("class_source").asText()).isEqualTo("SENSE_DATA");
    }

    @Test
    void fusionStatusReportsSourcesAndDataInterruption() throws Exception {
        JsonNode status = data("/api/v1/fusion/status", reader);
        assertThat(status.path("as_of").asLong()).isPositive();
        // 观测时间是固定的 2026-09-05，早于 short_lost_after_ms：所有来源离线即数据中断。
        assertThat(status.path("data_interrupted").asBoolean()).isTrue();
        JsonNode mine = null;
        for (JsonNode source : status.path("available_sources")) if (("SRC-RADAR-" + suffix).equals(source.path("source_code").asText())) mine = source;
        assertThat(mine).isNotNull();
        assertThat(mine.path("source_type").asText()).isEqualTo("RADAR");
        assertThat(mine.path("schema_status").asText()).isEqualTo("CONFIRMED");
        assertThat(mine.path("online").asBoolean()).isFalse();
        assertThat(mine.path("last_observed_at").asLong()).isEqualTo(FusionFixture.T0.plusSeconds(5).toInstant().toEpochMilli());
    }

    @Test
    void configRequiresFusionRead() throws Exception {
        error("/api/v1/fusion/config", reader, 403, "FORBIDDEN");
        JsonNode config = data("/api/v1/fusion/config", readerWithFusion);
        assertThat(config.path("active").path("config_version").asText()).isEqualTo(CONFIG);
        assertThat(config.path("active").path("schema_status").asText()).isEqualTo("DEMO");
        assertThat(config.path("active").path("params").path("degradation").path("undetermined_deficit").asDouble()).isEqualTo(0.5);
    }

    private JsonNode data(String path, String session) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private void error(String path, String session, int expectedStatus, String code) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + session))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value(code));
    }
}
