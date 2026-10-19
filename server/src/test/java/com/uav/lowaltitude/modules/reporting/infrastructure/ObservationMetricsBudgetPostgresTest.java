package com.uav.lowaltitude.modules.reporting.infrastructure;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class ObservationMetricsBudgetPostgresTest extends ObservationMetricsBudgetTest {
    private JdbcTemplate root;
    private String schema;
    @Override protected DataSource database() {
        String url = System.getenv("POSTGRES_TEST_URL"), user = System.getenv("POSTGRES_TEST_USER"), password = System.getenv("POSTGRES_TEST_PASSWORD");
        root = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        if (!root.queryForObject("SELECT current_database()", String.class).matches("advisory_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Requires isolated test database");
        schema = "report_budget_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        return new DriverManagerDataSource(url + "?currentSchema=" + schema + ",public", user, password);
    }
    @Override protected void closeDatabase() {
        if (root != null && schema != null) root.execute("DROP SCHEMA " + schema + " CASCADE");
    }
}
