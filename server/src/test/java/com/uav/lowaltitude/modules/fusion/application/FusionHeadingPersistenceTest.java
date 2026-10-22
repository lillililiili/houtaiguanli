package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.*;
import com.uav.lowaltitude.modules.fusion.domain.SourceObservation;
import com.uav.lowaltitude.modules.fusion.infrastructure.*;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusedTrackRepository.LatestState;
import com.uav.lowaltitude.platform.time.AppClock;

class FusionHeadingPersistenceTest {
    @ParameterizedTest
    @CsvSource({"359.9999,0.00", "-0.001,0.00", "-90,270.00", "450.126,90.13", "180,180.00"})
    void fusedWriteAndPublishedSummaryUseTheSameWrappedValue(double input,String expected) {
        String target=UUID.randomUUID().toString(),source=UUID.randomUUID().toString();
        Instant observed=Instant.parse("2026-10-20T12:00:00Z").plusMillis(Math.abs(target.hashCode()%100_000));
        var tracks=mock(FusedTrackRepository.class);var states=mock(DegradationRepository.class);
        var events=mock(FusionEventEmitter.class);var config=mock(FusionConfigService.class);
        var params=mock(FusionParams.class);
        when(config.params("heading-regression")).thenReturn(params);
        when(params.configVersion()).thenReturn("heading-regression");
        when(params.weights()).thenReturn(Map.of("RADAR",Map.of("position",1.0,"motion",1.0,"class",1.0,"identity",1.0)));
        when(params.integer("degradation","three_source_min")).thenReturn(3);
        when(params.number("degradation","single_source_deficit")).thenReturn(0.2);
        when(params.number("degradation","undetermined_deficit")).thenReturn(0.9);
        when(tracks.findOpenTracks(anyList())).thenReturn(new HashMap<>());
        when(tracks.nextPointSeqs(anyList())).thenReturn(new HashMap<>());
        var writer=new DefaultFusedLayerWriter(config,tracks,states,events,mock(FusionEventContextRepository.class),
                new AppClock(),new ObjectMapper(),mock(FusionConfigLoader.class),mock(IdentityRepository.class));
        var estimate=new SourceEstimate(source,source,"RADAR","CONFIRMED",UUID.randomUUID().toString(),null,null,observed,
                116.6+(Math.abs(target.hashCode()%100)/10000d),35.2,12.0,null,null,7.0,input,null,null,null,null,PointKind.MEAS,Map.of());
        writer.write(new TargetFrameResult(target,new FusionDomainKey("live","org","district"),observed,
                List.of(estimate),TrackStatus.STABLE,0,"heading-regression"));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<LatestState>> written=ArgumentCaptor.forClass(List.class);
        verify(tracks).upsertLatestStates(written.capture());
        assertThat(written.getValue()).hasSize(1);
        assertThat(written.getValue().get(0).headingDeg()).isEqualByComparingTo(expected);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> payload=ArgumentCaptor.forClass(Map.class);
        verify(events).emit(eq(FusionEventEmitter.STATUS_STABLE),eq(target),any(),payload.capture());
        assertThat(((Map<?,?>)payload.getValue().get("latest_state")).get("heading_deg"))
                .isEqualTo(written.getValue().get(0).headingDeg());
    }

    @ParameterizedTest
    @CsvSource({"359.9999,0.00", "-0.006,359.99", "-90,270.00", "810,90.00", "179.999,180.00"})
    void sourceObservationSingleAndBatchWritesSatisfyTheNumericColumnConstraint(double input,String expected) {
        var source=new DriverManagerDataSource("jdbc:h2:mem:heading_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE source_observation (observation_id VARCHAR PRIMARY KEY,inbox_id VARCHAR,source_id VARCHAR,device_id VARCHAR,"
                +"source_type VARCHAR,source_session_key VARCHAR,external_target_id VARCHAR,external_track_id VARCHAR,observed_at TIMESTAMP,received_at TIMESTAMP,"
                +"location GEOMETRY,position_accuracy_m NUMERIC,altitude_amsl_m NUMERIC,height_agl_m NUMERIC,speed_mps NUMERIC,"
                +"heading_deg NUMERIC(6,2) CHECK(heading_deg IS NULL OR (heading_deg>=0 AND heading_deg<360)),class_code VARCHAR,class_confidence NUMERIC,"
                +"identity_clue VARCHAR,identity_confidence NUMERIC,latency_ms BIGINT,quality JSON,source_mode VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,"
                +"created_at TIMESTAMP,pilot_location GEOMETRY,class_source VARCHAR)");
        var repository=new ObservationRepository(jdbc,new ObjectMapper());
        var single=observation(input,"live");
        var batch=observation(input,"replay");
        var unknown=observation(null,"replay");
        repository.insert(single);
        repository.insertAll(List.of(batch,unknown));
        for(String id:List.of(single.observationId(),batch.observationId()))
            assertThat(jdbc.queryForObject("SELECT heading_deg FROM source_observation WHERE observation_id=?",BigDecimal.class,id))
                    .isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject("SELECT heading_deg FROM source_observation WHERE observation_id=?",BigDecimal.class,unknown.observationId())).isNull();
        jdbc.execute("SHUTDOWN");
    }

    private SourceObservation observation(Double heading,String mode) {
        String id=UUID.randomUUID().toString();Instant observed=Instant.now();
        return new SourceObservation(id,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"source","RADAR","CONFIRMED",
                null,"session-"+id,id,null,observed,observed,117.3,36.5,10.0,null,null,8.0,heading,null,null,null,null,null,
                Map.of(),mode,"org","district",0);
    }
}
