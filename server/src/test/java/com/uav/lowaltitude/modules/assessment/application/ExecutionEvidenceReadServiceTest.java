package com.uav.lowaltitude.modules.assessment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.*;
import com.uav.lowaltitude.modules.flight.domain.FlightExecutionFacts.*;
import com.uav.lowaltitude.modules.flight.infrastructure.*;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.platform.api.ApiException;

class ExecutionEvidenceReadServiceTest {
    final LegalityEvaluationReadService evaluations=mock(LegalityEvaluationReadService.class);
    final FlightExecutionFactRepository facts=mock(FlightExecutionFactRepository.class);
    final FlightReadRepository plans=mock(FlightReadRepository.class);
    final AccessControlService access=mock(AccessControlService.class);
    final ExecutionEvidenceReadService service=new ExecutionEvidenceReadService(evaluations,facts,plans,access);
    final String evaluationId=id(),factId=id(),planId=id(),target=id(),track=id(),org=id(),district=id();
    final EvaluationDto evaluation=mock(EvaluationDto.class);
    ExecutionEvidenceReadServiceTest(){
        when(evaluations.detail(evaluationId)).thenReturn(evaluation);
        when(evaluation.targetId()).thenReturn(target);when(evaluation.trackId()).thenReturn(track);
        when(evaluation.ownerOrgId()).thenReturn(org);when(evaluation.districtId()).thenReturn(district);
        when(evaluation.planId()).thenReturn(planId);when(evaluation.sourceMode()).thenReturn("live");
    }
    HitDetailDto hit(String rule,String dimension){
        return new HitDetailDto(rule,null,"FAIL","MISMATCH",null,
                Map.of("dimension",dimension,"comparison","MISMATCH","plan_version",7,"fact_version",2),List.of(),
                List.of(new EvidenceRefDto("flight_plan",planId),new EvidenceRefDto("flight_execution_fact",factId)),null);
    }
    void plan(long version){
        var row=mock(FlightReadRepository.PlanRow.class);when(row.version()).thenReturn(version);
        when(row.planId()).thenReturn(planId);when(row.ownerOrgId()).thenReturn(org);when(row.districtId()).thenReturn(district);
        when(row.sourceMode()).thenReturn("live");when(plans.findPlan(eq(planId),any())).thenReturn(row);
        when(plans.findFiling(eq(planId),any())).thenReturn(new FlightReadRepository.FilingRow("计划飞手",null,null,null,
                new BigDecimal("117.1"),new BigDecimal("36.2"),new BigDecimal("117.3"),new BigDecimal("36.4"),"WGS84"));
    }
    Input actual(){
        return new Input(id(),"live",id(),2L,target,track,id(),1000L,5000L,"LANDED",
                new PositionEvent(2000L,new BigDecimal("117.2"),new BigDecimal("36.3"),BigDecimal.ZERO),
                new PositionEvent(4000L,new BigDecimal("117.4"),new BigDecimal("36.5"),BigDecimal.ONE),id(),id(),List.of("source-evidence"));
    }
    @Test void exactSavedFactProvidesActualCoordinatesAndSameVersionFilingWithoutInventingDatum(){
        when(evaluation.hitDetails()).thenReturn(List.of(hit("C02-9","takeoff_point"),hit("C02-10","landing_point")));
        plan(7);var input=actual();
        when(facts.historical(factId,target,track,org,district,"live")).thenReturn(new FlightExecutionFactRepository.StoredFact(input,3000L,"source"));
        var result=service.read(evaluationId);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.items().get(0).actual().longitude()).isEqualByComparingTo("117.2");
        assertThat(result.items().get(0).actual().accuracyM()).isZero();
        assertThat(result.items().get(0).actual().coordinateSystem()).isNull();
        assertThat(result.items().get(0).reason()).contains("未记录坐标系");
        assertThat(result.items().get(1).actual().occurredAt()).isEqualTo(4000L);
        assertThat(result.items().get(1).planned().latitude()).isEqualByComparingTo("36.4");
        assertThat(result.items().get(1).planned().coordinateSystem()).isEqualTo("WGS84");
        verify(facts,times(2)).historical(factId,target,track,org,district,"live");
        verifyNoMoreInteractions(facts);
    }
    @Test void changedPlanDoesNotSubstituteCurrentFiling(){
        when(evaluation.hitDetails()).thenReturn(List.of(hit("C02-9","takeoff_point")));plan(8);
        var result=service.read(evaluationId);
        assertThat(result.items().get(0).planned()).isNull();
        assertThat(result.items().get(0).plannedAvailability()).isEqualTo("UNKNOWN");
        assertThat(result.items().get(0).reason()).contains("原计划版本未留存或已变更");
        verify(plans,never()).findFiling(any(),any());
    }
    @Test void identitiesExposeOnlyScopedDirectoryLabelsAndNotPrivateIds(){
        when(evaluation.hitDetails()).thenReturn(List.of(hit("C02-11","pilot"),hit("C02-12","reporting_unit")));plan(7);
        var input=actual();when(facts.historical(factId,target,track,org,district,"live")).thenReturn(new FlightExecutionFactRepository.StoredFact(input,3000L,"source"));
        when(facts.pilotLabel(input.pilotContactId(),org)).thenReturn("实际飞手");
        when(facts.reportingLabel(input.reportingBindingId(),input.sourceId(),org)).thenReturn("实际报送单位");
        when(facts.planReportingLabel(planId,7,org,district,"live")).thenReturn("计划报送单位");
        var result=service.read(evaluationId);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.items().get(0).actual().name()).isEqualTo("实际飞手");
        assertThat(result.items().get(1).actual().name()).isEqualTo("实际报送单位");
        assertThat(result.toString()).doesNotContain(input.pilotContactId(),input.reportingBindingId(),input.messageId(),input.executionId());
    }
    @Test void missingOrAmbiguousReferenceDoesNotQueryLatestAndPermissionDenialStopsReads(){
        when(evaluation.hitDetails()).thenReturn(List.of(new HitDetailDto("C02-9",null,"FAIL",null,null,Map.of(),List.of(),List.of(),null)));
        assertThat(service.read(evaluationId).items().get(0).actualAvailability()).isEqualTo("UNKNOWN");
        verifyNoInteractions(facts,plans);
        when(access.require(com.uav.lowaltitude.modules.identity.domain.PermissionCode.TARGET_READ)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","denied"));
        assertThatThrownBy(()->service.read(evaluationId)).isInstanceOf(ApiException.class);
        verify(evaluations,times(1)).detail(evaluationId);
    }
    private static String id(){return UUID.randomUUID().toString();}
}
