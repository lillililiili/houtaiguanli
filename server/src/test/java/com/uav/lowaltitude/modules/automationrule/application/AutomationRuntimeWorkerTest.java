package com.uav.lowaltitude.modules.automationrule.application;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.uav.lowaltitude.modules.alarm.application.AlarmRuleCounter;
import com.uav.lowaltitude.modules.alarm.application.AlarmRuleVerification;
import com.uav.lowaltitude.modules.automationrule.infrastructure.*;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.platform.time.AppClock;

@ExtendWith(MockitoExtension.class)
class AutomationRuntimeWorkerTest {
    @Mock AutomationRuntimePolicy policy;
    @Mock AutomationRuntimeRepository runs;
    @Mock AutomationRuntimeFactsRepository facts;
    @Mock AutomationRuntimeService service;
    @Mock AlarmRuleVerification verification;
    @Mock AlarmRuleCounter counter;
    @Mock HandoffSubmissionService handoffs;
    @Test void passingCounterInvokesTheGuardedAutomaticChain() {
        ready("PASS").poll();
        verify(counter).launchIfPassed("event-a", "run-a");
    }
    @ParameterizedTest @ValueSource(strings = {"DISABLED", "NOT_MATCHED", "REVIEW", "WAITING", "OUT_OF_SCOPE"})
    void absentOrUnmetRulesLeaveCounterToTheOperator(String state) {
        ready(state).poll();
        verifyNoInteractions(counter);
    }
    private AutomationRuntimeWorker ready(String status) {
        when(policy.enabled()).thenReturn(true);
        when(runs.cursor()).thenReturn("");
        when(facts.candidates(anyLong(), eq(100), eq(""))).thenReturn(List.of("event-a"));
        when(runs.state(anyString(), eq("event-a"))).thenAnswer(call -> "counter".equals(call.getArgument(0))
                ? new AutomationRuntimeRepository.State("counter", "event-a", 3, "run-a", status, null, "{}", "sig") : null);
        return new AutomationRuntimeWorker(policy, runs, facts, service, verification, counter, handoffs, new AppClock());
    }
}
