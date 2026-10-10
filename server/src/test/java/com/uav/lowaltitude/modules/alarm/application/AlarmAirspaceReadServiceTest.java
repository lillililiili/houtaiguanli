package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.airspace.application.AirspaceReadService;
import com.uav.lowaltitude.modules.airspace.api.AirspaceDtos;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.*;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;

class AlarmAirspaceReadServiceTest {
    final AccessControlService access = mock(AccessControlService.class);
    final AlarmReadRepository repository = mock(AlarmReadRepository.class);
    final LegalityEvaluationReadService evaluations = mock(LegalityEvaluationReadService.class);
    final AirspaceReadService airspaces = mock(AirspaceReadService.class);
    final String alarmId = UUID.randomUUID().toString(), evaluationId = UUID.randomUUID().toString();
    final AlarmAirspaceReadService service = new AlarmAirspaceReadService(access, repository, evaluations, airspaces, new ObjectMapper());

    AlarmReadRepository.AlarmRow alarm(String detail) {
        var row = mock(AlarmReadRepository.AlarmRow.class);
        when(row.detailJson()).thenReturn(detail); when(row.targetId()).thenReturn("target");
        when(row.ownerOrgId()).thenReturn("org"); when(row.districtId()).thenReturn("district");
        when(row.sourceMode()).thenReturn("replay");
        when(repository.find(eq(alarmId), any())).thenReturn(row);
        return row;
    }
    EvaluationDto evaluation(String id, String result, String version) {
        var e = mock(EvaluationDto.class);
        when(e.evaluationId()).thenReturn(id); when(e.targetId()).thenReturn("target");
        when(e.ownerOrgId()).thenReturn("org"); when(e.districtId()).thenReturn("district");
        when(e.sourceMode()).thenReturn("replay"); when(e.mode()).thenReturn("ACTIVE");
        when(e.hitDetails()).thenReturn(List.of(new HitDetailDto("C02-1", null, result, "INSIDE_RESTRICTED_AIRSPACE", null,
                Map.of("airspace_id", "space", "airspace_version_id", version), List.of(), List.of(), null)));
        when(evaluations.detail(id)).thenReturn(e);
        return e;
    }
    @Test void usesPinnedVersionAndKeepsEscalationSeparate() {
        alarm("{\"evaluation_id\":\"" + evaluationId + "\"}");
        var upgrade = UUID.randomUUID().toString();
        when(repository.airspaceEvaluationReferences(alarmId)).thenReturn(List.of(new AlarmReadRepository.AirspaceEvaluationReference(upgrade, 2)));
        evaluation(evaluationId, "FAIL", "old-version"); evaluation(upgrade, "FAIL", "upgrade-version");
        for (String id : List.of("old-version", "upgrade-version")) {
            var v = mock(AirspaceDtos.AirspaceVersionDto.class); when(v.airspaceId()).thenReturn("space");
            when(airspaces.versionContext(id)).thenReturn(new AirspaceDtos.VersionContextDto("space", "编号", "名称", "replay", "单位", v));
        }
        var result = service.read(alarmId);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.items()).extracting(x -> x.occurrence()).containsExactly(0, 2);
        verify(airspaces).versionContext("old-version"); verify(airspaces).versionContext("upgrade-version");
        verify(evaluations).detail(evaluationId); verify(evaluations).detail(upgrade);
        verifyNoMoreInteractions(evaluations);
    }
    @Test void missingEvidenceDoesNotUseCurrentOrLatest() {
        alarm(null);
        assertThat(service.read(alarmId).status()).isEqualTo("UNKNOWN");
        verifyNoInteractions(evaluations, airspaces);
    }
    @Test void undeterminedCoverageIsNotAHit() {
        alarm("{\"evaluation_id\":\"" + evaluationId + "\"}"); evaluation(evaluationId, "UNDETERMINED", "boundary");
        assertThat(service.read(alarmId).status()).isEqualTo("NO_HIT"); verifyNoInteractions(airspaces);
    }
    @Test void foreignTargetOrModeCannotSupplyHistory() {
        alarm("{\"evaluation_id\":\"" + evaluationId + "\"}");
        when(evaluation(evaluationId, "FAIL", "foreign").targetId()).thenReturn("other-target");
        assertThat(service.read(alarmId).status()).isEqualTo("PARTIAL"); verifyNoInteractions(airspaces);
    }
    @Test void inaccessibleVersionIsNotReportedAsNoHit() {
        alarm("{\"evaluation_id\":\"" + evaluationId + "\"}"); evaluation(evaluationId, "FAIL", "hidden");
        when(airspaces.versionContext("hidden")).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "AIRSPACE_VERSION_NOT_FOUND", "不可见"));
        assertThat(service.read(alarmId).status()).isEqualTo("PARTIAL");
    }
    @Test void deniedAirspacePermissionStopsBeforeReadingAlarm() {
        when(access.require(PermissionCode.AIRSPACE_READ)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权限"));
        assertThatThrownBy(() -> service.read(alarmId)).isInstanceOf(ApiException.class);
        verifyNoInteractions(repository, evaluations, airspaces);
    }
}
