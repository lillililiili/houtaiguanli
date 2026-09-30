package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeFacts;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeFactsRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.platform.time.AppClock;

@SpringBootTest
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@Transactional
class AlarmRuleVerificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AlarmRuleVerification verification;
    @Autowired AutomationRuntimeRepository runtime;
    @Autowired AppClock clock;
    @Autowired com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeWorker worker;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoBean AutomationRuntimePolicy policy;
    @MockitoBean AutomationRuntimeFactsRepository facts;
    private String event, run, rule;
    private long originalRecords;

    @BeforeEach void fixture() {
        event = jdbc.queryForObject("select event_id from uav_event order by event_id limit 1", String.class);
        jdbc.update("update uav_event set state_code='PENDING_VERIFICATION' where event_id=?", event);
        originalRecords = records();
        long now = clock.nowMillis();
        rule = UUID.randomUUID().toString(); run = UUID.randomUUID().toString();
        jdbc.update("update automation_rule_condition set enabled=false where category='verify'");
        jdbc.update("insert into automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) values(?,'verify','隔离核实条件','confidence','90',0,true,?,?,'qa')", rule, now, now);
        jdbc.update("update automation_rule_group set version=731,scope_mode='ALL',schedule_mode='ALL_DAY',wait_seconds=0 where category='verify'");
        runtime.insertRun(new AutomationRuntimeRepository.RunRow(run,"verify",event,null,731,"PASS","隔离测试",now,now,"mock","[]"),"{}");
        runtime.state("verify",event,731,run,"PASS",null,"{}", "qa");
        when(policy.enabled()).thenReturn(true); when(policy.maxAge()).thenReturn(30000L);
        when(facts.read(eq(event),anyLong())).thenReturn(new AutomationRuntimeFacts(event,"target","alarm","org","district","mock","PENDING_VERIFICATION","UAV",now,
                Set.of(),true,Map.of("confidence",new AutomationRuntimeFacts.Fact("95",now,"qa-evidence",null))));
    }
    @Test void disabledEngineCannotApplyAnEarlierPass() {
        when(policy.enabled()).thenReturn(false); verification.confirmIfPassed(event,run); assertUnchanged();
    }
    @Test void newerRuleVersionCannotApplyAnEarlierPass() {
        jdbc.update("update automation_rule_group set version=732 where category='verify'");
        verification.confirmIfPassed(event,run); assertUnchanged();
    }
    @Test void disabledRuleCannotApplyAnEarlierPass() {
        jdbc.update("update automation_rule_condition set enabled=false where rule_id=?",rule);
        verification.confirmIfPassed(event,run); assertUnchanged();
    }
    @Test void unrelatedRunCannotBecomeVerificationEvidence() {
        verification.confirmIfPassed(event,UUID.randomUUID().toString()); assertUnchanged();
    }
    @Test void currentPassConfirmsOnlyOnceAndPreservesItsRunReference() {
        verification.confirmIfPassed(event,run); verification.confirmIfPassed(event,run);
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?",String.class,event)).isEqualTo("CONFIRMED");
        assertThat(records()).isEqualTo(originalRecords+1);
        assertThat(jdbc.queryForObject("select count(*) from uav_event_verification where event_id=? and automation_run_id=?",Integer.class,event,run)).isEqualTo(1);
    }
    @Test void workerFailureThenRecoveryAndFreshWorkerInstanceDoNotDuplicateVerification() {
        jdbc.update("update automation_rule_condition set enabled=false where category<>'verify'");
        when(facts.candidates(anyLong(), eq(100), anyString()))
                .thenThrow(new IllegalStateException("isolated fact source unavailable"))
                .thenReturn(List.of(event));
        worker.poll();
        assertUnchanged();
        assertThat(runtime.lastError()).isNotBlank();
        worker.poll();
        assertThat(runtime.lastError()).isNull();
        assertThat(records()).isEqualTo(originalRecords + 1);
        String recoveredRun = runtime.state("verify", event).runId();
        var restartedWorker = new com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeWorker(
                policy, runtime, facts,
                context.getBean(com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeService.class),
                verification, context.getBean(AlarmRuleCounter.class),
                context.getBean(com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService.class), clock);
        restartedWorker.poll();
        assertThat(records()).isEqualTo(originalRecords + 1);
        assertThat(runtime.state("verify", event).runId()).isEqualTo(recoveredRun);
        assertThat(runtime.lastError()).isNull();
    }
    private long records() { return jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?",Long.class,event); }
    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?",String.class,event)).isEqualTo("PENDING_VERIFICATION");
        assertThat(records()).isEqualTo(originalRecords);
    }
}
