package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.uav.lowaltitude.modules.alarm.api.UavAdvisoryDtos.Overview;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.api.ApiException;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UavAdvisoryReadConsistencyTest {
    private final UavRiskCaseIdentity identities=mock(UavRiskCaseIdentity.class);
    private final UavEventRepository events=mock(UavEventRepository.class);
    private final AccessControlService access=mock(AccessControlService.class);
    private final AccessDecision scope=new AccessDecision(id(),ScopeMode.ALL);
    private final String risk=id(),area=id(),event=id(),alarm=id(),target=id();
    private UavAdvisoryService service;
    private UavEventRepository.EventRow row;

    @BeforeEach void setup(){
        service=spy(new UavAdvisoryService(events,null,access,null,null,null,null,null,null,null,null,null,null,null));
        service.responseOrder(null,identities);
        when(access.require(any())).thenReturn(scope);
        row=row(event,0);
        when(events.find(event,scope)).thenReturn(row);
    }

    @Test void concurrentConfirmationCannotAttachNewCaseVersionToOldEligibility(){
        var before=ref(event,2,false,"ENTERED");var after=ref(event,3,true,"ENTERED");
        when(identities.cases(event)).thenReturn(List.of(before),List.of(after),List.of(after),List.of(after));
        var mixed=view(row,after,false);var coherent=view(row,after,true);
        doReturn(mixed,coherent).when(service).view(row);
        var result=service.overview(event);
        assertThat(result).isSameAs(coherent);
        assertThat(result.canRequestCounter()).isTrue();
        verify(service,times(2)).view(row);
    }

    @Test void predictionHandoffReadsNewEventAndVersionUnderTheSameScope(){
        String measured=id();var measuredRow=row(measured,7);
        var predicted=ref(event,1,false,"PREDICTED");var entered=ref(measured,2,false,"ENTERED");
        when(identities.cases(event)).thenReturn(List.of(predicted),List.of(entered));
        when(identities.cases(measured)).thenReturn(List.of(entered));
        when(events.find(measured,scope)).thenReturn(measuredRow);
        doReturn(view(row,entered,false)).when(service).view(row);
        var coherent=view(measuredRow,entered,false);
        doReturn(coherent).when(service).view(measuredRow);
        assertThat(service.overview(event)).isSameAs(coherent);
        assertThat(coherent.eventId()).isEqualTo(measured);
        assertThat(coherent.eventVersion()).isEqualTo(7);
        verify(events,atLeastOnce()).find(measured,scope);
    }

    @Test void stageCanChangeWithoutVersionAndStillRequiresAConsistentRead(){
        var entered=ref(event,3,true,"ENTERED");var unknown=ref(event,3,true,"UNKNOWN");
        when(identities.cases(event)).thenReturn(List.of(entered),List.of(unknown),List.of(unknown),List.of(unknown));
        doReturn(view(row,unknown,true),view(row,unknown,false)).when(service).view(row);
        assertThat(service.overview(event).canRequestCounter()).isFalse();
        verify(service,times(2)).view(row);
    }

    @Test void continuingChangesReturnReadConflictInsteadOfMixedPermissions(){
        when(identities.cases(event)).thenReturn(List.of(ref(event,1,false,"PREDICTED")),List.of(ref(event,2,false,"ENTERED")),
                List.of(ref(event,2,false,"ENTERED")),List.of(ref(event,3,true,"ENTERED")));
        doReturn(view(row,ref(event,2,false,"ENTERED"),false),view(row,ref(event,3,true,"ENTERED"),false)).when(service).view(row);
        assertThatThrownBy(()->service.overview(event)).isInstanceOfSatisfying(ApiException.class,
                ex->assertThat(ex.getCode()).isEqualTo("RISK_CASE_CHANGED"));
        verify(service,times(2)).view(row);
    }

    @Test void ExistingEventsWithoutRiskCasesRemainReadable(){
        when(identities.cases(event)).thenReturn(List.of());
        var original=new Overview(event,0,"SIMULATED",false,false,false,false,"",List.of(),null,null,null,null,false,null,null,null,false,null);
        doReturn(original).when(service).view(row);
        assertThat(service.overview(event)).isSameAs(original);
    }

    private UavRiskCaseIdentity.CaseRef ref(String action,long version,boolean confirmed,String stage){
        return new UavRiskCaseIdentity.CaseRef(risk,risk,area,stage,action,List.of(action),confirmed,true,version);
    }
    private Overview view(UavEventRepository.EventRow row,UavRiskCaseIdentity.CaseRef risk,boolean eligible){
        var summary=Map.<String,Object>of("risk_case_id",risk.riskCaseId(),"version",risk.version(),"action_event_id",risk.actionEventId(),
                "stage",risk.stage(),"verification_status",risk.confirmed()?"CONFIRMED":"PENDING_VERIFICATION");
        return new Overview(row.eventId(),row.version(),"SIMULATED",false,eligible,eligible,false,"",List.of(),null,null,null,null,
                eligible,null,null,null,false,null,null,summary);
    }
    private UavEventRepository.EventRow row(String event,long version){
        var now=OffsetDateTime.now();return new UavEventRepository.EventRow(event,alarm,target,"PENDING_VERIFICATION",id(),id(),now,now,version,"replay");
    }
    private static String id(){return UUID.randomUUID().toString();}
}
