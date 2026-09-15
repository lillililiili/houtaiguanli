package com.uav.lowaltitude.modules.target.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TargetReadApiTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 4, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private String suffix;
    private String role;
    private String userId;
    private String sessionId;
    private String orgA;
    private String districtA;
    private String orgB;
    private String districtB;
    private String sourceMock;
    private String sourceMockOther;
    private String sourceReplay;
    private String deviceMock;
    private String deviceMockOther;
    private String deviceReplay;
    private String targetLatest;
    private String targetUnknownTime;
    private String targetOtherScope;
    private String targetIncomplete;
    private String linkMockA;
    private String linkMockB;
    private String linkCrossMode;
    private String linkCrossDeviceMode;
    private String linkCrossSource;
    private String linkUnknownTarget;
    private String validTrack;
    private String unknownTimeTrack;
    private String crossModeTrack;
    private String crossDeviceModeTrack;
    private String crossSourceTrack;
    private String mismatchedTrack;

    @BeforeEach
    void seedContractFixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        role = "ROLE-TARGET-" + suffix;
        userId = id();
        sessionId = id();
        orgA = id();
        districtA = id();
        orgB = id();
        districtB = id();
        sourceMock = id();
        sourceMockOther = id();
        sourceReplay = id();
        deviceMock = id();
        deviceMockOther = id();
        deviceReplay = id();
        targetLatest = id();
        targetUnknownTime = id();
        targetOtherScope = id();
        targetIncomplete = id();
        linkMockA = id();
        linkMockB = id();
        linkCrossMode = id();
        linkCrossDeviceMode = id();
        linkCrossSource = id();
        linkUnknownTarget = id();
        validTrack = id();
        unknownTimeTrack = id();
        crossModeTrack = id();
        crossDeviceModeTrack = id();
        crossSourceTrack = id();
        mismatchedTrack = id();

        jdbc.update("""
                insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)
                values (?,?,'',false,true,0,0,0,false)
                """, role, "Target reader " + suffix);
        jdbc.update("""
                insert into app_role_permission
                    (role_code,permission_code,permission_level,menu_enabled,created_at)
                values (?,'target:read','READ',false,current_timestamp)
                """, role);
        org(orgA, "ORG-A-" + suffix);
        district(districtA, "DIST-A-" + suffix);
        org(orgB, "ORG-B-" + suffix);
        district(districtB, "DIST-B-" + suffix);
        jdbc.update("""
                insert into app_user
                    (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,
                     permission_version,created_at,updated_at,version)
                values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)
                """, userId, "target-reader-" + suffix, "Target reader", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)",
                userId, orgA, districtA);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)",
                sessionId, userId, System.currentTimeMillis() + 3_600_000L);

        source(sourceMock, "SRC-MOCK-" + suffix, "mock", "credential-secret-mock");
        source(sourceMockOther, "SRC-MOCK-OTHER-" + suffix, "mock", "credential-secret-other");
        source(sourceReplay, "SRC-REPLAY-" + suffix, "replay", "credential-secret-replay");
        jdbc.update("""
                insert into device
                    (device_id,source_id,external_device_id,device_no,name,enabled,source_mode,
                     owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,?, ?,?,true,'mock',?,?,?, ?,0)
                """, deviceMock, sourceMock, "EXT-DEV-" + suffix, "DEV-" + suffix, "Radar", orgA, districtA, T0, T0);
        jdbc.update("""
                insert into device
                    (device_id,source_id,external_device_id,device_no,name,enabled,source_mode,
                     owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,?, ?,?,true,'mock',?,?,?, ?,0)
                """, deviceMockOther, sourceMockOther, "EXT-OTHER-" + suffix, "DEV-OTHER-" + suffix,
                "Other radar", orgA, districtA, T0, T0);
        jdbc.update("""
                insert into device
                    (device_id,source_id,external_device_id,device_no,name,enabled,source_mode,
                     owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,?, ?,?,true,'replay',?,?,?, ?,0)
                """, deviceReplay, sourceReplay, "EXT-REPLAY-" + suffix, "DEV-REPLAY-" + suffix,
                "Replay radar", orgA, districtA, T0, T0);

        target(targetLatest, "TGT-A-" + suffix, "UAV", "quad", "SN-secret", T0.plusSeconds(1), T0.plusSeconds(9),
                "mock", orgA, districtA);
        target(targetUnknownTime, "TGT-B-" + suffix, null, null, null, null, null, "mock", orgA, districtA);
        target(targetOtherScope, "TGT-C-" + suffix, "BIRD", null, null, T0, T0.plusSeconds(20), "mock", orgB, districtB);
        target(targetIncomplete, "TGT-D-" + suffix, null, null, null, null, null, "mock", null, districtA);

        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,altitude_amsl_m,height_agl_m,speed_mps,heading_deg,
                     classification_confidence,fusion_confidence,observed_at,received_at,unknown_fields,
                     created_at,updated_at,version)
                values (?,GEOMETRY 'SRID=4326;POINT (120.125 30.25)',120.50,null,15.250,null,
                        0.80000,null,?,?,? FORMAT JSON, ?,?,0)
                """, targetLatest, T0.plusSeconds(9), T0.plusSeconds(10),
                "[{\"field\":\"heading_deg\",\"reason_code\":\"NOT_REPORTED\"},"
                        + "{\"field\":\"credential_ref\",\"reason_code\":\"NOT_REPORTED\"},"
                        + "{\"field\":\"height_agl_m\",\"reason_code\":\"REFERENCE_UNKNOWN\"},"
                        + "{\"field\":\"speed_mps\",\"reason_code\":\"BAD_REASON\"}]",
                T0.plusSeconds(10), T0.plusSeconds(10));
        jdbc.update("""
                insert into target_latest_state
                    (target_id,location,observed_at,received_at,unknown_fields,created_at,updated_at,version)
                values (?,null,?,?,'[]',?,?,0)
                """, targetOtherScope, T0.plusSeconds(20), T0.plusSeconds(21), T0.plusSeconds(21), T0.plusSeconds(21));

        link(linkMockB, targetLatest, sourceMock, deviceMock, "session-b", "ext-b", "3.0");
        link(linkMockA, targetLatest, sourceMock, deviceMock, "session-a", "ext-a", null);
        link(linkCrossMode, targetLatest, sourceReplay, null, "session-cross", "ext-cross", "9.9");
        link(linkCrossDeviceMode, targetLatest, sourceMock, deviceReplay,
                "session-cross-device", "ext-cross-device", null);
        link(linkCrossSource, targetLatest, sourceMock, deviceMockOther,
                "session-cross-source", "ext-cross-source", null);
        link(linkUnknownTarget, targetUnknownTime, sourceMock, deviceMock, "session-z", "ext-z", null);

        track(validTrack, targetLatest, linkMockA, "track-valid", T0.plusSeconds(3));
        track(unknownTimeTrack, targetLatest, linkMockB, "track-unknown", null);
        track(crossModeTrack, targetLatest, linkCrossMode, "track-cross", T0.plusSeconds(8));
        track(crossDeviceModeTrack, targetLatest, linkCrossDeviceMode, "track-cross-device", T0.plusSeconds(6));
        track(crossSourceTrack, targetLatest, linkCrossSource, "track-cross-source", T0.plusSeconds(5));
        track(mismatchedTrack, targetLatest, linkUnknownTarget, "track-mismatch", T0.plusSeconds(7));
        point(id(), validTrack, 2, T0.plusSeconds(2), T0.plusSeconds(5), 120.2, 30.2,
                "{\"raw\":\"secret-2\"}");
        point(id(), validTrack, 1, null, T0.plusSeconds(1), 120.1, 30.1,
                "{\"raw\":\"secret-1\"}");
    }

    @Test
    void listsTargetsWithStableOrderLatestStateAndNoSensitiveFields() throws Exception {
        JsonNode response = getJson("/api/v1/targets");
        JsonNode data = response.path("data");

        assertThat(data.path("page").asInt()).isEqualTo(1);
        assertThat(data.path("size").asInt()).isEqualTo(20);
        assertThat(data.path("total").asLong()).isEqualTo(2);
        assertThat(data.path("items").get(0).path("target_id").asText()).isEqualTo(targetLatest);
        assertThat(data.path("items").get(1).path("target_id").asText()).isEqualTo(targetUnknownTime);
        JsonNode latest = data.path("items").get(0).path("latest_state");
        assertThat(latest.path("observed_at").asLong()).isEqualTo(T0.plusSeconds(9).toInstant().toEpochMilli());
        assertThat(latest.path("location").path("coordinate_system").asText()).isEqualTo("WGS84");
        assertThat(latest.path("location").path("longitude").decimalValue()).isEqualByComparingTo("120.125");
        assertThat(latest.path("field_issues").findValuesAsText("field"))
                .containsExactly("heading_deg", "height_agl_m");
        assertThat(data.path("items").get(1).has("latest_state")).isFalse();
        assertNoSensitiveFields(response);
    }

    /**
     * 阶段 8.5：融合层写入的飞手位置要露给读侧（C02-6 的判定依据，页面也要能标出飞手在哪）。
     * 可空列，没有就整个字段不下发——不出现 null 占位，也不拿目标位置顶替。
     */
    @Test
    void latestStateExposesPilotLocationOnlyWhenItExists() throws Exception {
        JsonNode before = getJson("/api/v1/targets/" + targetLatest).path("data").path("latest_state");
        assertThat(before.has("pilot_location")).as("没有飞手位置就不下发该字段").isFalse();

        jdbc.update("update target_latest_state set pilot_location=GEOMETRY 'SRID=4326;POINT (120.130 30.260)' where target_id=?", targetLatest);
        JsonNode after = getJson("/api/v1/targets/" + targetLatest).path("data").path("latest_state");
        assertThat(after.path("pilot_location").path("longitude").decimalValue()).isEqualByComparingTo("120.130");
        assertThat(after.path("pilot_location").path("latitude").decimalValue()).isEqualByComparingTo("30.260");
        assertThat(after.path("pilot_location").path("coordinate_system").asText()).isEqualTo("WGS84");
    }

    @Test
    void paginatesAndFiltersTargetsWithoutSourceLinkDuplication() throws Exception {
        String sourceCode = "SRC-MOCK-" + suffix;
        JsonNode filtered = getJson("/api/v1/targets?source_code=" + sourceCode
                + "&device_id=" + deviceMock + "&object_type_code=UAV&seen_from="
                + T0.toInstant().toEpochMilli() + "&seen_to=" + T0.plusSeconds(9).toInstant().toEpochMilli());
        assertThat(filtered.path("data").path("total").asLong()).isEqualTo(1);
        assertThat(filtered.path("data").path("items")).hasSize(1);

        JsonNode emptyPage = getJson("/api/v1/targets?page=3&size=1");
        assertThat(emptyPage.path("data").path("items")).isEmpty();
        assertThat(emptyPage.path("data").path("total").asLong()).isEqualTo(2);
        JsonNode noMatches = getJson("/api/v1/targets?source_code=UNKNOWN");
        assertThat(noMatches.path("data").path("items")).isEmpty();
        assertThat(noMatches.path("data").path("total").asLong()).isZero();
        assertThat(getJson("/api/v1/targets?source_code=" + sourceCode.toLowerCase())
                .path("data").path("total").asLong()).isZero();
        assertThat(getJson("/api/v1/targets?device_id=" + deviceMockOther)
                .path("data").path("total").asLong()).isZero();
    }

    @Test
    void rejectsInvalidPaginationTimeRangesAndScalarParameters() throws Exception {
        assertError("/api/v1/targets?page=0", 400, "INVALID_PAGE");
        assertError("/api/v1/targets?size=101", 400, "INVALID_PAGE");
        assertError("/api/v1/targets?page=one", 400, "INVALID_PAGE");
        assertError("/api/v1/targets?page=1&page=2", 400, "INVALID_PAGE");
        assertError("/api/v1/targets?seen_from=1", 400, "INVALID_TIME_RANGE");
        assertError("/api/v1/targets?seen_from=2&seen_to=1", 400, "INVALID_TIME_RANGE");
        assertError("/api/v1/targets?seen_from=x&seen_to=2", 400, "INVALID_TIME_RANGE");
        assertError("/api/v1/targets?seen_from=1&seen_from=1&seen_to=2", 400, "INVALID_TIME_RANGE");
        assertError("/api/v1/targets?source_code=", 400, "VALIDATION_ERROR");
        assertError("/api/v1/targets?source_code=a&source_code=b", 400, "VALIDATION_ERROR");
        assertError("/api/v1/targets?device_id=" + "x".repeat(37), 400, "VALIDATION_ERROR");
        assertError("/api/v1/targets/" + "x".repeat(37), 400, "VALIDATION_ERROR");
    }

    @Test
    void requiresTargetReadBeforeRunningObjectQueries() throws Exception {
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='target:read'", role);

        assertError("/api/v1/targets", 403, "FORBIDDEN");
        assertError("/api/v1/targets?page=0", 403, "FORBIDDEN");
        assertError("/api/v1/targets/" + targetLatest, 403, "FORBIDDEN");
        assertError("/api/v1/targets/" + "x".repeat(37), 403, "FORBIDDEN");
        assertError("/api/v1/tracks/" + validTrack + "/points", 403, "FORBIDDEN");
        assertError("/api/v1/tracks/" + "x".repeat(37) + "/points", 403, "FORBIDDEN");
    }

    @Test
    void assignedScopeUsesExactTupleForListsDetailsAndNestedResources() throws Exception {
        JsonNode list = getJson("/api/v1/targets?owner_org_id=" + orgA + "&district_id=" + districtA);
        assertThat(list.path("data").path("total").asLong()).isEqualTo(2);
        assertThat(getJson("/api/v1/targets?owner_org_id=" + orgB).path("data").path("total").asLong()).isZero();

        assertError("/api/v1/targets/" + targetOtherScope, 404, "TARGET_NOT_FOUND");
        assertError("/api/v1/targets/" + targetOtherScope + "/tracks", 404, "TARGET_NOT_FOUND");
        String otherTrack = id();
        jdbc.update("insert into target_source_link (link_id,target_id,source_id,source_session_key,external_target_id,created_at) values (?,?,?,?,?,?)",
                id(), targetOtherScope, sourceMock, "other-session-" + suffix, "other-ext-" + suffix, T0);
        String otherLink = jdbc.queryForObject("select link_id from target_source_link where target_id=?", String.class, targetOtherScope);
        track(otherTrack, targetOtherScope, otherLink, "other-track", T0);
        assertError("/api/v1/tracks/" + otherTrack + "/points", 404, "TRACK_NOT_FOUND");
    }

    @Test
    void assignedScopeNeverCreatesCartesianAccessAcrossGrantedTuples() throws Exception {
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id,created_at) values (?,?,?,current_timestamp)",
                userId, orgB, districtB);
        String crossedTarget = id();
        target(crossedTarget, "TGT-CROSS-" + suffix, null, null, null, null, null, "mock", orgA, districtB);
        String crossedLink = id();
        link(crossedLink, crossedTarget, sourceMock, null, "crossed-session", "crossed-external", null);
        String crossedTrack = id();
        track(crossedTrack, crossedTarget, crossedLink, "crossed-track", null);

        assertThat(getJson("/api/v1/targets?owner_org_id=" + orgA + "&district_id=" + districtB)
                .path("data").path("total").asLong()).isZero();
        assertError("/api/v1/targets/" + crossedTarget, 404, "TARGET_NOT_FOUND");
        assertError("/api/v1/targets/" + crossedTarget + "/tracks", 404, "TARGET_NOT_FOUND");
        assertError("/api/v1/tracks/" + crossedTrack + "/points", 404, "TRACK_NOT_FOUND");
    }

    @Test
    void allScopeStillExcludesIncompleteOwnership() throws Exception {
        jdbc.update("update app_user set scope_mode='ALL' where user_id=?", userId);

        JsonNode data = getJson("/api/v1/targets?size=100").path("data");
        assertThat(data.path("total").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(data.path("items").findValuesAsText("target_id")).doesNotContain(targetIncomplete);
        JsonNode noLocation = null;
        for (JsonNode item : data.path("items")) {
            if (targetOtherScope.equals(item.path("target_id").asText())) noLocation = item;
        }
        assertThat(noLocation).isNotNull();
        assertThat(noLocation.path("latest_state").has("location")).isFalse();
        getJson("/api/v1/targets/" + targetOtherScope);
        assertError("/api/v1/targets/" + targetIncomplete, 404, "TARGET_NOT_FOUND");
    }

    @Test
    void targetDetailSortsOnlyModeConsistentSourceLinksAndOmitsNulls() throws Exception {
        JsonNode detail = getJson("/api/v1/targets/" + targetLatest).path("data");

        assertThat(detail.path("target_id").asText()).isEqualTo(targetLatest);
        assertThat(detail.path("created_at").asLong()).isEqualTo(T0.toInstant().toEpochMilli());
        assertThat(detail.path("source_links")).hasSize(2);
        assertThat(detail.path("source_links").get(0).path("source_session_key").asText()).isEqualTo("session-a");
        assertThat(detail.path("source_links").get(1).path("source_session_key").asText()).isEqualTo("session-b");
        assertThat(detail.path("source_links").get(0).has("protocol_version")).isFalse();
        assertNoSensitiveFields(detail);
    }

    @Test
    void listsOnlyValidTracksWithFixedSortingAndFilters() throws Exception {
        JsonNode data = getJson("/api/v1/targets/" + targetLatest + "/tracks?size=100").path("data");

        assertThat(data.path("total").asLong()).isEqualTo(2);
        assertThat(data.path("items").get(0).path("track_id").asText()).isEqualTo(validTrack);
        assertThat(data.path("items").get(1).path("track_id").asText()).isEqualTo(unknownTimeTrack);
        assertThat(data.path("items").get(1).has("started_at")).isFalse();
        String sourceCode = "SRC-MOCK-" + suffix;
        assertThat(getJson("/api/v1/targets/" + targetLatest + "/tracks?source_code=" + sourceCode
                + "&device_id=" + deviceMock + "&started_from=" + T0.toInstant().toEpochMilli()
                + "&started_to=" + T0.plusSeconds(4).toInstant().toEpochMilli())
                .path("data").path("items")).hasSize(1);
        assertError("/api/v1/targets/" + targetLatest + "/tracks?started_from=1", 400, "INVALID_TIME_RANGE");
    }

    @Test
    void pointsUseDisplayTimeSortingBasisAndNeverExposeRawPosition() throws Exception {
        JsonNode response = getJson("/api/v1/tracks/" + validTrack + "/points?size=1&page=1");
        JsonNode data = response.path("data");

        assertThat(data.path("total").asLong()).isEqualTo(2);
        JsonNode first = data.path("items").get(0);
        assertThat(first.path("point_seq").asLong()).isEqualTo(1);
        assertThat(first.path("time_basis").asText()).isEqualTo("RECEIVED");
        assertThat(first.path("sort_time").asLong()).isEqualTo(T0.plusSeconds(1).toInstant().toEpochMilli());
        assertThat(first.has("observed_at")).isFalse();
        assertThat(first.path("location").path("longitude").decimalValue()).isEqualByComparingTo("120.1");
        JsonNode second = getJson("/api/v1/tracks/" + validTrack + "/points?size=1&page=2")
                .path("data").path("items").get(0);
        assertThat(second.path("point_seq").asLong()).isEqualTo(2);
        assertThat(second.path("time_basis").asText()).isEqualTo("OBSERVED");
        assertNoSensitiveFields(response);

        long from = T0.plusSeconds(2).toInstant().toEpochMilli();
        long to = T0.plusSeconds(2).toInstant().toEpochMilli();
        assertThat(getJson("/api/v1/tracks/" + validTrack + "/points?time_from=" + from + "&time_to=" + to)
                .path("data").path("total").asLong()).isEqualTo(1);
    }

    @Test
    void rejectsModeAndRelationshipInvalidTracksAsNotFound() throws Exception {
        assertError("/api/v1/tracks/" + crossModeTrack + "/points", 404, "TRACK_NOT_FOUND");
        assertError("/api/v1/tracks/" + crossDeviceModeTrack + "/points", 404, "TRACK_NOT_FOUND");
        assertError("/api/v1/tracks/" + crossSourceTrack + "/points", 404, "TRACK_NOT_FOUND");
        assertError("/api/v1/tracks/" + mismatchedTrack + "/points", 404, "TRACK_NOT_FOUND");
        assertError("/api/v1/tracks/" + "x".repeat(37) + "/points", 400, "VALIDATION_ERROR");
        assertError("/api/v1/tracks/" + validTrack + "/points?time_from=2&time_to=1", 400, "INVALID_TIME_RANGE");
    }

    @Test
    void reportsStoredShapeAndCoordinateIntegrityFailuresWithoutInventingData() throws Exception {
        jdbc.update("update target_latest_state set unknown_fields=? FORMAT JSON where target_id=?", "{}", targetLatest);
        assertError("/api/v1/targets/" + targetLatest, 500, "INTERNAL_ERROR");

        jdbc.update("update target_latest_state set unknown_fields=? FORMAT JSON where target_id=?", "[]", targetLatest);
        jdbc.update("update track_point set location=CAST(? AS GEOMETRY) where track_id=? and point_seq=1",
                "SRID=4326;POINT (181 0)", validTrack);
        assertError("/api/v1/tracks/" + validTrack + "/points", 500, "INTERNAL_ERROR");
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /* ---------- 阶段 8 追加：既有字段与排序不变，新增字段一律可空 ---------- */

    @Test
    void stage8FieldsAreAbsentWhenFusionEngineNeverTouchedTheTarget() throws Exception {
        JsonNode detail = getJson("/api/v1/targets/" + targetLatest).path("data");
        // 阶段 2 的既有断言必须继续成立：新增字段不能改变已有字段的存在性与取值。
        assertThat(detail.path("target_id").asText()).isEqualTo(targetLatest);
        assertThat(detail.path("source_links")).hasSize(2);
        assertThat(detail.path("source_links").get(0).path("source_session_key").asText()).isEqualTo("session-a");
        assertThat(detail.has("track_status")).isFalse();
        assertThat(detail.has("degradation")).isFalse();
        assertThat(detail.has("attribute_selection")).isFalse();
        assertThat(detail.has("lineage_summary")).isFalse();
        // 该来源没有登记 source_type：字段缺省而不是猜一个类型。
        assertThat(detail.path("source_links").get(0).has("source_type")).isFalse();
        // 读者没有 fusion:revise：动作列表为空数组而不是给出不可执行的入口。
        assertThat(detail.path("allowed_actions")).isEmpty();
        assertNoSensitiveFields(detail);

        JsonNode tracks = getJson("/api/v1/targets/" + targetLatest + "/tracks?size=100").path("data");
        assertThat(tracks.path("total").asLong()).isEqualTo(2);
        // 阶段 2 既有轨迹默认落在 RAW 层，link_id 仍必须存在。
        assertThat(tracks.path("items").get(0).path("layer").asText()).isEqualTo("RAW");
        assertThat(tracks.path("items").get(0).path("link_id").asText()).isNotEmpty();
        assertThat(tracks.path("items").get(0).has("config_version")).isFalse();
        assertThat(tracks.path("items").get(0).has("ended_at")).isFalse();
    }

    @Test
    void stage8PointFieldsDefaultToMeasuredWithoutFusionMetadata() throws Exception {
        JsonNode points = getJson("/api/v1/tracks/" + validTrack + "/points?size=100").path("data");
        assertThat(points.path("total").asLong()).isEqualTo(2);
        JsonNode first = points.path("items").get(0);
        // 迁移默认值 MEAS 让阶段 2 的历史点继续出现在默认查询里（默认 kind=MEAS,BRIDGE）。
        assertThat(first.path("point_kind").asText()).isEqualTo("MEAS");
        assertThat(first.has("contributing")).isFalse();
        assertThat(first.has("position_accuracy_m")).isFalse();
        assertThat(first.has("degradation_level")).isFalse();
        assertThat(first.path("source_switched").asBoolean()).isFalse();
        assertNoSensitiveFields(points);
        // 过滤参数只接受契约词典。
        assertError("/api/v1/tracks/" + validTrack + "/points?kind=EVERYTHING", 400, "VALIDATION_ERROR");
        assertError("/api/v1/targets/" + targetLatest + "/tracks?layer=OTHER", 400, "VALIDATION_ERROR");
    }

    private void assertError(String path, int expectedStatus, String code) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + sessionId))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static void assertNoSensitiveFields(JsonNode node) {
        String json = node.toString();
        assertThat(json).doesNotContain("credential_ref", "raw_position", "inbox_id", "credential-secret");
    }

    private void org(String id, String code) {
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                id, code, code);
    }

    private void district(String id, String code) {
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)",
                id, code, code);
    }

    private void source(String id, String code, String mode, String credential) {
        jdbc.update("""
                insert into integration_source
                    (source_id,source_code,name,protocol_code,protocol_version,enabled,credential_ref,
                     source_mode,created_at,updated_at,version)
                values (?,?,?,'RADAR','3.0',true,?,?,?, ?,0)
                """, id, code, code, credential, mode, T0, T0);
    }

    private void target(String id, String no, String objectType, String subtype, String uavSn,
            OffsetDateTime firstSeen, OffsetDateTime lastSeen, String mode, String org, String district) {
        jdbc.update("""
                insert into target
                    (target_id,target_no,object_type_code,subtype,uav_sn,first_seen_at,last_seen_at,
                     source_mode,owner_org_id,district_id,created_at,updated_at,version)
                values (?,?,?,?,?,?,?,?,?,?,?,?,0)
                """, id, no, objectType, subtype, uavSn, firstSeen, lastSeen, mode, org, district, T0, T0.plusSeconds(30));
    }

    private void link(String id, String targetId, String sourceId, String deviceId,
            String sessionKey, String externalId, String protocolVersion) {
        jdbc.update("""
                insert into target_source_link
                    (link_id,target_id,source_id,device_id,source_session_key,external_target_id,protocol_version,created_at)
                values (?,?,?,?,?,?,?,?)
                """, id, targetId, sourceId, deviceId, sessionKey, externalId, protocolVersion, T0);
    }

    private void track(String id, String targetId, String linkId, String externalId, OffsetDateTime startedAt) {
        jdbc.update("insert into track (track_id,target_id,link_id,external_track_id,started_at,created_at) values (?,?,?,?,?,?)",
                id, targetId, linkId, externalId, startedAt, T0);
    }

    private void point(String id, String trackId, long seq, OffsetDateTime observedAt,
            OffsetDateTime receivedAt, double longitude, double latitude, String rawPosition) {
        String geometry = "SRID=4326;POINT (" + longitude + " " + latitude + ")";
        jdbc.update("""
                insert into track_point
                    (point_id,track_id,point_seq,observed_at,received_at,location,
                     altitude_amsl_m,height_agl_m,raw_position,created_at)
                values (?,?,?,?,?,CAST(? AS GEOMETRY),10.5,null,? FORMAT JSON,?)
                """, id, trackId, seq, observedAt, receivedAt, geometry, rawPosition, T0);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    /** 阶段 8 决策 8-26：目标详情暴露可空 version，是修订/合并/分裂 expected_version 的唯一来源。 */
    @Test
    void detailExposesTargetRowVersionForExpectedVersion() throws Exception {
        jdbc.update("update target set version=3 where target_id=?", targetLatest);
        JsonNode detail = getJson("/api/v1/targets/" + targetLatest).path("data");
        assertThat(detail.path("version").asLong()).isEqualTo(3L);
    }
}
