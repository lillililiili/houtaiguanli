package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.device.infrastructure.EoTrackingRepository;
import com.uav.lowaltitude.platform.time.AppClock;

class EoTrackingPolicyTest {
    private final EoTrackingRepository repository=mock(EoTrackingRepository.class);
    private final long now=Instant.parse("2026-09-28T12:00:00Z").toEpochMilli();
    private final com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch departure=mock(com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.class);
    private final com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository edges=mock(com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository.class);
    private final EoTrackingPolicy policy=new EoTrackingPolicy(repository,new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),departure,edges,true,true,15000,30000,300000);
    @org.junit.jupiter.api.BeforeEach void noHistoricalUncertainty() {when(edges.uncertainTaskByTarget(anyString())).thenReturn(null);}
    @Test void notificationStateDoesNotRemoveConfirmedAlarmDemand() {
        when(repository.alarms(anyString(),anyLong(),anyLong())).thenReturn(List.of(Map.of("state_code","CONFIRMED")));
        assertThat(policy.demand("target")).extracting(EoTrackingPolicy.DemandReason::code).containsExactly("ALARM_OBSERVE");
    }
    @Test void simulatedAutoTrackingNeverEnablesRealDevices() {
        var scoped=new EoTrackingPolicy(repository,new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),
                departure,edges,true,true,15000,30000,300000);
        org.springframework.test.util.ReflectionTestUtils.setField(scoped,"autoSourceModes","replay");
        assertThat(scoped.enabledFor(Map.of("source_mode","replay"))).isTrue();
        assertThat(scoped.enabledFor(Map.of("source_mode","mock"))).isTrue();
        assertThat(scoped.enabledFor(Map.of("source_mode","live"))).isFalse();
        assertThat(scoped.enabledFor(Map.of("source_mode","unknown"))).isFalse();
    }
    @Test void latestLegalAndUndeterminedDoNotTriggerButCurrentIllegalDoes() {
        for(String status:List.of("LEGAL","UNDETERMINED","NOT_APPLICABLE","ILLEGAL","ABNORMAL")) {
            when(repository.evaluation("target")).thenReturn(Map.of("legal_status",status,"observed_at",new Timestamp(now),"evaluated_at",new Timestamp(now)));
            assertThat(policy.demand("target").isEmpty()).isEqualTo(!Set.of("ILLEGAL","ABNORMAL").contains(status));
        }
    }
    @Test void staleOrFutureEvaluationDoesNotTrigger() {
        for(long at:new long[]{now-300001,now+1}) {
            when(repository.evaluation("target")).thenReturn(Map.of("legal_status","ILLEGAL","observed_at",new Timestamp(at),"evaluated_at",new Timestamp(now)));
            assertThat(policy.demand("target")).isEmpty();
        }
    }
    @Test void riskReasonsUseActualCodesAndExcludeWeatherMissingDataAndLowNearRoute() {
        assertThat(EoTrackingPolicy.visualRisk(risk("FLIGHT_OPERATION","ROUTE_DEVIATION","LOW"))).isTrue();
        assertThat(EoTrackingPolicy.visualRisk(risk("SPACE_OBJECT","SPACE_OBJECT_IN_CORRIDOR","MEDIUM"))).isTrue();
        assertThat(EoTrackingPolicy.visualRisk(risk("SPACE_OBJECT","SPACE_OBJECT_NEAR_ROUTE","LOW"))).isFalse();
        assertThat(EoTrackingPolicy.visualRisk(risk("WEATHER","WIND","CRITICAL"))).isFalse();
        assertThat(EoTrackingPolicy.visualRisk(risk("FLIGHT_OPERATION","ALTITUDE_DATUM_UNKNOWN","HIGH"))).isFalse();
        assertThat(EoTrackingPolicy.visualRisk(risk("FLIGHT_OPERATION","PLAN_MISSING","CRITICAL"))).isFalse();
    }
    @Test void positionAgeAndTrackLossAndClassAreHardGuards() {
        var facts=new HashMap<String,Object>(Map.of("position","POINT (118 37)","srid",4326,"object_type_code","UAV","source_mode","replay","owner_org_id","o","district_id","d","observed_at",new Timestamp(now)));
        assertThat(policy.block(facts)).isNull();
        facts.put("observed_at",new Timestamp(now+1));assertThat(policy.block(facts)).isEqualTo("TARGET_POSITION_STALE");
        facts.put("observed_at",new Timestamp(now));facts.put("track_status","SHORT_LOST");assertThat(policy.block(facts)).isEqualTo("TARGET_POSITION_STALE");
        facts.remove("track_status");facts.put("object_type_code","UNKNOWN");assertThat(policy.block(facts)).isEqualTo("EO_CLASS_UNSUPPORTED");
        facts.put("srid",3857);assertThat(policy.block(facts)).isEqualTo("TARGET_POSITION_UNAVAILABLE");
    }
    private static Map<String,Object> risk(String type,String reason,String severity) {return Map.of("risk_type",type,"reason_code",reason,"severity",severity);}
}
