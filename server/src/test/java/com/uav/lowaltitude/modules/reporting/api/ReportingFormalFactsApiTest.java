package com.uav.lowaltitude.modules.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

/** Rollback-only synthetic facts test reporting semantics, never legal validity or a real penalty. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class ReportingFormalFactsApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @ParameterizedTest(name = "synthetic decision={0}; effective penalties={1}")
    @CsvSource({"NONE,0", "FORMAL,1", "TWO_FORMAL,1", "DEMO_TEMPLATE,0", "DEMO_RULE,0", "DRAFT,0", "SUPERSEDED,0", "REVOKED,0", "MOCK_SOURCE,0"})
    @Transactional
    void exactCountsSeparateTargetsFilingsAndEffectiveDecisions(String scenario, int effective) throws Exception {
        String auth = "Bearer " + json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        String org = UUID.randomUUID().toString();
        String actor = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        var at = OffsetDateTime.parse("2005-01-01T00:00:00+08:00");
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",
                org, org, "QA合成统计事实-非真实执法");
        for (int index = 0; index < 4; index++) {
            String id = UUID.randomUUID().toString();
            var first = index == 3 ? at.plusDays(1) : at.plusHours(index);
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV',?,?,'seed-stage3-district',?,?)",
                    id, id, first, at.plusDays(5), index == 2 ? "mock" : "live", org, first, at.plusDays(5));
        }
        // Existing seeded case remains in this rollback transaction or disposable PostgreSQL schema only.
        String caseId = "seed-stage14-case-investigating", discretion = "seed-stage14-discretion-draft";
        jdbc.update("update punishment_case set owner_org_id=?,filed_at=?,source_mode=?,party_type='ORG',party_name='QA合成主体' where case_id=?",
                org, at, "MOCK_SOURCE".equals(scenario) ? "mock" : "live", caseId);
        jdbc.update("update handoff set created_at=? where handoff_id='seed-stage14-handoff-punish'", at.minusDays(1));
        if (!"NONE".equals(scenario)) {
            jdbc.update("insert into penalty_rule(rule_code,violation_code,title,legal_basis,fine_min,fine_max,penalty_types,schema_status,enabled,created_at,updated_at) values('QA-SYNTHETIC','OTHER','隔离测试规则','合成测试值，不构成法律依据',0,999999,'[\"FINE\"]',?,true,?,?)",
                    "DEMO_RULE".equals(scenario) ? "DEMO" : "CONFIRMED", at, at);
            String discretionStatus = "DRAFT".equals(scenario) ? "DRAFT" : "SUPERSEDED".equals(scenario) ? "SUPERSEDED" : "CONFIRMED";
            jdbc.update("update penalty_discretion set rule_code='QA-SYNTHETIC',penalty_type='FINE',fine_amount=12345,status=?,decided_by=?,decided_at=? where discretion_id=?",
                    discretionStatus, actor, at, discretion);
            String doc = UUID.randomUUID().toString();
            jdbc.update("insert into penalty_decision_document(document_id,document_no,case_id,discretion_id,template_version,status,fields,rendered_sha256,issued_by,issued_at,updated_at) values(?,?,?,?,?,'ISSUED',CAST('{\"synthetic_fixture\":true}' AS JSON),?,?,?,?)",
                    doc, "QA-" + doc, caseId, discretion, "DEMO_TEMPLATE".equals(scenario) ? "demo-qa" : "qa-synthetic-v1", "a".repeat(64), actor, at.plusHours(1), at.plusHours(1));
            if ("REVOKED".equals(scenario)) jdbc.update("update penalty_decision_document set status='REVOKED',revoked_at=?,revoke_reason='隔离测试撤销' where document_id=?", at.plusHours(2), doc);
            if ("TWO_FORMAL".equals(scenario)) {
                String older = UUID.randomUUID().toString();
                jdbc.update("insert into penalty_decision_document(document_id,document_no,case_id,discretion_id,template_version,status,fields,rendered_sha256,issued_by,issued_at,updated_at) values(?,?,?,?,'qa-synthetic-v1','ISSUED',CAST('{\"synthetic_fixture\":true}' AS JSON),?,?,?,?)",
                        older, "QA-" + older, caseId, discretion, "b".repeat(64), actor, at, at);
            }
        }
        int filed = "MOCK_SOURCE".equals(scenario) ? 0 : 1;
        JsonNode report = json.readTree(mvc.perform(get("/api/v1/stats/operations").header("Authorization", auth)
                .param("from", "2005-01-01").param("to", "2005-01-01").param("owner_org_id", org))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(report.path("summary").path("total").asInt()).isEqualTo(2);
        assertThat(report.path("summary").path("punish").asInt()).isEqualTo(filed);
        assertThat(sum(report.path("days"), "total")).isEqualTo(2);
        assertThat(sum(report.path("regions"), "total")).isEqualTo(2);
        assertThat(sum(report.path("days"), "punish")).isEqualTo(filed);
        assertThat(sum(report.path("regions"), "punish")).isEqualTo(filed);
        assertThat(sum(report.path("by_penalty"), "value")).isEqualTo(effective);
        assertThat(report.path("availability").path("by_penalty").path("missing_count").asInt()).isEqualTo(filed - effective);
        if (filed == 1) {
            assertThat(report.path("partners").get(0).path("case_count").asInt()).isEqualTo(1);
            if (effective == 1) assertThat(report.path("partners").get(0).path("fine").decimalValue()).isEqualByComparingTo("123.45");
            else assertThat(report.path("partners").get(0).hasNonNull("fine")).isFalse();
        }
        String csv = mvc.perform(get("/api/v1/stats/operations/export.csv").header("Authorization", auth)
                .param("from", "2005-01-01").param("to", "2005-01-01").param("owner_org_id", org))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"新增目标数\",\"2\"", "\"处罚案件数\",\"" + filed + "\"");
        JsonNode preview = json.readTree(mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization", auth)
                .param("report_type", "DAILY").param("anchor_date", "2005-01-01"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("report");
        assertThat(preview.path("summary")).isEqualTo(report.path("summary"));
        assertThat(preview.path("by_penalty")).isEqualTo(report.path("by_penalty"));
        byte[] workbook = mvc.perform(get("/api/v1/stats/reports/export.xlsx").header("Authorization", auth)
                .param("report_type", "DAILY").param("anchor_date", "2005-01-01"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
            var day = book.getSheet("每日趋势").getRow(3);
            assertThat(day.getCell(1).getNumericCellValue()).isEqualTo(2);
            assertThat(day.getCell(3).getNumericCellValue()).isEqualTo(filed);
            int exportedPenalties = 0;
            for (var row : book.getSheet("分类分布")) {
                if (row.getCell(0) != null && "处罚类型".equals(row.getCell(0).toString()))
                    exportedPenalties += (int) row.getCell(2).getNumericCellValue();
            }
            assertThat(exportedPenalties).isEqualTo(effective);
        }
        // The day of transfer must never turn into a filing date.
        JsonNode previous = json.readTree(mvc.perform(get("/api/v1/stats/operations").header("Authorization", auth)
                .param("from", "2004-12-31").param("to", "2004-12-31").param("owner_org_id", org))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(previous.path("summary").path("punish").asInt()).isZero();
    }

    private static int sum(JsonNode rows, String field) {
        int total = 0;
        for (JsonNode row : rows) total += row.path(field).asInt();
        return total;
    }
}
