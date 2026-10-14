package com.uav.lowaltitude.modules.target.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.*;
import com.uav.lowaltitude.modules.fusion.application.*;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.TrackRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.SourceLinkRow;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.time.AppClock;

class NormalizedSourceContributionTest {
    @Test void replayContributionUsesTheSameSimulationParametersAsIngestion() throws Exception {
        var now = Instant.parse("2026-10-13T09:00:00Z");
        var json = new ObjectMapper().findAndRegisterModules();
        var tracks = mock(RawTrackRepository.class);
        var config = mock(FusionConfigService.class);
        var params = mock(FusionParams.class);
        when(config.params(null)).thenReturn(params);
        when(params.integer("identity", "short_lost_after_ms")).thenReturn(5000);
        when(params.weights()).thenReturn(Map.of("RADAR", Map.of("position", 1d)));
        when(params.accuracyDefaults()).thenReturn(Map.of("RADAR", 10d));
        var policy = mock(SimulationPolicy.class);
        when(policy.allowed()).thenReturn(true);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap("SELECT * FROM simulator_fusion_parameters WHERE source_type='SIM_NORMALIZED'"))
                .thenReturn(Map.of("accuracy_m", 10d, "position_weight", 1d, "motion_weight", 1d,
                        "class_weight", 1d, "identity_weight", 1d));
        var loader = new FusionConfigLoader(jdbc, policy);
        for (String suffix : List.of("alpha", "beta")) {
            var estimate = new SourceEstimate("source-" + suffix, "SIM-" + suffix, "SIM_NORMALIZED", "CONFIRMED",
                    "link-" + suffix, "track-" + suffix, "obs-" + suffix, now, 118d, 37d, 10d,
                    220d, 200d, null, null, "UAV", .98d, "uav-" + suffix, .98d, PointKind.MEAS, Map.of());
            var link = new SourceLinkRow("link-" + suffix, "source-" + suffix, "SIM-" + suffix, "replay",
                    "batch-" + suffix, "external-" + suffix, "device-" + suffix, null, "Simulation", "SIM_NORMALIZED", "CONFIRMED");
            when(tracks.findOpenRawTracks(any())).thenReturn(Map.of(link.linkId(), new TrackRow(
                    "track-" + suffix, "target-" + suffix, link.linkId(), "external-" + suffix, now,
                    json.writeValueAsString(Map.of("source_estimate", estimate)))));
            try (var context = new AnnotationConfigApplicationContext()) {
                context.registerBean(RawTrackRepository.class, () -> tracks);
                context.registerBean(ObjectMapper.class, () -> json);
                context.registerBean(FusionConfigService.class, () -> config);
                context.registerBean(FusionConfigLoader.class, () -> loader);
                context.registerBean(AppClock.class, () -> new AppClock(Clock.fixed(now, ZoneOffset.UTC)));
                context.registerBean(EoFusionTaskContextPort.class, () -> mock(EoFusionTaskContextPort.class));
                context.registerBean(SourceContributionReader.class);
                context.refresh();
                var result = context.getBean(SourceContributionReader.class).read("target-" + suffix, List.of(link)).get(0);
                assertThat(result.fusionStatus()).isEqualTo("CURRENT");
                assertThat(result.contributionAttributes()).contains("position", "class", "identity");
                assertThat(result.sourceMode()).isEqualTo("replay");
            }
        }
        // Supplementing the read does not mutate the active configuration.
        assertThat(params.weights()).doesNotContainKey("SIM_NORMALIZED");
        clearInvocations(jdbc);
        when(policy.allowed()).thenReturn(false);
        assertThat(loader.simulationParameters(params)).isSameAs(params);
        verifyNoInteractions(jdbc);
    }
}
