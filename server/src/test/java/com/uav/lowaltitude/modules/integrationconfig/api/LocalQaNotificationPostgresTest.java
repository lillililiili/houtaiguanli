package com.uav.lowaltitude.modules.integrationconfig.api;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same API contracts in a new schema of the explicitly isolated plan-filing verification database. */
@EnabledIfEnvironmentVariable(named="PLAN_FILING_TEST_DB_URL",matches="jdbc:postgresql:.*plan_filing_verify_.*")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LocalQaNotificationPostgresTest extends LocalQaNotificationApiTest {
    private static final String SCHEMA="qa_notification_"+UUID.randomUUID().toString().replace("-","");
    private static JdbcTemplate root;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("PLAN_FILING_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/plan_filing_verify_[a-zA-Z0-9_]+"))throw new IllegalStateException("Dedicated plan_filing_verify database required");
        String user=System.getenv("PLAN_FILING_TEST_DB_USER"),password=System.getenv("PLAN_FILING_TEST_DB_PASSWORD");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));root.execute("CREATE SCHEMA "+SCHEMA);
        p.add("spring.datasource.url",()->url+"?currentSchema="+SCHEMA+",public");
        p.add("spring.datasource.username",()->user);p.add("spring.datasource.password",()->password);
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        p.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.flyway.schemas",()->SCHEMA);
    }
    @AfterAll static void cleanup(@org.springframework.beans.factory.annotation.Autowired org.springframework.context.ConfigurableApplicationContext context) {
        context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class).values().forEach(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor::destroy);
        context.getBeansOfType(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class).values().forEach(s->{s.setWaitForTasksToCompleteOnShutdown(true);s.setAwaitTerminationSeconds(5);s.shutdown();});
        if(root!=null)root.execute("DROP SCHEMA "+SCHEMA+" CASCADE");
    }
}
