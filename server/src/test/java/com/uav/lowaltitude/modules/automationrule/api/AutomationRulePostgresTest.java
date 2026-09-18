package com.uav.lowaltitude.modules.automationrule.api;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/automation_rule_verify_[a-z0-9_]+")
class AutomationRulePostgresTest extends AutomationRuleApiTest {
    @MockitoSpyBean AutomationRuleRepository repository;
    @Autowired PlatformTransactionManager transactions;

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void aggregateReadKeepsOneSnapshotWhileAnotherTransactionCommits() throws Exception {
        // The test-managed transaction must be disabled: both the login session and the
        // concurrent writer need committed visibility on separate PostgreSQL connections.
        var before=ok(auth(get(BASE+"verify")));
        assertThat(before.path("version").asLong()).isZero();
        assertThat(before.path("rules")).isEmpty();
        assertThat(before.path("settings").path("scope_mode").asText()).isEqualTo("ALL");
        String airspace=jdbc.queryForObject("SELECT airspace_id FROM airspace ORDER BY airspace_id LIMIT 1",String.class);
        String ruleId=UUID.randomUUID().toString();
        var headRead=new CountDownLatch(1);
        var resumeRead=new CountDownLatch(1);
        var interceptNextRead=new AtomicBoolean(true);
        doAnswer(call -> {
            Object head=call.callRealMethod();
            if(interceptNextRead.compareAndSet(true,false)) {
                headRead.countDown();
                if(!resumeRead.await(10,TimeUnit.SECONDS)) throw new AssertionError("Reader was not resumed");
            }
            return head;
        }).when(repository).head("verify",false);
        var pool=Executors.newSingleThreadExecutor();
        var tx=new TransactionTemplate(transactions);
        try {
            var pending=pool.submit(() -> ok(auth(get(BASE+"verify"))));
            assertThat(headRead.await(10,TimeUnit.SECONDS)).isTrue();
            // Commit a complete second configuration after the reader fetched version 0,
            // before it queries scopes and conditions. No notification/device path is involved.
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE automation_rule_group SET version=1,scope_mode='AIRSPACES' WHERE category='verify'");
                jdbc.update("INSERT INTO automation_rule_scope(category,airspace_id,ordinal) VALUES('verify',?,0)",airspace);
                jdbc.update("INSERT INTO automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) VALUES(?,'verify','Concurrent snapshot','confidence','95',0,TRUE,1,1,'admin1')",ruleId);
            });
            resumeRead.countDown();
            var during=pending.get(10,TimeUnit.SECONDS);
            // READ COMMITTED fails these assertions: it returns version 0 / ALL together
            // with the newly committed scope and condition. REPEATABLE_READ returns all old.
            assertThat(during.path("version")).isEqualTo(before.path("version"));
            assertThat(during.path("settings")).isEqualTo(before.path("settings"));
            assertThat(during.path("rules")).isEqualTo(before.path("rules"));
            // A subsequent request must see all new, proving the writer really committed.
            var after=ok(auth(get(BASE+"verify")));
            assertThat(after.path("version").asLong()).isEqualTo(1);
            assertThat(after.path("settings").path("scope_mode").asText()).isEqualTo("AIRSPACES");
            assertThat(after.path("settings").path("airspace_ids").get(0).asText()).isEqualTo(airspace);
            assertThat(after.path("rules").size()).isEqualTo(1);
            assertThat(after.path("rules").get(0).path("rule_id").asText()).isEqualTo(ruleId);
        } finally {
            resumeRead.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
            // This class is restricted to an isolated, freshly migrated verification DB.
            // Undo only this committed fixture so inherited transactional tests stay independent.
            tx.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM automation_rule_condition WHERE rule_id=?",ruleId);
                jdbc.update("DELETE FROM automation_rule_scope WHERE category='verify' AND airspace_id=?",airspace);
                jdbc.update("UPDATE automation_rule_group SET version=0,scope_mode='ALL' WHERE category='verify'");
            });
        }
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null || !url.matches("jdbc:postgresql://[^/]+/automation_rule_verify_[a-z0-9_]+")) throw new IllegalArgumentException("仅允许隔离规则测试库");
        r.add("spring.datasource.url",()->url);
        r.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));
        r.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
    }
}
