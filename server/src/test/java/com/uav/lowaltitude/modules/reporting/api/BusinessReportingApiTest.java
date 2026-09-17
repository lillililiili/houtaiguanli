package com.uav.lowaltitude.modules.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.uav.lowaltitude.modules.reporting.application.ReportingService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessReportingApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        // Explicit opt-in, disposable database only; never use the local business database.
        String url=System.getenv("REPORT_TEST_PG_URL");
        if(url!=null) {
            if(!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/report_test_[a-z0-9_]+"))
                throw new IllegalArgumentException("Reporting tests require a disposable report_test_* database");
            r.add("spring.datasource.url",()->url);
            r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
            r.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));
            r.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));
            r.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
        }
    }
    private String token() throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
    private String today() {return LocalDate.now(ReportingService.ZONE).toString();}

    @Test
    @org.springframework.transaction.annotation.Transactional
    void mqttDeviceAndMaintenanceRequireTheirOwnSharedTuple() throws Exception {
        String auth="Bearer "+token();
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        var sourceDevice=jdbc.queryForMap("SELECT device_id,source_id,external_device_id FROM device"
                +" WHERE source_mode='replay' AND external_device_id IS NOT NULL FETCH FIRST 1 ROWS ONLY");
        var device=java.util.Map.of("device_id","report-ops","source_id","report-source");
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,source_mode,created_at,updated_at)"
                +" VALUES('report-source','REPORT-SOURCE','报表测试来源','replay',0,0)");
        jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_name,channel,source_mode,created_at,updated_at)"
                +" VALUES('report-ops','report-source',?,'REPORT-DEVICE','报表设备','雷达','MQTT','replay',0,0)",sourceDevice.get("external_device_id"));
        var plan=jdbc.queryForMap("SELECT plan_id,owner_org_id,district_id FROM flight_plan WHERE owner_org_id IS NOT NULL AND district_id IS NOT NULL FETCH FIRST 1 ROWS ONLY");
        String org=plan.get("owner_org_id").toString(), district=plan.get("district_id").toString();
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES('report-other','REPORT-OTHER','另一报表区域',TRUE,0,0,0)");
        jdbc.update("INSERT INTO mqtt_broker(broker_id,name,host,port,tls,client_id,allowed_cidrs,source_mode,owner_org_id,district_id,created_at,updated_at)"
                +" VALUES('report-broker','报表隔离测试','127.0.0.1',1883,FALSE,'report-test-client','127.0.0.1/32','replay',?,?,0,0)",org,district);
        jdbc.update("INSERT INTO mqtt_device_binding(ops_device_id,device_id,ops_source_id,source_id,broker_id,provider_code,device_type_abbr,external_device_id,source_mode)"
                +" VALUES(?,?,?,?,'report-broker','report-test','radar',?,'replay')",
                device.get("device_id"),sourceDevice.get("device_id"),device.get("source_id"),sourceDevice.get("source_id"),sourceDevice.get("external_device_id"));
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                device.get("device_id"),org,"report-other");
        jdbc.update("INSERT INTO ops_device_maintenance_task(task_id,plan_id,device_id,owner_org_id,district_id,device_name,reason,simulated,status,active_key,reported_by,reported_by_name,reported_at,version)"
                +" VALUES('report-task',?,?,?,?, '范围测试设备','范围测试异常',TRUE,'PENDING','report-task-key',?,'范围测试人',?,1)",
                plan.get("plan_id"),device.get("device_id"),org,district,user,System.currentTimeMillis());
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE user_id=?",user);
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,org,district);
        sqlSession.clearCache();
        String uri="/api/v1/stats/reports/details";
        JsonNode hidden=json.readTree(mvc.perform(get(uri).header("Authorization",auth)
                .param("report_category","DEVICE_OPERATIONS").param("period_type","DAILY").param("anchor_date",today())
                .param("section","maintenance")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hidden.path("data").path("items").toString()).doesNotContain("report-task");
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,org,"report-other");
        sqlSession.clearCache();
        JsonNode visible=json.readTree(mvc.perform(get(uri).header("Authorization",auth)
                .param("report_category","DEVICE_OPERATIONS").param("period_type","DAILY").param("anchor_date",today())
                .param("section","maintenance")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(visible.path("data").path("items").toString()).contains("report-task");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void forbiddenSourcesAreNotReportedAsZeroAndExportsEnforcePermissions() throws Exception {
        String auth="Bearer "+token();
        jdbc.update("DELETE FROM app_role_permission WHERE role_code='ROLE-ADMIN' AND permission_code='risk:read'");
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth)
                .param("report_category","OVERVIEW").param("period_type","DAILY").param("anchor_date",today()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sections[2].accessible").value(false))
                .andExpect(jsonPath("$.data.sections[2].total").doesNotExist());
        for(String endpoint:java.util.List.of("preview","export.xlsx","export.pdf")) {
            mvc.perform(get("/api/v1/stats/reports/"+endpoint).header("Authorization",auth)
                    .param("report_category","ALARM_RISK").param("period_type","DAILY").param("anchor_date",today()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/stats/reports/details").header("Authorization",auth)
                .param("report_category","ALARM_RISK").param("period_type","DAILY").param("anchor_date",today())
                .param("section","alarms")).andExpect(status().isForbidden());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void shanghaiBoundariesAndAssignedScopeUseOneExactTuple() throws Exception {
        String auth="Bearer "+token();
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES('report-org','REPORT-ORG','报表组织',TRUE,0,0,0)");
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES('report-district','REPORT-DISTRICT','报表区域',TRUE,0,0,0)");
        String org=jdbc.queryForObject("SELECT org_id FROM app_org WHERE org_code='ORG-DEV'",String.class);
        String district=jdbc.queryForObject("SELECT district_id FROM app_district WHERE district_id<>'report-district' ORDER BY district_id FETCH FIRST 1 ROWS ONLY",String.class);
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE user_id=?",user);
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,org,district);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,"report-org","report-district");
        String insert="INSERT INTO target(target_id,target_no,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                +" VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)";
        var start=java.time.OffsetDateTime.parse("2001-01-01T00:00:00+08:00");
        jdbc.update(insert,"report-visible","REPORT-VISIBLE",start,start,"live",org,district);
        jdbc.update(insert,"report-cross","REPORT-CROSS",start,start,"mock",org,"report-district");
        jdbc.update(insert,"report-before","REPORT-BEFORE",start.minusNanos(1000000),start.minusNanos(1000000),"live",org,district);
        jdbc.update(insert,"report-after","REPORT-AFTER",start.plusDays(1),start.plusDays(1),"live",org,district);
        sqlSession.clearCache();
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth).param("report_category","OVERVIEW")
                .param("period_type","DAILY").param("anchor_date","2001-01-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sections[0].total").value(1))
                .andExpect(jsonPath("$.data.sections[0].days[0].value").value(1))
                .andExpect(jsonPath("$.data.source_mode").value("live"));
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE user_id=?",user);
        sqlSession.clearCache();
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth).param("report_category","OVERVIEW")
                .param("period_type","DAILY").param("anchor_date",today())).andExpect(status().isForbidden());
    }
    @ParameterizedTest
    @CsvSource({
        "OVERVIEW,DAILY","OVERVIEW,WEEKLY","OVERVIEW,MONTHLY",
        "DEVICE_OPERATIONS,DAILY","DEVICE_OPERATIONS,WEEKLY","DEVICE_OPERATIONS,MONTHLY",
        "ALARM_RISK,DAILY","ALARM_RISK,WEEKLY","ALARM_RISK,MONTHLY",
        "FLIGHT_VERIFICATION,DAILY","FLIGHT_VERIFICATION,WEEKLY","FLIGHT_VERIFICATION,MONTHLY",
        "EVENT_DISPOSAL,DAILY","EVENT_DISPOSAL,WEEKLY","EVENT_DISPOSAL,MONTHLY"})
    void allCategoriesUseBusinessQueriesAndDetails(String category,String period) throws Exception {
        String auth="Bearer "+token();
        var response=mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth)
                .param("report_category",category).param("period_type",period).param("anchor_date",today()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.report_category").value(category))
                .andExpect(jsonPath("$.data.period_type").value(period)).andReturn().getResponse();
        JsonNode data=json.readTree(response.getContentAsString()).path("data");
        for(JsonNode section:data.path("sections")) {
            assertThat(section.path("accessible").asBoolean()).isTrue();
            if(!section.path("snapshot").asBoolean()) {
                long sum=0;for(JsonNode day:section.path("days"))sum+=day.path("value").asLong();
                assertThat(sum).isEqualTo(section.path("total").asLong());
            }
            if(!category.equals("OVERVIEW")) {
                mvc.perform(get("/api/v1/stats/reports/details").header("Authorization",auth)
                        .param("report_category",category).param("period_type",period).param("anchor_date",today())
                        .param("section",section.path("key").asText()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(20))
                        .andExpect(jsonPath("$.data.total").value(section.path("total").asInt()));
            }
        }
    }
    @ParameterizedTest
    @CsvSource({"OVERVIEW","DEVICE_OPERATIONS","ALARM_RISK","FLIGHT_VERIFICATION","EVENT_DISPOSAL"})
    void downloadsContainChineseAndValidWorkbook(String category) throws Exception {
        String auth="Bearer "+token();
        Path dir=Path.of("target","report-qa");Files.createDirectories(dir);
        byte[] pdf=mvc.perform(get("/api/v1/stats/reports/export.pdf").header("Authorization",auth)
                .param("report_category",category).param("period_type","MONTHLY").param("anchor_date",today()))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
        try(var doc=Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("统计口径","状态截至生成时");
            assertThat(doc.getNumberOfPages()).isGreaterThan(0);
        }
        Files.write(dir.resolve(category+".pdf"),pdf);
        byte[] xlsx=mvc.perform(get("/api/v1/stats/reports/export.xlsx").header("Authorization",auth)
                .param("report_category",category).param("period_type","MONTHLY").param("anchor_date",today()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(book.getSheet("报表摘要")).isNotNull();
            assertThat(book.getSheet("每日趋势")).isNotNull();
        }
        Files.write(dir.resolve(category+".xlsx"),xlsx);
    }
    @Test void validatesDatesSectionsAndAuthentication() throws Exception {
        mvc.perform(get("/api/v1/stats/reports/export.pdf")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/stats/reports/details")).andExpect(status().isUnauthorized());
        String auth="Bearer "+token();
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth).param("report_category","NOPE")
                .param("period_type","DAILY").param("anchor_date",today())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth).param("report_category","OVERVIEW")
                .param("period_type","DAILY").param("anchor_date","2999-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stats/reports/details").header("Authorization",auth).param("report_category","DEVICE_OPERATIONS")
                .param("period_type","DAILY").param("anchor_date",today()).param("section","plans")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stats/reports/details").header("Authorization",auth).param("report_category","DEVICE_OPERATIONS")
                .param("period_type","DAILY").param("anchor_date",today()).param("section","devices").param("size","101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth).param("report_type","DAILY")
                .param("anchor_date",today())).andExpect(status().isOk()).andExpect(jsonPath("$.data.report.summary").exists());
    }
}
