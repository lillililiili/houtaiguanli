package com.uav.lowaltitude.modules.prediction.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.disposal.application.DirectDisposalAccess;
import com.uav.lowaltitude.modules.disposal.application.DisposalCommandGuard;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

class PredictionDispatchGuardTest {
    @Test void queueStartTakesEpisodeBeforeEventAuthorizationAndDeviceAndStoppedAuthorizationNeverRestarts() {
        var now=Instant.parse("2026-10-20T14:00:00Z");
        String event=UUID.randomUUID().toString(),auth=UUID.randomUUID().toString(),device=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();
        var gate=mock(PredictionEventGate.class);var stops=mock(EmergencyStopRepository.class);
        var repo=mock(DisposalRepository.class);var advisory=mock(UavAdvisoryRepository.class);
        var access=mock(DirectDisposalAccess.class);
        var guard=new DisposalCommandGuard(stops,repo,advisory,access,new AppClock(Clock.fixed(now,ZoneOffset.UTC)));
        guard.setPredictionEventGate(gate);
        var at=now.atOffset(ZoneOffset.UTC);
        var row=new AuthorizationRow(auth,"AUTH","COUNTERMEASURE","UAV_EVENT",event,null,device,"COUNTERMEASURE_4CH","",
                actor,at,null,null,null,at,at.plusSeconds(60),"APPROVED",null,null,null,"policy","org","district","live",1,null,null,"DIRECT",null);
        when(repo.findUnlocked(auth)).thenReturn(row);when(stops.lockAuthorizationStatus(auth)).thenReturn("APPROVED");
        when(advisory.counterBlockReason(event)).thenReturn("");
        when(access.eligibleRequester(row,true)).thenReturn(mock(AuthUser.class));
        assertThat(guard.mayStart(auth,actor,device)).isTrue();
        var order=inOrder(gate,stops,advisory,access);
        order.verify(gate).lockEpisode(event);
        order.verify(stops).lockEvent(event);
        order.verify(advisory).counterBlockReason(event);
        order.verify(stops).lockAuthorizationStatus(auth);
        order.verify(stops).lockDevice(device);
        order.verify(access).eligibleRequester(row,true);
        when(stops.lockAuthorizationStatus(auth)).thenReturn("STOPPED");
        assertThat(guard.mayStart(auth,actor,device)).isFalse();
        verify(access,times(1)).eligibleRequester(row,true);
    }
}
