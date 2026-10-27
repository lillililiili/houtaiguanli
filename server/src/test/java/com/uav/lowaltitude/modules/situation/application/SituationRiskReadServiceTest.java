package com.uav.lowaltitude.modules.situation.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.*;
import com.uav.lowaltitude.modules.risk.application.RiskPresenceService;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

class SituationRiskReadServiceTest {
    private final AccessControlService access=mock(AccessControlService.class);
    private final AlarmReadRepository alarms=mock(AlarmReadRepository.class);
    private final RiskRepository risks=mock(RiskRepository.class);
    private final RiskPresenceService presence=mock(RiskPresenceService.class);
    private final AccessDecision scope=new AccessDecision(UUID.randomUUID().toString(),ScopeMode.ASSIGNED);
    private final long now=Instant.now().toEpochMilli();
    private final SituationRiskReadService service=new SituationRiskReadService(access,alarms,risks,presence,
            new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),new ObjectMapper());
    @BeforeEach void setUp(){
        when(access.require(any())).thenReturn(scope);
        when(alarms.situationRows(scope)).thenReturn(List.of());
        when(risks.situationCandidates(any(),eq(scope),eq(now))).thenReturn(List.of());
    }
    @Test void deduplicatesCasesBeforePagingAndKeepsEachAreaAndEachPlanRisk(){
        String first=id(),second=id();
        when(alarms.situationRows(scope)).thenReturn(List.of(alarm(first,"PREDICTED","HIGH",now-10,false),
                alarm(first,"ENTERED","HIGH",now,false),alarm(second,"ENTERED","CRITICAL",now-20,false)));
        var bird=risk("SPACE_OBJECT","MEDIUM",now-3);
        var weather=risk("WEATHER","LOW",now-1);
        when(risks.situationCandidates(any(),eq(scope),eq(now))).thenReturn(List.of(candidate(bird),candidate(weather)));
        when(presence.read(any(),eq(now))).thenReturn(new RiskPresenceService.Presence("CURRENT","当前有效",now));
        var query=new LinkedMultiValueMap<String,String>();query.add("size","2");
        var result=service.list(query);
        assertThat(result.total()).isEqualTo(4);assertThat(result.counts().all()).isEqualTo(4);
        assertThat(result.counts().uav()).isEqualTo(2);assertThat(result.counts().spaceObject()).isEqualTo(1);
        assertThat(result.items()).extracting(i->i.key()).containsExactly("risk:"+second,"risk:"+first);
        query.set("kind","WEATHER");
        result=service.list(query);
        assertThat(result.total()).isEqualTo(1);assertThat(result.counts().all()).isEqualTo(4);
        assertThat(result.items().get(0).key()).isEqualTo("weather:"+weather.riskId());
        verify(risks,times(1)).simulatedObservation(weather);
        verify(risks,never()).simulatedObservation(bird);
    }
    @Test void unknownSpaceRiskExitsActiveCountsButKeepsProcessingAndFocusedUnknown(){
        var bird=risk("SPACE_OBJECT","HIGH",now);
        var weather=risk("WEATHER","CRITICAL",now);
        when(risks.situationCandidates(any(),eq(scope),eq(now))).thenReturn(List.of(candidate(bird),candidate(weather)));
        when(presence.read(bird,now)).thenReturn(new RiskPresenceService.Presence("UNKNOWN","缺少新位置",null));
        when(presence.read(weather,now)).thenReturn(new RiskPresenceService.Presence("EXPIRED","时段已过期",null));
        var result=service.list(new LinkedMultiValueMap<>());
        assertThat(result.total()).isZero();assertThat(result.counts().all()).isZero();assertThat(result.counts().spaceObject()).isZero();
        var query=new LinkedMultiValueMap<String,String>();query.add("view","PROCESSING");
        result=service.list(query);assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).anyMatch(i->weather.riskId().equals(i.riskId()));assertThat(result.counts().weather()).isZero();
        assertThat(result.items()).anyMatch(i->"UNKNOWN".equals(i.currentStatus())&&i.presenceReason().contains("当前影响待确认")&&i.position()==null);
        when(risks.find(bird.riskId(),scope)).thenReturn(bird);when(risks.currentUnit(bird)).thenReturn(true);
        assertThat(service.detail("space:"+bird.riskId()).currentStatus()).isEqualTo("UNKNOWN");
    }
    @Test void unknownUavAndEndedDeviceWorkStayOutsideActiveCountsButReadableForStopping(){
        when(alarms.situationRows(scope)).thenReturn(List.of(alarm(id(),"UNKNOWN","HIGH",now,false),alarm(id(),"ENDED","HIGH",now,true)));
        assertThat(service.list(new LinkedMultiValueMap<>()).total()).isZero();
        var query=new LinkedMultiValueMap<String,String>();query.add("view","PROCESSING");
        var result=service.list(query);assertThat(result.total()).isEqualTo(2);assertThat(result.counts().uav()).isZero();
    }
    @Test void deniedCategoryIsUnavailableWhileReadFailurePropagates(){
        when(access.require(PermissionCode.RISK_READ)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","denied"));
        var result=service.list(new LinkedMultiValueMap<>());
        assertThat(result.counts().weather()).isNull();assertThat(result.counts().all()).isNull();
        assertThat(result.availability().weather()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(risks,presence);
        when(alarms.situationRows(scope)).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(()->service.list(new LinkedMultiValueMap<>())).isInstanceOf(IllegalStateException.class);
    }
    @Test void validatesRepeatedUnknownAndOversizedParametersAndStableTieOrder(){
        when(alarms.situationRows(scope)).thenReturn(List.of(alarm("z","ENTERED","HIGH",now,false),alarm("a","ENTERED","HIGH",now,false)));
        assertThat(service.list(new LinkedMultiValueMap<>()).items()).extracting(i->i.key()).containsExactly("risk:a","risk:z");
        for(String key:List.of("size","page","kind","unexpected")){
            var query=new LinkedMultiValueMap<String,String>();query.add(key,"101");
            if("page".equals(key))query.add(key,"1");
            assertThatThrownBy(()->service.list(query)).isInstanceOf(ApiException.class);
        }
    }
    @Test void missingRelatedPermissionsRedactTargetAndRouteWithoutRemovingReadableRisk(){
        when(access.require(PermissionCode.TARGET_READ)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","denied"));
        when(access.require(PermissionCode.ROUTE_READ)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","denied"));
        var bird=risk("SPACE_OBJECT","HIGH",now);
        when(risks.situationCandidates(any(),eq(scope),eq(now))).thenReturn(List.of(candidate(bird)));
        when(presence.read(bird,now)).thenReturn(new RiskPresenceService.Presence("CURRENT","current",now));
        var result=service.list(new LinkedMultiValueMap<>());
        assertThat(result.total()).isEqualTo(1);assertThat(result.items().get(0).targetId()).isNull();
        assertThat(result.items().get(0).targetNo()).isNull();assertThat(result.items().get(0).routeVersionId()).isNull();
        verify(alarms,never()).targetVisible(any(),any(),any(),any());
        verify(risks,never()).routeReferenceVisible(any());
    }
    @Test void processingKeepsCurrentPendingAndEndedRiskAwaitingRealNoticeReceiptWithoutPresenceExpansion(){
        var bird=risk("SPACE_OBJECT","HIGH",now);
        var weather=risk("WEATHER","LOW",now,"NOTIFIED");
        when(risks.situationCandidates(any(),eq(scope),eq(now))).thenReturn(List.of(candidate(bird),new RiskRepository.SituationCandidate(weather,true,false)));
        when(presence.read(bird,now)).thenReturn(new RiskPresenceService.Presence("CURRENT","current",now));
        var query=new LinkedMultiValueMap<String,String>();query.add("view","PROCESSING");
        var result=service.list(query);
        assertThat(result.total()).isEqualTo(2);assertThat(result.counts().weather()).isZero();
        assertThat(result.items()).anyMatch(i->"NOTIFIED".equals(i.state())&&"NOT_CURRENT".equals(i.currentStatus()));
        verify(presence,never()).read(weather,now);
    }
    private static RiskRepository.SituationCandidate candidate(RiskRepository.RiskRow row){return new RiskRepository.SituationCandidate(row,true,true);}
    @Test void byKeyReadsExactUavAndRetiresEndedOrNoncurrentUnitWithoutScanningLists(){
        String caseId=id();var current=alarm(caseId,"ENTERED","HIGH",now,false);
        when(alarms.situationItem(scope,"risk",caseId)).thenReturn(current);
        assertThat(service.detail("risk:"+caseId).currentStatus()).isEqualTo("CURRENT");
        var ended=alarm(caseId,"CLEARED","HIGH",now,false);
        when(alarms.situationItem(scope,"risk",caseId)).thenReturn(ended);
        assertThat(service.detail("risk:"+caseId).currentStatus()).isEqualTo("NOT_CURRENT");
        when(alarms.situationItem(scope,"risk",caseId)).thenReturn(new AlarmReadRepository.SituationRow(current.alarm(),1L,2L,
                current.updatedAt(),current.areaId(),current.areaName(),now,"ENTERED",false,false,false));
        assertThat(service.detail("risk:"+caseId).currentStatus()).isEqualTo("NOT_CURRENT");
        verify(alarms,never()).situationRows(any());verifyNoInteractions(presence,risks);
    }
    @Test void byKeySpaceAndWeatherUseFreshPresenceAndDoNotFallbackAcrossKindsOrPages(){
        var bird=risk("SPACE_OBJECT","HIGH",now);var weather=risk("WEATHER","HIGH",now);
        when(risks.find(bird.riskId(),scope)).thenReturn(bird);when(risks.find(weather.riskId(),scope)).thenReturn(weather);
        when(risks.currentUnit(any())).thenReturn(true);
        when(presence.read(bird,now)).thenReturn(new RiskPresenceService.Presence("UNKNOWN","缺新观测",null));
        when(presence.read(weather,now)).thenReturn(new RiskPresenceService.Presence("EXPIRED","时段已结束",now));
        assertThat(service.detail("space:"+bird.riskId()).currentStatus()).isEqualTo("UNKNOWN");
        assertThat(service.detail("weather:"+weather.riskId()).currentStatus()).isEqualTo("NOT_CURRENT");
        when(risks.currentUnit(bird)).thenReturn(false);
        assertThat(service.detail("space:"+bird.riskId()).currentStatus()).isEqualTo("NOT_CURRENT");
        assertThatThrownBy(()->service.detail("weather:"+bird.riskId())).isInstanceOf(ApiException.class);
        verify(risks,never()).situationCandidates(any(),any(),anyLong());
    }
    @Test void byKeyDeniedCategoryIsForbiddenAndMissingScopedObjectIsNotFound(){
        String id=id();
        assertThatThrownBy(()->service.detail("risk:"+id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        when(access.require(PermissionCode.ALARM_READ)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","denied"));
        assertThatThrownBy(()->service.detail("event:"+id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->service.detail("risk:../../escape")).isInstanceOf(ApiException.class);
    }
    private AlarmReadRepository.SituationRow alarm(String caseId,String stage,String severity,long at,boolean work){
        var row=new AlarmReadRepository.AlarmRow(id(),id(),id(),"PENDING_VERIFICATION","RULE_LEGALITY",severity,time(at),time(at),
                "source","live","org","district","source-alarm","source","org","district","target-no","alarm-no",severity,
                "{\"violation_reasons\":[\"NO_AUTHORIZATION\"]}",null,0,null,"CURRENT","CURRENT",caseId);
        return new AlarmReadRepository.SituationRow(row,1L,2L,time(at),id(),"area",at,stage,work,true,false);
    }
    private RiskRepository.RiskRow risk(String kind,String severity,long at){
        return risk(kind,severity,at,"PENDING_VERIFICATION");
    }
    private RiskRepository.RiskRow risk(String kind,String severity,long at,String state){
        return new RiskRepository.RiskRow(id(),id(),id(),id(),id(),id(),id(),kind,severity,state,"reason","风险原因",
                time(at),time(at),null,null,null,"source","live","org","district",time(at),time(at),1L,"source","org","district","plan-no","target-no","risk-no");
    }
    private static String id(){return UUID.randomUUID().toString();}
    private static OffsetDateTime time(long ms){return Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC);}
}
