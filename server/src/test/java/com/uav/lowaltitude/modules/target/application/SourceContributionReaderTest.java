package com.uav.lowaltitude.modules.target.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.*;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigLoader;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.fusion.application.EoFusionTaskContextPort;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.RawTrackRepository.TrackRow;
import com.uav.lowaltitude.modules.target.infrastructure.TargetReadRepository.SourceLinkRow;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.time.AppClock;

class SourceContributionReaderTest {
    private final Instant now = Instant.parse("2026-10-11T12:00:00Z");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RawTrackRepository tracks = mock(RawTrackRepository.class);
    private final FusionConfigService config = mock(FusionConfigService.class);
    private final EoFusionTaskContextPort tasks = mock(EoFusionTaskContextPort.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SourceContributionReader reader = reader("local", "qa");
    private final SourceLinkRow link = new SourceLinkRow("link-z", "source-z", "RADAR-Z", "replay", "batch-z", "target-z", "device-z", null, "Radar", "RADAR", "CONFIRMED");

    private SourceContributionReader reader(String... profiles) {
        var environment = new StandardEnvironment();
        environment.setActiveProfiles(profiles);
        return new SourceContributionReader(tracks, json, config, new FusionConfigLoader(jdbc, new SimulationPolicy(environment)),
                new AppClock(Clock.fixed(now, ZoneOffset.UTC)), tasks);
    }
    /** demo-v1 的权重表：没有 SIM_NORMALIZED（它的权重在 simulator_fusion_parameters，由 V202610040002 迁移写入）。 */
    private FusionParams demoParams() {
        FusionParams params = mock(FusionParams.class);
        when(config.params(null)).thenReturn(params);
        when(params.integer("identity", "short_lost_after_ms")).thenReturn(5000);
        when(params.weights()).thenReturn(Map.of("RADAR", Map.of("position",1d,"class",1d,"identity",0d,"motion",1d)));
        return params;
    }
    private void simulatorWeights() {
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("source_type", "SIM_NORMALIZED", "accuracy_m", new BigDecimal("60.0000"),
                "position_weight", new BigDecimal("0.25"), "motion_weight", new BigDecimal("0.20"),
                "class_weight", new BigDecimal("0.15"), "identity_weight", new BigDecimal("0.40")));
    }
    private TrackRow track(SourceEstimate estimate) throws Exception {
        return new TrackRow("track-" + estimate.linkId(), "target-z", estimate.linkId(), "external-" + estimate.linkId(), estimate.observedAt(),
                json.writeValueAsString(Map.of("source_estimate", estimate)));
    }
    private void snapshot(Instant at, Map<String,Object> quality) throws Exception {
        demoParams();
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

    // 目标详情（GET /targets/{id}）曾因“缺少 SIM_NORMALIZED 权重”整体 500：规范化模拟观测的权重不在 fusion_config，而在 simulator_fusion_parameters。
    @Test void simulatedNormalizedSourceReadsWithSimulatorWeightsInsteadOfFailingTheWholeRead() throws Exception {
        demoParams();
        simulatorWeights();
        var simulated = new SourceEstimate("source-s","SIM-S","SIM_NORMALIZED","CONFIRMED","link-s","track-s","obs-s",now.minusMillis(100),
                118d,37d,60d,null,null,null,null,"UAV",0.9d,null,null,PointKind.MEAS,Map.of());
        when(tracks.findOpenRawTracks(any())).thenReturn(Map.of("link-s",track(simulated)));
        var simLink = new SourceLinkRow("link-s","source-s","SIM-S","replay","batch-s","ext-s","device-s",null,"规范化模拟观测","SIM_NORMALIZED","CONFIRMED");
        var result = reader.read("target-z",List.of(simLink)).get(0);
        assertThat(result.fusionStatus()).isEqualTo("CURRENT");
        assertThat(result.contributionAttributes()).containsExactly("position","class");
    }
    @Test void simulatedAndMeasuredSourcesOfDifferentTypesAreWeightedTogether() throws Exception {
        demoParams();
        simulatorWeights();
        var radar = new SourceEstimate("source-r","RADAR-R","RADAR","CONFIRMED","link-r","track-r","obs-r",now.minusMillis(50),
                118.0001d,37d,10d,null,null,null,null,"UAV",null,null,null,PointKind.MEAS,Map.of());
        var simulated = new SourceEstimate("source-s","SIM-S","SIM_NORMALIZED","CONFIRMED","link-s","track-s","obs-s",now.minusMillis(100),
                118d,37d,60d,null,null,null,null,"UAV",0.9d,null,null,PointKind.MEAS,Map.of());
        when(tracks.findOpenRawTracks(any())).thenReturn(Map.of("link-r",track(radar),"link-s",track(simulated)));
        var radarLink = new SourceLinkRow("link-r","source-r","RADAR-R","replay","batch-r","ext-r","device-r",null,"Radar","RADAR","CONFIRMED");
        var simLink = new SourceLinkRow("link-s","source-s","SIM-S","replay","batch-s","ext-s","device-s",null,"规范化模拟观测","SIM_NORMALIZED","CONFIRMED");
        var result = reader.read("target-z",List.of(radarLink,simLink));
        assertThat(result.get(0).fusionStatus()).isEqualTo("CURRENT");
        assertThat(result.get(0).contributionAttributes()).containsExactly("position","class");
        assertThat(result.get(1).fusionStatus()).isEqualTo("CURRENT");
        assertThat(result.get(1).contributionAttributes()).containsExactly("position");
    }
    @Test void measuredSourcesNeverTouchTheSimulatorWeightTable() throws Exception {
        snapshot(now.minusMillis(100),Map.of());
        assertThat(reader.read("target-z",List.of(link)).get(0).fusionStatus()).isEqualTo("CURRENT");
        verifyNoInteractions(jdbc);
    }
    @Test void formalEnvironmentNeverBorrowsSimulatorWeights() throws Exception {
        demoParams();
        var simulated = new SourceEstimate("source-s","SIM-S","SIM_NORMALIZED","CONFIRMED","link-s","track-s","obs-s",now.minusMillis(100),
                118d,37d,60d,null,null,null,null,"UAV",0.9d,null,null,PointKind.MEAS,Map.of());
        when(tracks.findOpenRawTracks(any())).thenReturn(Map.of("link-s",track(simulated)));
        var simLink = new SourceLinkRow("link-s","source-s","SIM-S","replay","batch-s","ext-s","device-s",null,"规范化模拟观测","SIM_NORMALIZED","CONFIRMED");
        assertThatThrownBy(() -> reader("prod").read("target-z",List.of(simLink)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SIM_NORMALIZED");
        verifyNoInteractions(jdbc);
    }

    @Test void normalizedReplayAndRadarUseTheSameEffectiveParametersAsThePipeline() throws Exception {
        snapshot(now.minusMillis(100), Map.of());
        var jdbc = mock(JdbcTemplate.class);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local", "qa");
        var effective = loader(jdbc, environment);
        var readerWithPipelineConfig = new SourceContributionReader(tracks, json, config, effective,
                new AppClock(Clock.fixed(now, ZoneOffset.UTC)), tasks);
        String suffix = UUID.randomUUID().toString();
        String sourceId = "normalized-" + suffix, linkId = "link-" + suffix;
        var simulated = new SourceLinkRow(linkId, sourceId, "SIM-" + suffix, "replay", "session-" + suffix,
                "external-" + suffix, "device-" + suffix, null, "Simulator", "SIM_NORMALIZED", "CONFIRMED");
        var radarTrack = tracks.findOpenRawTracks(List.of()).get(link.linkId());
        var estimate = new SourceEstimate(sourceId, simulated.sourceCode(), "SIM_NORMALIZED", "CONFIRMED",
                linkId, "track-" + suffix, "observation-" + suffix, now.minusMillis(50),
                119d, 38d, 5d, null, 80d, 4d, 90d, "UAV", .9d, "identity-" + suffix, .95d, PointKind.MEAS, Map.of());
        when(tracks.findOpenRawTracks(any())).thenReturn(Map.of(link.linkId(), radarTrack,
                linkId, new TrackRow(estimate.rawTrackId(), "target-z", linkId, simulated.externalTargetId(),
                        estimate.observedAt(), json.writeValueAsString(Map.of("source_estimate", estimate)))));
        var result = readerWithPipelineConfig.read("target-z", List.of(link, simulated));
        assertThat(result).extracting(r -> r.fusionStatus()).containsExactly("CURRENT", "CURRENT");
        assertThat(result.get(0).contributionAttributes()).contains("position");
        assertThat(result.get(1).contributionAttributes()).containsExactly("position", "class", "identity", "motion");
        assertThat(result.get(1).sourceMode()).isEqualTo("replay");
        assertThat(result.get(1).observationAt()).isEqualTo(estimate.observedAt().toEpochMilli());
        verify(jdbc).queryForMap("SELECT * FROM simulator_fusion_parameters WHERE source_type='SIM_NORMALIZED'");
    }

    @Test void productionKeepsRealSourceParametersWithoutEnablingSimulation() throws Exception {
        snapshot(now.minusMillis(100), Map.of());
        var jdbc = mock(JdbcTemplate.class);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "local", "qa");
        var effective = loader(jdbc, environment);
        var readerWithPipelineConfig = new SourceContributionReader(tracks, json, config, effective,
                new AppClock(Clock.fixed(now, ZoneOffset.UTC)), tasks);
        var liveLink = new SourceLinkRow(link.linkId(), link.sourceId(), link.sourceCode(), "live",
                link.sourceSessionKey(), link.externalTargetId(), link.deviceId(), null, link.sourceName(), "RADAR", "CONFIRMED");
        var result = readerWithPipelineConfig.read("target-z", List.of(liveLink)).get(0);
        assertThat(result.sourceMode()).isEqualTo("live");
        assertThat(result.fusionStatus()).isEqualTo("CURRENT");
        assertThat(result.contributionAttributes()).containsExactly("position", "class");
        assertThat(effective.active().weights()).doesNotContainKey("SIM_NORMALIZED");
        verify(jdbc, never()).queryForMap(anyString());
    }

    @SuppressWarnings("unchecked")
    private FusionConfigLoader loader(JdbcTemplate jdbc, MockEnvironment environment) {
        String params = """
                {"identity":{"short_lost_after_ms":5000},"filter":{"accuracy_default_m":{"RADAR":10}},
                 "weights":{"RADAR":{"position":1,"class":1,"identity":0,"motion":1}}}
                """;
        doReturn(Collections.singletonList(new String[] {"test-" + UUID.randomUUID(), params}))
                .when(jdbc).query(startsWith("SELECT config_version"), any(RowMapper.class));
        when(jdbc.queryForMap("SELECT * FROM simulator_fusion_parameters WHERE source_type='SIM_NORMALIZED'"))
                .thenReturn(Map.of("accuracy_m", 5d, "position_weight", 2d, "motion_weight", 2d,
                        "class_weight", 2d, "identity_weight", 1d));
        return new FusionConfigLoader(jdbc, new SimulationPolicy(environment));
    }
}
