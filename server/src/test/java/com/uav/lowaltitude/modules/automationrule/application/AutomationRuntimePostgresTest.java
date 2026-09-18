package com.uav.lowaltitude.modules.automationrule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeFacts.Fact;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeFactsRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.platform.time.AppClock;

@SpringBootTest(properties={
    "app.automation-rules.enabled=true",
    "app.automation-rules.fact-max-age-ms=30000",
    "app.automation-rules.poll-millis=2000",
    "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/automation_rule_verify_[a-z0-9_]+")
class AutomationRuntimePostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AutomationRuntimeService service;
    @Autowired AutomationRuntimeRepository runtime;
    @Autowired AppClock clock;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean AutomationRuntimeFactsRepository facts;
    private TransactionTemplate tx;
    private String eventId,targetId,ruleId;
    private long fixtureObservedAt;

    @BeforeEach void fixture(){
        tx=new TransactionTemplate(transactionManager);
        clear();
        eventId=jdbc.queryForObject("SELECT event_id FROM uav_event ORDER BY event_id LIMIT 1",String.class);
        targetId=jdbc.queryForObject("SELECT a.target_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?",String.class,eventId);
        ruleId=UUID.randomUUID().toString();
        long now=clock.nowMillis();fixtureObservedAt=now;
        when(facts.read(eq(eventId),anyLong())).thenReturn(facts("replay","evidence-fixture","95"));
        tx.executeWithoutResult(ignored->{
            jdbc.update("INSERT INTO automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) VALUES(?,'verify','PostgreSQL runtime confidence','confidence','90',0,TRUE,?,?, 'runtime-pg-test')",ruleId,now,now);
            jdbc.update("UPDATE automation_rule_group SET version=1,scope_mode='ALL',schedule_mode='ALL_DAY',actions_json='[]',updated_at=? WHERE category='verify'",now);
        });
    }

    @AfterEach void cleanup(){clear();}

    @Test void migrationFiftyUpgradesFortyAndCreatesRuntimeSchema(){
        Integer v40=jdbc.queryForObject("SELECT installed_rank FROM flyway_schema_history WHERE version='202609170040' AND success=TRUE",Integer.class);
        Integer v50=jdbc.queryForObject("SELECT installed_rank FROM flyway_schema_history WHERE version='202609170050' AND success=TRUE",Integer.class);
        assertThat(v50).isGreaterThan(v40);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_worker WHERE worker_key='automation-rules'",Long.class)).isEqualTo(1);
        assertThat(columns("automation_runtime_run")).contains("event_id","group_version","conditions_json","facts_json");
        assertThat(columns("automation_runtime_state")).contains("category","event_id","signature");
    }

    @Test void persistsRunAndStateWithoutCreatingAnyActionForEmptyConfiguredActions(){
        service.evaluate("verify",eventId);
        var state=runtime.state("verify",eventId);
        assertThat(state).isNotNull();
        assertThat(state.version()).isEqualTo(1);
        assertThat(state.status()).isEqualTo("PASS");
        assertThat(runtime.run(state.runId())).satisfies(run->{assertThat(run.eventId()).isEqualTo(eventId);assertThat(run.targetId()).isEqualTo(targetId);assertThat(run.mode()).isEqualTo("replay");});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_action WHERE event_id=?",Long.class,eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_run_action ra JOIN automation_runtime_run r ON r.run_id=ra.run_id WHERE r.event_id=?",Long.class,eventId)).isZero();
    }

    @Test void concurrentEvaluationOfSameCategorySerializesAndDoesNotDuplicateRun() throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->{ready.countDown();start.await(10,TimeUnit.SECONDS);service.evaluate("verify",eventId);return null;});
            var second=pool.submit(()->{ready.countDown();start.await(10,TimeUnit.SECONDS);service.evaluate("verify",eventId);return null;});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_run WHERE category='verify' AND event_id=?",Long.class,eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_state WHERE category='verify' AND event_id=?",Long.class,eventId)).isEqualTo(1);
            assertThat(runtime.state("verify",eventId).version()).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }

    @Test void evaluatingAgainAfterServiceUseDoesNotInsertSameRunTwice(){
        service.evaluate("verify",eventId);
        String first=runtime.state("verify",eventId).runId();
        // All deduplication inputs live in PostgreSQL. A subsequent proxied invocation must use persisted state,
        // not instance memory, so this is also the behavior required after rebuilding the service bean/process.
        service.evaluate("verify",eventId);
        assertThat(runtime.state("verify",eventId).runId()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_run WHERE category='verify' AND event_id=?",Long.class,eventId)).isEqualTo(1);
    }

    @Test void sameObservationWithChangedSourceOrEvidenceCreatesANewImmutableRun(){
        service.evaluate("verify",eventId);String first=runtime.state("verify",eventId).runId();
        when(facts.read(eq(eventId),anyLong())).thenReturn(facts("mock","evidence-fixture-2","95"));
        service.evaluate("verify",eventId);
        assertThat(runtime.state("verify",eventId).runId()).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_run WHERE category='verify' AND event_id=?",Long.class,eventId)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT source_mode FROM automation_runtime_run WHERE category='verify' AND event_id=? ORDER BY evaluated_at,run_id",String.class,eventId)).containsExactlyInAnyOrder("replay","mock");
        assertThat(jdbc.queryForObject("SELECT facts_json FROM automation_runtime_run WHERE run_id=?",String.class,runtime.state("verify",eventId).runId())).contains("evidence-fixture-2");
    }

    @Test void missingFactAfterPassMovesToReviewAndClearsStaleHolds(){
        service.evaluate("verify",eventId);String first=runtime.state("verify",eventId).runId();
        tx.executeWithoutResult(ignored->{jdbc.update("UPDATE automation_runtime_state SET holds_json=? WHERE category='verify' AND event_id=?","{\"stale-rule\":{\"since\":1,\"observedAt\":1}}",eventId);jdbc.update("UPDATE automation_rule_group SET wait_seconds=0 WHERE category='verify'");});
        when(facts.read(eq(eventId),anyLong())).thenReturn(facts("replay","evidence-missing",null));
        service.evaluate("verify",eventId);
        var state=runtime.state("verify",eventId);
        assertThat(state.status()).isEqualTo("REVIEW");assertThat(state.runId()).isNotEqualTo(first);assertThat(state.holds()).isEqualTo("{}");assertThat(state.unknownSince()).isNotNull();
        assertThat(runtime.run(state.runId()).conditions()).contains("UNKNOWN").contains("缺少当前判定依据");
    }

    @Test void disablingRuleAdvancesVersionAndResetsDecisionToPaused(){
        service.evaluate("verify",eventId);String first=runtime.state("verify",eventId).runId();
        tx.executeWithoutResult(ignored->{jdbc.update("UPDATE automation_rule_condition SET enabled=FALSE,updated_at=? WHERE rule_id=?",clock.nowMillis(),ruleId);jdbc.update("UPDATE automation_rule_group SET version=2,updated_at=? WHERE category='verify'",clock.nowMillis());});
        service.evaluate("verify",eventId);
        var state=runtime.state("verify",eventId);
        assertThat(state.version()).isEqualTo(2);assertThat(state.status()).isEqualTo("PAUSED");assertThat(state.runId()).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_run WHERE category='verify' AND event_id=?",Long.class,eventId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_runtime_action WHERE event_id=?",Long.class,eventId)).isZero();
    }

    private AutomationRuntimeFacts facts(String sourceMode,String evidenceId,String confidence){
        Fact fact=new Fact(confidence,fixtureObservedAt,evidenceId,confidence==null?"缺少当前判定依据":null);
        return new AutomationRuntimeFacts(eventId,targetId,"alarm-fixture","org-fixture","district-fixture",sourceMode,"CONFIRMED","UAV",fixtureObservedAt,Set.of(),true,Map.of("confidence",fact));
    }
    private Set<String> columns(String table){return Set.copyOf(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=?",String.class,table));}
    private void clear(){
        if(tx==null)tx=new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(ignored->{jdbc.update("DELETE FROM automation_runtime_run_action");jdbc.update("DELETE FROM automation_runtime_action");jdbc.update("DELETE FROM automation_runtime_state");jdbc.update("DELETE FROM automation_runtime_run");jdbc.update("DELETE FROM automation_rule_condition WHERE updated_by='runtime-pg-test'");jdbc.update("UPDATE automation_rule_group SET version=0,scope_mode='ALL',schedule_mode='ALL_DAY',start_time='08:00',end_time='20:00',wait_seconds=15,actions_json='[]',updated_at=0 WHERE category='verify'");});
        reset(facts);
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/automation_rule_verify_[a-z0-9_]+"))throw new IllegalArgumentException("仅允许全新隔离的自动规则测试库");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));registry.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
    }
}
