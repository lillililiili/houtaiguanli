package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class TargetNumberFilterApiTest {
    private static final List<String> LISTS = List.of("/alarms", "/legality-evaluations", "/handoffs");
    private static final List<String> READS = List.of("/alarms", "/alarms/export.csv", "/legality-evaluations",
            "/legality-evaluations/summary", "/handoffs", "/handoffs/stats");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AlarmMergePolicy policy;
    AlarmViolationReasonFilterApiTest fixture;
    String role, number, firstTarget;

    @BeforeEach void prepare() {
        fixture = new AlarmViolationReasonFilterApiTest();
        fixture.mvc = mvc; fixture.jdbc = jdbc; fixture.json = json; fixture.policy = policy;
        fixture.fixture();
        role = "ROLE-VR-" + fixture.suffix;
        for (String permission : List.of("target:read", "assessment:read", "handoff:read")) {
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " VALUES (?,?,'READ',FALSE,current_timestamp)", role, permission);
        }
        number = "目标-" + UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis();
    }

    private void seed(String source) {
        firstTarget = add(number + "-001", source);
        add(number + "-002", source);
        add("OTHER-" + UUID.randomUUID(), source);
    }

    private String add(String targetNo, String source) {
        String target = UUID.randomUUID().toString(), evaluation = UUID.randomUUID().toString();
        OffsetDateTime at = OffsetDateTime.now();
        jdbc.update("INSERT INTO target(target_id,target_no,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " VALUES (?,?,?,?,?,current_timestamp,current_timestamp,0)", target, targetNo, source, fixture.org, fixture.district);
        jdbc.update("INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,"
                + "freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,"
                + "input_snapshot,owner_org_id,district_id,source_mode,created_at)"
                + " VALUES (?,?,?,'ACTIVE','TARGET',?,?,?,'FRESH','FULL','ILLEGAL',70,'HIGH',CAST('[]' AS JSON),CAST('[]' AS JSON),"
                + "CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,?,?)",
                evaluation, fixture.run, fixture.ruleSetVersion, target, at, at, fixture.org, fixture.district, source, at);
        var alarm = policy.apply(new AlarmMergePolicy.MergeInput(evaluation, target, fixture.org, fixture.district, source,
                fixture.ruleSet, fixture.ruleSetVersion, "ILLEGAL", "FULL", "HIGH", new java.math.BigDecimal("70"),
                List.of("NO_AUTHORIZATION"), at, at), new AlarmViolationReasonFilterApiTest.StubParams());
        String recipient = UUID.randomUUID().toString(), handoff = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at)"
                + " VALUES (?,'编号筛选测试','UAV_PUNISHMENT',TRUE,current_timestamp,current_timestamp)", recipient);
        jdbc.update("INSERT INTO handoff(handoff_id,source_kind,source_id,event_id,handoff_type,recipient_id,source_version,"
                + "owner_org_id,district_id,source_mode,submitted_by,created_at)"
                + " VALUES (?,'UAV_EVENT',?,?,'UAV_PUNISHMENT',?,0,?,?,?, ?,current_timestamp)",
                handoff, alarm.eventId(), alarm.eventId(), recipient, fixture.org, fixture.district, source, fixture.actor);
        jdbc.update("INSERT INTO handoff_delivery(delivery_id,handoff_id,attempt_no,delivery_status,receipt_status,created_at)"
                + " VALUES (?,?,1,'PENDING_DELIVERY','NOT_EXPECTED',current_timestamp)", UUID.randomUUID().toString(), handoff);
        return target;
    }

    @ParameterizedTest @ValueSource(strings = {"mock", "replay", "live"})
    void substringSearchCountsBeforePaginationAndKeepsExportAndStatisticsConsistent(String source) throws Exception {
        seed(source);
        String partial = number.substring("目标-".length()).toLowerCase();
        for (String path : LISTS) {
            JsonNode page1 = data(path, partial, 1), page2 = data(path, partial, 2);
            assertThat(page1.path("total").asInt()).as(path).isEqualTo(2);
            assertThat(page2.path("total").asInt()).as(path).isEqualTo(2);
            assertThat(page1.path("items")).hasSize(1);
            assertThat(page2.path("items")).hasSize(1);
            assertThat(page1.path("items").get(0)).isNotEqualTo(page2.path("items").get(0));
            assertThat(data(path, "  " + number + "-001  ", 1).path("total").asInt()).as(path).isEqualTo(1);
            assertThat(data(path, "missing-" + UUID.randomUUID(), 1).path("total").asInt()).isZero();
        }
        for (String path : List.of("/legality-evaluations/summary", "/handoffs/stats")) {
            JsonNode result = json.readTree(mvc.perform(get("/api/v1" + path).param("target_no", partial)
                    .header("Authorization", "Bearer " + fixture.token)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).path("data");
            assertThat(result.path("total").asInt()).as(path).isEqualTo(2);
        }
        String csv = mvc.perform(get("/api/v1/alarms/export.csv").param("target_no", partial)
                .header("Authorization", "Bearer " + fixture.token)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines().skip(1).toList()).hasSize(2);
        assertThat(csv).contains(number + "-001", number + "-002").doesNotContain("OTHER-");
        for (String path : LISTS) {
            mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + fixture.token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        }
    }

    @Test void literalsAndInvalidInputsDoNotWidenTheSearch() throws Exception {
        seed("live");
        for (String path : LISTS) for (String text : List.of("%", "_", "' OR 1=1"))
            assertThat(data(path, text, 1).path("total").asInt()).as(path + text).isZero();
        for (String path : READS) for (String text : List.of(" ", "X".repeat(65)))
            mvc.perform(get("/api/v1" + path).param("target_no", text).header("Authorization", "Bearer " + fixture.token))
                    .andExpect(status().isBadRequest());
        for (String path : READS)
            mvc.perform(get("/api/v1" + path).param("target_no", "one", "two").header("Authorization", "Bearer " + fixture.token))
                    .andExpect(status().isBadRequest());
    }

    @Test void targetPermissionIsCheckedBeforeParsing() throws Exception {
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=? AND permission_code='target:read'", role);
        for (String path : READS)
            mvc.perform(get("/api/v1" + path).param("target_no", " ").header("Authorization", "Bearer " + fixture.token))
                    .andExpect(status().isForbidden());
    }

    @Test void wrongTargetScopeDoesNotAffectTotals() throws Exception {
        seed("replay");
        String otherOrg = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)",
                otherOrg, otherOrg, "其他机构");
        jdbc.update("UPDATE target SET owner_org_id=? WHERE target_id=?", otherOrg, firstTarget);
        for (String path : LISTS)
            assertThat(data(path, number + "-001", 1).path("total").asInt()).as(path).isZero();
    }

    private JsonNode data(String path, String targetNo, int page) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1" + path).param("target_no", targetNo)
                .param("page", String.valueOf(page)).param("size", "1").header("Authorization", "Bearer " + fixture.token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
