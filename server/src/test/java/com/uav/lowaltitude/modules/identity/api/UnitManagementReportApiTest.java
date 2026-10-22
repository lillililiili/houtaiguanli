package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Exercises actual report reads/exports with empty personal grants, siblings and a child unit. */
class UnitManagementReportApiTest extends UnitDelegationApiTest {
    private static final String ROOT="/api/v1/unit-admin/operations/stats/reports/";
    private String configure(String level) throws Exception {
        var body=delegation(0,"READ",false);
        body.withArray("permissions").add(json.createObjectNode().put("permission_code","sensing").put("level","READ").put("menu_enabled",true));
        body.putArray("management_permissions").add(json.createObjectNode().put("module_key","statistics").put("level",level));
        body.put("preview_token",preview(org,body).path("preview_token").asText());
        mvc.perform(put("/api/v1/organizations/"+org+"/delegation").header("Authorization","Bearer "+platform)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk());
        // Management reports must not depend on the administrator's personal front-office role.
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=(SELECT role_code FROM app_user WHERE user_id=?)",administrator);
        sql.clearCache();
        return login(administrator,"UnitMembers#2026",true);
    }
    private MockHttpServletRequestBuilder report(String endpoint,String token,String category) {
        return get(ROOT+endpoint).header("Authorization","Bearer "+token).param("report_category",category)
                .param("period_type","DAILY").param("anchor_date",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
    }
    private void targets() {
        String district=jdbc.queryForObject("SELECT district_id FROM app_district WHERE enabled=TRUE ORDER BY district_id FETCH FIRST 1 ROW ONLY",String.class);
        var now=OffsetDateTime.now();
        for(String owner:new String[]{org,other,child}) {
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO target(target_id,target_no,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,?,?,'live',?,?,?, ?,0)",
                    id,id,now,now,owner,district,now,now);
        }
        // Even stale or corrupt extra scope tuples must not widen the fixed unit report scope.
        for(String owner:new String[]{other,child}) jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",administrator,owner,district);
    }
    @Test void reportsUseUnitModulesWithoutPersonalBusinessGrantsAndNeverIncludeSiblingOrChild() throws Exception {
        String token=configure("READ"); targets();
        JsonNode result=json.readTree(mvc.perform(report("preview",token,"OVERVIEW").param("owner_org_id",other))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        JsonNode target=result.path("sections").get(0);
        assertThat(target.path("key").asText()).isEqualTo("targets");
        assertThat(target.path("accessible").asBoolean()).isTrue();
        assertThat(target.path("total").asLong()).isEqualTo(1);
        JsonNode risks=result.path("sections").get(2);
        assertThat(risks.path("accessible").asBoolean()).isFalse();
        assertThat(risks.hasNonNull("total")).isFalse();
        mvc.perform(get("/api/v1/stats/reports/preview").header("Authorization","Bearer "+token)
                .param("report_category","OVERVIEW").param("period_type","DAILY").param("anchor_date",LocalDate.now().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/targets").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(report("details",token,"ALARM_RISK").param("section","risks")).andExpect(status().isForbidden());
    }
    @Test void readGrantCannotExportAndOrdinaryMemberCannotUseReports() throws Exception {
        String token=configure("READ");
        for(String extension:new String[]{"pdf","xlsx"}) mvc.perform(report("export."+extension,token,"OVERVIEW")).andExpect(status().isForbidden());
        String person=login(member,"UnitMembers#2026",false);
        mvc.perform(report("preview",person,"OVERVIEW")).andExpect(status().isForbidden());
        assertThat(com.uav.lowaltitude.platform.report.ReportAccessContext.active()).isFalse();
    }
    @Test void exportedPdfAndExcelKeepTheSameScopeAndUnavailableSections() throws Exception {
        String token=configure("OP"); targets();
        byte[] pdf=mvc.perform(report("export.pdf",token,"OVERVIEW")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try(var document=Loader.loadPDF(pdf)) {
            String text=new PDFTextStripper().getText(document);
            assertThat(text).contains("新增目标","无权限");
        }
        byte[] excel=mvc.perform(report("export.xlsx",token,"OVERVIEW")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(excel))) {
            var summary=workbook.getSheet("报表摘要");
            boolean found=false;
            for(var row:summary) if("新增目标".equals(row.getCell(0).getStringCellValue())) {
                assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(1); found=true;
            }
            assertThat(found).isTrue();
        }
        assertThat(com.uav.lowaltitude.platform.report.ReportAccessContext.active()).isFalse();
        // No rights leaked to the next request on a reused servlet thread.
        mvc.perform(get("/api/v1/targets").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void revokedReportGrantIsCheckedAgainWithoutDependingOnMenuRefresh() throws Exception {
        String token=configure("OP");
        jdbc.update("DELETE FROM app_org_management_permission WHERE org_id=? AND module_key='statistics'",org);
        mvc.perform(report("preview",token,"OVERVIEW")).andExpect(status().isForbidden());
        mvc.perform(report("export.pdf",token,"OVERVIEW")).andExpect(status().isForbidden());
        assertThat(com.uav.lowaltitude.platform.report.ReportAccessContext.active()).isFalse();
    }
}
