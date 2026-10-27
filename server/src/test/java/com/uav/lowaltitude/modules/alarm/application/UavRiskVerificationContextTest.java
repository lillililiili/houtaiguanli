package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.UavEventDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerificationBasisDto;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavVerificationBasisRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.prediction.application.PredictionEventGate;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class UavRiskVerificationContextTest {
    private final UavRiskCaseIdentity identities=mock(UavRiskCaseIdentity.class);
    private final UavEventRepository events=mock(UavEventRepository.class);
    private final UavEventVerificationService rawEvents=mock(UavEventVerificationService.class);
    private final AlarmReadService alarms=mock(AlarmReadService.class);
    private final UavAdvisoryService advisory=mock(UavAdvisoryService.class);
    private final UavVerificationBasisRepository basis=mock(UavVerificationBasisRepository.class);
    private final PredictionEventGate predictions=mock(PredictionEventGate.class);
    private final AccessControlService access=mock(AccessControlService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final AtomicLong time=new AtomicLong(Instant.parse("2026-10-18T02:00:00Z").toEpochMilli());
    private final String risk=id(),event=id(),alarm=id(),area=id(),target=id();
    private final AccessDecision scope=new AccessDecision(id(),ScopeMode.ALL);
    private UavRiskCaseService service;
    private AlarmDto alarmDto;

    @BeforeEach void setup(){
        Clock clock=new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return Instant.ofEpochMilli(time.get());}
        };
        service=new UavRiskCaseService(identities,rawEvents,events,alarms,advisory,basis,predictions,
                access,mock(IdempotencyGuard.class),jdbc,new ObjectMapper(),new AppClock(clock),mock(AuditService.class));
        when(access.require(any())).thenReturn(scope);
        when(identities.withCase(eq(risk),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        when(identities.find(risk)).thenReturn(ref(event,"PREDICTED",4,false));
        when(identities.summary(risk)).thenAnswer(call->summary(event,"PREDICTED",4,"PENDING_VERIFICATION"));
        when(events.find(event,scope)).thenReturn(row(event,alarm,7));
        alarmDto=alarm(alarm,event);
        when(alarms.verificationDetail(eq(alarm),anyMap())).thenReturn(alarmDto);
        when(alarms.currentVerificationPrediction(any())).thenAnswer(call->
                new VerificationBasisDto(true,List.of(),null,time.get()));
    }

    @Test void contextReadsBasisLastWithoutNotificationOrHistoryWork(){
        String snapshot=id();
        alarmDto.withPrediction("PREDICTED_AIRSPACE_ENTRY",Map.of("prediction_id",snapshot,"valid_until",time.get()+3_000),Map.of(),List.of());
        when(alarms.verificationDetail(eq(alarm),anyMap())).thenAnswer(call->{time.addAndGet(750);return alarmDto;});
        var result=service.detail(risk,"verification");
        var projected=(UavEventDto)result.get("event");
        assertThat(projected.verificationBasis().confirmable()).isTrue();
        assertThat(projected.verificationBasis().checkedAt()).isEqualTo(time.get());
        assertThat(projected.allowedActions()).containsExactly("VERIFY");
        assertThat(projected.version()).isEqualTo(7);
        assertThat(((Map<?,?>)result.get("risk_case")).get("prediction_id")).isEqualTo(snapshot);
        assertThat(result).containsOnlyKeys("event","risk_case","alarm");
        verify(alarms,never()).detail(anyString());
        verifyNoInteractions(rawEvents,advisory,jdbc,basis,predictions);
    }

    @Test void currentEvidenceFailureIsReturnedWithoutExtendingPredictionValidity(){
        when(alarms.currentVerificationPrediction(any())).thenAnswer(call->
                new VerificationBasisDto(false,List.of("RISK_EVIDENCE_UNAVAILABLE"),
                        "缺少当前可靠实测和有效预测，暂不能核实预测风险",time.get()));
        var result=service.detail(risk,"verification");
        var projected=(UavEventDto)result.get("event");
        assertThat(projected.verificationBasis().confirmable()).isFalse();
        assertThat(projected.verificationBasis().missing()).containsExactly("RISK_EVIDENCE_UNAVAILABLE");
        verifyNoInteractions(jdbc,advisory);
    }

    @Test void changedActionEventIsRetriedAndCannotCarryTheOldEventVersion(){
        String enteredEvent=id(),enteredAlarm=id();
        var predicted=ref(event,"PREDICTED",4,false);
        var entered=ref(enteredEvent,"ENTERED",5,false);
        var current=new AtomicReference<>(predicted);
        when(identities.find(risk)).thenAnswer(call->current.get());
        when(identities.summary(risk)).thenAnswer(call->{
            var value=current.get();
            return summary(value.actionEventId(),value.stage(),value.version(),"PENDING_VERIFICATION");
        });
        // The handoff occurs during the old alarm's display read, independent of how many
        // identity reads the implementation uses before or after that boundary.
        when(alarms.verificationDetail(eq(alarm),anyMap())).thenAnswer(call->{current.set(entered);return alarmDto;});
        when(events.find(enteredEvent,scope)).thenReturn(row(enteredEvent,enteredAlarm,2));
        when(alarms.verificationDetail(eq(enteredAlarm),anyMap())).thenReturn(alarm(enteredAlarm,enteredEvent));
        when(identities.currentMeasuredArea(entered)).thenReturn(false);
        var result=service.detail(risk,"verification");
        var projected=(UavEventDto)result.get("event");
        assertThat(projected.eventId()).isEqualTo(enteredEvent);
        assertThat(projected.version()).isEqualTo(2);
        assertThat(((Map<?,?>)result.get("risk_case")).get("action_event_id")).isEqualTo(enteredEvent);
        assertThat(((Map<?,?>)result.get("risk_case")).get("version")).isEqualTo(5L);
        assertThat(((Map<?,?>)result.get("risk_case")).get("action_event_version")).isEqualTo(2L);
        assertThat(projected.verificationBasis().confirmable()).isFalse();
        assertThat(projected.verificationBasis().message()).contains("本禁飞区");
        verify(events).find(event,scope);verify(events).find(enteredEvent,scope);
        verify(alarms,never()).currentVerificationPrediction(any());
        verifyNoInteractions(predictions,basis,jdbc,advisory);
    }

    @Test void confirmedRiskDoesNotInheritPendingRawEventActions(){
        when(identities.find(risk)).thenReturn(ref(event,"ENTERED",5,true));
        when(identities.summary(risk)).thenAnswer(call->summary(event,"ENTERED",5,"CONFIRMED"));
        var result=service.detail(risk,"verification");
        var projected=(UavEventDto)result.get("event");
        assertThat(projected.state()).isEqualTo("CONFIRMED");
        assertThat(projected.allowedActions()).isEmpty();
        assertThat(projected.verificationBasis()).isNull();
        assertThat(((Map<?,?>)result.get("risk_case")).get("allowed_actions")).isEqualTo(List.of());
        verifyNoInteractions(predictions,basis,advisory,jdbc);
    }

    @Test void readScopeAndVerifyPermissionRemainIndependent(){
        when(access.require(PermissionCode.ALARM_VERIFY)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","denied"));
        var result=service.detail(risk,"verification");
        assertThat(((UavEventDto)result.get("event")).allowedActions()).isEmpty();
        verifyNoInteractions(predictions,basis);
        when(events.find(event,scope)).thenReturn(null);
        assertThatThrownBy(()->service.detail(risk,"verification")).isInstanceOfSatisfying(ApiException.class,
                ex->assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test void redactedTargetReferenceStaysRedactedInVerificationEvent(){
        var redacted=new AlarmDto(alarm,event,"OPEN","RULE_LEGALITY","HIGH",time.get(),time.get(),"source","live",id(),id(),null,
                "alarm-reference",null,null,null,null,"HIGH",List.of(),0,null,null,null,null);
        when(alarms.verificationDetail(eq(alarm),anyMap())).thenReturn(redacted);
        var result=service.detail(risk,"verification");
        assertThat(((UavEventDto)result.get("event")).targetId()).isNull();
    }

    @Test void defaultDetailsRemainCompatibleAndUnknownPurposeIsRejected(){
        var delegate=spy(service);
        doReturn(Map.of("full",true)).when(delegate).detail(risk);
        assertThat(delegate.detail(risk,null)).containsEntry("full",true);
        assertThatThrownBy(()->service.detail(risk,"unknown")).isInstanceOfSatisfying(ApiException.class,
                ex->assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(identities,events,alarms,jdbc,advisory);
    }

    private UavRiskCaseIdentity.CaseRef ref(String action,String stage,long version,boolean confirmed){
        return new UavRiskCaseIdentity.CaseRef(risk,risk,area,stage,action,List.of(action),confirmed,true,version);
    }
    private Map<String,Object> summary(String action,String stage,long version,String verification){
        var value=new LinkedHashMap<String,Object>();
        value.put("risk_case_id",risk);value.put("version",version);value.put("action_event_id",action);
        value.put("stage",stage);value.put("verification_status",verification);value.put("airspace_id",area);
        return value;
    }
    private UavEventRepository.EventRow row(String eventId,String alarmId,long version){
        var at=Instant.ofEpochMilli(time.get()).atOffset(ZoneOffset.UTC);
        return new UavEventRepository.EventRow(eventId,alarmId,target,"PENDING_VERIFICATION",id(),id(),at,at,version,"replay");
    }
    private AlarmDto alarm(String alarmId,String eventId){
        return new AlarmDto(alarmId,eventId,"OPEN","PREDICTED_AIRSPACE_ENTRY","HIGH",time.get(),time.get(),"source","replay",id(),id(),target,
                "alarm-reference",null,null,null,"target-reference","HIGH",List.of(),0,null,null,null,null);
    }
    private static String id(){return UUID.randomUUID().toString();}
}
