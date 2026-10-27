package com.uav.lowaltitude.modules.disposal.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.uav.lowaltitude.modules.disposal.application.DisposalCompletionAdapter;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Synthetic completion facts only; no application, device dispatch or notification channel. */
class DisposalPredictionOriginTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DisposalRepository repository;
    private DisposalCompletionAdapter adapter;
    private String event, authorization, actor, device, command;
    private final long now=1_799_999_000_000L;

    @BeforeEach void setup() {
        database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc=new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE disposal_authorization(authorization_id VARCHAR PRIMARY KEY,subject_kind VARCHAR,subject_id VARCHAR,action_type VARCHAR,status VARCHAR,channel VARCHAR,chained_from_authorization_id VARCHAR,risk_case_id VARCHAR,requested_by VARCHAR,requested_at TIMESTAMP,device_id VARCHAR,execution_command_id VARCHAR)");
        jdbc.execute("CREATE TABLE prediction_event_link(event_id VARCHAR,episode_id VARCHAR,role VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_case(risk_case_id VARCHAR PRIMARY KEY,episode_id VARCHAR,superseded_by VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_case_member(risk_case_id VARCHAR,event_id VARCHAR,role VARCHAR,created_at BIGINT)");
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR,alarm_id VARCHAR)");
        jdbc.execute("CREATE TABLE alarm(alarm_id VARCHAR,alarm_type VARCHAR)");
        jdbc.execute("CREATE TABLE disposal_device_run(authorization_id VARCHAR,device_id VARCHAR,off_command_id VARCHAR)");
        jdbc.execute("CREATE TABLE device_command(command_id VARCHAR,device_id VARCHAR,status VARCHAR,completed_at BIGINT)");
        jdbc.execute("CREATE TABLE countermeasure_4ch_command(command_id VARCHAR,authorization_id VARCHAR,action VARCHAR,mask INT)");
        jdbc.execute("CREATE TABLE disposal_authorization_event(authorization_id VARCHAR,event_kind VARCHAR,occurred_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE handoff(source_kind VARCHAR,source_id VARCHAR,handoff_type VARCHAR,risk_case_id VARCHAR)");
        repository=new DisposalRepository(jdbc,new DataSourceTransactionManager(database));
        var origin=spy(new com.uav.lowaltitude.modules.handoff.application.MeasuredPunishmentBasis(jdbc,
                mock(com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity.class),new com.uav.lowaltitude.platform.time.AppClock()));
        // H2 verifies the repository's completion/origin boundary. Independent spatial proof is
        // exercised with real migrations, MEAS lineage and device-off receipts in the PostGIS suite.
        doReturn(null).when(origin).resolve(anyString(),anyString(),any());
        repository.measuredPunishment(origin);
        adapter=new DisposalCompletionAdapter(repository);
        event=id();authorization=id();actor=id();device=id();command=id();
        jdbc.update("INSERT INTO uav_event VALUES (?,?)",event,event);
        jdbc.update("INSERT INTO alarm VALUES (?,'RULE_LEGALITY')",event);
    }
    @AfterEach void close(){database.shutdown();}

    @ParameterizedTest @ValueSource(strings={"COUNTERMEASURE","JAMMING"})
    void predictionDerivedCompletionNeedsIndependentSpatialProofBeforeAndAfterMeasuredEntry(String action) {
        completed(action);
        String predictedCase=caseRow(id(),null);
        jdbc.update("UPDATE disposal_authorization SET risk_case_id=?",predictedCase);
        jdbc.update("INSERT INTO prediction_event_link VALUES (?,?,'OBSERVED')",event,id());
        rejected();
        jdbc.update("UPDATE prediction_event_link SET role='PREDICTED'");
        rejected();
    }

    @ParameterizedTest @ValueSource(strings={"COUNTERMEASURE","JAMMING"})
    void supersededAndLegacyUnscopedAuthorizationsCannotBorrowPredictionCompletion(String action) {
        completed(action);
        String predictedCase=caseRow(id(),null);
        String originalCase=caseRow(null,predictedCase);
        jdbc.update("UPDATE disposal_authorization SET risk_case_id=?",originalCase);
        rejected();
        jdbc.update("UPDATE disposal_authorization SET risk_case_id=NULL");
        jdbc.update("INSERT INTO prediction_event_link VALUES (?,?,'OBSERVED')",event,id());
        rejected();
    }

    @ParameterizedTest @ValueSource(strings={"COUNTERMEASURE","JAMMING"})
    void ordinaryMeasuredCompletionKeepsOriginalEligibility(String action) {
        completed(action);
        eligible(action);
        String independentCase=caseRow(null,null);
        jdbc.update("UPDATE disposal_authorization SET risk_case_id=?",independentCase);
        eligible(action);
    }

    private void completed(String action) {
        jdbc.update("INSERT INTO disposal_authorization VALUES (?,'UAV_EVENT',?,?,'COMPLETED','COUNTERMEASURE_4CH',NULL,NULL,?,?,?,?)",authorization,event,action,actor,new Timestamp(now),device,command);
        jdbc.update("INSERT INTO device_command VALUES (?,?,'SUCCEEDED',?)",command,device,now);
        jdbc.update("INSERT INTO disposal_device_run VALUES (?,?,?)",authorization,device,command);
        jdbc.update("INSERT INTO countermeasure_4ch_command VALUES (?,?,'SET_MASK',0)",command,authorization);
        jdbc.update("INSERT INTO disposal_authorization_event VALUES (?,'COMPLETE',?)",authorization,new Timestamp(now));
    }
    private String caseRow(String episode,String successor) {
        String risk=id();jdbc.update("INSERT INTO uav_risk_case VALUES (?,?,?)",risk,episode,successor);
        jdbc.update("INSERT INTO uav_risk_case_member VALUES (?,?,'OBSERVED',?)",risk,event,now);return risk;
    }
    private void rejected() {
        assertThat(repository.punishmentCompletion(event)).isNull();
        assertThat(repository.completedWithoutPunishment()).doesNotContain(event);
        assertThat(adapter.punishmentCompletion(event)).isNull();
        assertThat(adapter.jammingCompletedWithoutPunishment()).doesNotContain(event);
        assertThat(adapter.completedJammingRequester(event)).isNull();
        assertThat(adapter.completedExists("UAV_EVENT",event)).as("completion history remains available").isTrue();
    }
    private void eligible(String action) {
        assertThat(repository.punishmentCompletion(event).authorizationId()).isEqualTo(authorization);
        assertThat(repository.completedWithoutPunishment()).contains(event);
        assertThat(adapter.punishmentCompletion(event).requestedBy()).isEqualTo(actor);
        assertThat(adapter.completedJammingRequester(event)).isEqualTo("JAMMING".equals(action)?actor:null);
    }
    private static String id(){return UUID.randomUUID().toString();}
}
