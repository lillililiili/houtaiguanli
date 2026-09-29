package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"app.dev-seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:qa_rule_catalog;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Transactional
class LocalQaRuleCatalogTest {
    @Autowired JdbcTemplate jdbc;
    @Test void profileRequiresExplicitLocalQaAndNeverAllowsProduction() {
        var profiles=org.springframework.core.env.Profiles.of(LocalQaRuleCatalog.class.getAnnotation(org.springframework.context.annotation.Profile.class).value());
        var env=new org.springframework.core.env.StandardEnvironment();
        for(String[] active:List.of(new String[]{"local"},new String[]{"qa"},new String[]{"test"},new String[]{"production","local","qa"})) {
            env.setActiveProfiles(active);assertThat(env.acceptsProfiles(profiles)).isFalse();
        }
        env.setActiveProfiles("local","qa");assertThat(env.acceptsProfiles(profiles)).isTrue();
    }
    @Test void catalogOnlyDoesNotCreateBusinessFactsOrActivateRules() {
        Map<String,Long> before=businessCounts();
        LocalQaRuleCatalog catalog=new LocalQaRuleCatalog(jdbc);
        catalog.run(null);
        assertThat(businessCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT active_version_id FROM rule_set WHERE rule_set_id=?",String.class,LocalStage7RuleEngineSeeder.RULE_SET_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rule_set_member WHERE rule_set_version_id=?",Long.class,LocalStage7RuleEngineSeeder.VERSION_1)).isEqualTo(11);
        jdbc.update("UPDATE rule_set SET active_version_id=?,version=2 WHERE rule_set_id=?",LocalStage7RuleEngineSeeder.VERSION_2,LocalStage7RuleEngineSeeder.RULE_SET_ID);
        catalog.run(null);
        assertThat(businessCounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT active_version_id FROM rule_set WHERE rule_set_id=?",String.class,LocalStage7RuleEngineSeeder.RULE_SET_ID)).isEqualTo(LocalStage7RuleEngineSeeder.VERSION_2);
    }
    private Map<String,Long> businessCounts() {
        Map<String,Long> values=new LinkedHashMap<>();
        for(String table:List.of("target","track","track_point","flight_plan","airspace","app_org","app_user","alarm","assessment_result","device_command"))values.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
        return values;
    }
}
