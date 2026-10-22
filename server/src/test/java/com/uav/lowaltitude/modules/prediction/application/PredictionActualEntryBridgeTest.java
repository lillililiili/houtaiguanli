package com.uav.lowaltitude.modules.prediction.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.prediction.infrastructure.PredictionInputRepository;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine;

class PredictionActualEntryBridgeTest {
    private final ObjectMapper json=new ObjectMapper();
    private final long now=Instant.parse("2026-10-20T12:00:00Z").toEpochMilli();
    private final String target=id(),alarm=id(),event=id(),evaluation=id(),region=id(),version=id(),episode=id();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private PredictionActualEntryBridge bridge;
    @BeforeEach void setup() throws Exception {
        database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc=new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE alarm(alarm_id VARCHAR PRIMARY KEY,target_id VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,source_mode VARCHAR)");
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR PRIMARY KEY,alarm_id VARCHAR)");
        jdbc.execute("CREATE TABLE rule_evaluation(evaluation_id VARCHAR PRIMARY KEY,target_id VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,source_mode VARCHAR,observed_at TIMESTAMP,hit_details VARCHAR,input_snapshot VARCHAR,alarm_id VARCHAR,mode VARCHAR,legal_status VARCHAR,freshness_code VARCHAR)");
        jdbc.execute("CREATE TABLE prediction_event_link(event_id VARCHAR,episode_id VARCHAR,role VARCHAR,created_at BIGINT,PRIMARY KEY(event_id,episode_id))");
        jdbc.execute("CREATE TABLE prediction_episode(episode_id VARCHAR PRIMARY KEY,target_id VARCHAR,airspace_id VARCHAR,source_mode VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,active_key VARCHAR,started_at BIGINT,status VARCHAR)");
        jdbc.execute("CREATE TABLE prediction_actual_entry_link(event_id VARCHAR,target_id VARCHAR,airspace_id VARCHAR,observed_at BIGINT,evaluation_id VARCHAR,airspace_version_id VARCHAR,PRIMARY KEY(event_id,evaluation_id,airspace_id))");
        bridge=new PredictionActualEntryBridge(jdbc,json,new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)));
        jdbc.update("INSERT INTO alarm VALUES (?,?,'org','district','live')",alarm,target);
        jdbc.update("INSERT INTO uav_event VALUES (?,?)",event,alarm);
        jdbc.update("INSERT INTO prediction_episode VALUES (?,?,?,'live','org','district',?,?,'ACTIVE')",episode,target,region,id(),now-5000);
        jdbc.update("INSERT INTO rule_evaluation VALUES (?,?,'org','district','live',?,?,?,NULL,'ACTIVE','ILLEGAL','FRESH')",
                evaluation,target,new Timestamp(now-100),hits(region,version,"FAIL","COVERS"),snapshot(version,"COVERS",now-100));
    }
    @AfterEach void close(){database.shutdown();}

    @Test void measuredFrozenViolationLinksIdempotentlyBeforeAutomaticRules() {
        bridge.linkEvaluation(evaluation,alarm);
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_actual_entry_link",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link WHERE role='OBSERVED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT airspace_version_id FROM prediction_actual_entry_link",String.class)).isEqualTo(version);
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","TOUCHES","OUTSIDE"})
    void boundaryOrUnknownFrozenRelationNeverBecomesActualEntry(String relation) throws Exception {
        jdbc.update("UPDATE rule_evaluation SET input_snapshot=?",snapshot(version,relation,now-100));
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_actual_entry_link",Long.class)).isZero();
    }
    @Test void wrongVersionOrTimeCannotBorrowAnotherFrozenSpatialFact() throws Exception {
        assertThat(bridge.entries(hits(region,version,"FAIL","COVERS"),snapshot(id(),"COVERS",now-100),now-100)).isEmpty();
        assertThat(bridge.entries(hits(region,version,"FAIL","COVERS"),snapshot(version,"COVERS",now-500),now-100)).isEmpty();
        assertThat(bridge.entries(hits(region,version,"PASS","COVERS"),snapshot(version,"COVERS",now-100),now-100)).isEmpty();
        assertThat(bridge.entries(hits(region,version,"FAIL","COVERS"),snapshot(id(),version,"COVERS",now-100),now-100)).isEmpty();
        assertThat(bridge.entries(hits(region,version,"FAIL","COVERS").replace("PROHIBITED","RESTRICTED"),
                snapshot(version,"COVERS",now-100).replace("PROHIBITED","RESTRICTED"),now-100)).isEmpty();
    }
    @Test void sourceScopeAndPredictionOriginCannotBeUsedAsMeasuredEvidence() {
        jdbc.update("UPDATE alarm SET source_mode='replay'");
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_actual_entry_link",Long.class)).isZero();
        jdbc.update("UPDATE alarm SET source_mode='live'");
        jdbc.update("INSERT INTO prediction_event_link VALUES (?,?,'PREDICTED',?)",event,episode,now);
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_actual_entry_link",Long.class)).isZero();
    }
    @Test void multipleRegionsAndLaterEpisodesAppendWithoutRebindingOriginalLinks() throws Exception {
        bridge.linkEvaluation(evaluation,alarm);
        String later=id();
        jdbc.update("UPDATE prediction_episode SET active_key=NULL,status='CLEARED' WHERE episode_id=?",episode);
        jdbc.update("INSERT INTO prediction_episode VALUES (?,?,?,'live','org','district',?,?,'ACTIVE')",later,target,region,id(),now-50);
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForList("SELECT episode_id FROM prediction_event_link",String.class)).containsExactly(episode);
        String next=id();
        jdbc.update("INSERT INTO rule_evaluation VALUES (?,?,'org','district','live',?,?,?,NULL,'ACTIVE','ILLEGAL','FRESH')",
                next,target,new Timestamp(now-10),hits(region,version,"FAIL","COVERS"),snapshot(version,"COVERS",now-10));
        bridge.linkEvaluation(next,alarm);
        String otherRegion=id(),otherVersion=id(),otherEpisode=id();
        jdbc.update("INSERT INTO prediction_episode VALUES (?,?,?,'live','org','district',?,?,'ENTERED')",otherEpisode,target,otherRegion,id(),now-300);
        String otherEvaluation=id();
        jdbc.update("INSERT INTO rule_evaluation VALUES (?,?,'org','district','live',?,?,?,NULL,'ACTIVE','ILLEGAL','FRESH')",
                otherEvaluation,target,new Timestamp(now-10),hits(otherRegion,otherVersion,"FAIL","COVERS"),
                snapshot(otherRegion,otherVersion,"COVERS",now-10));
        bridge.linkEvaluation(otherEvaluation,alarm);
        assertThat(jdbc.queryForList("SELECT episode_id FROM prediction_event_link",String.class))
                .containsExactlyInAnyOrder(episode,later,otherEpisode);
    }
    @Test void staleOrOlderThanEpisodeMeasurementDoesNotAttachToNewRisk() throws Exception {
        jdbc.update("UPDATE prediction_episode SET started_at=?",now-50);
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link",Long.class)).isZero();
        jdbc.update("UPDATE rule_evaluation SET observed_at=?,input_snapshot=?",new Timestamp(now-5000),snapshot(version,"COVERS",now-5000));
        jdbc.update("UPDATE prediction_episode SET started_at=?",now-10000);
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(longs={2000,6000})
    void measuredLinkUsesConfiguredFreshnessForEveryTarget(long freshness) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode parameters=json.valueToTree(new PredictionConfiguration(json,"").parameters());
        ((com.fasterxml.jackson.databind.node.ObjectNode)parameters.get("forecast")).put("freshnessMillis",freshness);
        bridge.currentInputs(mock(PredictionInputRepository.class),
                new PredictionConfiguration(json,json.writeValueAsString(parameters)));
        jdbc.update("UPDATE prediction_episode SET started_at=?",now-10000);
        jdbc.update("UPDATE rule_evaluation SET observed_at=?,input_snapshot=?",new Timestamp(now-freshness-1),
                snapshot(version,"COVERS",now-freshness-1));
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link",Long.class)).isZero();
        jdbc.update("UPDATE rule_evaluation SET observed_at=?,input_snapshot=?",new Timestamp(now-freshness+1),
                snapshot(version,"COVERS",now-freshness+1));
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link",Long.class)).isEqualTo(1);
    }
    @Test void realtimeReplayUsesCurrentTrustedMeasWithoutWaitingForPredictionSnapshot() throws Exception {
        var inputs=mock(PredictionInputRepository.class);
        var config=new PredictionConfiguration(json,"");
        bridge.currentInputs(inputs,config);
        jdbc.update("UPDATE alarm SET source_mode='replay'");
        jdbc.update("UPDATE prediction_episode SET source_mode='replay'");
        jdbc.update("UPDATE rule_evaluation SET source_mode='replay',freshness_code='REPLAY',input_snapshot=?",
                snapshot(version,"COVERS",now-100).replace("FRESH","REPLAY"));
        var targetFact=new PredictionInputRepository.Target(target,"目标", "org","district","replay",true);
        var point=new PredictionEngine.Measurement(id(),now-100,now-90,118.7,37.4,2,100.0,50.0);
        when(inputs.load(eq(target),eq(now),any())).thenReturn(new PredictionInputRepository.Inputs(targetFact,List.of(point),
                "stream","HISTORICAL_REPLAY_NOT_CURRENT",List.of(),Map.of(),java.util.Set.of(),java.util.Set.of(),List.of()));
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link",Long.class)).isZero();
        when(inputs.load(eq(target),eq(now),any())).thenReturn(new PredictionInputRepository.Inputs(targetFact,List.of(point),
                "stream",null,List.of(),Map.of(),java.util.Set.of(region),java.util.Set.of(),List.of()));
        bridge.linkEvaluation(evaluation,alarm);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prediction_event_link",Long.class)).isEqualTo(1);
        // This fixture has no prediction_snapshot table: proof came from the current input adapter.
    }
    private String hits(String airspace,String airspaceVersion,String result,String relation) throws Exception {
        return json.writeValueAsString(List.of(Map.of("rule_code","C02-1","result_code",result,"reason_code","INSIDE_RESTRICTED_AIRSPACE",
                "facts",Map.of("airspace_id",airspace,"airspace_version_id",airspaceVersion,"kind_code","PROHIBITED","relation",relation))));
    }
    private String snapshot(String airspaceVersion,String relation,long observed) throws Exception {
        return snapshot(region,airspaceVersion,relation,observed);
    }
    private String snapshot(String airspace,String airspaceVersion,String relation,long observed) throws Exception {
        return json.writeValueAsString(Map.of("freshness","FRESH","state",Map.of("observed_at",observed,"longitude",118.7,"latitude",37.4),
                "airspace_hits",List.of(Map.of("airspace_id",airspace,"airspace_version_id",airspaceVersion,"kind_code","PROHIBITED","relation",relation))));
    }
    private static String id(){return UUID.randomUUID().toString();}
}
