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
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
class LocalQaRadarPostgresTest extends LocalQaRadarApiTest {
    @org.junit.jupiter.api.Test
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void zzConcurrentRegistrationCreatesOnlyOneDeviceAndOnePreparationAudit() throws Exception {
        var body=input();
        var start=new java.util.concurrent.CountDownLatch(1);
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> request=()->{start.await();return send(body,200).path("device").path("device_id").asText();};
            var first=workers.submit(request);var second=workers.submit(request);start.countDown();
            String id=first.get(30,java.util.concurrent.TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThat(second.get(30,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(id);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from audit_log where action='local_qa_device_prepare' and object_id=?",Long.class,id)).isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }
    private static final String SCHEMA="qa_radar_"+UUID.randomUUID().toString().replace("-","");
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
