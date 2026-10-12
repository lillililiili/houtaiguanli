package com.uav.lowaltitude.modules.target.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.*;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.fusion.application.EoFusionTaskContextPort;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.TrackRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.SourceLinkRow;
import com.uav.lowaltitude.platform.time.AppClock;

class SourceContributionReaderTest {
    private final Instant now = Instant.parse("2026-10-11T12:00:00Z");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RawTrackRepository tracks = mock(RawTrackRepository.class);
    private final FusionConfigService config = mock(FusionConfigService.class);
    private final EoFusionTaskContextPort tasks = mock(EoFusionTaskContextPort.class);
    private final SourceContributionReader reader = new SourceContributionReader(tracks, json, config, new AppClock(Clock.fixed(now, ZoneOffset.UTC)), tasks);
    private final SourceLinkRow link = new SourceLinkRow("link-z", "source-z", "RADAR-Z", "replay", "batch-z", "target-z", "device-z", null, "Radar", "RADAR", "CONFIRMED");

    private void snapshot(Instant at, Map<String,Object> quality) throws Exception {
        FusionParams params = mock(FusionParams.class);
        when(config.params(null)).thenReturn(params);
        when(params.integer("identity", "short_lost_after_ms")).thenReturn(5000);
        when(params.weights()).thenReturn(Map.of("RADAR", Map.of("position",1d,"class",1d,"identity",0d,"motion",1d)));
        var estimate = new SourceEstimate("source-z", "RADAR-Z", "RADAR", "CONFIRMED", "link-z", "track-z", "observation-z", at,
                118d, 37d, 10d, null,null,null,null,"UAV",null,null,null,PointKind.MEAS,quality);
        when(tracks.findOpenRawTracks(any())).thenReturn(Map.of("link-z",new TrackRow("track-z","target-z","link-z","external-z",at,json.writeValueAsString(Map.of("source_estimate",estimate)))));
    }
    @Test void currentAttributesComeFromAcceptedObservationAndExpireByServerClock() throws Exception {
        snapshot(now.minusMillis(100),Map.of());
        var current = reader.read("target-z",List.of(link)).get(0);
        assertThat(current.fusionStatus()).isEqualTo("CURRENT");
        assertThat(current.contributionAttributes()).containsExactly("position","class");
        assertThat(current.observationAt()).isEqualTo(now.minusMillis(100).toEpochMilli());
        snapshot(now.minusMillis(5001),Map.of());
        var expired=reader.read("target-z",List.of(link)).get(0);
        assertThat(expired.fusionStatus()).isEqualTo("EXPIRED");
        assertThat(expired.contributionAttributes()).isEmpty();
    }
    @Test void futureTimeAndExcludedEvidenceNeverBecomeCurrent() throws Exception {
        snapshot(now.plusMillis(1),Map.of());
        assertThat(reader.read("target-z",List.of(link)).get(0).fusionStatus()).isEqualTo("UNAVAILABLE");
        snapshot(now,Map.of("eo_fusion_exclusion","TASK_ENDED"));
        assertThat(reader.read("target-z",List.of(link)).get(0).fusionStatus()).isEqualTo("UNAVAILABLE");
    }
    @Test void onlyLatestLinkOfSameSourceHasCurrentAttributes() throws Exception {
        snapshot(now.minusMillis(100),Map.of());
        var old = new SourceEstimate("source-z","RADAR-Z","RADAR","CONFIRMED","old-link","old-track","old-obs",now.minusMillis(200),
                118d,37d,10d,null,null,null,null,"UAV",null,null,null,PointKind.MEAS,Map.of());
        var fresh = new SourceEstimate("source-z","RADAR-Z","RADAR","CONFIRMED","link-z","track-z","observation-z",now.minusMillis(100),
                118d,37d,10d,null,null,null,null,"UAV",null,null,null,PointKind.MEAS,Map.of());
        when(tracks.findOpenRawTracks(any())).thenReturn(Map.of(
                "old-link",new TrackRow("old-track","target-z","old-link","external-old",now.minusMillis(200),json.writeValueAsString(Map.of("source_estimate",old))),
                "link-z",new TrackRow("track-z","target-z","link-z","external-z",now.minusMillis(100),json.writeValueAsString(Map.of("source_estimate",fresh)))));
        var oldLink = new SourceLinkRow("old-link","source-z","RADAR-Z","replay","old-batch","old-target","device-z",null,"Radar","RADAR","CONFIRMED");
        var result=reader.read("target-z",List.of(oldLink,link));
        assertThat(result.get(0).fusionStatus()).isEqualTo("HISTORICAL");
        assertThat(result.get(0).contributionAttributes()).isEmpty();
        assertThat(result.get(1).fusionStatus()).isEqualTo("CURRENT");
    }
    @Test void endedEoTaskCannotRetainCurrentContribution() throws Exception {
        FusionParams params = mock(FusionParams.class);
        when(config.params(null)).thenReturn(params);
        when(params.integer("identity", "short_lost_after_ms")).thenReturn(5000);
        var quality = Map.<String,Object>of("eo_task_id","task-e", "eo_device_id","device-e", "eo_source_mode","replay",
                "eo_owner_org_id","org-e", "eo_district_id","district-e");
        var estimate = new SourceEstimate("source-e","EO-E","EO","CONFIRMED","link-e","track-e","obs-e",now,
                118d,37d,10d,null,null,null,null,"UAV",0.9d,null,null,PointKind.MEAS,quality);
        var eo = new SourceLinkRow("link-e","source-e","EO-E","replay","batch-e","target-e","device-e",null,"EO","EO","CONFIRMED");
        when(tracks.findOpenRawTracks(any())).thenReturn(Map.of("link-e",new TrackRow("track-e","target-e","link-e","external-e",now,json.writeValueAsString(Map.of("source_estimate",estimate)))));
        when(tasks.lookup("task-e","source-e","device-e","replay","org-e","district-e",now.toEpochMilli(),now.toEpochMilli()))
                .thenReturn(new EoFusionTaskContextPort.Context(null,"EO_TASK_NOT_OPEN"));
        var result=reader.read("target-e",List.of(eo)).get(0);
        assertThat(result.fusionStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.contributionAttributes()).isEmpty();
        assertThat(result.fusionReason()).contains("跟踪任务");
    }
    @Test void historicalLinkNeverCreatesAContribution() {
        var params=mock(FusionParams.class);
        when(config.params(null)).thenReturn(params);
        when(tracks.findOpenRawTracks(any())).thenReturn(Map.of());
        assertThat(reader.read("target-z",List.of(link)).get(0).fusionStatus()).isEqualTo("HISTORICAL");
    }
}
