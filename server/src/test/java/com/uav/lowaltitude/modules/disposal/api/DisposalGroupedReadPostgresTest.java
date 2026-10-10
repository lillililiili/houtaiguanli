package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Only queries isolated fixture records; never requests, approves, executes or synchronizes devices. */
@SpringBootTest(properties={"app.dev-seed.enabled=false", "app.outbox.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class DisposalGroupedReadPostgresTest {
    private static final String BASE = "/api/v1/disposal-authorizations";
    private static final String SCHEMA = "disposal_group_read_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate root;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String org, district, otherOrg, otherDistrict, actor, session, role, subject;
    long now;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Dedicated test database required");
        String user = System.getenv("POSTGRES_TEST_USER"), password = System.getenv("POSTGRES_TEST_PASSWORD");
        root = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        root.execute("CREATE SCHEMA " + SCHEMA);
        p.add("spring.datasource.url", () -> url + "?currentSchema=" + SCHEMA + ",public");
        p.add("spring.datasource.username", () -> user); p.add("spring.datasource.password", () -> password);
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
        p.add("spring.flyway.default-schema", () -> SCHEMA); p.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @AfterAll static void cleanup(@Autowired org.springframework.context.ConfigurableApplicationContext context) {
        context.getBeansOfType(ThreadPoolTaskScheduler.class).values().forEach(ThreadPoolTaskScheduler::shutdown);
        if (root != null) root.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @BeforeEach void fixture() {
        now = System.currentTimeMillis();
        org = id(); district = id(); otherOrg = id(); otherDistrict = id(); subject = id();
        catalog(org, district); catalog(otherOrg, otherDistrict);
        role = "ROLE-GRP-" + UUID.randomUUID().toString().substring(0, 8);
        actor = id(); session = id();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)", role, role);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " VALUES(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)", actor, actor, "处置记录读取测试", role);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)", actor, org, district);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at)"
                + " VALUES(?,'disposal:read','READ',FALSE,current_timestamp)", role);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",
                session, actor, now + 3600000);
    }

    @ParameterizedTest @ValueSource(strings={"live", "mock", "replay"})
    void explicitPairPreservesOriginalRecordsAndDoesNotWrite(String source) throws Exception {
        String parent = authorization("COUNTERMEASURE", "COMPLETED", source, null, now - 3000);
        String child = authorization("JAMMING", "EXECUTING", source, parent, now - 1000);
        String unlinked = authorization("JAMMING", "FAILED", source, null, now - 2000);
        List<java.util.Map<String, Object>> before = jdbc.queryForList("SELECT * FROM disposal_authorization ORDER BY authorization_id");
        long eventCount = jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization_event", Long.class);
        JsonNode original = read(BASE);
        assertThat(original.path("total").asLong()).isEqualTo(3);
        JsonNode data = read(BASE + "/grouped");
        assertThat(data.path("total").asLong()).isEqualTo(2);
        assertThat(data.path("items").get(0).path("disposal_id").asText()).isEqualTo(unlinked);
        JsonNode group = data.path("items").get(1);
        assertThat(group.path("disposal_id").asText()).isEqualTo(parent);
        assertThat(memberIds(group)).containsExactly(parent, child);
        for (JsonNode member : group.path("authorizations")) {
            JsonNode single = null;
            for (JsonNode item : original.path("items"))
                if (item.path("authorization_id").equals(member.path("authorization_id"))) single = item;
            assertThat(member).isEqualTo(single);
            assertThat(member.path("allowed_actions")).isEmpty();
        }
        assertThat(jdbc.queryForList("SELECT * FROM disposal_authorization ORDER BY authorization_id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization_event", Long.class)).isEqualTo(eventCount);
    }

    @Test void filtersMatchOneMemberAndReturnWholeGroupWithGroupPagination() throws Exception {
        String first = authorization("COUNTERMEASURE", "COMPLETED", "live", null, now - 3000);
        String child = authorization("JAMMING", "EXECUTING", "live", first, now);
        String second = authorization("COUNTERMEASURE", "REQUESTED", "live", null, now - 2000);
        String standalone = authorization("JAMMING", "EXPIRED", "live", null, now - 1000);
        JsonNode firstPage = read(BASE + "/grouped?page=1&size=1");
        JsonNode secondPage = read(BASE + "/grouped?page=2&size=1");
        JsonNode thirdPage = read(BASE + "/grouped?page=3&size=1");
        assertThat(firstPage.path("total").asInt()).isEqualTo(3);
        assertThat(firstPage.path("items").get(0).path("disposal_id").asText()).isEqualTo(standalone);
        assertThat(secondPage.path("items").get(0).path("disposal_id").asText()).isEqualTo(second);
        assertThat(memberIds(thirdPage.path("items").get(0))).containsExactly(first, child);
        for (String query : List.of("status=EXECUTING", "status=COMPLETED", "action_type=JAMMING&status=EXECUTING",
                "exclude_status=COMPLETED&status=EXECUTING", "subject_kind=UAV_EVENT&subject_id=" + subject + "&status=EXECUTING")) {
            JsonNode selected = read(BASE + "/grouped?" + query);
            assertThat(selected.path("total").asInt()).isEqualTo(1);
            assertThat(memberIds(selected.path("items").get(0))).containsExactly(first, child);
        }
        assertThat(read(BASE + "/grouped?action_type=COUNTERMEASURE&status=EXECUTING").path("total").asInt()).isZero();
        assertThat(read(BASE + "/grouped?page=4&size=1").path("items")).isEmpty();
        assertThat(read(BASE + "?status=EXECUTING").path("total").asInt()).isEqualTo(1);
    }

    @Test void equalRootTimesUseStableIdOrder() throws Exception {
        List<String> roots = new ArrayList<>();
        for (int i = 0; i < 3; i++) roots.add(authorization("COUNTERMEASURE", "COMPLETED", "replay", null, now));
        roots.sort(java.util.Comparator.reverseOrder());
        for (int page = 1; page <= 3; page++)
            assertThat(read(BASE + "/grouped?page=" + page + "&size=1").path("items").get(0).path("disposal_id").asText())
                    .isEqualTo(roots.get(page - 1));
    }

    @Test void membersKeepTheirOwnAllowedActionsWithoutGroupPermissionUnion() throws Exception {
        for (String permission : List.of("disposal:request", "disposal:stop"))
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " VALUES(?,?,'OP',FALSE,current_timestamp)", role, permission);
        String parent = authorization("COUNTERMEASURE", "APPROVED", "replay", null, now - 1000);
        String child = authorization("JAMMING", "EXECUTING", "replay", parent, now);
        JsonNode original = read(BASE).path("items");
        JsonNode grouped = read(BASE + "/grouped").path("items").get(0).path("authorizations");
        assertThat(grouped.get(0).path("authorization_id").asText()).isEqualTo(parent);
        assertThat(grouped.get(1).path("authorization_id").asText()).isEqualTo(child);
        assertThat(grouped.get(0)).isEqualTo(original.get(1));
        assertThat(grouped.get(1)).isEqualTo(original.get(0));
        assertThat(grouped.get(0).path("allowed_actions").toString()).contains("STOP", "CANCEL");
        assertThat(grouped.get(1).path("allowed_actions").toString()).contains("STOP").doesNotContain("CANCEL", "EXECUTE");
    }

    @ParameterizedTest @ValueSource(strings={"live", "mock", "replay"})
    void retiredSuccessorCannotOfferExecuteButKeepsCancelAndStop(String source) throws Exception {
        for (String permission : List.of("disposal:execute", "disposal:request", "disposal:stop", "devices"))
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " VALUES(?,?,'OP',FALSE,current_timestamp)", role, permission);
        String parent = authorization("COUNTERMEASURE", "APPROVED", source, null, now - 2000);
        String child = authorization("JAMMING", "APPROVED", source, parent, now - 1000);
        JsonNode grouped = read(BASE + "/grouped").path("items").get(0).path("authorizations");
        assertThat(grouped.get(0).path("allowed_actions").toString()).contains("EXECUTE");
        assertThat(grouped.get(1).path("authorization_id").asText()).isEqualTo(child);
        assertThat(grouped.get(1).path("allowed_actions").toString()).contains("CANCEL", "STOP").doesNotContain("EXECUTE");
        assertThat(grouped.get(1).path("execution_block_reason").asText()).isEqualTo("LEGACY_JAMMING_RETIRED");
        JsonNode raw = read(BASE).path("items").get(0);
        assertThat(raw).isEqualTo(grouped.get(1));
        assertThat(jdbc.queryForObject("SELECT status FROM disposal_authorization WHERE authorization_id=?", String.class, child))
                .isEqualTo("APPROVED");
    }

    @ParameterizedTest @ValueSource(strings={"subject_kind", "subject_id", "owner_org_id", "district_id", "source_mode", "action_type"})
    void inconsistentExplicitRelationsRemainSeparate(String field) throws Exception {
        jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE user_id=?", actor);
        String parent = authorization("COUNTERMEASURE", "COMPLETED", "live", null, now - 1000);
        String child = authorization("JAMMING", "EXECUTING", "live", parent, now);
        String value = switch (field) {
            case "subject_kind" -> "TARGET";
            case "subject_id" -> id();
            case "owner_org_id" -> otherOrg;
            case "district_id" -> otherDistrict;
            case "source_mode" -> "mock";
            default -> "DECOY";
        };
        jdbc.update("UPDATE disposal_authorization SET " + field + "=? WHERE authorization_id=?", value, child);
        JsonNode data = read(BASE + "/grouped");
        assertThat(data.path("total").asInt()).isEqualTo(2);
        for (JsonNode group : data.path("items")) assertThat(group.path("authorizations")).hasSize(1);
    }

    @Test void invisibleParentOrChildIsNeverDisclosedOrUsedAsGroupId() throws Exception {
        String parent = authorization("COUNTERMEASURE", "COMPLETED", "live", null, now - 1000);
        String child = authorization("JAMMING", "EXECUTING", "live", parent, now);
        jdbc.update("UPDATE disposal_authorization SET owner_org_id=?,district_id=? WHERE authorization_id=?", otherOrg, otherDistrict, parent);
        JsonNode data = read(BASE + "/grouped");
        assertThat(data.path("total").asInt()).isEqualTo(1);
        assertThat(data.path("items").get(0).path("disposal_id").asText()).isEqualTo(child);
        assertThat(data.toString()).doesNotContain(parent);
        jdbc.update("UPDATE disposal_authorization SET owner_org_id=?,district_id=? WHERE authorization_id=?", org, district, parent);
        jdbc.update("UPDATE disposal_authorization SET owner_org_id=?,district_id=? WHERE authorization_id=?", otherOrg, otherDistrict, child);
        data = read(BASE + "/grouped");
        assertThat(memberIds(data.path("items").get(0))).containsExactly(parent);
        assertThat(data.toString()).doesNotContain(child);
        assertThat(read(BASE + "/grouped?status=EXECUTING").path("total").asInt()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"self", "cycle", "multi_level"})
    void malformedChainsStayStandalone(String shape) throws Exception {
        String parent = authorization("COUNTERMEASURE", "COMPLETED", "mock", null, now - 2000);
        String child = authorization("JAMMING", "EXECUTING", "mock", "self".equals(shape) ? null : parent, now - 1000);
        int expected = 2;
        if ("self".equals(shape)) jdbc.update("UPDATE disposal_authorization SET chained_from_authorization_id=? WHERE authorization_id=?", child, child);
        if ("cycle".equals(shape)) jdbc.update("UPDATE disposal_authorization SET chained_from_authorization_id=? WHERE authorization_id=?", child, parent);
        if ("multi_level".equals(shape)) { authorization("JAMMING", "FAILED", "mock", child, now); expected = 3; }
        JsonNode data = read(BASE + "/grouped");
        assertThat(data.path("total").asInt()).isEqualTo(expected);
        for (JsonNode group : data.path("items")) assertThat(group.path("authorizations")).hasSize(1);
    }

    @Test void anonymousAndPaginationBoundariesAreEnforced() throws Exception {
        mvc.perform(get(BASE + "/grouped")).andExpect(status().isUnauthorized());
        for (String query : List.of("page=0", "size=0", "size=101", "exclude_status=UNKNOWN"))
            mvc.perform(get(BASE + "/grouped?" + query).header("Authorization", "Bearer " + session)).andExpect(status().isBadRequest());
        assertThat(read(BASE + "/grouped?page=2147483647&size=100").path("items")).isEmpty();
    }

    @Test void missingScopeIsForbidden() throws Exception {
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE user_id=?", actor);
        mvc.perform(get(BASE + "/grouped").header("Authorization", "Bearer " + session)).andExpect(status().isForbidden());
    }

    @Test void missingReadPermissionIsForbidden() throws Exception {
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=?", role);
        mvc.perform(get(BASE + "/grouped").header("Authorization", "Bearer " + session)).andExpect(status().isForbidden());
    }

    private JsonNode read(String endpoint) throws Exception {
        return json.readTree(mvc.perform(get(endpoint).header("Authorization", "Bearer " + session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private List<String> memberIds(JsonNode group) {
        List<String> ids = new ArrayList<>();
        for (JsonNode member : group.path("authorizations")) ids.add(member.path("authorization_id").asText());
        return ids;
    }
    private String authorization(String action, String status, String source, String parent, long requestedAt) {
        String id = id();
        Timestamp at = new Timestamp(requestedAt);
        jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,device_id,channel,"
                + "reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,chained_from_authorization_id,version,created_at,updated_at)"
                + " VALUES(?,?,?,'UAV_EVENT',?,?,'COUNTERMEASURE_4CH','只读历史夹具',?, ?,?,'demo-v1',?,?,?,?,0,?,?)",
                id, id.replace("-", ""), action, subject, id(), actor, at, status, org, district, source, parent, at, at);
        return id;
    }
    private void catalog(String organization, String area) {
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)", organization, organization, organization);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)", area, area, area);
    }
    private static String id() { return UUID.randomUUID().toString(); }
}
