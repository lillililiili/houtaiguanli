package com.uav.lowaltitude.modules.target.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.fusion.infrastructure.DegradationRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.LineageRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.TargetRow;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class TargetSituationVisibilityTest {
    @Test void mapPresentationUsesOnlyTheAuthorizedPageAndLeavesHistoryAndOtherTypesIntact() {
        var access=mock(AccessControlService.class);
        var scope=new AccessDecision("isolated-map-reader",ScopeMode.ALL);
        when(access.require(PermissionCode.TARGET_READ)).thenReturn(scope);
        var repository=mock(TargetReadRepository.class);
        String id=UUID.randomUUID().toString(),birdId=UUID.randomUUID().toString();
        TargetRow uav=row(id,"UAV"),bird=row(birdId,"BIRD");
        when(repository.countTargets(any(),eq(scope))).thenReturn(2L);
        when(repository.listTargets(any(),eq(scope),anyInt(),anyInt())).thenReturn(List.of(uav,bird));
        when(repository.summaries(anyList(),anyList())).thenReturn(Map.of());
        var config=mock(FusionConfigService.class);
        var params=mock(FusionParams.class);
        when(config.params(null)).thenReturn(params);
        when(params.integer("identity","terminate_after_ms")).thenReturn(15000);
        var cases=mock(UavRiskCaseIdentity.class);
        when(cases.situationCompletedTargets(List.of(id))).thenReturn(Set.of(id));
        var service=new TargetReadService(access,repository,mock(LineageRepository.class),mock(DegradationRepository.class),
                mock(FusedTrackRepository.class),new ObjectMapper(),config,mock(SourceContributionReader.class));
        service.situationVisibility(cases);

        var query=new LinkedMultiValueMap<String,String>();
        var historical=service.targets(query);
        assertThat(historical.items()).allSatisfy(target->assertThat(target.situationVisibility()).isNull());
        verifyNoInteractions(cases);

        query.add("map_visible_at",Long.toString(System.currentTimeMillis()));
        var map=service.targets(query);
        assertThat(map.items().get(0).situationVisibility().state()).isEqualTo("COUNTERMEASURE_COMPLETED");
        assertThat(map.items().get(1).situationVisibility().state()).isEqualTo("VISIBLE");
        assertThat(map.total()).isEqualTo(2);
        assertThat(map.items()).extracting(target->target.targetId()).containsExactly(id,birdId);
        verify(cases).situationCompletedTargets(List.of(id));
        // A new risk cycle returns the target immediately; no completion cache is kept by this service.
        when(cases.situationCompletedTargets(List.of(id))).thenReturn(Set.of());
        assertThat(service.targets(query).items().get(0).situationVisibility().state()).isEqualTo("VISIBLE");
    }

    private static TargetRow row(String id,String type) {
        TargetRow row=mock(TargetRow.class);
        when(row.targetId()).thenReturn(id);
        when(row.objectTypeCode()).thenReturn(type);
        return row;
    }
}
