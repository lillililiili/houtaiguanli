package com.uav.lowaltitude.modules.target.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.*;

/** A located target must not scan bearing history; other summaries still cover the whole page. */
class TargetBearingPrefilterTest {
    private final TargetReadRepository repository = mock(TargetReadRepository.class);
    private final FusionConfigService config = mock(FusionConfigService.class);
    private final TargetReadService service = new TargetReadService(mock(AccessControlService.class), repository,
            mock(LineageRepository.class), mock(DegradationRepository.class), mock(FusedTrackRepository.class),
            new ObjectMapper(), config, mock(SourceContributionReader.class));
    private final OffsetDateTime now = OffsetDateTime.now();
    private final Coordinate position = new Coordinate(new BigDecimal("118.61"), new BigDecimal("37.42"));

    TargetBearingPrefilterTest() {
        FusionParams params = mock(FusionParams.class);
        when(config.params(null)).thenReturn(params);
        when(params.integer("identity", "terminate_after_ms")).thenReturn(60_000);
    }

    @Test
    void mixedPageKeepsAllThreeSummariesAndOnlyRequestsUsableBearings() {
        TargetRow located = row(position, true), unlocated = row(null, true), noState = row(null, false);
        when(unlocated.pilotLocation()).thenReturn(position);
        List<String> allIds = List.of(located.targetId(), unlocated.targetId(), noState.targetId());
        var locatedSummary = summaries("located", null);
        var unlocatedSummary = summaries("unlocated", new BearingRow(new BigDecimal("217.5"), "direction-device"));
        List<String> bearingIds = List.of(unlocated.targetId());
        Map<String, TargetSummariesRow> summaryRows = Map.of(
                located.targetId(), locatedSummary, unlocated.targetId(), unlocatedSummary);
        when(repository.summaries(allIds, bearingIds)).thenReturn(summaryRows);
        page(List.of(located, unlocated, noState));

        var result = service.targets(new LinkedMultiValueMap<>()).items();

        assertThat(result).hasSize(3);
        assertThat(result.get(0).riskSummary().riskId()).isEqualTo("risk-located");
        assertThat(result.get(0).legalitySummary().evaluationId()).isEqualTo("evaluation-located");
        assertThat(result.get(0).disposalSummary().authorizationId()).isEqualTo("authorization-located");
        assertThat(result.get(0).latestState().bearingDeg()).isNull();
        assertThat(result.get(1).riskSummary().riskId()).isEqualTo("risk-unlocated");
        assertThat(result.get(1).legalitySummary().evaluationId()).isEqualTo("evaluation-unlocated");
        assertThat(result.get(1).disposalSummary().authorizationId()).isEqualTo("authorization-unlocated");
        assertThat(result.get(1).latestState().pilotLocation()).isNotNull();
        assertThat(result.get(1).latestState().bearingDeg()).isEqualByComparingTo("217.5");
        assertThat(result.get(2).latestState()).isNull();
        verify(repository).summaries(allIds, List.of(unlocated.targetId()));
        verify(repository, never()).summaries(anyList());
    }

    @Test
    void locatedAndUnknownStatePageUsesEmptyBearingSelection() {
        TargetRow located = row(position, true), noState = row(null, false);
        page(List.of(located, noState));

        service.targets(new LinkedMultiValueMap<>());

        verify(repository).summaries(List.of(located.targetId(), noState.targetId()), List.of());
    }

    @Test
    void emptyPageDoesNotNeedAnySummaryOrMapLifetime() {
        page(List.of());

        assertThat(service.targets(new LinkedMultiValueMap<>()).items()).isEmpty();

        verify(repository).summaries(List.of(), List.of());
        verify(config, never()).params(any());
    }

    @Test
    void detailReevaluatesEligibilityAfterPositionIsLostAndRestored() {
        TargetRow target = row(position, true);
        String targetId = target.targetId();
        List<String> targetIds = List.of(targetId);
        Map<String, TargetSummariesRow> summaryRows = Map.of(targetId,
                summaries("lost", new BearingRow(new BigDecimal("83.2"), "device-lost")));
        when(repository.findTarget(eq(targetId), any())).thenReturn(target);
        when(repository.summaries(targetIds, targetIds)).thenReturn(summaryRows);

        assertThat(service.target(targetId).latestState().bearingDeg()).isNull();
        when(target.location()).thenReturn(null);
        assertThat(service.target(targetId).latestState().bearingDeg()).isEqualByComparingTo("83.2");
        when(target.location()).thenReturn(position);
        assertThat(service.target(targetId).latestState().bearingDeg()).isNull();

        verify(repository, times(2)).summaries(targetIds, List.of());
        verify(repository).summaries(targetIds, targetIds);
    }

    @Test
    void detailWithoutObservedStateDoesNotRequestBearingEvenWhenPositionIsMissing() {
        TargetRow target = row(null, false);
        when(repository.findTarget(eq(target.targetId()), any())).thenReturn(target);

        assertThat(service.target(target.targetId()).latestState()).isNull();

        verify(repository).summaries(List.of(target.targetId()), List.of());
    }

    private void page(List<TargetRow> rows) {
        when(repository.listTargets(any(), any(), anyInt(), anyInt())).thenReturn(rows);
        when(repository.countTargets(any(), any())).thenReturn((long) rows.size());
    }

    private TargetRow row(Coordinate location, boolean observed) {
        TargetRow row = mock(TargetRow.class);
        when(row.targetId()).thenReturn(UUID.randomUUID().toString());
        when(row.location()).thenReturn(location);
        when(row.stateObservedAt()).thenReturn(observed ? now : null);
        when(row.stateReceivedAt()).thenReturn(observed ? now : null);
        when(row.createdAt()).thenReturn(now);
        when(row.updatedAt()).thenReturn(now);
        when(row.unknownFields()).thenReturn("[]");
        return row;
    }

    private TargetSummariesRow summaries(String suffix, BearingRow bearing) {
        return new TargetSummariesRow(new RiskSummaryRow("risk-" + suffix, "HIGH", "PENDING_VERIFICATION", now),
                new LegalitySummaryRow("evaluation-" + suffix, "ILLEGAL", "HIGH", "[]", null),
                new DisposalSummaryRow("authorization-" + suffix, "AUTH-" + suffix, "COUNTER", "COMPLETED"), bearing);
    }
}
