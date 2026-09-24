package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 同一组 HTTP、并发与权限契约在隔离 PostgreSQL/PostGIS 模式上验证。 */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class FlightNotificationPostgresTest extends FlightVerificationApiTest {
    private static final String SCHEMA="flight_notification_"+UUID.randomUUID().toString().replace("-","");
    private static boolean created;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        initialize();
        p.add("spring.datasource.url",()->System.getenv("POSTGRES_TEST_URL")+"?currentSchema="+SCHEMA+",public");
        p.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));
        p.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        p.add("spring.flyway.enabled",()->false);
        p.add("app.rule-engine.enabled",()->false);
        p.add("app.rule-engine.replay.run-on-start",()->false);
        p.add("app.rule-engine.c04.enabled",()->false);
        p.add("app.fusion.enabled",()->false);
        p.add("app.fusion.replay.run-on-start",()->false);
        p.add("app.disposal.expiry.enabled",()->false);
    }
    static synchronized void initialize() {
        if(created)return;
        var jdbc=new JdbcTemplate(root());
        if(!jdbc.queryForObject("select current_database()",String.class).matches("^advisory_verify_[a-z0-9_]+$")) throw new IllegalStateException("Requires isolated advisory_verify_ database");
        jdbc.execute("create schema "+SCHEMA);created=true;
        Flyway.configure().dataSource(root()).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(false).cleanDisabled(true).locations("classpath:db/migration","classpath:db/postgresql").load().migrate();
    }
    @AfterAll static void cleanSchema(@org.springframework.beans.factory.annotation.Autowired org.springframework.context.ConfigurableApplicationContext context) {context.getBeansOfType(ThreadPoolTaskScheduler.class).values().forEach(ThreadPoolTaskScheduler::shutdown);if(created){new JdbcTemplate(root()).execute("drop schema "+SCHEMA+" cascade");created=false;}}
    static DataSource root(){return new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),System.getenv("POSTGRES_TEST_USER"),System.getenv("POSTGRES_TEST_PASSWORD"));}
}
