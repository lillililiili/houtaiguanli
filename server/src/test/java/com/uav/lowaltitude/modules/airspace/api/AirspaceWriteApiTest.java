package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.audit.AuditService;

/**
 * 空域写入：接替式版本、范围与权限、幂等与版本冲突、成功审计同事务。
 * 不套测试事务：回滚证明与"历史版本几何未变"都要求业务事务真正提交或真正回滚。
 */
@SpringBootTest(properties = "app.dev-seed.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AirspaceWriteApiTest {
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private static final String SQUARE = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[118.0,37.0],[118.1,37.0],[118.1,37.1],[118.0,37.1],[118.0,37.0]]]]}";
    private static final String SQUARE_EAST = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[118.2,37.0],[118.3,37.0],[118.3,37.1],[118.2,37.1],[118.2,37.0]]]]}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @SpyBean AuditService audit;

    private String suffix, orgId, district, session, readOnlySession, userId;

    @BeforeEach
    void fixture() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        orgId = "org-9w-" + suffix; district = "dist-9w-" + suffix;
        jdbc.update("insert into app_org (org_id,org_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", orgId, "ORG-9W-" + suffix, "空域写测试机构");
        jdbc.update("insert into app_district (district_id,district_code,name,enabled,created_at,updated_at,version) values (?,?,?,true,0,0,0)", district, "DIST-9W-" + suffix, "空域写测试区域");
        userId = UUID.randomUUID().toString();
        session = user(userId, true, true);
        readOnlySession = user(UUID.randomUUID().toString(), true, false);
    }

    @AfterEach
    void cleanup() {
        org.mockito.Mockito.reset(org.springframework.test.util.AopTestUtils.<AuditService>getTargetObject(audit));
        jdbc.update("delete from airspace_version_origin where airspace_version_id in (select airspace_version_id from airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.owner_org_id=?)", orgId);
        jdbc.update("delete from airspace_version where airspace_id in (select airspace_id from airspace where owner_org_id=?)", orgId);
        jdbc.update("delete from airspace where owner_org_id=?", orgId);
        jdbc.update("delete from audit_log where account like 'airspace-w-%'");
        jdbc.update("delete from idempotency_request where user_id in (select user_id from app_user where account like 'airspace-w-%')");
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'airspace-w-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'airspace-w-%')");
        jdbc.update("delete from app_user where account like 'airspace-w-%'");
        jdbc.update("delete from app_role_permission where role_code like 'ROLE-9W-%'");
        jdbc.update("delete from app_role where role_code like 'ROLE-9W-%'");
        jdbc.update("delete from app_district where district_id=?", district);
        jdbc.update("delete from app_org where org_id=?", orgId);
    }

    @Test
    void missingManagePermissionIsForbiddenBeforeBodyParsing() throws Exception {
        // 缺写权限时连坏 body 都不该被解析：403 必须先于 400，否则可以用报错差异探测接口形状。
        mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + readOnlySession)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(count("airspace where owner_org_id=?", orgId)).isZero();
    }

    @Test
    void createStoresAirspaceFirstVersionAndManualOrigin() throws Exception {
        MvcResult result = create(session, "KY-9W-" + suffix, SQUARE, T0, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version_no").value(1))
                .andReturn();
        String airspaceId = json.readTree(result.getResponse().getContentAsString()).path("data").path("airspace_id").asText();
        String versionId = json.readTree(result.getResponse().getContentAsString()).path("data").path("airspace_version_id").asText();
        assertThat(count("airspace where airspace_id=? and source_id is null and source_mode='live'", airspaceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version_no from airspace_version where airspace_version_id=?", Integer.class, versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", java.sql.Timestamp.class, versionId)).isNull();
        // 人工新建的来源痕迹：MANUAL + 操作者，没有外部来源行。
        Map<String, Object> origin = jdbc.queryForMap("select origin_kind,actor_id,superseded_version_id from airspace_version_origin where airspace_version_id=?", versionId);
        assertThat(origin.get("origin_kind")).isEqualTo("MANUAL");
        assertThat(origin.get("actor_id")).isEqualTo(userId);
        assertThat(origin.get("superseded_version_id")).isNull();
        assertThat(count("audit_log where action='airspace_created' and object_id=? and result='SUCCESS'", airspaceId)).isEqualTo(1);
    }

    @Test
    void addingVersionClosesPreviousVersionAndBumpsAirspaceVersion() throws Exception {
        String airspaceId = created("KY-9W-B-" + suffix, SQUARE, T0);
        String firstVersionId = jdbc.queryForObject("select airspace_version_id from airspace_version where airspace_id=? and version_no=1", String.class, airspaceId);
        Instant secondFrom = T0.plusSeconds(3600);

        MvcResult result = mvc.perform(version(session, airspaceId, SQUARE_EAST, secondFrom, 0, "范围调整"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version_no").value(2))
                .andExpect(jsonPath("$.data.superseded_version_id").value(firstVersionId))
                .andReturn();
        String secondVersionId = json.readTree(result.getResponse().getContentAsString()).path("data").path("airspace_version_id").asText();

        // 接替：上一版的 valid_to 正好是新版的 valid_from，历史区间连续且不重叠。
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", java.sql.Timestamp.class, firstVersionId).toInstant())
                .isEqualTo(secondFrom);
        assertThat(jdbc.queryForObject("select valid_to from airspace_version where airspace_version_id=?", java.sql.Timestamp.class, secondVersionId)).isNull();
        assertThat(jdbc.queryForObject("select version from airspace where airspace_id=?", Long.class, airspaceId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select superseded_version_id from airspace_version_origin where airspace_version_id=?", String.class, secondVersionId))
                .isEqualTo(firstVersionId);
        assertThat(count("audit_log where action='airspace_version_created' and object_id=? and result='SUCCESS'", airspaceId)).isEqualTo(1);
    }

    @Test
    void historicalVersionGeometryAndAltitudeStayUnchangedAfterSuccession() throws Exception {
        String airspaceId = created("KY-9W-C-" + suffix, SQUARE, T0);
        String firstVersionId = jdbc.queryForObject("select airspace_version_id from airspace_version where airspace_id=? and version_no=1", String.class, airspaceId);
        String before = jdbc.queryForObject("select cast(boundary as varchar) from airspace_version where airspace_version_id=?", String.class, firstVersionId);
        String kindBefore = jdbc.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, firstVersionId);

        mvc.perform(version(session, airspaceId, SQUARE_EAST, T0.plusSeconds(3600), 0, "范围调整")).andExpect(status().isCreated());

        // 研判引用的是版本行本身：接替只允许写 valid_to，几何与种类必须原样保留。
        assertThat(jdbc.queryForObject("select cast(boundary as varchar) from airspace_version where airspace_version_id=?", String.class, firstVersionId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select kind_code from airspace_version where airspace_version_id=?", String.class, firstVersionId)).isEqualTo(kindBefore);
    }

    @Test
    void versionNotLaterThanPreviousIsOverlapConflict() throws Exception {
        String airspaceId = created("KY-9W-D-" + suffix, SQUARE, T0);
        mvc.perform(version(session, airspaceId, SQUARE_EAST, T0, 0, "同一时刻"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_OVERLAP"));
        mvc.perform(version(session, airspaceId, SQUARE_EAST, T0.minusSeconds(60), 0, "更早时刻"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_OVERLAP"));
        assertThat(count("airspace_version where airspace_id=?", airspaceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from airspace where airspace_id=?", Long.class, airspaceId)).isZero();
    }

    @Test
    void invalidValidityAndUnknownFieldsAreRejected() throws Exception {
        String body = "{\"airspace_no\":\"KY-9W-E-" + suffix + "\",\"name\":\"倒挂有效期\",\"kind_code\":\"PROHIBITED\",\"boundary\":" + SQUARE
                + ",\"valid_from\":" + T0.toEpochMilli() + ",\"valid_to\":" + T0.minusSeconds(60).toEpochMilli()
                + ",\"owner_org_id\":\"" + orgId + "\",\"district_id\":\"" + district + "\"}";
        mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + session).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_VALIDITY"));
        mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + session).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"name\"", "\"nickname\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        // 种类必须来自字典；自由文本会让页面的图层映射失效。
        mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + session).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("PROHIBITED", "NO_FLY_ZONE").replace(
                                "\"valid_to\":" + T0.minusSeconds(60).toEpochMilli(), "\"valid_to\":" + T0.plusSeconds(60).toEpochMilli())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(count("airspace where owner_org_id=?", orgId)).isZero();
    }

    @Test
    void invalidGeometryIsRejectedWithGeoJsonCode() throws Exception {
        String unclosed = "{\"type\":\"Polygon\",\"coordinates\":[[[118.0,37.0],[118.1,37.0],[118.1,37.1],[118.0,37.1]]]}";
        mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + session).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("KY-9W-F-" + suffix, unclosed, T0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_GEOJSON"));
    }

    @Test
    void replayedKeyAndStaleExpectedVersionAreConflicts() throws Exception {
        String airspaceId = created("KY-9W-G-" + suffix, SQUARE, T0);
        String replayKey = key();
        mvc.perform(version(session, airspaceId, SQUARE_EAST, T0.plusSeconds(3600), 0, "第一次追加", replayKey))
                .andExpect(status().isCreated());
        // 同键同请求：必须先被幂等拦下，而不是因为版本已经推进而报版本冲突。
        mvc.perform(version(session, airspaceId, SQUARE_EAST, T0.plusSeconds(3600), 0, "第一次追加", replayKey))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_REPLAY"));
        // 版本已经变成 1：仍用 0 提交属于拿着过期状态写入。
        mvc.perform(version(session, airspaceId, SQUARE_EAST, T0.plusSeconds(7200), 0, "过期版本"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertThat(count("airspace_version where airspace_id=?", airspaceId)).isEqualTo(2);
    }

    @Test
    void concurrentCreateWithTheSameNumberYieldsOneWinnerAndOneConflict() throws Exception {
        // 存在性预检挡不住并发：两个请求可能同时查到"编号不存在"。唯一约束是最终保障，
        // 输家必须看到契约里的 409 AIRSPACE_NO_EXISTS，而不是 500。
        String airspaceNo = "KY-9W-RACE-" + suffix;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<MvcResult> attempt = () -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + session)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(airspaceNo, SQUARE, T0))).andReturn();
            };
            java.util.concurrent.Future<MvcResult> first = pool.submit(attempt), second = pool.submit(attempt);
            List<MvcResult> results = List.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(201, 409);
            MvcResult rejected = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(json.readTree(rejected.getResponse().getContentAsString()).path("error").path("code").asText())
                    .isEqualTo("AIRSPACE_NO_EXISTS");
        } finally {
            pool.shutdownNow();
        }
        // 输家整体回滚：既没有第二片空域，也没有留下半个版本或来源行。
        assertThat(count("airspace where airspace_no=?", airspaceNo)).isEqualTo(1);
        assertThat(count("airspace_version v join airspace a on a.airspace_id=v.airspace_id where a.airspace_no=?", airspaceNo)).isEqualTo(1);
        assertThat(count("airspace_version_origin o join airspace_version v on v.airspace_version_id=o.airspace_version_id"
                + " join airspace a on a.airspace_id=v.airspace_id where a.airspace_no=?", airspaceNo)).isEqualTo(1);
    }

    @Test
    void successAuditFailureRollsBackAirspaceAndVersion() throws Exception {
        AuditService target = org.springframework.test.util.AopTestUtils.getTargetObject(audit);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(target).record(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("airspace"), org.mockito.ArgumentMatchers.eq("airspace_created"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("SUCCESS"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        create(session, "KY-9W-H-" + suffix, SQUARE, T0, null).andExpect(status().is5xxServerError());
        org.mockito.Mockito.reset(target);
        // 成功审计与业务写入同事务：审计失败必须让空域、版本、来源、幂等占位一起消失。
        assertThat(count("airspace where owner_org_id=?", orgId)).isZero();
        assertThat(count("idempotency_request where user_id=?", userId)).isZero();
    }

    private ResultActions create(String token, String airspaceNo, String boundary, Instant validFrom, String key) throws Exception {
        return mvc.perform(post("/api/v1/airspaces").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key == null ? key() : key)
                .contentType(MediaType.APPLICATION_JSON).content(createBody(airspaceNo, boundary, validFrom)));
    }

    private String createBody(String airspaceNo, String boundary, Instant validFrom) {
        return "{\"airspace_no\":\"" + airspaceNo + "\",\"name\":\"演示空域\",\"kind_code\":\"PROHIBITED\",\"boundary\":" + boundary
                + ",\"valid_from\":" + validFrom.toEpochMilli() + ",\"change_reason\":\"首次划设\""
                + ",\"owner_org_id\":\"" + orgId + "\",\"district_id\":\"" + district + "\"}";
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder version(String token, String airspaceId,
            String boundary, Instant validFrom, long expectedVersion, String reason) {
        return version(token, airspaceId, boundary, validFrom, expectedVersion, reason, key());
    }

    /** 幂等键显式传入：同一请求重放必须用同一个键，不能靠追加第二个同名请求头。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder version(String token, String airspaceId,
            String boundary, Instant validFrom, long expectedVersion, String reason, String idempotencyKey) {
        return post("/api/v1/airspaces/" + airspaceId + "/versions").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind_code\":\"PROHIBITED\",\"boundary\":" + boundary + ",\"valid_from\":" + validFrom.toEpochMilli()
                        + ",\"change_reason\":\"" + reason + "\",\"expected_version\":" + expectedVersion + "}");
    }

    private String created(String airspaceNo, String boundary, Instant validFrom) throws Exception {
        MvcResult result = create(session, airspaceNo, boundary, validFrom, null).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("airspace_id").asText();
    }

    private String user(String userId, boolean read, boolean manage) {
        String role = "ROLE-9W-" + UUID.randomUUID().toString().substring(0, 8), token = UUID.randomUUID().toString();
        jdbc.update("insert into app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values (?,?,'',false,true,0,0,0,false)", role, role);
        if (read) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'airspace:read','READ',false,current_timestamp)", role);
        if (manage) jdbc.update("insert into app_role_permission (role_code,permission_code,permission_level,menu_enabled,created_at) values (?,'airspace:manage','OP',false,current_timestamp)", role);
        jdbc.update("insert into app_user (user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values (?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",
                userId, "airspace-w-" + UUID.randomUUID().toString().substring(0, 8), "空域管理员", role);
        jdbc.update("insert into app_user_data_scope (user_id,org_id,district_id) values (?,?,?)", userId, orgId, district);
        jdbc.update("insert into app_session (session_id,user_id,expire_at,ip,permission_version) values (?,?,?,'127.0.0.1',0)", token, userId, System.currentTimeMillis() + 3_600_000L);
        return token;
    }

    private long count(String fromWhere, Object... args) {
        return jdbc.queryForObject("select count(*) from " + fromWhere, Long.class, args);
    }

    private static String key() { return "airspace-" + UUID.randomUUID(); }
}
