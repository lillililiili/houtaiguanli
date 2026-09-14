package com.uav.lowaltitude.modules.fusion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 融合参数版本激活：旧 ACTIVE→RETIRED 同事务、重复激活 409、读取只需 fusion:read、激活需 fusion:manage。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FusionConfigApiTest {
    private static final String ACTIVE = "demo-v1";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private FusionFixture fixture;
    private String draft, reader, manager;

    @BeforeEach
    void seed() {
        fixture = new FusionFixture(jdbc);
        String suffix = FusionFixture.id().substring(0, 8);
        String org = FusionFixture.id(), district = FusionFixture.id();
        fixture.org(org, "ORG-CFG-" + suffix); fixture.district(district, "DIST-CFG-" + suffix);
        draft = "demo-draft-" + suffix.substring(0, 6);
        // 草稿版本参数与 demo-v1 同形，只改一个阈值，证明激活换的是整份参数而不是就地改。
        jdbc.update("insert into fusion_config (config_version,status,schema_status,params,note,created_at,version) values (?,'DRAFT','DEMO',CAST(? AS JSON),'测试草稿',?,0)",
                draft, "{\"degradation\":{\"undetermined_deficit\":0.6}}", FusionFixture.T0);
        reader = fixture.session(fixture.role("CR-" + suffix, "fusion:read"), org, district, "ALL");
        manager = fixture.session(fixture.role("CM-" + suffix, "fusion:read", "fusion:manage"), org, district, "ALL");
    }

    @Test
    void activatingDraftRetiresPreviousActiveInOneTransaction() throws Exception {
        assertThat(jdbc.queryForObject("select status from fusion_config where config_version=?", String.class, ACTIVE)).isEqualTo("ACTIVE");
        JsonNode result = ok(activate(manager, draft, "{\"expected_version\":0}", "cfg-" + FusionFixture.id()));
        assertThat(result.path("config_version").asText()).isEqualTo(draft);
        assertThat(result.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(result.path("activated_at").asLong()).isPositive();
        // 任何时刻只有一个 ACTIVE：旧版本必须在同一事务里退役。
        assertThat(jdbc.queryForObject("select status from fusion_config where config_version=?", String.class, ACTIVE)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("select count(*) from fusion_config where status='ACTIVE'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='fusion_config_activated' and object_id=? and result='SUCCESS'", Long.class, draft)).isEqualTo(1L);

        // 再次激活同一版本：409，且不重复退役其它版本。
        mvc.perform(activate(manager, draft, "{\"expected_version\":1}", "cfg2-" + FusionFixture.id()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONFIG_ALREADY_ACTIVE"));
        assertThat(jdbc.queryForObject("select count(*) from fusion_config where status='ACTIVE'", Long.class)).isEqualTo(1L);
    }

    @Test
    void activationChecksPermissionVersionAndExistence() throws Exception {
        mvc.perform(activate(reader, draft, "{\"expected_version\":0}", "no-perm-" + FusionFixture.id()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(activate(manager, "missing-version", "{\"expected_version\":0}", "missing-" + FusionFixture.id()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CONFIG_NOT_FOUND"));
        mvc.perform(activate(manager, draft, "{\"expected_version\":5}", "bad-version-" + FusionFixture.id()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(activate(manager, draft, "{\"expected_version\":0,\"note\":\"未知字段\"}", "unknown-" + FusionFixture.id()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
        assertThat(jdbc.queryForObject("select status from fusion_config where config_version=?", String.class, draft)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select status from fusion_config where config_version=?", String.class, ACTIVE)).isEqualTo("ACTIVE");
    }

    @Test
    void configReadNeedsFusionReadAndListsAllVersions() throws Exception {
        mvc.perform(get("/api/v1/fusion/config")).andExpect(status().isUnauthorized());
        JsonNode config = ok(get("/api/v1/fusion/config").header("Authorization", "Bearer " + reader));
        assertThat(config.path("active").path("config_version").asText()).isEqualTo(ACTIVE);
        boolean hasDraft = false;
        for (JsonNode version : config.path("versions")) if (draft.equals(version.path("config_version").asText())) hasDraft = true;
        assertThat(hasDraft).isTrue();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private static MockHttpServletRequestBuilder activate(String session, String version, String body, String key) {
        return post("/api/v1/fusion/config/" + version + "/activate").header("Authorization", "Bearer " + session)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
