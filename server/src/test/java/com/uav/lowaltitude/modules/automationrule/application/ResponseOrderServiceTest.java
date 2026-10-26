package com.uav.lowaltitude.modules.automationrule.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import com.uav.lowaltitude.modules.automationrule.api.ResponseOrderDtos.*;
import com.uav.lowaltitude.modules.automationrule.infrastructure.ResponseOrderRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.ResponseOrderRepository.*;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity;
import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity.CaseRef;
import com.uav.lowaltitude.modules.alarm.infrastructure.*;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.platform.time.AppClock;

class ResponseOrderServiceTest {
    final ResponseOrderRepository repo=mock(ResponseOrderRepository.class);
    final UavRiskCaseIdentity cases=mock(UavRiskCaseIdentity.class);
    final UavEventRepository events=mock(UavEventRepository.class);
    final UavAdvisoryRepository evidence=mock(UavAdvisoryRepository.class);
    final AppClock clock=mock(AppClock.class);
    final AtomicLong now=new AtomicLong(Instant.parse("2027-02-10T04:00:00Z").toEpochMilli());
    final String eventId=UUID.randomUUID().toString(),caseId=UUID.randomUUID().toString(),airspace=UUID.randomUUID().toString();
    final Map<String,Window> windows=new HashMap<>();
    ResponseOrderService service;
    EventRow event;
    @BeforeEach void setup(){
        event=new EventRow(eventId,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"PENDING_VERIFICATION","org-other","area-other",OffsetDateTime.now(),OffsetDateTime.now(),1,"replay");
        when(events.find(eq(eventId),any())).thenReturn(event);when(clock.nowMillis()).thenAnswer(x->now.get());
        when(repo.policy(false)).thenReturn(policy(true,60));when(repo.severity(eventId)).thenReturn("HIGH");
        when(cases.hasCases(eventId)).thenReturn(true);when(cases.confirmedForDisposal(eventId)).thenReturn(true);
        when(cases.cases(eventId)).thenReturn(List.of(ref("PREDICTED",eventId,true)));
        when(evidence.counterBlockReason(anyString())).thenReturn("");
        when(repo.window(anyString())).thenAnswer(call->windows.get(call.getArgument(0)));
        doAnswer(call->{String key=call.getArgument(0);Policy p=call.getArgument(2);long at=call.getArgument(3);
            windows.putIfAbsent(key,new Window(key,p.version(),at,at+p.maxWaitSeconds()*1000L,p.maxWaitSeconds()));return null;}).when(repo).start(anyString(),anyString(),any(),anyLong());
        when(repo.startEvidence(anyString(),anyList(),anyBoolean(),anyLong(),anyLong())).thenReturn(new StartEvidence(false,false));
        service=new ResponseOrderService(repo,cases,events,evidence,clock,mock(PlatformTransactionManager.class));
    }
    @Test void defaultOffLeavesExistingOrderAndReadingNeverStartsDeadline(){
        when(repo.policy(false)).thenReturn(policy(false,60));
        assertThat(service.summary(eventId).mode()).isEqualTo("NOTIFY_FIRST");
        service.ensureLocked(event);assertThat(windows).isEmpty();
        when(repo.policy(false)).thenReturn(policy(true,60));
        assertThat(service.summary(eventId).counterPriority()).isTrue();assertThat(windows).isEmpty();
        assertThat(service.summary(eventId).phoneReleased()).isFalse();
        verify(repo,never()).start(anyString(),anyString(),any(),anyLong());
    }
    @ParameterizedTest @ValueSource(longs={0,57_000})
    void deadlineSurvivesStagePolicyAndServiceRestartWithoutSms(long shift){
        now.addAndGet(shift);var first=service.ensureLocked(event);long deadline=first.priorityDeadlineAt();
        assertThat(first.counterPriority()).isTrue();assertThat(first.voiceAllowed()).isFalse();
        String observed=UUID.randomUUID().toString();when(cases.cases(eventId)).thenReturn(List.of(ref("ENTERED",observed,true)));
        when(repo.policy(false)).thenReturn(policy(true,300));now.addAndGet(59_000);
        service=new ResponseOrderService(repo,cases,events,evidence,clock,mock(PlatformTransactionManager.class));
        var before=service.ensureLocked(event);assertThat(before.priorityDeadlineAt()).isEqualTo(deadline);assertThat(before.voiceAllowed()).isFalse();
        now.addAndGet(1000);var expired=service.summary(eventId);
        assertThat(expired.status()).isEqualTo("WAIT_TIMEOUT");assertThat(expired.voiceAllowed()).isTrue();assertThat(expired.counterPriority()).isTrue();
        assertThat(windows).hasSize(1);
    }
    @Test void receiptsOrDefiniteNoDeviceReleasePhoneButUnknownDoesNot(){
        service.ensureLocked(event);
        assertThat(service.summary(eventId).status()).isEqualTo("WAITING_START");
        when(repo.startEvidence(anyString(),anyList(),anyBoolean(),anyLong(),anyLong())).thenReturn(new StartEvidence(true,false));
        assertThat(service.summary(eventId).status()).isEqualTo("START_CONFIRMED");
        when(repo.startEvidence(anyString(),anyList(),anyBoolean(),anyLong(),anyLong())).thenReturn(new StartEvidence(false,true));
        assertThat(service.summary(eventId).status()).isEqualTo("START_FAILED");
        when(repo.startEvidence(anyString(),anyList(),anyBoolean(),anyLong(),anyLong())).thenReturn(new StartEvidence(false,false));
        when(repo.noCounterDevice("org-other","area-other","replay")).thenReturn(true);
        assertThat(service.summary(eventId).status()).isEqualTo("START_UNAVAILABLE");
    }
    @Test void unknownOrUnverifiedOtherRegionCannotBorrowConfirmedRisk(){
        service.ensureLocked(event);now.addAndGet(70_000);
        when(cases.cases(eventId)).thenReturn(List.of(ref("UNKNOWN",eventId,true)));
        assertThat(service.summary(eventId).voiceAllowed()).isFalse();assertThat(service.summary(eventId).counterPriority()).isFalse();
        when(cases.cases(eventId)).thenReturn(List.of(ref("PREDICTED",eventId,true),new CaseRef(UUID.randomUUID().toString(),null,UUID.randomUUID().toString(),"ENTERED",eventId,List.of(eventId),false,false,1)));
        when(cases.confirmedForDisposal(eventId)).thenReturn(false);
        assertThat(service.ensureLocked(event).status()).isEqualTo("PAUSED");assertThat(windows).hasSize(1);
    }
    @Test void riskAndOvernightWindowHaveExplicitBoundaries(){
        assertThat(ResponseOrderService.riskMatches("HIGH","MEDIUM")).isFalse();
        assertThat(ResponseOrderService.riskMatches("MEDIUM","LOW")).isFalse();
        assertThat(ResponseOrderService.riskMatches("MEDIUM","MEDIUM")).isTrue();
        var p=new Policy(2,true,"HIGH",true,true,"ALL",List.of(),List.of(),"DAILY","20:00","06:00","Asia/Shanghai",60,false);
        assertThat(ResponseOrderService.timeMatches(p,Instant.parse("2027-01-03T12:00:00Z").toEpochMilli())).isTrue();
        assertThat(ResponseOrderService.timeMatches(p,Instant.parse("2027-01-03T21:59:59Z").toEpochMilli())).isTrue();
        assertThat(ResponseOrderService.timeMatches(p,Instant.parse("2027-01-03T22:00:00Z").toEpochMilli())).isFalse();
    }
    Policy policy(boolean enabled,int wait){return new Policy(2,enabled,"HIGH",true,true,"ALL",List.of(),List.of(),"ALL_DAY","00:00","00:00","Asia/Shanghai",wait,false);}
    CaseRef ref(String stage,String action,boolean confirmed){return new CaseRef(caseId,UUID.randomUUID().toString(),airspace,stage,action,List.of(eventId,action).stream().distinct().toList(),confirmed,true,1);}
}
