package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;
import com.uav.lowaltitude.modules.fusion.domain.SourceObservation;

class FusionSplitOriginTest {
    @Test
    void distinctExplicitSerialsExcludeSplitOriginWhileSameOrMissingSerialKeepsExistingPolicy() {
        for (int sample = 0; sample < 3; sample++) {
            String source = id(), origin = id(), serial = id();
            var domain = new FusionDomainKey("replay", id(), id());
            var first = observation(source, domain, " " + serial.toLowerCase() + " ");
            assertThat(origin(first, observation(source, domain, id()), origin, Map.of())).isNull();
            assertThat(origin(first, observation(source, domain, serial.toUpperCase()), origin, Map.of())).isEqualTo(origin);
            assertThat(origin(first, observation(source, domain, null), origin, Map.of())).isEqualTo(origin);
            assertThat(origin(observation(source, domain, null), observation(source, domain, serial), origin,
                    Map.of(origin, Set.of(id())))).isNull();
        }
    }

    @Test
    void splitOriginNeverCrossesSourceModeOrganizationOrDistrictOrUsesANewTarget() {
        String source = id(), target = id(), org = id(), district = id();
        var domain = new FusionDomainKey("replay", org, district);
        var first = observation(source, domain, null);
        for (var other : List.of(observation(id(), domain, null),
                observation(source, new FusionDomainKey("live", org, district), null),
                observation(source, new FusionDomainKey("replay", id(), district), null),
                observation(source, new FusionDomainKey("replay", org, id()), null)))
            assertThat(origin(first, other, target, Map.of())).isNull();
        assertThat(FusionPipeline.sameSourceOrigin(List.of(first, observation(source, domain, null)),
                new String[]{target, null}, Set.of(), Map.of(), 1)).isNull();
    }

    private String origin(SourceObservation first, SourceObservation second, String target, Map<String, Set<String>> serials) {
        return FusionPipeline.sameSourceOrigin(List.of(first, second), new String[]{target, null}, Set.of(target), serials, 1);
    }

    private SourceObservation observation(String source, FusionDomainKey domain, String serial) {
        SourceObservation observation = mock(SourceObservation.class);
        when(observation.sourceId()).thenReturn(source);
        when(observation.domain()).thenReturn(domain);
        when(observation.quality()).thenReturn(serial == null ? Map.of() : Map.of("uav_sn", serial));
        return observation;
    }

    private static String id() { return UUID.randomUUID().toString(); }
}
