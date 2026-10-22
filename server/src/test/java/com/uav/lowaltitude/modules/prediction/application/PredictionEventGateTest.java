package com.uav.lowaltitude.modules.prediction.application;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Presence;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** Dispatch policy is checked with unrelated generated identities and both origin branches. */
class PredictionEventGateTest {
    private final long now=Instant.parse("2026-10-20T10:00:00Z").toEpochMilli();
    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private PredictionEventGate gate;
    private String episode,predicted,observed,snapshot;

    @BeforeEach void setup() {
        db=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc=new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE prediction_episode(episode_id VARCHAR PRIMARY KEY,status VARCHAR,version BIGINT,latest_prediction_id VARCHAR,last_observed_at BIGINT,valid_until BIGINT,current_evidence_valid BOOLEAN,entered_evidence_valid BOOLEAN,manual_counter_only BOOLEAN,started_at BIGINT,active_key VARCHAR,last_paused_version BIGINT DEFAULT -1)");
        jdbc.execute("CREATE TABLE prediction_event_link(event_id VARCHAR,episode_id VARCHAR,role VARCHAR,PRIMARY KEY(event_id,episode_id))");
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR PRIMARY KEY,state_code VARCHAR)");
        jdbc.execute("CREATE TABLE uav_event_verification(history_id VARCHAR,event_id VARCHAR,conclusion VARCHAR,actor_id VARCHAR)");
        jdbc.execute("CREATE TABLE prediction_episode_snapshot(episode_id VARCHAR,prediction_id VARCHAR)");
        jdbc.execute("CREATE TABLE prediction_snapshot(prediction_id VARCHAR PRIMARY KEY,status VARCHAR,generated_at BIGINT)");
        jdbc.execute("CREATE TABLE prediction_verification_basis(history_id VARCHAR,prediction_id VARCHAR,checked_prediction_id VARCHAR,episode_id VARCHAR)");
        jdbc.execute("CREATE TABLE uav_auto_sms_task(event_id VARCHAR,attempt_count INT,triggered_at BIGINT)");
        jdbc.execute("CREATE TABLE disposal_authorization(authorization_id VARCHAR,subject_id VARCHAR,subject_kind VARCHAR,action_type VARCHAR,status VARCHAR)");
        gate=new PredictionEventGate(jdbc,new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)));
        episode=id();predicted=id();observed=id();snapshot=id();
        jdbc.update("INSERT INTO prediction_episode VALUES (?,'ACTIVE',4,?,?,?,TRUE,FALSE,TRUE,?,?,-1)",episode,snapshot,now-100,now+20_000,now-1000,id());
        jdbc.update("INSERT INTO uav_event VALUES (?,'PENDING_VERIFICATION'),(?,'CONFIRMED')",predicted,observed);
        jdbc.update("INSERT INTO prediction_event_link VALUES (?,?,'PREDICTED'),(?,?,'OBSERVED')",predicted,episode,observed,episode);
        jdbc.update("INSERT INTO prediction_episode_snapshot VALUES (?,?)",episode,snapshot);
        jdbc.update("INSERT INTO prediction_snapshot VALUES (?,'READY',?)",snapshot,now-50);
    }
    @AfterEach void close(){db.shutdown();}

    @Test void predictionRequiresHumanConfirmationAndObservedEventKeepsIndependentEvidence() {
        assertThat(gate.confirmationBlock(predicted)).isEmpty();
        assertThat(gate.counterBlock(predicted,false)).contains("人工核实");
        jdbc.update("UPDATE uav_event SET state_code='CONFIRMED' WHERE event_id=?",predicted);
        jdbc.update("INSERT INTO uav_event_verification VALUES (?,?, 'CONFIRMED',NULL)",id(),predicted);
        assertThat(gate.counterBlock(predicted,false)).contains("人工核实");
        confirm();
        assertThat(gate.counterBlock(predicted,false)).isEmpty();
        assertThat(gate.counterBlock(observed,false)).isNull();
        assertThat(gate.confirmationBlock(observed)).isNull();
        assertThat(gate.counterBlock(id(),false)).isNull();
    }

    @Test void automationOriginCannotBeLostWhenObservedAlarmIsLinked() {
        for(String category:new String[]{"verify","counter","dispose"})assertThat(gate.blocksAutomatic(category,predicted)).isTrue();
        assertThat(gate.blocksAutomatic("counter",observed)).isTrue();
        assertThat(gate.blocksAutomatic("verify",observed)).isFalse();
        assertThat(gate.blocksAutomatic("dispose",observed)).isFalse();
        assertThat(gate.blocksAutomatic("counter",id())).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"UNKNOWN","CLEARED","CANDIDATE"})
    void unreliableOrEndedEpisodeCannotStartOrContinuePredictionCounter(String status) {
        confirm();jdbc.update("UPDATE prediction_episode SET status=? WHERE episode_id=?",status,episode);
        assertThat(gate.counterBlock(predicted,false)).isNotEmpty();
        assertThat(gate.counterBlock(predicted,true)).isNotEmpty();
        assertThat(gate.notificationBlock(predicted)).isNotEmpty();
    }

    @Test void enteredOnlyContinuesExistingWindowWithFreshMeasuredEntryEvidence() {
        confirm();
        jdbc.update("UPDATE prediction_episode SET status='ENTERED',entered_evidence_valid=TRUE WHERE episode_id=?",episode);
        assertThat(gate.counterBlock(predicted,false)).contains("关联实测告警");
        assertThat(gate.counterBlock(predicted,true)).isEmpty();
        jdbc.update("UPDATE prediction_episode SET entered_evidence_valid=FALSE WHERE episode_id=?",episode);
        assertThat(gate.counterBlock(predicted,true)).isNotEmpty();
        jdbc.update("UPDATE prediction_episode SET entered_evidence_valid=TRUE,valid_until=? WHERE episode_id=?",now,episode);
        assertThat(gate.counterBlock(predicted,true)).isNotEmpty();
    }

    @Test void operatorMayVerifyViewedSnapshotWhileNewerEvidenceIsCheckedAndFrozen() {
        String viewed=id();
        jdbc.update("INSERT INTO prediction_episode_snapshot VALUES (?,?)",episode,viewed);
        jdbc.update("INSERT INTO prediction_snapshot VALUES (?,'READY',?)",viewed,now-500);
        gate.requireSelectedPrediction(predicted,viewed);
        String history=id();gate.freezeVerification(history,predicted,viewed);
        var row=jdbc.queryForMap("SELECT * FROM prediction_verification_basis WHERE history_id=?",history);
        assertThat(row.get("PREDICTION_ID")).isEqualTo(viewed);
        assertThat(row.get("CHECKED_PREDICTION_ID")).isEqualTo(snapshot);
        assertThatThrownBy(()->gate.requireSelectedPrediction(predicted,id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->gate.requireSelectedPrediction(predicted,null)).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE prediction_snapshot SET status='UNKNOWN' WHERE prediction_id=?",viewed);
        assertThatThrownBy(()->gate.requireSelectedPrediction(predicted,viewed)).isInstanceOf(ApiException.class);
    }

    @Test void notificationAndCounterAreSharedWithoutCopyingFactOwnership() {
        confirm();
        jdbc.update("INSERT INTO uav_auto_sms_task VALUES (?,1,?)",predicted,now-50);
        assertThat(gate.notificationOwner(observed)).isEqualTo(predicted);
        assertThat(gate.notificationBlock(observed)).contains("不重复");
        String authorization=id();
        jdbc.update("INSERT INTO disposal_authorization VALUES (?,?,'UAV_EVENT','COUNTERMEASURE','APPROVED')",authorization,predicted);
        assertThat(gate.hasOtherActiveCounter(observed,null)).isTrue();
        assertThat(gate.hasOtherActiveCounter(predicted,authorization)).isFalse();
        jdbc.update("UPDATE disposal_authorization SET status='STOPPED'");
        assertThat(gate.hasOtherActiveCounter(observed,null)).isFalse();
    }

    @Test void notificationOwnershipRequiresAnAttemptInTheSameOpenRiskCycle() {
        String otherEpisode=id(),otherPrediction=id();
        jdbc.update("INSERT INTO prediction_episode VALUES (?,'ENTERED',1,?,?,?,FALSE,TRUE,TRUE,?,?,-1)",
                otherEpisode,id(),now-10,now+1000,now-100,id());
        jdbc.update("INSERT INTO uav_event VALUES (?,'CONFIRMED')",otherPrediction);
        jdbc.update("INSERT INTO prediction_event_link VALUES (?,?,'PREDICTED')",otherPrediction,otherEpisode);
        jdbc.update("INSERT INTO uav_auto_sms_task VALUES (?,1,?)",otherPrediction,now-5);
        // A different active cycle, even with a completed attempt, is not this event's owner.
        assertThat(gate.notificationOwner(observed)).isEqualTo(observed);
        jdbc.update("INSERT INTO uav_auto_sms_task VALUES (?,0,?)",predicted,now-50);
        assertThat(gate.notificationOwner(observed)).isEqualTo(observed);
        jdbc.update("UPDATE uav_auto_sms_task SET attempt_count=1,triggered_at=? WHERE event_id=?",now-1001,predicted);
        assertThat(gate.notificationOwner(observed)).isEqualTo(observed);
        jdbc.update("UPDATE uav_auto_sms_task SET triggered_at=? WHERE event_id=?",now-50,predicted);
        assertThat(gate.notificationOwner(observed)).isEqualTo(predicted);
        jdbc.update("UPDATE prediction_episode SET active_key=NULL WHERE episode_id=?",episode);
        assertThat(gate.notificationOwner(observed)).isEqualTo(observed);
    }

    @Test void absenceOrOldMeasurementsNeverProveDeparture() {
        assertThat(gate.assessment(predicted,now-50,now).presence()).isEqualTo(Presence.UNKNOWN);
        jdbc.update("UPDATE prediction_episode SET status='UNKNOWN'");
        assertThat(gate.assessment(predicted,now-200,now).presence()).isEqualTo(Presence.UNKNOWN);
        jdbc.update("UPDATE prediction_episode SET status='CLEARED'");
        assertThat(gate.assessment(predicted,now-200,now).presence()).isEqualTo(Presence.LEFT);
        assertThat(gate.assessment(observed,now-200,now)).isNull();
    }

    @Test void observedEventKeepsStickyManualOnlyAcrossRegionsWithoutInheritingClosedCycleLifecycle() {
        jdbc.update("UPDATE prediction_episode SET status='CLEARED',active_key=NULL WHERE episode_id=?",episode);
        jdbc.update("INSERT INTO uav_auto_sms_task VALUES (?,1,?)",predicted,now-50);
        String second=id(),secondPrediction=id();
        jdbc.update("INSERT INTO prediction_episode VALUES (?,'ENTERED',1,?,?,?,FALSE,TRUE,TRUE,?,?,-1)",
                second,id(),now-10,now+1000,now-20,id());
        jdbc.update("INSERT INTO uav_event VALUES (?,'CONFIRMED')",secondPrediction);
        jdbc.update("INSERT INTO prediction_event_link VALUES (?,?,'OBSERVED'),(?,?,'PREDICTED')",
                observed,second,secondPrediction,second);
        gate.lockEpisode(observed);
        assertThat(gate.manualCounterOnly(observed)).isTrue();
        assertThat(gate.blocksAutomatic("counter",observed)).isTrue();
        assertThat(gate.counterBlock(observed,false)).isNull();
        assertThat(gate.notificationOwner(observed)).isEqualTo(observed);
        jdbc.update("INSERT INTO uav_auto_sms_task VALUES (?,1,?)",secondPrediction,now-5);
        assertThat(gate.notificationOwner(observed)).isEqualTo(secondPrediction);
        assertThat(gate.linkedEvents(observed)).containsExactlyInAnyOrder(observed,predicted,secondPrediction);
    }

    @Test void actualEventStillUsesActualEvidenceWhenLinkedPredictionIsUnknown() {
        jdbc.update("UPDATE prediction_episode SET status='UNKNOWN'");
        assertThat(gate.counterBlock(observed,false)).isNull();
        assertThat(gate.confirmationBlock(observed)).isNull();
        assertThat(gate.notificationBlock(observed)).isEmpty();
        assertThat(gate.blocksAutomatic("counter",observed)).isTrue();
    }

    @Test void recoveryCannotReviveNotificationClaimFromBeforePause() {
        Long revision=gate.notificationRevision(predicted);
        gate.markPaused(predicted,5);
        jdbc.update("UPDATE prediction_episode SET status='ACTIVE',version=6 WHERE episode_id=?",episode);
        assertThat(gate.notificationClaimInterrupted(predicted,revision)).isTrue();
        assertThat(gate.notificationClaimInterrupted(predicted,gate.notificationRevision(predicted))).isFalse();
        assertThat(gate.notificationRevision(observed)).isNull();
        gate.markPaused(predicted,3);
        assertThat(gate.notificationClaimInterrupted(predicted,revision)).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void predictivePilotRequiresLatestMeasuredPlanMatchInExactSourceAndScope(String source) throws Exception {
        jdbc.execute("ALTER TABLE prediction_episode ADD target_id VARCHAR");
        jdbc.execute("ALTER TABLE prediction_episode ADD owner_org_id VARCHAR");
        jdbc.execute("ALTER TABLE prediction_episode ADD district_id VARCHAR");
        jdbc.execute("ALTER TABLE prediction_episode ADD source_mode VARCHAR");
        jdbc.execute("CREATE TABLE flight_plan(plan_id VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,source_mode VARCHAR)");
        jdbc.execute("CREATE TABLE rule_evaluation(evaluation_id VARCHAR,target_id VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,source_mode VARCHAR,plan_id VARCHAR,mode VARCHAR,freshness_code VARCHAR,plan_match_code VARCHAR,observed_at TIMESTAMP,evaluated_at TIMESTAMP)");
        String target=id(),plan=id(),evaluation=id(),org=id(),district=id();
        jdbc.update("UPDATE prediction_episode SET target_id=?,owner_org_id=?,district_id=?,source_mode=?",target,org,district,source);
        jdbc.update("INSERT INTO flight_plan VALUES (?,?,?,?)",plan,org,district,source);
        jdbc.update("INSERT INTO rule_evaluation VALUES (?,?,?,?,?,?,'ACTIVE','FRESH','FULL',?,?)",
                evaluation,target,org,district,source,plan,new java.sql.Timestamp(now-100),new java.sql.Timestamp(now-50));
        assertThat(gate.pilotPlan(predicted,evaluation)).isEqualTo(plan);
        assertThat(gate.pilotPlan(observed,evaluation)).isNull();
        if("replay".equals(source)) {
            for(String column:new String[]{"target_id VARCHAR","owner_org_id VARCHAR","district_id VARCHAR","source_mode VARCHAR",
                    "simulated BOOLEAN","based_on_observed_at BIGINT"})jdbc.execute("ALTER TABLE prediction_snapshot ADD "+column);
            jdbc.update("UPDATE prediction_snapshot SET target_id=?,owner_org_id=?,district_id=?,source_mode='replay',simulated=TRUE,based_on_observed_at=?",
                    target,org,district,now-100);
            jdbc.update("UPDATE rule_evaluation SET freshness_code='REPLAY'");
            assertThat(gate.pilotPlan(predicted,evaluation)).isEqualTo(plan);
            jdbc.update("UPDATE prediction_snapshot SET simulated=FALSE");
            assertThat(gate.pilotPlan(predicted,evaluation)).isNull();
            jdbc.update("UPDATE prediction_snapshot SET simulated=TRUE,based_on_observed_at=?",now-101);
            assertThat(gate.pilotPlan(predicted,evaluation)).isNull();
            jdbc.update("UPDATE prediction_snapshot SET based_on_observed_at=?",now-100);
        }
        jdbc.update("UPDATE flight_plan SET owner_org_id=?",id());
        assertThat(gate.pilotPlan(predicted,evaluation)).isNull();
        jdbc.update("UPDATE flight_plan SET owner_org_id=?,source_mode='mock'",org);
        assertThat(gate.pilotPlan(predicted,evaluation)).isNull();
        jdbc.update("UPDATE flight_plan SET source_mode=?",source);
        jdbc.update("UPDATE rule_evaluation SET observed_at=?",new java.sql.Timestamp(now-4000));
        assertThat(gate.pilotPlan(predicted,evaluation)).isNull();
        gate.configuration(configurationWithFreshness(6000));
        jdbc.update("UPDATE prediction_episode SET last_observed_at=?",now-4000);
        if("replay".equals(source)) jdbc.update("UPDATE prediction_snapshot SET based_on_observed_at=?",now-4000);
        assertThat(gate.pilotPlan(predicted,evaluation)).isEqualTo(plan);
        gate.configuration(configurationWithFreshness(2000));
        jdbc.update("UPDATE rule_evaluation SET observed_at=?",new java.sql.Timestamp(now-2500));
        jdbc.update("UPDATE prediction_episode SET last_observed_at=?",now-2500);
        if("replay".equals(source)) jdbc.update("UPDATE prediction_snapshot SET based_on_observed_at=?",now-2500);
        assertThat(gate.pilotPlan(predicted,evaluation)).isNull();
        gate.configuration(configurationWithFreshness(3000));
        jdbc.update("UPDATE prediction_episode SET last_observed_at=?",now-100);
        if("replay".equals(source)) jdbc.update("UPDATE prediction_snapshot SET based_on_observed_at=?",now-100);
        jdbc.update("UPDATE rule_evaluation SET observed_at=?",new java.sql.Timestamp(now-100));
        jdbc.update("INSERT INTO rule_evaluation VALUES (?,?,?,?,?,NULL,'ACTIVE','FRESH','NONE',?,?)",
                id(),target,org,district,source,new java.sql.Timestamp(now-10),new java.sql.Timestamp(now));
        assertThat(gate.pilotPlan(predicted,evaluation)).isNull();
    }
    private static PredictionConfiguration configurationWithFreshness(long freshness) throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode parameters=json.valueToTree(new PredictionConfiguration(json,"").parameters());
        ((com.fasterxml.jackson.databind.node.ObjectNode)parameters.get("forecast")).put("freshnessMillis",freshness);
        return new PredictionConfiguration(json,json.writeValueAsString(parameters));
    }
    private void confirm(){
        jdbc.update("UPDATE uav_event SET state_code='CONFIRMED' WHERE event_id=?",predicted);
        jdbc.update("INSERT INTO uav_event_verification VALUES (?,?,'CONFIRMED',?)",id(),predicted,id());
    }
    private static String id(){return UUID.randomUUID().toString();}
}
