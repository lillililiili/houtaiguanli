package com.uav.lowaltitude.modules.alarm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MergedEvaluationAlarmTest {
    @Test void mergedEvidenceKeepsItsOriginalAlarmAcrossLaterUpgrades() {
        check(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=PostgreSQL","sa",""));
    }
    @Test @EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches=".+")
    void postgresKeepsHistoricalAssociation() {
        String url=System.getenv("POSTGRES_TEST_URL"), user=System.getenv("POSTGRES_TEST_USER"), password=System.getenv("POSTGRES_TEST_PASSWORD");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        if(!admin.queryForObject("select current_database()",String.class).matches("^advisory_verify_[a-z0-9_]+$")) throw new IllegalStateException("Isolated test database required");
        String schema="merged_notice_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        try { check(new DriverManagerDataSource(url+"?currentSchema="+schema,user,password)); }
        finally { admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
    private void check(DriverManagerDataSource source) {
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE alarm_merge_member(member_id VARCHAR(40),group_id VARCHAR(40),evaluation_id VARCHAR(40),alarm_id VARCHAR(40),created_at BIGINT)");
        jdbc.update("INSERT INTO alarm_merge_member VALUES ('1','g1','created','a1',100),('2','g1','merged',NULL,200),('3','g1','upgrade','a2',300),('4','g1','after-upgrade',NULL,400),('5','g2','other-group','foreign',190),('6','empty','orphan',NULL,500)");
        var repo=new AlarmMergeRepository(jdbc);
        assertThat(repo.associatedAlarmId("merged")).isEqualTo("a1");
        assertThat(repo.associatedAlarmId("after-upgrade")).isEqualTo("a2");
        assertThat(repo.associatedAlarmId("orphan")).isNull();
        assertThat(repo.associatedAlarmId("unknown")).isNull();
        assertThat(jdbc.queryForObject("SELECT alarm_id FROM alarm_merge_member WHERE evaluation_id='merged'",String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alarm_merge_member",Integer.class)).isEqualTo(6);
    }
}
