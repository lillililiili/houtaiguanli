package com.uav.lowaltitude.modules.evidence.api;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Executes the same HTTP assertions and UNION/scope SQL in a fresh schema of an explicit test database. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class EvidenceLedgerPostgresTest extends EvidenceApiTest {
    private static final String SCHEMA="evidence_ledger_"+UUID.randomUUID().toString().replace("-","");
    private static JdbcTemplate root;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+"))throw new IllegalStateException("Dedicated test database required");
        String user=System.getenv("POSTGRES_TEST_USER"),password=System.getenv("POSTGRES_TEST_PASSWORD");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));root.execute("CREATE SCHEMA "+SCHEMA);
        p.add("spring.datasource.url",()->url+"?currentSchema="+SCHEMA+",public");
        p.add("spring.datasource.username",()->user);p.add("spring.datasource.password",()->password);
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        p.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.flyway.schemas",()->SCHEMA);
        p.add("app.dev-seed.enabled",()->false);
    }
    @AfterAll static void cleanupSchema(@org.springframework.beans.factory.annotation.Autowired org.springframework.context.ConfigurableApplicationContext context){
        context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).values()
                .forEach(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler::shutdown);
        if(root!=null)root.execute("DROP SCHEMA "+SCHEMA+" CASCADE");
    }
}
