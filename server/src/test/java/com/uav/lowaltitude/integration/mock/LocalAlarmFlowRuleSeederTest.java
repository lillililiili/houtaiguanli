package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 预置自动规则只补缺失项：重启可重复执行，管理员改过或删掉的规则不被覆盖、不被补回。 */
@SpringBootTest(properties = {"app.flight.status-advance.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LocalAlarmFlowRuleSeederTest {
    private static final String BASE = "/api/v1/automation-rule-groups/";

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ApplicationArguments arguments;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    LocalAlarmFlowRuleSeeder seeder;
    String session;

    @BeforeEach
    void setUp() throws Exception {
        seeder = new LocalAlarmFlowRuleSeeder(jdbc, passwords);
        session = json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("session_id").asText();
    }

    @Test
    void testProfileDoesNotRegisterSeeder() {
        assertThat(context.containsBean("localAlarmFlowRuleSeeder")).isFalse();
        assertThat(LocalAlarmFlowRuleSeeder.class.getAnnotation(Profile.class).value())
                .containsExactly("local & qa & !prod & !production");
    }

    @Test
    void repeatedStartupKeepsOneCopyOfEachPreset() {
        seeder.run(arguments);
        seeder.run(arguments);
        seeder.run(arguments);
        assertThat(count("SELECT COUNT(*) FROM automation_rule_condition WHERE updated_by='alarm-flow-preset'")).isEqualTo(6);
        for (String category : new String[] {"verify", "counter", "dispose"}) {
            assertThat(count("SELECT COUNT(*) FROM automation_rule_condition WHERE category='" + category + "'")).isEqualTo(2);
        }
        assertThat(count("SELECT COUNT(*) FROM automation_rule_change")).isZero();
    }

    @Test
    void restartAfterAdminEditsNeitherFailsNorOverwritesNorDeletes() throws Exception {
        seeder.run(arguments);
        // 管理员在规则页停用通知处罚的两条预置，再删掉核实的一条预置
        write(patch(BASE + "dispose/rules/alarm-flow-dispose-link/enabled"), Map.of("enabled", false, "expected_version", 0));
        write(patch(BASE + "dispose/rules/alarm-flow-dispose-freshness/enabled"), Map.of("enabled", false, "expected_version", 1));
        write(delete(BASE + "verify/rules/alarm-flow-verify-freshness"), Map.of("expected_version", 0));

        seeder.run(arguments);
        seeder.run(arguments);

        assertThat(jdbc.queryForList("SELECT enabled FROM automation_rule_condition WHERE category='dispose'", Boolean.class))
                .containsExactly(false, false);
        assertThat(count("SELECT COUNT(*) FROM automation_rule_condition WHERE category='dispose' AND updated_by='admin1'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM automation_rule_condition WHERE rule_id='alarm-flow-verify-freshness'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM automation_rule_condition WHERE rule_id='alarm-flow-verify-confidence'")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT rule_id FROM automation_rule_condition WHERE category='counter' ORDER BY rule_id", String.class))
                .containsExactly("alarm-flow-counter-freshness", "alarm-flow-counter-risk");
        assertThat(jdbc.queryForObject("SELECT version FROM automation_rule_group WHERE category='dispose'", Long.class)).isEqualTo(2);
    }

    @Test
    void restoresPresetsLostOutsideTheRulePageButSkipsAdminRulesOnTheSameItem() throws Exception {
        // 管理员先给核实建了同一判定项的规则：预置不再插入，避免唯一约束冲突
        write(post(BASE + "verify/rules"), Map.of("name", "置信度达标", "item_code", "confidence", "value", "90",
                "hold_seconds", 0, "enabled", true, "expected_version", 0));
        seeder.run(arguments);
        assertThat(jdbc.queryForList("SELECT name FROM automation_rule_condition WHERE category='verify' ORDER BY name", String.class))
                .containsExactly("核实观测仍有效", "置信度达标");

        // 旧版种子在启动失败前会删掉未改动的反制预置；这类没有删除记录的丢失由下次启动补回
        jdbc.update("DELETE FROM automation_rule_condition WHERE category='counter'");
        seeder.run(arguments);
        assertThat(jdbc.queryForList("SELECT rule_id FROM automation_rule_condition WHERE category='counter' ORDER BY rule_id", String.class))
                .containsExactly("alarm-flow-counter-freshness", "alarm-flow-counter-risk");
        assertThat(count("SELECT COUNT(*) FROM automation_rule_condition")).isEqualTo(6);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private void write(MockHttpServletRequestBuilder request, Object body) throws Exception {
        mvc.perform(request.header("Authorization", "Bearer " + session)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }
}
