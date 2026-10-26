package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository.AlarmQuery;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityEvaluationReadRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.reporting.infrastructure.ReportingRepository;
import com.uav.lowaltitude.platform.report.BusinessReportSource.Range;
import com.uav.lowaltitude.platform.report.BusinessReportSource.SourceScope;
import com.uav.lowaltitude.platform.report.ReportDatasetReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** A real retirement keeps original read-only facts, without restoring current-action eligibility. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrganizationHistoryReadApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AlarmReadRepository alarms;
    @Autowired LegalityEvaluationReadRepository evaluations;
    @Autowired DisposalRepository disposals;
    @Autowired EvidenceRepository evidence;
    @Autowired ReportingRepository reporting;
    @Autowired ReportDatasetReader reports;

    @ParameterizedTest
    @ValueSource(strings={"live", "replay"})
    void deletedOrganizationHistoryRemainsReadableOnlyToUnrestrictedReaders(String mode) throws Exception {
        String actor = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        String district = jdbc.queryForObject("SELECT district_id FROM app_district WHERE enabled=TRUE ORDER BY district_id LIMIT 1", String.class);
        var all = new AccessDecision(actor, ScopeMode.ALL);
        var assigned = new AccessDecision(actor, ScopeMode.ASSIGNED);
        String retired = organization(), other = organization(), disabled = organization();
        OffsetDateTime at = OffsetDateTime.now().minusDays(mode.equals("live") ? 3 : 7);
        Fact original = fact(retired, district, actor, mode, at);
        Fact neighbor = fact(other, district, actor, mode, at.plusHours(1));
        Fact unavailable = fact(disabled, district, actor, mode, at.plusHours(2));
        jdbc.update("UPDATE app_org SET enabled=FALSE WHERE org_id=?", disabled);
        // Retaining a stale assignment row must never grant access to the retired unit.
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)", actor, retired, district);
        String token = login();

        assertThat(alarms.find(original.alarm(), all)).isNotNull();
        assertThat(evidence.subjectVisible("EVENT", original.event(), all)).isTrue();
        mvc.perform(delete("/api/v1/organizations/" + retired).param("expected_version", "0")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", id()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM app_org WHERE org_id=?", Long.class, retired)).isNotNull();
        assertThat(alarms.find(original.alarm(), all).ownerOrgName()).isEqualTo("历史单位-" + retired.substring(0, 8));
        AlarmQuery history = new AlarmQuery(null, null, null, null, null, retired, district, mode, null, null, null);
        assertThat(alarms.count(history, all)).isEqualTo(1);
        assertThat(alarms.list(history, all, 0, 10, "received_at", "desc"))
                .extracting(AlarmReadRepository.AlarmRow::alarmId).containsExactly(original.alarm());
        assertThat(alarms.find(original.alarm(), assigned)).isNull();
        assertThat(alarms.count(history, assigned)).isZero();
        assertThat(alarms.find(neighbor.alarm(), all)).isNotNull();
        assertThat(alarms.find(unavailable.alarm(), all)).isNull();

        var query = new DisposalRepository.Query("UAV_EVENT", original.event(), null, null, null);
        assertThat(disposals.find(original.authorization(), all)).isNotNull();
        assertThat(disposals.list(all, query, 0, 10)).extracting(DisposalRepository.AuthorizationRow::authorizationId)
                .containsExactly(original.authorization());
        assertThat(disposals.find(original.authorization(), assigned)).isNull();
        assertThat(disposals.lock(original.authorization(), all)).isNull();
        assertThat(evidence.subjectReadable("EVENT", original.event(), all)).isTrue();
        assertThat(evidence.subjectReadable("EVENT", original.event(), assigned)).isFalse();
        assertThat(evidence.subjectVisible("EVENT", original.event(), all)).isFalse();
        assertThat(evidence.catalogEnabled(retired, district)).isFalse();
        assertThat(evaluations.alarmReadable(original.alarm(), retired, district, all)).isTrue();
        assertThat(evaluations.alarmVisible(original.alarm(), retired, district, all)).isFalse();

        var day = at.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var source = mode.equals("live") ? SourceScope.LIVE : SourceScope.SIMULATED;
        var range = new Range(day.minusDays(1), day.plusDays(1), source);
        var dataset = alarms.reportDataset(reports, range, all);
        assertThat(reports.rows(dataset, source)).extracting(row -> row.id()).contains(original.alarm(), neighbor.alarm())
                .doesNotContain(unavailable.alarm());
        assertThat(reports.rows(alarms.reportDataset(reports, range, assigned), source))
                .extracting(row -> row.id()).doesNotContain(original.alarm());
        assertThat(reports.rows(disposals.reportDataset(reports, range, all), source))
                .extracting(row -> row.id()).contains(original.authorization());
        assertThat(reporting.organizations(new ReportingRepository.Scope(true, actor)))
                .extracting(ReportingRepository.OrganizationOption::orgId).contains(retired, other).doesNotContain(disabled);
        assertThat(reporting.organizations(new ReportingRepository.Scope(false, actor)))
                .extracting(ReportingRepository.OrganizationOption::orgId).doesNotContain(retired);
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM uav_event WHERE event_id=?", String.class, original.event())).isEqualTo(retired);
    }

    private String organization() {
        String org = id();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",
                org, "H-" + org.substring(0, 12), "历史单位-" + org.substring(0, 8));
        return org;
    }

    private Fact fact(String org, String district, String actor, String mode, OffsetDateTime at) {
        String source = id(), alarm = id(), event = id(), authorization = id();
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES(?,?,?,TRUE,?,?,?,0)",
                source, "H-" + source.substring(0, 12), "历史读取测试来源", mode, at, at);
        jdbc.update("""
                INSERT INTO alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at)
                VALUES(?,?,?,'RULE_LEGALITY','LOW',?,?,?,?,?,?)
                """, alarm, source, alarm, at, at, mode, org, district, at);
        jdbc.update("""
                INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version)
                VALUES(?,?,'FALSE_POSITIVE',?,?,?,?,0)
                """, event, alarm, org, district, at, at);
        String policy = jdbc.queryForObject("SELECT policy_code FROM disposal_policy ORDER BY policy_code LIMIT 1", String.class);
        jdbc.update("""
                INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,
                    channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,created_at,updated_at)
                VALUES(?,?,'COUNTERMEASURE','UAV_EVENT',?,'MANUAL','历史测试申请',?,?,'CANCELLED',?,?,?,?,?,?)
                """, authorization, "H-" + authorization.substring(0, 24), event, actor, at, policy, org, district, mode, at, at);
        return new Fact(alarm, event, authorization);
    }

    private String login() throws Exception {
        var body = json.createObjectNode().put("account", "admin1").put("password", "changeme").put("client_type", "BACKEND");
        String result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("session_id").asText();
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private record Fact(String alarm, String event, String authorization) { }
}
