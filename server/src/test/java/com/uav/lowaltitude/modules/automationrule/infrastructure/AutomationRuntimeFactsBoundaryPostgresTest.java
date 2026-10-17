package com.uav.lowaltitude.modules.automationrule.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Execute the complete 19-case fact SQL contract on PostgreSQL, including the inclusive
 * 60,000 ms device window, future/stale exclusion, physical-device deduplication,
 * unregistered/foreign identities and MQTT/EO/TCP/normalized relationships.
 * This deliberately small schema tests query semantics, not Flyway or spatial calculation;
 * those have separate full-migration PostgreSQL tests. No application scheduler is started.
 */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class AutomationRuntimeFactsBoundaryPostgresTest extends AutomationRuntimeFactsRepositoryTest {
    private JdbcTemplate admin;
    private String schema;

    @Override protected DataSource testDataSource() {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null || !url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))
            throw new IllegalArgumentException("Only the isolated verification database is permitted");
        String user=System.getenv("POSTGRES_TEST_USER"), password=System.getenv("POSTGRES_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        admin.setQueryTimeout(5);
        assertThat(admin.queryForObject("SELECT current_database()",String.class)).matches("stage456_verify_[a-z0-9_]+");
        schema="facts_boundary_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        return new DriverManagerDataSource(url+"?currentSchema="+schema,user,password);
    }

    @AfterEach void removeOwnSchema() {
        if(admin!=null && schema!=null && schema.matches("facts_boundary_[a-f0-9]{32}"))
            admin.execute("DROP SCHEMA "+schema+" CASCADE");
    }
}
