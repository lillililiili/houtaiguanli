package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
class AuditApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void superAdminCanQueryAndExportSafeCsv() throws Exception {
        String token = login("admin1");
        jdbcTemplate.update("""
                insert into audit_log(audit_id, account, action, object_type, object_id, detail,
                                      occurred_at, ip, module_code, role_code, result, user_agent)
                values ('csv-safety-test', '=2+2', 'csv_safety', 'test', 'csv-safety', '+command',
                        1, '', 'csv-safety-test', 'ROLE-ADMIN', 'SUCCESS', 'test')
                """);
        try {
            mvc.perform(get("/api/v1/audit-logs")
                            .header("Authorization", bearer(token))
                            .param("module", "csv-safety-test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1));

            String csv = mvc.perform(get("/api/v1/audit-logs/export.csv")
                            .header("Authorization", bearer(token))
                            .param("module", "csv-safety-test"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/csv"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"audit-logs.csv\""))
                    .andReturn().getResponse().getContentAsString();
            assertThat(csv).contains("\"'=2+2\"").contains("\"'+command\"");

            mvc.perform(get("/api/v1/users").header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        } finally {
            jdbcTemplate.update("delete from audit_log where module_code='csv-safety-test'");
        }
    }

    @Test
    void auditCsvRejectsMoreThanFiftyThousandRows() throws Exception {
        String token = login("admin1");
        jdbcTemplate.update("""
                insert into audit_log(audit_id, account, action, object_type, object_id, detail,
                                      occurred_at, ip, module_code, role_code, result, user_agent)
                select 'limit-' || n, 'admin1', 'limit_test', 'test', cast(n as varchar), '',
                       n, '', 'export-limit-test', 'ROLE-ADMIN', 'SUCCESS', 'test'
                from system_range(1, 50001) as generated(n)
                """);
        try {
            mvc.perform(get("/api/v1/audit-logs/export.csv")
                            .header("Authorization", bearer(token))
                            .param("module", "export-limit-test"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("EXPORT_LIMIT_EXCEEDED"));
        } finally {
            jdbcTemplate.update("delete from audit_log where module_code='export-limit-test'");
        }
    }

    private String login(String account) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + account + "\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(body);
        return root.path("data").path("session_id").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
