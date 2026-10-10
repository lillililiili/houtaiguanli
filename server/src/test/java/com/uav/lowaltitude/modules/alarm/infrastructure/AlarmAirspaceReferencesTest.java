package com.uav.lowaltitude.modules.alarm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AlarmAirspaceReferencesTest {
    @Test void h2KeepsOnlySameAlarmAndScopeInSequence() {
        check(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }
    @Test @EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
    void postgresKeepsOnlySameAlarmAndScopeInSequence() {
        String url = System.getenv("POSTGRES_TEST_URL"), user = System.getenv("POSTGRES_TEST_USER"), password = System.getenv("POSTGRES_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        if (!admin.queryForObject("select current_database()", String.class).matches("advisory_verify_[a-z0-9_]+")) throw new IllegalStateException("隔离测试库必需");
        String schema = "airspace_history_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try { check(new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password)); }
        finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    private void check(DriverManagerDataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE alarm(alarm_id varchar(40),owner_org_id varchar(40),district_id varchar(40))");
        jdbc.execute("CREATE TABLE alarm_escalation(alarm_id varchar(40),evaluation_id varchar(40),seq int,owner_org_id varchar(40),district_id varchar(40))");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarm VALUES (?,'o','d')", id);
        jdbc.update("INSERT INTO alarm_escalation VALUES (?,'second',2,'o','d'),(?,'first',1,'o','d'),(?,'foreign',3,'other','d'),('other','unrelated',1,'o','d')", id,id,id);
        var repository = new AlarmReadRepository(jdbc, null, null);
        assertThat(repository.airspaceEvaluationReferences(id)).extracting(AlarmReadRepository.AirspaceEvaluationReference::evaluationId).containsExactly("first", "second");
        assertThat(repository.airspaceEvaluationReferences("missing")).isEmpty();
    }
}
