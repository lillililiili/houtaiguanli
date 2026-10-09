package com.uav.lowaltitude.modules.alarm.application;

import static com.uav.lowaltitude.modules.alarm.application.AlarmRuleCounterTest.authorization;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.alarm.application.CounterHistory.Next;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;

class CounterHistoryTest {
    private static final OffsetDateTime NOW = Instant.parse("2026-09-23T08:00:00Z").atOffset(ZoneOffset.UTC);
    private static final String AUTO = AutomationPrincipal.USER_ID;

    @Test void noCounterYetLaunchesTheFirstAttempt() {
        assertThat(CounterHistory.of(List.of(), NOW)).isEqualTo(new CounterHistory(Next.LAUNCH, null, 1));
    }

    @Test void startCancelledBeforeSendingOrExpiredUnsentIsRetriedUpToThreeAttempts() {
        var cancelled = authorization("a1", DisposalRules.FAILED, AUTO, "cmd-1", "AUTHORIZATION_STOPPED");
        var offline = authorization("a2", DisposalRules.FAILED, AUTO, "cmd-2", "DEVICE_NOT_OPERABLE");
        var expired = authorization("a3", DisposalRules.EXPIRED, AUTO, null, null);
        assertThat(CounterHistory.of(List.of(cancelled), NOW)).isEqualTo(new CounterHistory(Next.LAUNCH, null, 2));
        assertThat(CounterHistory.of(List.of(cancelled, offline), NOW)).isEqualTo(new CounterHistory(Next.LAUNCH, null, 3));
        assertThat(CounterHistory.of(List.of(cancelled, offline, expired), NOW).next()).isEqualTo(Next.NOTHING);
    }

    @Test void queuedAutomaticCounterIsDispatchedInsteadOfCreatingAnother() {
        var cancelled = authorization("a1", DisposalRules.FAILED, AUTO, "cmd-1", "AUTHORIZATION_STOPPED");
        var queued = authorization("a2", DisposalRules.APPROVED, AUTO, null, null);
        var history = CounterHistory.of(List.of(cancelled, queued), NOW);
        assertThat(history.next()).isEqualTo(Next.DISPATCH_QUEUED);
        assertThat(history.queued()).isSameAs(queued);
        assertThat(history.attempt()).isEqualTo(2);
    }

    @Test void approvedPastItsWindowCountsAsUnsentButNotAsQueued() {
        var stale = withWindow(authorization("a1", DisposalRules.APPROVED, AUTO, null, null), NOW.minusMinutes(20), NOW.minusMinutes(10));
        assertThat(CounterHistory.of(List.of(stale), NOW)).isEqualTo(new CounterHistory(Next.LAUNCH, null, 2));
    }

    @Test void anythingThatMayHaveReachedTheDeviceOrInvolvedAPersonStopsAutomation() {
        for (AuthorizationRow row : List.of(
                authorization("timeout", DisposalRules.FAILED, AUTO, "cmd", "ADAPTER_TIMEOUT"),
                authorization("device-failed", DisposalRules.FAILED, AUTO, "cmd", "COUNTERMEASURE_SET_FAILED"),
                authorization("running", DisposalRules.EXECUTING, AUTO, "cmd", null),
                authorization("done", DisposalRules.COMPLETED, AUTO, "cmd", "COUNTERMEASURE_SET_OK"),
                authorization("stopped", DisposalRules.STOPPED, AUTO, "cmd", "STOPPED_BY_OPERATOR"),
                authorization("cancelled", DisposalRules.CANCELLED, AUTO, null, null),
                authorization("manual-cancelled-start", DisposalRules.FAILED, "operator-1", "cmd", "AUTHORIZATION_STOPPED"),
                authorization("manual-queued", DisposalRules.APPROVED, "operator-1", null, null))) {
            assertThat(CounterHistory.of(List.of(row), NOW).next()).as(row.authorizationId()).isEqualTo(Next.NOTHING);
        }
    }

    private static AuthorizationRow withWindow(AuthorizationRow r, OffsetDateTime from, OffsetDateTime until) {
        return new AuthorizationRow(r.authorizationId(), r.authorizationNo(), r.actionType(), r.subjectKind(), r.subjectId(),
                r.targetId(), r.deviceId(), r.channel(), r.reason(), r.requestedBy(), from, r.approvedBy(), r.approvedAt(),
                r.decisionNote(), from, until, r.status(), r.executionCommandId(), r.resultCode(), r.resultDetail(),
                r.policyVersion(), r.ownerOrgId(), r.districtId(), r.sourceMode(), r.version(), r.requestedByName(),
                r.approvedByName(), r.authorizationMode());
    }
}
