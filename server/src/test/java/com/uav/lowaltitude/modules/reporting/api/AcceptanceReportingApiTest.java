package com.uav.lowaltitude.modules.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.reporting.application.ReportingService;

/** Test-only fixtures. The acceptance service never loads these records or seeds. */
@SpringBootTest(properties="app.qa.reporting.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AcceptanceReportingApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) { BusinessReportingApiTest.database(r); }
    private String auth() throws Exception {
        return "Bearer " + json.readTree(mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
    private String today() { return LocalDate.now(ReportingService.ZONE).toString(); }
    private MockHttpServletRequestBuilder query(String endpoint, String auth, String category, String scope) {
        return get("/api/v1/stats/reports/"+endpoint).header("Authorization",auth)
                .param("report_category",category).param("period_type","DAILY").param("anchor_date",today())
                .param("source_mode",scope);
    }
    private JsonNode data(MockHttpServletRequestBuilder query) throws Exception {
        return json.readTree(mvc.perform(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @ParameterizedTest @ValueSource(strings={"OVERVIEW","DEVICE_OPERATIONS","ALARM_RISK","FLIGHT_VERIFICATION","EVENT_DISPOSAL"})
    void eachCategoryHasOneScopeForPreviewDetailsAndExports(String category) throws Exception {
        String auth=auth();
        JsonNode preview=data(query("preview",auth,category,"simulated"));
        assertThat(preview.path("report_scope").asText()).isEqualTo("simulated");
        assertThat(preview.path("simulated").asBoolean()).isTrue();
        assertThat(preview.path("available_source_modes").toString()).contains("live","simulated");
        for(JsonNode section:preview.path("sections")) {
            for(JsonNode source:section.path("sources")) assertThat(source.path("name").asText()).isIn("mock","replay");
            if(!category.equals("OVERVIEW")) {
                JsonNode details=data(query("details",auth,category,"simulated").param("section",section.path("key").asText()));
                assertThat(details.path("total").asLong()).isEqualTo(section.path("total").asLong());
                for(JsonNode row:details.path("items")) assertThat(row.path("source_mode").asText()).isIn("mock","replay");
            }
        }
        Path dir=Path.of("target/item3-20261007/exports"); Files.createDirectories(dir);
        for(String extension:List.of("xlsx","pdf")) {
            var response=mvc.perform(query("export."+extension,auth,category,"simulated"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andReturn().getResponse();
            assertThat(java.net.URLDecoder.decode(response.getHeader("Content-Disposition"),java.nio.charset.StandardCharsets.UTF_8)).contains("模拟验收");
            byte[] bytes=response.getContentAsByteArray();
            if(extension.equals("pdf")) {
                try(var doc=Loader.loadPDF(bytes)) {
                    var text=new PDFTextStripper();
                    for(int page=1;page<=doc.getNumberOfPages();page++) {
                        text.setStartPage(page);text.setEndPage(page);
                        assertThat(text.getText(doc)).contains("模拟验收");
                    }
                }
            } else {
                try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    assertThat(book.getSheet("报表摘要").getRow(1).getCell(1).getStringCellValue()).contains("模拟验收");
                    for(var sheet:book) assertThat(sheet.getHeader().getCenter()).contains("模拟验收");
                }
            }
            Files.write(dir.resolve(category+"-simulated."+extension),bytes);
        }
    }
    @Test void emptySimulationIsStillLabelledAndFormalDefaultIsUnchanged() throws Exception {
        String auth=auth();
        JsonNode empty=data(query("preview",auth,"OVERVIEW","simulated").with(r -> { r.setParameter("anchor_date","1901-01-01"); return r; }));
        assertThat(empty.path("simulated").asBoolean()).isTrue();
        assertThat(empty.path("source_mode").asText()).isEqualTo("simulated");
        assertThat(empty.path("sections").get(0).path("total").asLong()).isZero();
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization",auth)
                .param("report_category","OVERVIEW").param("period_type","DAILY").param("anchor_date",today()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.report_scope").value("live"))
                .andExpect(jsonPath("$.data.source_mode").value("live"))
                .andExpect(jsonPath("$.data.simulated").value(false));
    }
    @Test @Transactional void simulationRespectsPermissionsOnEveryEndpoint() throws Exception {
        String auth=auth();
        jdbc.update("DELETE FROM app_role_permission WHERE role_code='ROLE-ADMIN' AND permission_code='risk:read'");
        mvc.perform(query("preview",auth,"OVERVIEW","simulated")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sections[2].accessible").value(false))
                .andExpect(jsonPath("$.data.sections[2].total").doesNotExist());
        for(String endpoint:List.of("preview","details","export.xlsx","export.pdf"))
            mvc.perform(query(endpoint,auth,"ALARM_RISK","simulated").param("section","alarms"))
                    .andExpect(status().isForbidden());
    }
    @Test @Transactional void sourceSelectionDoesNotCrossAssignedTuplesOrTimeBoundaries() throws Exception {
        String auth=auth(), suffix=UUID.randomUUID().toString().substring(0,16);
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        String org=jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROWS ONLY",String.class);
        String district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROWS ONLY",String.class);
        String other="report-"+suffix;
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",other,other,"另一测试区域");
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE user_id=?",user);
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,org,district);
        var start=LocalDate.of(2002,3,4).atStartOfDay(ReportingService.ZONE).toOffsetDateTime();
        String sql="INSERT INTO target(target_id,target_no,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)";
        for(String mode:List.of("live","mock","replay")) {
            jdbc.update(sql,mode+suffix,mode+suffix,start,start,mode,org,district);
            jdbc.update(sql,"cross"+mode+suffix,"cross"+mode+suffix,start,start,mode,org,other);
            jdbc.update(sql,"after"+mode+suffix,"after"+mode+suffix,start.plusDays(1),start.plusDays(1),mode,org,district);
        }
        sqlSession.clearCache();
        for(String scope:List.of("live","simulated")) {
            var result=data(query("preview",auth,"OVERVIEW",scope).with(r -> { r.setParameter("anchor_date","2002-03-04"); return r; }));
            assertThat(result.path("sections").get(0).path("total").asLong()).isEqualTo(scope.equals("live")?1:2);
        }
    }
    @Test void invalidAndLegacySimulationRequestsFailInsteadOfFallingBackToFormalData() throws Exception {
        String auth=auth();
        for(String scope:List.of("all","mixed","replay","mock","SIMULATED",""))
            mvc.perform(query("preview",auth,"OVERVIEW",scope)).andExpect(status().isBadRequest());
        for(String endpoint:List.of("preview","export.xlsx"))
            mvc.perform(get("/api/v1/stats/reports/"+endpoint).header("Authorization",auth)
                    .param("report_type","DAILY").param("anchor_date",today()).param("source_mode","simulated"))
                    .andExpect(status().isBadRequest());
    }
}
