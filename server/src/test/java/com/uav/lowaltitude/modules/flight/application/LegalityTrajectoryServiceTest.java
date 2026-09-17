package com.uav.lowaltitude.modules.flight.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService;
import com.uav.lowaltitude.modules.assessment.api.LegalityEvaluationDtos.EvaluationDto;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.flight.infrastructure.*;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.modules.target.api.TargetDtos.*;
import com.uav.lowaltitude.platform.api.ApiException;

class LegalityTrajectoryServiceTest {
    final FlightReadRepository plans=mock(FlightReadRepository.class);
    final FlightActualsRepository evaluations=mock(FlightActualsRepository.class);
    final AccessControlService access=mock(AccessControlService.class);
    final TargetReadService targets=mock(TargetReadService.class);
    final SpatialFactPort spatial=mock(SpatialFactPort.class);
    final LegalityEvaluationReadService reads=mock(LegalityEvaluationReadService.class);
    final EvaluationDto evaluation=mock(EvaluationDto.class);
    final FlightTrajectoryService service=new FlightTrajectoryService(plans,evaluations,access,targets,spatial,reads);
    @BeforeEach void setup(){
        when(reads.detail("evaluation-a")).thenReturn(evaluation);
        when(evaluation.evaluationId()).thenReturn("evaluation-a");
        when(evaluation.targetId()).thenReturn("target-a");
        when(evaluation.routeVersionId()).thenReturn("route-historical");
        when(evaluation.asOf()).thenReturn(3000L);
        when(evaluation.evaluatedAt()).thenReturn(4000L);
        when(evaluations.trackGapMillis("evaluation-a")).thenReturn(30000L);
        when(plans.findRouteVersion(eq("route-historical"),any())).thenReturn(mock(FlightReadRepository.RouteVersionRow.class));
        var track=mock(TrackSummaryDto.class);when(track.trackId()).thenReturn("fused-a");
        when(targets.tracks(eq("target-a"),any())).thenReturn(new PageDto<>(List.of(track),1,100,1));
        when(targets.points(eq("fused-a"),any())).thenReturn(new PageDto<>(List.of(point(1,1000),point(2,2000)),1,100,2));
        when(spatial.distanceToRoute(any(),eq("route-historical"))).thenReturn(
                new RouteDistance("route-historical",BigDecimal.ONE,BigDecimal.TEN,null),
                new RouteDistance("route-historical",new BigDecimal("20"),BigDecimal.TEN,null));
    }
    @Test void usesExactEvaluationTargetRouteAndCutoffWithRedGreenSegments(){
        var result=service.readEvaluation("evaluation-a");
        assertThat(result.targetId()).isEqualTo("target-a");
        assertThat(result.points()).extracting(FlightTrajectoryService.Point::corridorRelation).containsExactly("WITHIN","OUTSIDE");
        assertThat(result.points().get(1).breakBefore()).isFalse();
        verify(evaluations,never()).findLatestActiveEvaluation(any(),any());
        verify(targets).points(eq("fused-a"),argThat(q->"3000".equals(q.getFirst("time_to"))&&"0".equals(q.getFirst("time_from"))));
    }
    @Test void missingRouteRemainsUnknown(){
        when(evaluation.routeVersionId()).thenReturn(null);
        assertThat(service.readEvaluation("evaluation-a").points()).allMatch(p->"UNKNOWN".equals(p.corridorRelation()));
        verifyNoInteractions(spatial);
    }
    @Test void missingGapDoesNotJoinPoints(){
        when(evaluations.trackGapMillis("evaluation-a")).thenReturn(null);
        assertThat(service.readEvaluation("evaluation-a").points()).allMatch(FlightTrajectoryService.Point::breakBefore);
    }
    @Test void preservesGapsRatherThanDrawingShortcut(){
        when(targets.points(eq("fused-a"),any())).thenReturn(new PageDto<>(List.of(point(1,1000),point(4,2000)),1,100,2));
        assertThat(service.readEvaluation("evaluation-a").points()).allMatch(FlightTrajectoryService.Point::breakBefore);
    }
    @Test void unavailableRouteDoesNotSilentlyBecomeGreen(){
        when(plans.findRouteVersion(any(),any())).thenReturn(null);
        assertThatThrownBy(()->service.readEvaluation("evaluation-a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(targets);
    }
    @Test void targetPermissionFailureStopsTrackRead(){
        when(access.require(PermissionCode.TARGET_READ)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","禁止"));
        assertThatThrownBy(()->service.readEvaluation("evaluation-a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(targets);
    }
    private TrackPointDto point(long seq,long at){return new TrackPointDto("p"+seq,"fused-a",seq,at,"OBSERVED",at,at,
        new LocationDto(new BigDecimal("118.5"),new BigDecimal("37.5"),"WGS84"),null,null,"MEAS",null,null,false,null);}
}
