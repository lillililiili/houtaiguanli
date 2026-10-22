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

    @Test void simulationIsDisabledByDefaultForAllBusinessReportReads() throws Exception {
        String auth="Bearer "+token();
        for(String endpoint:java.util.List.of("preview","details","export.xlsx","export.pdf")) {
            mvc.perform(get("/api/v1/stats/reports/"+endpoint).header("Authorization",auth)
                    .param("report_category","FLIGHT_VERIFICATION").param("period_type","DAILY")
                    .param("anchor_date",today()).param("section","plans").param("source_mode","simulated"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("SIMULATED_REPORT_DISABLED"));
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void simulatedMqttDeviceAndMaintenanceStayExcludedEvenWithAllSharedTuples() throws Exception {
        String auth="Bearer "+token();
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        // This case owns its provenance and scope fixtures; no replay/demo seeder is required.
        String tag=java.util.UUID.randomUUID().toString().substring(0,12);
        String org="report-org-"+tag, district="report-district-"+tag, otherDistrict="report-other-"+tag;
        String source="report-source-"+tag, sourceDevice="report-device-"+tag, external="report-external-"+tag;
        String opsSource="report-ops-source-"+tag, device="report-ops-"+tag, broker="report-broker-"+tag;
        String route="report-route-"+tag, routeVersion="report-route-v-"+tag, plan="report-plan-"+tag;
        String task="report-task-"+tag, liveDevice="report-live-device-"+tag, liveTask="report-live-task-"+tag;
        long now=System.currentTimeMillis();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",org,org,"报表来源隔离单位");
        for(String id:java.util.List.of(district,otherDistrict)) jdbc.update(
                "INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",id,id,id);
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,?,TRUE,'live',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                route,route,"报表范围航线",org,district);
        boolean postgres=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection->connection.getMetaData().getDatabaseProductName().contains("PostgreSQL")));
        String geometry=postgres?"ST_GeomFromText('LINESTRING(117 36,117.01 36.01)',4326)"
                :"CAST('SRID=4326;LINESTRING(117 36,117.01 36.01)' AS GEOMETRY)";
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,valid_from,created_at) VALUES(?,?,1,"+geometry+",CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",routeVersion,route);
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'APPROVED','live',?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                plan,plan,routeVersion,org,district);
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,protocol_code,source_mode,created_at,updated_at) VALUES(?,?,?,'LINGYUN_MQTT_V8_6','replay',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",source,source,"报表模拟感知来源");
        jdbc.update("INSERT INTO device(device_id,source_id,external_device_id,device_no,name,device_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,?,?,'RADAR','replay',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sourceDevice,source,external,sourceDevice,"报表模拟感知设备",org,district);
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,'LINGYUN_MQTT_V8_6','replay',TRUE,0,0)",opsSource,opsSource,"报表模拟运维来源");
        jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_name,channel,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,?,?,'雷达','MQTT','replay',TRUE,0,0)",
                device,opsSource,external,device,"报表模拟设备");
        jdbc.update("INSERT INTO mqtt_broker(broker_id,name,host,port,tls,client_id,allowed_cidrs,source_mode,owner_org_id,district_id,created_at,updated_at)"
                +" VALUES(?,'报表隔离测试','127.0.0.1',1883,FALSE,?,'127.0.0.1/32','replay',?,?,0,0)",broker,broker,org,district);
        jdbc.update("INSERT INTO mqtt_device_binding(ops_device_id,device_id,ops_source_id,source_id,broker_id,provider_code,device_type_abbr,external_device_id,source_mode)"
                +" VALUES(?,?,?,?,?,'report-test','radar',?,'replay')",device,sourceDevice,opsSource,source,broker,external);
        // A real-source control proves that an empty/broken report cannot satisfy the exclusion assertion.
        jdbc.update("INSERT INTO ops_device(device_id,device_no,name,device_type_name,channel,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,'雷达','TCP','live',FALSE,0,0)",
                liveDevice,liveDevice,"报表真实来源对照设备");
        for(String id:java.util.List.of(device,liveDevice)) {
            jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,org,otherDistrict);
            String taskId=id.equals(device)?task:liveTask;
            jdbc.update("INSERT INTO ops_device_maintenance_task(task_id,plan_id,device_id,owner_org_id,district_id,device_name,reason,simulated,status,active_key,reported_by,reported_by_name,reported_at,version)"
                    +" VALUES(?,?,?,?,?,'范围测试设备','范围测试异常',?,'PENDING',? ,?,'范围测试人',?,1)",
                    taskId,plan,id,org,district,id.equals(device),taskId,user,now);
        }
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED',scope_org_rule=NULL WHERE user_id=?",user);
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,org,district);
        sqlSession.clearCache();
        String uri="/api/v1/stats/reports/details";
        JsonNode hidden=json.readTree(mvc.perform(get(uri).header("Authorization",auth)
                .param("report_category","DEVICE_OPERATIONS").param("period_type","DAILY").param("anchor_date",today())
                .param("section","maintenance")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hidden.path("data").path("items").toString()).doesNotContain(task);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,org,otherDistrict);
        sqlSession.clearCache();
        for(String section:java.util.List.of("devices","maintenance")) {
            JsonNode visible=json.readTree(mvc.perform(get(uri).header("Authorization",auth)
                    .param("report_category","DEVICE_OPERATIONS").param("period_type","DAILY").param("anchor_date",today())
                    .param("section",section)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(visible.path("data").path("items").toString())
                    .contains(section.equals("devices")?liveDevice:liveTask)
                    .doesNotContain(section.equals("devices")?device:task);
            assertThat(visible.path("data").path("total").asLong()).isEqualTo(1);
        }
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
