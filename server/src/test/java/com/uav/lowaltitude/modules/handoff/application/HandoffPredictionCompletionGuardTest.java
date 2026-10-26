package com.uav.lowaltitude.modules.handoff.application;

import static org.mockito.Mockito.*;

import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy;
import com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffFollowupRepository;
import com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HandoffPredictionCompletionGuardTest {
    private final HandoffRepository repository=mock(HandoffRepository.class);
    private final HandoffFollowupRepository legacy=mock(HandoffFollowupRepository.class);
    private final DisposalCompletionPort disposals=mock(DisposalCompletionPort.class);
    private final UavEventRepository events=mock(UavEventRepository.class);
    private final AutomationRuntimePolicy policy=mock(AutomationRuntimePolicy.class);
    // The guard must return before material assembly, delivery, or any state mutation.
    private final HandoffSubmissionService service=new HandoffSubmissionService(null,repository,null,null,
            null,null,null,null,disposals,events,null,null,null,null,policy,null,null,null,null,legacy);

    @Test void excludedPredictionCompletionCreatesNoAutomaticPunishment() {
        String event=UUID.randomUUID().toString();
        when(disposals.punishmentCompletion(event)).thenReturn(null);
        service.automaticAfterDisposal(event);
        verify(disposals).punishmentCompletion(event);
        verify(repository,never()).enabledRecipients(anyString());
        verifyNoInteractions(events,policy);
    }

    @Test void waitingPunishmentRechecksOriginBeforeTouchingTheFrozenDelivery() {
        String event=UUID.randomUUID().toString(),handoff=UUID.randomUUID().toString();
        when(repository.existingPunishment(event)).thenReturn(handoff);
        when(disposals.punishmentCompletion(event)).thenReturn(
                new DisposalCompletionPort.PunishmentCompletion(UUID.randomUUID().toString(),"COUNTERMEASURE_COMPLETED"),null);
        service.automaticAfterDisposal(event);
        verify(disposals,times(2)).punishmentCompletion(event);
        verify(repository,never()).lockNotification(anyString(),any());
        verifyNoInteractions(events);
    }
}
