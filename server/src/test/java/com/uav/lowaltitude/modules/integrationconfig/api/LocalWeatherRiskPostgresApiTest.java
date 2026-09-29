package com.uav.lowaltitude.modules.integrationconfig.api;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Reuses HTTP acceptance cases with real PostGIS in a disposable schema of an explicit test database. */
@EnabledIfEnvironmentVariable(named="WEATHER_TEST_DB_URL",matches="jdbc:postgresql:.*weather_risk_verify_.*")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LocalWeatherRiskPostgresApiTest extends LocalWeatherRiskApiTest {
    private static final String SCHEMA="weather_risk_"+UUID.randomUUID().toString().replace("-","");
    private static JdbcTemplate root;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("WEATHER_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/weather_risk_verify_[a-zA-Z0-9_]+"))
            throw new IllegalStateException("Dedicated weather_risk_verify database required");
        String user=System.getenv("WEATHER_TEST_DB_USER"),password=System.getenv("WEATHER_TEST_DB_PASSWORD");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));root.execute("CREATE SCHEMA "+SCHEMA);
        p.add("spring.datasource.url",()->url+"?currentSchema="+SCHEMA+",public");
        p.add("spring.datasource.username",()->user);p.add("spring.datasource.password",()->password);
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        p.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.flyway.schemas",()->SCHEMA);
    }
    @AfterAll static void cleanupSchema() { if(root!=null)root.execute("DROP SCHEMA "+SCHEMA+" CASCADE"); }
}
