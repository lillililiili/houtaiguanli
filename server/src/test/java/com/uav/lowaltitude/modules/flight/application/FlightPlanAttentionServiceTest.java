package com.uav.lowaltitude.modules.flight.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.HitDetailDto;
import com.uav.lowaltitude.modules.flight.api.FlightActualsDtos.MatchDto;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.OpenDeviceTaskRow;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanQuery;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository.PlanRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.PlanStateCount;
import com.uav.lowaltitude.platform.api.ApiException;

class FlightPlanAttentionServiceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 16, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final AccessDecision ACCESS = new AccessDecision("user", ScopeMode.ALL);
    private static final List<String> ACTIVE = List.of("EXECUTING", "PENDING", "APPROVED");

    private FlightReadRepository plans;
    private FlightActualsService actuals;
    private RiskRepository risks;
    private AccessControlService access;
    private FlightPlanAttentionService service;

    // 执行中：偏离（较早开始）、未找到对应飞机（最新开始）、设备、正常两条；待执行两条（风险、正常）。
    private final PlanRow deviated = row("deviated", "EXECUTING", T0.minusHours(3));
    private final PlanRow unmatched = row("unmatched", "EXECUTING", T0.minusHours(1));
    private final PlanRow device = row("device", "EXECUTING", T0.minusHours(2));
    private final PlanRow normalOld = row("normal-old", "EXECUTING", T0.minusHours(5));
    private final PlanRow normalNew = row("normal-new", "EXECUTING", T0.minusHours(4));
    private final PlanRow pendingRisk = row("pending-risk", "PENDING", T0.plusHours(5));
    private final PlanRow pendingSoon = row("pending-soon", "PENDING", T0.plusHours(1));
    private final PlanRow pendingLater = row("pending-later", "PENDING", T0.plusHours(9));
    private final PlanRow ended = row("ended", "COMPLETED", T0.minusDays(1));

    @BeforeEach
    void setUp() {
        plans = mock(FlightReadRepository.class);
        actuals = mock(FlightActualsService.class);
        risks = mock(RiskRepository.class);
        access = mock(AccessControlService.class);
        service = new FlightPlanAttentionService(plans, actuals, risks, access);

        when(plans.listPlansInStatuses(any(), eq(ACCESS), eq(ACTIVE), eq(true), eq(0), anyInt())).thenReturn(List.of(
                normalOld, pendingLater, deviated, pendingSoon, device, unmatched, normalNew, pendingRisk));
        when(plans.countPlansInStatuses(any(), eq(ACCESS), eq(ACTIVE), eq(false))).thenReturn(1L);
        when(plans.listPlansInStatuses(any(), eq(ACCESS), eq(ACTIVE), eq(false), anyInt(), anyInt())).thenReturn(List.of(ended));
        when(plans.openDeviceTasks(anyList())).thenReturn(List.of(new OpenDeviceTaskRow("device", "task-1", "雷达一号", "SUBMITTED")));

        when(actuals.assessmentAccess()).thenReturn(ACCESS);
        when(actuals.latestMatch(any(), eq(ACCESS))).thenReturn(match("MATCHED", null));
        when(actuals.latestMatch(eq("deviated"), eq(ACCESS))).thenReturn(match("PARTIAL", "MISMATCH"));
        when(actuals.latestMatch(eq("unmatched"), eq(ACCESS))).thenReturn(match("NONE", null));

        when(access.require(PermissionCode.RISK_READ)).thenReturn(ACCESS);
        when(risks.pendingCountsByPlan(any(), anyList(), eq(ACCESS))).thenReturn(List.of(
                new PlanStateCount("pending-risk", "PENDING_VERIFICATION", "WEATHER", "WEATHER_THUNDERSTORM", 2),
                new PlanStateCount("pending-risk", "PENDING_NOTIFICATION", "WEATHER", "WEATHER_THUNDERSTORM", 1)));
    }

    @Test
    void onlyDeviceAndRiskNeedActionWhileMatchFactsRemainReadable() {
        var ranked = service.page(PlanQuery.empty(), ACCESS, null, 0, 20);

        assertThat(ranked.rows()).extracting(PlanRow::planId).containsExactly(
                "device", "pending-risk", "unmatched", "deviated",
                "normal-new", "normal-old",
                "pending-soon", "pending-later",
                "ended");
        assertThat(ranked.total()).isEqualTo(9);
        assertThat(ranked.summary().needsAction()).isEqualTo(2);
        assertThat(ranked.summary().deviation()).isZero();
        assertThat(ranked.summary().aircraft()).isZero();
        assertThat(ranked.summary().device()).isEqualTo(1);
        assertThat(ranked.summary().risk()).isEqualTo(1);
        assertThat(ranked.summary().otherExecuting()).isEqualTo(4);
        assertThat(ranked.attention().get("pending-risk").pendingRiskKinds()).extracting(kind -> kind.reasonCode())
                .containsExactly("WEATHER_THUNDERSTORM");

        assertThat(ranked.attention().get("deviated").matchIssue()).isEqualTo("DEVIATION");
        assertThat(ranked.attention().get("unmatched").kinds()).isEmpty();
        assertThat(ranked.attention().get("unmatched").matchIssue()).isEqualTo("NONE");
        assertThat(ranked.attention().get("device").deviceTasks()).singleElement()
                .satisfies(task -> assertThat(task.deviceName()).isEqualTo("雷达一号"));
        assertThat(ranked.attention().get("pending-risk").pendingVerificationRisks()).isEqualTo(2);
        assertThat(ranked.attention().get("pending-risk").pendingNotificationRisks()).isEqualTo(1);
        assertThat(ranked.attention().get("normal-new").needsAction()).isFalse();
        assertThat(ranked.attention()).doesNotContainKey("ended");
    }

    @Test
    void deviceAndRiskOnSameTaskCountOnceRegardlessOfMatchConclusion() {
        when(risks.pendingCountsByPlan(any(), anyList(), eq(ACCESS))).thenReturn(List.of(
                new PlanStateCount("device", "PENDING_VERIFICATION", "WEATHER", "WEATHER_THUNDERSTORM", 2)));
        when(actuals.latestMatch("device", ACCESS)).thenReturn(match("PARTIAL", "MISMATCH"));
        var ranked = service.page(PlanQuery.empty(), ACCESS, null, 0, 20);
        assertThat(ranked.summary().needsAction()).isEqualTo(1);
        assertThat(ranked.summary().device()).isEqualTo(1);
        assertThat(ranked.summary().risk()).isEqualTo(1);
        assertThat(ranked.attention().get("device").kinds()).containsExactly("DEVICE", "RISK");
        assertThat(ranked.attention().get("deviated").needsAction()).isFalse();
        assertThat(ranked.attention().get("unmatched").needsAction()).isFalse();
    }

    @Test
    void laterPagesContinueIntoEndedPlansFromTheDatabase() {
        var second = service.page(PlanQuery.empty(), ACCESS, null, 6, 5);
        assertThat(second.rows()).extracting(PlanRow::planId).containsExactly("pending-soon", "pending-later", "ended");
        verify(plans).listPlansInStatuses(any(), eq(ACCESS), eq(ACTIVE), eq(false), eq(0), eq(3));

        service.page(PlanQuery.empty(), ACCESS, null, 10, 5);
        verify(plans).listPlansInStatuses(any(), eq(ACCESS), eq(ACTIVE), eq(false), eq(2), eq(5));
    }

    @Test
    void kindFilterKeepsOnlyPlansWithThatProblem() {
        var risk = service.page(PlanQuery.empty(), ACCESS, "RISK", 0, 20);
        assertThat(risk.rows()).extracting(PlanRow::planId).containsExactly("pending-risk");
        assertThat(risk.total()).isEqualTo(1);
        assertThat(risk.summary().needsAction()).isEqualTo(2);

        var aircraft = service.page(PlanQuery.empty(), ACCESS, "AIRCRAFT", 0, 20);
        assertThat(aircraft.rows()).isEmpty();
    }

    @Test
    void conclusionsTheViewerCannotReadDoNotAffectOrdering() {
        when(actuals.assessmentAccess()).thenReturn(null);
        when(access.require(PermissionCode.RISK_READ)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权限"));

        var ranked = service.page(PlanQuery.empty(), ACCESS, null, 0, 20);
        assertThat(ranked.rows()).extracting(PlanRow::planId).containsExactly(
                "device", "unmatched", "deviated", "normal-new", "normal-old", "pending-soon", "pending-risk", "pending-later", "ended");
        assertThat(ranked.summary().needsAction()).isEqualTo(1);
        verify(actuals, never()).latestMatch(any(), any());
        verify(risks, never()).pendingCountsByPlan(any(), anyList(), any());
    }

    @Test
    void matchIssueSeparatesNotYetMatchedFromNoMatchAndIgnoresPartialWithoutCorridorMismatch() {
        assertThat(FlightPlanAttentionService.matchIssue(MatchDto.only("NO_EVALUATION"))).isEqualTo("NO_EVALUATION");
        assertThat(FlightPlanAttentionService.matchIssue(MatchDto.only("FORBIDDEN"))).isNull();
        assertThat(FlightPlanAttentionService.matchIssue(match("NONE", null))).isEqualTo("NONE");
        assertThat(FlightPlanAttentionService.matchIssue(match("PARTIAL", "MATCH"))).isEqualTo("PARTIAL");
        assertThat(FlightPlanAttentionService.matchIssue(match("UNDETERMINED", null))).isEqualTo("UNDETERMINED");
        assertThat(FlightPlanAttentionService.matchIssue(match("PARTIAL", "MISMATCH"))).isEqualTo("DEVIATION");
        assertThat(FlightPlanAttentionService.matchIssue(match("MATCHED", null))).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {2000, 2001, 4001})
    void completePopulationDrivesPriorityFiltersSummaryAndEveryPage(int activeCount) {
        String prefix = UUID.randomUUID().toString();
        var deviation = row(prefix + "-deviation", "EXECUTING", T0.minusDays(4));
        var aircraft = row(prefix + "-aircraft", "EXECUTING", T0.minusDays(2));
        var faultyDevice = row(prefix + "-device", "EXECUTING", T0.minusDays(3));
        var normal = row(prefix + "-normal", "EXECUTING", T0.minusDays(5));
        var riskPlan = row(prefix + "-risk", "APPROVED", T0.plusHours(2));
        // The oldest five tasks fall beyond the former cap. Tied times exercise deterministic ID ordering.
        var pending = IntStream.range(0, activeCount - 5)
                .mapToObj(i -> row(prefix + "-pending-" + i, "PENDING", T0.plusDays(1).plusSeconds(i / 3))).toList();
        List<PlanRow> population = new ArrayList<>(pending);
        population.addAll(List.of(deviation, aircraft, faultyDevice, normal, riskPlan));
        population.sort(Comparator.comparing(PlanRow::startAt).reversed().thenComparing(PlanRow::planId));
        var history = IntStream.range(0, 7).mapToObj(i -> row(prefix + "-ended-" + i,
                i % 2 == 0 ? "COMPLETED" : "CANCELLED", T0.minusDays(10).minusHours(i))).toList();
        when(plans.listPlansInStatuses(any(), eq(ACCESS), eq(ACTIVE), eq(true), anyInt(), anyInt()))
                .thenAnswer(call -> pageOf(population, call.getArgument(4), call.getArgument(5)));
        when(plans.countPlansInStatuses(any(), eq(ACCESS), eq(ACTIVE), eq(false))).thenReturn((long) history.size());
        when(plans.listPlansInStatuses(any(), eq(ACCESS), eq(ACTIVE), eq(false), anyInt(), anyInt()))
                .thenAnswer(call -> pageOf(history, call.getArgument(4), call.getArgument(5)));
        when(actuals.latestMatch(deviation.planId(), ACCESS)).thenReturn(match("PARTIAL", "MISMATCH"));
        when(actuals.latestMatch(aircraft.planId(), ACCESS)).thenReturn(match("NONE", null));
        when(plans.openDeviceTasks(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).contains(faultyDevice.planId())
                ? List.of(new OpenDeviceTaskRow(faultyDevice.planId(), prefix + "-task", "设备", "SUBMITTED")) : List.of());
        var facts = List.of(new PlanStateCount(aircraft.planId(), "PENDING_NOTIFICATION", "WEATHER", "WEATHER_THUNDERSTORM", 2),
                new PlanStateCount(riskPlan.planId(), "PENDING_VERIFICATION", "SPACE_OBJECT", "OBJECT_NEAR_ROUTE", 3));
        when(risks.pendingCountsByPlan(any(), anyList(), eq(ACCESS))).thenAnswer(call ->
                facts.stream().filter(fact -> ((List<?>) call.getArgument(1)).contains(fact.planId())).toList());

        List<PlanRow> expected = new ArrayList<>(List.of(aircraft, faultyDevice, riskPlan, deviation, normal));
        expected.addAll(pending.stream().sorted(Comparator.comparing(PlanRow::startAt).thenComparing(PlanRow::planId)).toList());
        expected.addAll(history);
        List<String> collected = new ArrayList<>();
        for (int offset = 0; offset < expected.size(); offset += 73) {
            var page = service.page(PlanQuery.empty(), ACCESS, null, offset, 73);
            assertThat(page.rows()).containsExactlyElementsOf(pageOf(expected, offset, 73));
            assertThat(page.total()).isEqualTo(expected.size());
            assertThat(page.summary().needsAction()).isEqualTo(3);
            assertThat(page.summary().deviation()).isZero();
            assertThat(page.summary().aircraft()).isZero();
            assertThat(page.summary().device()).isEqualTo(1);
            assertThat(page.summary().risk()).isEqualTo(2);
            assertThat(page.summary().otherExecuting()).isEqualTo(2);
            collected.addAll(page.rows().stream().map(PlanRow::planId).toList());
        }
        assertThat(collected).doesNotHaveDuplicates().containsExactlyElementsOf(expected.stream().map(PlanRow::planId).toList());
        for (var filter : Map.of("DEVICE", List.of(faultyDevice), "RISK", List.of(aircraft, riskPlan)).entrySet()) {
            for (int offset = 0; offset <= filter.getValue().size(); offset++) {
                var page = service.page(PlanQuery.empty(), ACCESS, filter.getKey(), offset, 1);
                assertThat(page.rows()).containsExactlyElementsOf(pageOf(filter.getValue(), offset, 1));
                assertThat(page.total()).isEqualTo(filter.getValue().size());
                assertThat(page.summary().needsAction()).isEqualTo(3);
            }
        }
        assertThat(service.page(PlanQuery.empty(), ACCESS, null, Integer.MAX_VALUE - 10, 100).rows()).isEmpty();
        var transition = service.page(PlanQuery.empty(), ACCESS, null, activeCount - 1, 4);
        assertThat(transition.rows()).containsExactlyElementsOf(expected.subList(activeCount - 1, activeCount + 3));
    }

    private static <T> List<T> pageOf(List<T> rows, int offset, int size) {
        return offset >= rows.size() ? List.of() : rows.subList(offset, (int) Math.min(rows.size(), (long) offset + size));
    }

    private static MatchDto match(String code, String corridor) {
        List<HitDetailDto> hits = corridor == null ? List.of()
                : List.of(new HitDetailDto("C01", "HIT", null, null, Map.of("dimensions", Map.of("corridor", corridor)), List.of(), List.of()));
        return new MatchDto("AVAILABLE", code, "evaluation", 0L, null, hits, null, List.of());
    }

    private static PlanRow row(String id, String status, OffsetDateTime startAt) {
        return new PlanRow(id, id.toUpperCase(), status, null, null, "mock", null, startAt, startAt.plusHours(1), "org", "district",
                "rv", "route", "R-1", "航线", 1, null, T0, T0, 0, null, null, null);
    }
}
