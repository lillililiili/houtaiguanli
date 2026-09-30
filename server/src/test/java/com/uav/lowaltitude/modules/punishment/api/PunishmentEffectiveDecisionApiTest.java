package com.uav.lowaltitude.modules.punishment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Synthetic legal facts in rollback-only tests; no test document represents a real penalty. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class PunishmentEffectiveDecisionApiTest {
    private static final String CASE = "seed-stage14-case-investigating";
    private static final String DISCRETION = "seed-stage14-discretion-draft";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2005-01-01T01:00:00+08:00");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private String auth, actor, org;

    @ParameterizedTest(name="effective decision {0}: {1}")
    @CsvSource({"DRAFT,false", "DECIDED_NO_DOCUMENT,false", "FORMAL,true", "REVOKED,false",
            "DEMO_TEMPLATE,false", "DEMO_RULE,false", "MOCK_SOURCE,false", "TWO_FORMAL,true", "NEWER_DEMO,false"})
    @Transactional
    void caseProjectionMatchesReportingAndKeepsNonEffectiveHistory(String scenario,boolean effective) throws Exception {
        prepare(scenario);
        String document = null;
        if (!List.of("DRAFT","DECIDED_NO_DOCUMENT").contains(scenario)) {
            document = document("DEMO_TEMPLATE".equals(scenario)?"demo-qa":"qa-synthetic-v1",AT);
            if ("TWO_FORMAL".equals(scenario)) document("qa-synthetic-v1",AT.minusMinutes(1));
            if ("NEWER_DEMO".equals(scenario)) document("demo-qa",AT.plusMinutes(1));
            if ("REVOKED".equals(scenario)) {
                assertThat(detail().path("effective_decision").path("status").asText()).isEqualTo("EFFECTIVE");
                mvc.perform(post("/api/v1/decision-documents/{id}/revoke",document).header("Authorization",auth)
                        .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                        .content("{\"reason\":\"QA合成撤销\",\"expected_version\":0}"))
                        .andExpect(status().isOk());
            }
        }
        var result = detail();
        var decision = result.path("effective_decision");
        assertThat(result.path("status").asText()).isEqualTo("DRAFT".equals(scenario)?"INVESTIGATING":"DECIDED");
        assertThat(decision.path("status").asText()).isEqualTo(effective?"EFFECTIVE":"NONE");
        if (effective) {
            assertThat(decision.path("document_id").asText()).isEqualTo(document);
            assertThat(decision.path("penalty_type").asText()).isEqualTo("FINE");
            assertThat(decision.path("fine_amount").asLong()).isEqualTo(12345);
        } else {
            assertThat(decision.hasNonNull("document_id")).isFalse();
            assertThat(decision.hasNonNull("fine_amount")).isFalse();
        }
        int histories = document == null ? 0 : List.of("TWO_FORMAL","NEWER_DEMO").contains(scenario)?2:1;
        assertThat(decision.path("history").size()).isEqualTo(histories);
        if ("REVOKED".equals(scenario)) {
            assertThat(result.path("issued_document_count").asInt()).isZero();
            assertThat(result.path("current_discretion").path("status").asText()).isEqualTo("CONFIRMED");
            assertThat(decision.path("history").get(0).path("status").asText()).isEqualTo("REVOKED");
            assertThat(decision.path("history").get(0).path("revoke_reason").asText()).isEqualTo("QA合成撤销");
        }
        if (List.of("DEMO_TEMPLATE","DEMO_RULE","MOCK_SOURCE","NEWER_DEMO").contains(scenario))
            assertThat(decision.path("history").get(0).path("simulated").asBoolean()).isTrue();

        var rows = json.readTree(mvc.perform(get("/api/v1/punishment-cases").header("Authorization",auth)
                .param("handoff_id","seed-stage14-handoff-punish")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("items");
        JsonNode listed = null;
        for(var row:rows) if(CASE.equals(row.path("case_id").asText())) listed=row;
        assertThat(listed).isNotNull();
        assertThat(listed.path("effective_decision")).isEqualTo(decision);
        var report = json.readTree(mvc.perform(get("/api/v1/stats/operations").header("Authorization",auth)
                .param("from","2005-01-01").param("to","2005-01-01").param("owner_org_id",org))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        int penalties=0;
        for(var row:report.path("by_penalty")) penalties+=row.path("value").asInt();
        assertThat(penalties).isEqualTo(effective?1:0);
    }

    @Test @Transactional
    void effectiveDecisionAndHistoryKeepCaseReadPermissionAndScope() throws Exception {
        prepare("FORMAL");document("qa-synthetic-v1",AT);
        var fixture = new PunishmentFixture(jdbc);
        String denied=fixture.user("NO-PUNISH",List.of("flight:read"),false)[0];
        mvc.perform(get("/api/v1/punishment-cases/{id}",CASE).header("Authorization","Bearer "+denied))
                .andExpect(status().isForbidden());
        String other=fixture.user("OTHER",List.of("punishment:read"),true)[0];
        mvc.perform(get("/api/v1/punishment-cases/{id}",CASE).header("Authorization","Bearer "+other))
                .andExpect(status().isNotFound());
        var listed=json.readTree(mvc.perform(get("/api/v1/punishment-cases").param("handoff_id","seed-stage14-handoff-punish")
                .header("Authorization","Bearer "+other)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(listed.path("data").path("total").asInt()).isZero();
    }

    private void prepare(String scenario) {
        actor=jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class);
        String session=UUID.randomUUID().toString();
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) select ?,user_id,?,'127.0.0.1',permission_version from app_user where user_id=?",
                session,System.currentTimeMillis()+3600000,actor);
        auth="Bearer "+session;
        org=UUID.randomUUID().toString();
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,"QA合成处罚投影-非真实执法");
        jdbc.update("update punishment_case set owner_org_id=?,source_mode=?,filed_at=?,status=?,decided_at=? where case_id=?",
                org,"MOCK_SOURCE".equals(scenario)?"mock":"live",AT,
                "DRAFT".equals(scenario)?"INVESTIGATING":"DECIDED","DRAFT".equals(scenario)?null:AT,CASE);
        jdbc.update("insert into penalty_rule(rule_code,violation_code,title,legal_basis,fine_min,fine_max,penalty_types,schema_status,enabled,created_at,updated_at) values('QA-EFFECTIVE','OTHER','QA合成罚则','QA合成依据',0,999999,'[\"FINE\"]',?,true,?,?)",
                "DEMO_RULE".equals(scenario)?"DEMO":"CONFIRMED",AT,AT);
        jdbc.update("update penalty_discretion set rule_code='QA-EFFECTIVE',penalty_type='FINE',fine_amount=12345,status=?,decided_by=?,decided_at=? where discretion_id=?",
                "DRAFT".equals(scenario)?"DRAFT":"CONFIRMED","DRAFT".equals(scenario)?null:actor,
                "DRAFT".equals(scenario)?null:AT,DISCRETION);
    }

    private String document(String template,OffsetDateTime at) {
        String id=UUID.randomUUID().toString();
        jdbc.update("insert into penalty_decision_document(document_id,document_no,case_id,discretion_id,template_version,status,fields,rendered_sha256,issued_by,issued_at,updated_at) values(?,?,?,?,?,'ISSUED',CAST('{\"synthetic_fixture\":true}' AS JSON),?,?,?,?)",
                id,"QA-"+id,CASE,DISCRETION,template,"a".repeat(64),actor,at,at);
        return id;
    }

    private JsonNode detail() throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/punishment-cases/{id}",CASE).header("Authorization",auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
}
