package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.*;
import com.uav.lowaltitude.modules.fusion.infrastructure.*;
import com.uav.lowaltitude.modules.fusion.ingest.*;

@ExtendWith(MockitoExtension.class)
class FusionFutureObservationTest {
    @Mock com.uav.lowaltitude.platform.config.SimulationPolicy simulation;
    @Mock ObservationRepository observations;
    @Mock RawTrackRepository rawTracks;
    @Mock IdentityRepository identities;
    @Mock AssociationPendingRepository pendings;
    @Mock FusionConfigLoader configLoader;
    @Mock ObjectProvider<FusedLayerWriter> writer;
    @Mock InboxSourceRouter router;
    @Mock LineageRepository lineages;
    @Mock FusionEventEmitter events;
    @Spy ObjectMapper json = new ObjectMapper();
    @Spy FusionProperties properties = new FusionProperties();
    @InjectMocks FusionPipeline pipeline;

    @Test void futureFrameIsRejectedBeforeCreatingAnyTargetOrTrack() {
        Instant received = Instant.parse("2026-09-28T00:00:00Z");
        var inbox = new FusionInboxRepository.InboxRow("inbox", "lingyun:test", "1", "source", received.toEpochMilli(), "{}");
        when(configLoader.active()).thenReturn(mock(FusionParams.class));
        when(observations.findSource("source")).thenReturn(new ObservationRepository.SourceMeta("source", "test", "RADAR", "CONFIRMED", "replay", true));
        var item = new FrameMapper.Item("external", "track", null, null, null, null, null, null, null, "UAV", 1d, null, null);
        when(router.map(inbox)).thenReturn(new FrameMapper.Frame("session", 1, "source", received.plusSeconds(86400), List.of(item)));
        assertThatThrownBy(() -> pipeline.processFrame(inbox)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OBSERVATION_TIME_IN_FUTURE");
        verify(observations, never()).insert(any());
        verifyNoInteractions(rawTracks, identities, pendings, writer, lineages, events);
    }
}
