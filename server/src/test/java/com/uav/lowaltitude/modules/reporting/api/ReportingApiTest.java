package com.uav.lowaltitude.modules.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.reporting.application.ReportingService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportingApiTest {

    private static final String TEMP_PASSWORD = "TempUser#2026A";
    private static final String NEW_PASSWORD = "Changed#2026UserA";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;

    @AfterEach
    void cleanCreatedFixtures() {
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'itest-stat-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'itest-stat-%')");
        jdbc.update("delete from app_user where account like 'itest-stat-%'");
        jdbc.update("delete from app_role_permission where role_code in (select role_code from app_role where name like '统计测试角色%')");
        jdbc.update("delete from app_role where name like '统计测试角色%'");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void repeatedAssessmentsAndRisksCountEachTargetOnceAndKeepHeightDatumsSeparate() throws Exception {
        String token=login("admin1","changeme");
        var at=java.time.OffsetDateTime.parse("2001-01-01T00:00:00+08:00");
        String org="seed-stage3-org", district="seed-stage3-district", id="stats-dedup";
        jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','mock',?,?,?,?)",id,id,at,at.plusHours(12),org,district,at,at);
        jdbc.update("insert into target_latest_state(target_id,height_agl_m,observed_at,received_at,created_at,updated_at) values(?,150,?,?,?,?)",id,at,at,at,at);
        for(int i=0;i<2;i++) {
            jdbc.update("insert into rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,subject_count,evaluated_count,alarm_created_count,alarm_merged_count,source_mode,created_at) select ?,v.rule_set_id,v.rule_set_version_id,'ACTIVE','MANUAL',?,?,'DONE',1,1,0,0,'mock',? from rule_set_version v order by v.rule_set_version_id limit 1","stats-run-"+i,at,at,at);
            jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,owner_org_id,district_id,source_mode,created_at) select ?,r.run_id,r.rule_set_version_id,'ACTIVE','TARGET',?,r.as_of,r.started_at,'FRESH','FULL',?,60,'HIGH',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),?,?,'mock',? from rule_run r where r.run_id=?","stats-eval-"+i,id,i==0?"ABNORMAL":"ILLEGAL",org,district,at.plusMinutes(i),"stats-run-"+i);
            jdbc.update("insert into flight_risk(risk_id,source_id,source_risk_id,plan_id,route_version_id,target_id,risk_type,severity,state_code,reason_code,reason_text,received_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) values(?,'seed-stage3-source',?,'seed-stage3-plan-legal','seed-stage3-rv-legal',?,'AIRSPACE',?,'PENDING_VERIFICATION','PROHIBITED_AIRSPACE_OVERLAP','隔离统计测试',?,'mock',?,?,?,?,0)","stats-risk-"+i,"stats-risk-"+i,id,i==0?"LOW":"HIGH",at.plusMinutes(i),org,district,at,at);
        }
        JsonNode result=operations(token,"2001-01-01");
        assertThat(result.path("summary").path("total").asInt()).isEqualTo(1);
        assertThat(result.path("summary").path("illegal").asInt()).isEqualTo(1);
        assertThat(result.path("summary").path("high_risk").asInt()).isEqualTo(1);
        assertThat(result.path("alt_total").asInt()).isZero();
        jdbc.update("update target_latest_state set altitude_amsl_m=0,observed_at=? where target_id=?",at.plusHours(1),id);
        result=operations(token,"2001-01-01");
        assertThat(result.path("summary").path("total").asInt()).isEqualTo(1);
        assertThat(result.path("alt_total").asInt()).isEqualTo(1);
        assertThat(sum(result.path("alt_bands"),"value")).isEqualTo(1);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void scopeAndDomainPermissionsDoNotLeakOrFabricateZeros() throws Exception {
        String token=login("admin1","changeme");
        String user=jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class);
        var at=java.time.OffsetDateTime.parse("2002-01-01T00:00:00+08:00");
        String org="seed-stage3-org", district="seed-stage3-district";
        String other=jdbc.queryForObject("select district_id from app_district where district_id<>? and enabled=TRUE fetch first 1 row only",String.class,district);
        for(String[] row:new String[][]{{"stats-visible",district},{"stats-hidden",other}})
            jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'replay',?,?,?,?)",row[0],row[0],at,at,org,row[1],at,at);
        jdbc.update("update app_user set scope_mode='ASSIGNED' where user_id=?",user);
        jdbc.update("delete from app_user_data_scope where user_id=?",user);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id) values(?,?,?)",user,org,district);
        JsonNode result=operations(token,"2002-01-01");
        assertThat(result.path("summary").path("total").asInt()).isEqualTo(1);
        jdbc.update("delete from app_role_permission where role_code='ROLE-ADMIN' and permission_code='target:read'");
        sqlSession.clearCache();
        result=operations(token,"2002-01-01");
        assertThat(result.path("summary").path("total").isMissingNode()||result.path("summary").path("total").isNull()).isTrue();
        assertThat(result.path("availability").path("total").path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("by_type").isEmpty()).isTrue();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void onlyFiledCasesAndEffectiveDecisionsContributeNeverHandoffsOrDraftFines() throws Exception {
        String token=login("admin1","changeme");
        String caseId="seed-stage14-case-investigating",discretion="seed-stage14-discretion-draft";
        String actor=jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class);
        var at=java.time.OffsetDateTime.parse("2003-01-01T00:00:00+08:00");
        assertThat(jdbc.update("update punishment_case set filed_at=? where case_id=?",at,caseId)).isEqualTo(1);
        jdbc.update("update handoff set created_at=? where handoff_id='seed-stage14-handoff-punish'",at.minusDays(1));
        assertThat(operations(token,"2002-12-31").path("summary").path("punish").asInt()).isZero();
        JsonNode result=operations(token,"2003-01-01");
        assertThat(result.path("summary").path("punish").asInt()).isEqualTo(1);
        assertThat(sum(result.path("by_penalty"),"value")).isZero();
        assertThat(result.path("partners").get(0).path("fine").isMissingNode()||result.path("partners").get(0).path("fine").isNull()).isTrue();
        jdbc.update("update penalty_discretion set status='CONFIRMED',fine_amount=12345,decided_by=?,decided_at=? where discretion_id=?",actor,at,discretion);
        jdbc.update("insert into penalty_decision_document(document_id,document_no,case_id,discretion_id,template_version,status,fields,rendered_sha256,issued_by,issued_at,updated_at) values('stats-doc','STATS-DOC',?,?,'v1','ISSUED',CAST('{}' AS JSON),?,?,?,?)",caseId,discretion,"a".repeat(64),actor,at,at);
        result=operations(token,"2003-01-01");
        assertThat(sum(result.path("by_penalty"),"value")).isEqualTo(1);
        assertThat(result.path("partners").get(0).path("fine").decimalValue()).isEqualByComparingTo("123.45");
        jdbc.update("update penalty_decision_document set status='REVOKED',revoked_at=?,revoke_reason='隔离测试撤销' where document_id='stats-doc'",at.plusHours(1));
        assertThat(sum(operations(token,"2003-01-01").path("by_penalty"),"value")).isZero();
    }

    private JsonNode operations(String token,String day) throws Exception {
        return data(mvc.perform(get("/api/v1/stats/operations").param("from",day).param("to",day).header("Authorization",bearer(token)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/stats/operations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/stats/operations/export.csv"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/stats/reports/preview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/stats/reports/export.xlsx"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void businessTargetsUseFirstSeenAndExposeUnavailableMetrics() throws Exception {
        String id = "stat-" + UUID.randomUUID().toString().substring(0, 20);
        var tuple = jdbc.queryForMap("select o.org_id,d.district_id from app_org o cross join app_district d where o.enabled=TRUE and d.enabled=TRUE fetch first 1 row only");
        var first = java.time.OffsetDateTime.parse("2040-01-01T15:59:59Z");
        var last = java.time.OffsetDateTime.parse("2040-01-02T02:00:00Z");
        jdbc.update("insert into target(target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,?,'replay',?,?,?,?)",
                id,id,"UAV",first,last,tuple.get("org_id"),tuple.get("district_id"),first,last);
        try {
            String token = login("admin1", "changeme");
            JsonNode firstDay = data(mvc.perform(get("/api/v1/stats/operations")
                    .param("from", "2040-01-01").param("to", "2040-01-01").header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(firstDay.path("summary").path("total").asInt()).isEqualTo(1);
            assertThat(firstDay.path("source_mode").asText()).isEqualTo("replay");
            assertThat(firstDay.path("simulated").asBoolean()).isTrue();
            assertThat(firstDay.path("generated_at").asLong()).isPositive();
            assertThat(firstDay.path("availability").path("by_duration").path("status").asText()).isEqualTo("UNAVAILABLE");
            assertThat(firstDay.path("by_duration").isEmpty()).isTrue();
            assertThat(firstDay.path("alt_total").asInt()).isZero();
            assertThat(firstDay.path("availability").path("alt_bands").path("missing_count").asInt()).isEqualTo(1);
            JsonNode nextDay = data(mvc.perform(get("/api/v1/stats/operations")
                    .param("from", "2040-01-02").param("to", "2040-01-02").header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(nextDay.path("summary").path("total").asInt()).isZero();
        } finally { jdbc.update("delete from target where target_id=?", id); }
    }

    @Test
    void operationsAggregatesBusinessFactsAndDeviceCounts() throws Exception {
        String token = login("admin1", "changeme");
        JsonNode data = data(mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))

                .andExpect(jsonPath("$.data.simulated").value(true))
                .andReturn().getResponse().getContentAsString());

        int total = data.path("summary").path("total").asInt();
        int illegal = data.path("summary").path("illegal").asInt();
        int punish = data.path("summary").path("punish").asInt();
        int highRisk = data.path("summary").path("high_risk").asInt();
        assertThat(total).isGreaterThan(0);
        assertThat(illegal).isBetween(0, total);
        assertThat(highRisk).isBetween(0, total);
        assertThat(punish).isGreaterThanOrEqualTo(0);
        assertThat(data.path("days").size()).isEqualTo(30);
        assertThat(sum(data.path("days"), "total")).isEqualTo(total);
        assertThat(sum(data.path("days"), "illegal")).isEqualTo(illegal);
        assertThat(sum(data.path("days"), "punish")).isEqualTo(punish);
        assertThat(sum(data.path("regions"), "total")).isEqualTo(total);
        assertThat(sum(data.path("regions"), "punish")).isEqualTo(punish);
        assertThat(sum(data.path("by_type"), "value")).isEqualTo(total);
        assertThat(sum(data.path("by_risk"), "value")).isEqualTo(total);
        assertThat(data.path("by_duration").isEmpty()).isTrue();
        assertThat(data.path("by_track").isEmpty()).isTrue();
        assertThat(sum(data.path("alt_bands"), "value")).isEqualTo(data.path("alt_total").asInt());
        assertThat(data.path("alt_total").asInt()).isBetween(0,total);
        assertThat(sum(data.path("by_penalty"), "value")).isLessThanOrEqualTo(punish);
        assertThat(data.path("by_risk").size()).isEqualTo(5);
        assertThat(data.path("regions").size()).isGreaterThanOrEqualTo(6);
        assertThat(data.path("partners").size()).isBetween(0, 5);

        int dbDevices = jdbc.queryForObject("select count(*) from ops_device where deleted_at is null", Integer.class);
        assertThat(data.path("devices").path("total").asInt()).isEqualTo(dbDevices);
        assertThat(data.path("devices").path("online").asInt()).isBetween(0, dbDevices);
    }

    @Test
    void dateRangeFiltersAndRejectsInvalidBounds() throws Exception {
        String token = login("admin1", "changeme");
        JsonNode full = data(mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String from = full.path("from").asText();
        String to = full.path("to").asText();
        JsonNode oneDay = data(mvc.perform(get("/api/v1/stats/operations")
                        .param("from", to).param("to", to)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(oneDay.path("from").asText()).isEqualTo(to);
        assertThat(oneDay.path("to").asText()).isEqualTo(to);
        assertThat(oneDay.path("days").size()).isEqualTo(1);
        assertThat(oneDay.path("summary").path("total").asInt())
                .isLessThanOrEqualTo(full.path("summary").path("total").asInt());

        mvc.perform(get("/api/v1/stats/operations").param("from", to).param("to", from)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/stats/operations").param("from", "2026-13-01")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/stats/operations/export.csv").param("from", to).param("to", from)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void exportCsvMatchesOperationsAndWritesAudit() throws Exception {
        String token = login("admin1", "changeme");
        JsonNode data = data(mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        int total = data.path("summary").path("total").asInt();
        int punish = data.path("summary").path("punish").asInt();
        String from = data.path("from").asText();
        String to = data.path("to").asText();

        String csv = mvc.perform(get("/api/v1/stats/operations/export.csv")
                        .param("from", from).param("to", to)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"operations-stats.csv\""))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("\"分类\",\"项目\",\"指标\",\"数值\"");
        assertThat(csv).contains("\"总览\",\"合计\",\"新增目标数\",\"" + total + "\"");
        assertThat(csv).contains("\"总览\",\"合计\",\"处罚案件数\",\"" + punish + "\"");
        assertThat(csv).contains("\"元数据\",\"统计区间\",\"开始日期\",\"" + from + "\"");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action='stats_export_requested' and module_code='statistics' and detail like ?",
                Integer.class, "%from=" + from + "; to=" + to + "%")).isGreaterThan(0);
    }

    @Test
    void reportPreviewUsesNaturalMonthAndRejectsInvalidSelections() throws Exception {
        String token = login("admin1", "changeme");
        LocalDate today = LocalDate.now(ReportingService.ZONE);
        JsonNode preview = data(mvc.perform(get("/api/v1/stats/reports/preview")
                        .param("report_type", "MONTHLY").param("anchor_date", today.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_type").value("MONTHLY"))
                .andExpect(jsonPath("$.data.anchor_date").value(today.toString()))
                .andExpect(jsonPath("$.data.report.from").value(today.withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.data.report.to").value(today.toString()))
                .andReturn().getResponse().getContentAsString());
        assertThat(preview.path("period_label").asText()).isNotBlank();
        assertThat(preview.path("generated_at").asLong()).isPositive();

        mvc.perform(get("/api/v1/stats/reports/preview")
                        .param("report_type", "YEARLY").param("anchor_date", today.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(get("/api/v1/stats/reports/preview")
                        .param("report_type", "DAILY").param("anchor_date", today.plusDays(1).toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void excelExportContainsFourStyledDataSheetsAndAudit() throws Exception {
        String token = login("admin1", "changeme");
        LocalDate today = LocalDate.now(ReportingService.ZONE);
        byte[] bytes = mvc.perform(get("/api/v1/stats/reports/export.xlsx")
                        .param("report_type", "DAILY").param("anchor_date", today.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 'P', (byte) 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetName(0)).isEqualTo("报表摘要");
            assertThat(workbook.getSheetName(1)).isEqualTo("每日趋势");
            assertThat(workbook.getSheetName(2)).isEqualTo("分类分布");
            assertThat(workbook.getSheetName(3)).isEqualTo("区域与处置");
            assertThat(workbook.getSheet("每日趋势").getRow(2).getCell(0).getStringCellValue()).isEqualTo("日期");
            assertThat(workbook.getSheet("每日趋势").getRow(3).getCell(1).getCellType().name()).isEqualTo("NUMERIC");
            assertThat(workbook.getSheet("分类分布").getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(workbook.getSheet("每日趋势").getPaneInformation()).isNotNull();
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action='stats_export_requested' and module_code='statistics' and detail like ?",
                Integer.class, "%format=XLSX; report_type=DAILY%")).isGreaterThan(0);
    }

    @Test
    void roleWithoutStatisticsCannotReadOperations() throws Exception {
        String admin = login("admin1", "changeme");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode catalog = data(mvc.perform(get("/api/v1/permissions/catalog").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ObjectNode roleBody = json.createObjectNode();
        ArrayNode permissions = roleBody.putArray("permissions");
        for (JsonNode item : catalog) {
            String code = item.path("permission_code").asText();
            boolean devices = "devices".equals(code);
            permissions.addObject().put("permission_code", code).put("level", devices ? "READ" : "NONE")
                    .put("menu_enabled", devices);
        }
        roleBody.put("name", "统计测试角色-" + suffix).put("description", "无统计分析权限");
        String roleCode = data(mvc.perform(post("/api/v1/roles").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "stat-role-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(roleBody)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("role_code").asText();
        String orgId = jdbc.queryForObject("select org_id from app_org where org_code='ORG-DEV'", String.class);
        String account = "itest-stat-" + suffix;
        ObjectNode createUser = json.createObjectNode().put("account", account).put("name", "统计越权用户")
                .put("org_id", orgId).put("role_code", roleCode).put("temporary_password", TEMP_PASSWORD);
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(admin))
                        .header("Idempotency-Key", "stat-user-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(createUser)))
                .andExpect(status().isOk());
        String first = login(account, TEMP_PASSWORD);
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current_password\":\"" + TEMP_PASSWORD + "\",\"new_password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk());
        String userSession = login(account, NEW_PASSWORD);
        mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/stats/operations/export.csv").header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/stats/reports/preview")
                        .param("report_type", "DAILY").param("anchor_date", LocalDate.now(ReportingService.ZONE).toString())
                        .header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/stats/reports/export.xlsx")
                        .param("report_type", "DAILY").param("anchor_date", LocalDate.now(ReportingService.ZONE).toString())
                        .header("Authorization", bearer(userSession)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static int sum(JsonNode array, String field) {
        int total = 0;
        for (JsonNode item : array) total += item.path(field).asInt();
        return total;
    }

    private String login(String account, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("account", account).put("password", password).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return data(body).path("session_id").asText();
    }

    private JsonNode data(String body) throws Exception { return json.readTree(body).path("data"); }

    private static String bearer(String token) { return "Bearer " + token; }
}
