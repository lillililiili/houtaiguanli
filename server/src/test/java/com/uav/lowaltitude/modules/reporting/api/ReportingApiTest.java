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

    @AfterEach
    void cleanCreatedFixtures() {
        jdbc.update("delete from app_session where user_id in (select user_id from app_user where account like 'itest-stat-%')");
        jdbc.update("delete from app_user_data_scope where user_id in (select user_id from app_user where account like 'itest-stat-%')");
        jdbc.update("delete from app_user where account like 'itest-stat-%'");
        jdbc.update("delete from app_role_permission where role_code in (select role_code from app_role where name like '统计测试角色%')");
        jdbc.update("delete from app_role where name like '统计测试角色%'");
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
    void operationsAggregatesSampleFactsAndDeviceCounts() throws Exception {
        String token = login("admin1", "changeme");
        JsonNode data = data(mvc.perform(get("/api/v1/stats/operations").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.source_mode").value("mock"))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andReturn().getResponse().getContentAsString());

        int total = data.path("summary").path("total").asInt();
        int illegal = data.path("summary").path("illegal").asInt();
        int punish = data.path("summary").path("punish").asInt();
        int highRisk = data.path("summary").path("high_risk").asInt();
        assertThat(total).isGreaterThan(0);
        assertThat(illegal).isBetween(0, total);
        assertThat(highRisk).isBetween(0, total);
        assertThat(punish).isGreaterThan(0);
        assertThat(data.path("days").size()).isEqualTo(30);
        assertThat(sum(data.path("days"), "total")).isEqualTo(total);
        assertThat(sum(data.path("days"), "illegal")).isEqualTo(illegal);
        assertThat(sum(data.path("days"), "punish")).isEqualTo(punish);
        assertThat(sum(data.path("regions"), "total")).isEqualTo(total);
        assertThat(sum(data.path("regions"), "punish")).isEqualTo(punish);
        assertThat(sum(data.path("by_type"), "value")).isEqualTo(total);
        assertThat(sum(data.path("by_risk"), "value")).isEqualTo(total);
        assertThat(sum(data.path("by_duration"), "value")).isEqualTo(total);
        assertThat(sum(data.path("by_track"), "value")).isEqualTo(total);
        assertThat(sum(data.path("alt_bands"), "value")).isEqualTo(data.path("alt_total").asInt());
        assertThat(data.path("alt_total").asInt()).isEqualTo(total);
        assertThat(sum(data.path("by_penalty"), "value")).isEqualTo(punish);
        assertThat(data.path("by_risk").size()).isEqualTo(5);
        assertThat(data.path("regions").size()).isEqualTo(6);
        assertThat(data.path("partners").size()).isBetween(1, 5);

        int dbDevices = jdbc.queryForObject("select count(*) from ops_device", Integer.class);
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
        assertThat(csv).contains("\"总览\",\"合计\",\"飞行/目标总次数\",\"" + total + "\"");
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
