package com.uav.lowaltitude.modules.automationrule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.automationrule.api.AutomationRuleDtos.Rule;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository.Head;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeFactsRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.State;
import com.uav.lowaltitude.platform.time.AppClock;

/** Rechecks actual evaluator semantics using explicit facts; no device or notification adapters. */
class AutomationRuntimeEligibilityTest {
    private static final long NOW = Instant.parse("2026-09-29T10:00:00Z").toEpochMilli();
    private final AutomationRuleRepository config = mock(AutomationRuleRepository.class);
    private final AutomationRuntimeRepository runs = mock(AutomationRuntimeRepository.class);
    private final AutomationRuntimeFactsRepository facts = mock(AutomationRuntimeFactsRepository.class);
    private final AutomationRuntimePolicy policy = mock(AutomationRuntimePolicy.class);
    private AutomationRuntimeEligibility eligibility;

    @BeforeEach void fixture() {
        eligibility = new AutomationRuntimeEligibility(config, runs, facts, policy, new ObjectMapper(),
                new AppClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)));
        when(policy.enabled()).thenReturn(true); when(policy.maxAge()).thenReturn(30000L);
        when(config.head("counter", false)).thenReturn(head(2, "ALL_DAY", "00:00", "23:59"));
        when(config.rules("counter")).thenReturn(List.of(rule(true, 0)));
        when(config.scopes("counter")).thenReturn(List.of());
        when(runs.state("counter", "event")).thenReturn(new State("counter", "event", 2, "run", "PASS", null, "{}", ""));
        when(facts.read("event", NOW)).thenReturn(observation("1", NOW));
    }
    @Test void unchangedCurrentFactsMayPass() { assertThat(check()).isEqualTo("PASS"); }
    @Test void currentRunAndFactsMayDispatch() { assertThat(eligibility.allowsRun("counter", "event", "run")).isTrue(); }
    @Test void oldOrOtherEventRunCannotDispatch() {
        assertThat(eligibility.allowsRun("counter", "event", "other-run")).isFalse();
        verifyNoInteractions(facts);
    }
    @Test void runReplacedDuringRecheckCannotDispatch() {
        var initial = new State("counter", "event", 2, "run", "PASS", null, "{}", "");
        var replaced = new State("counter", "event", 2, "new-run", "PASS", null, "{}", "");
        when(runs.state("counter", "event")).thenReturn(initial, initial, replaced);
        assertThat(eligibility.allowsRun("counter", "event", "run")).isFalse();
    }
    @Test void disabledEnginePausesBeforeReadingFacts() {
        when(policy.enabled()).thenReturn(false);
        assertThat(check()).isEqualTo("PAUSED"); verifyNoInteractions(facts);
    }
    @Test void newerRuleVersionCannotReusePersistedPass() {
        when(config.head("counter", false)).thenReturn(head(3, "ALL_DAY", "00:00", "23:59"));
        assertThat(check()).isEqualTo("WAITING"); verifyNoInteractions(facts);
    }
    @Test void versionChangeDuringReadCannotPass() {
        when(config.head("counter", false)).thenReturn(head(2, "ALL_DAY", "00:00", "23:59"), head(3, "ALL_DAY", "00:00", "23:59"));
        assertThat(check()).isEqualTo("WAITING");
    }
    @Test void noCurrentRuntimeStateCannotPass() {
        when(runs.state("counter", "event")).thenReturn(null);
        assertThat(check()).isEqualTo("WAITING");
    }
    @Test void disabledConditionPauses() {
        when(config.rules("counter")).thenReturn(List.of(rule(false, 0)));
        assertThat(check()).isEqualTo("PAUSED");
    }
    @Test void newHoldCannotBorrowElapsedWallClockTime() {
        when(config.rules("counter")).thenReturn(List.of(rule(true, 5)));
        assertThat(check()).isEqualTo("WAITING");
    }
    @Test void currentScheduleIsChecked() {
        when(config.head("counter", false)).thenReturn(head(2, "DAILY", "00:00", "01:00"));
        assertThat(check()).isEqualTo("OUT_OF_SCHEDULE");
    }
    @Test void failingConditionBlocksOldPass() {
        when(facts.read("event", NOW)).thenReturn(observation("61", NOW));
        assertThat(check()).isEqualTo("NOT_MATCHED");
    }
    @Test void unknownCurrentConditionNeverPasses() {
        when(facts.read("event", NOW)).thenReturn(observation(null, NOW));
        assertThat(check()).isEqualTo("REVIEW");
    }
    @ParameterizedTest @ValueSource(longs = {-30001, 1})
    void staleOrFutureObservationCannotReuseOldPass(long offset) {
        when(facts.read("event", NOW)).thenReturn(observation("1", NOW + offset));
        assertThat(check()).isEqualTo("REVIEW");
    }
    private String check() { return eligibility.check("counter", "event").status(); }
    private Head head(long version, String schedule, String start, String end) {
        return new Head("counter", version, "ALL", schedule, start, end, 0, "[]");
    }
    private Rule rule(boolean enabled, int hold) {
        return new Rule("fresh", "观测仍有效", "counterFreshness", "60", hold, enabled, 0, "qa");
    }
    private AutomationRuntimeFacts observation(String value, long observed) {
        return new AutomationRuntimeFacts("event", "target", "alarm", "org", "district", "mock", "CONFIRMED", "UAV", observed,
                Set.of(), true, Map.of("counterFreshness", new AutomationRuntimeFacts.Fact(value, observed, "qa-observation", null)));
    }
}
