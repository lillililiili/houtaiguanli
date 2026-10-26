package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/** Query contract uses real SQL on H2, or an explicitly isolated PostgreSQL test database. */
class EoAutoCaptureServiceTest {
    private JdbcTemplate jdbc;
    private JdbcTemplate root;
    private String schema;
    private DataSource database;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private TargetVideoService video;
    private EvidenceAssociationService evidence;
    private AuditService audit;

    @BeforeEach void setup() {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || url.isBlank()) {
            database = new DriverManagerDataSource("jdbc:h2:mem:" + id() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        } else {
            if (!url.matches("jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+"))
                throw new IllegalStateException("Requires isolated stage456_verify_ database without URL parameters");
            String user = System.getenv("POSTGRES_TEST_USER"), password = System.getenv("POSTGRES_TEST_PASSWORD");
            root = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            assertThat(root.queryForObject("SELECT current_database()", String.class)).matches("stage456_verify_[a-z0-9_]+");
            schema = "eo_capture_" + id().replace("-", "");
            root.execute("CREATE SCHEMA " + schema);
            database = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password);
        }
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE alarm(alarm_id VARCHAR PRIMARY KEY,target_id VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,source_mode VARCHAR)");
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR PRIMARY KEY,alarm_id VARCHAR,owner_org_id VARCHAR,district_id VARCHAR)");
        jdbc.execute("CREATE TABLE target_current_alias(historical_target_id VARCHAR PRIMARY KEY,current_target_id VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_case(risk_case_id VARCHAR PRIMARY KEY,target_id VARCHAR,owner_org_id VARCHAR,district_id VARCHAR,source_mode VARCHAR,airspace_id VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_case_member(risk_case_id VARCHAR,event_id VARCHAR,PRIMARY KEY(risk_case_id,event_id))");
        jdbc.execute("CREATE TABLE uav_risk_case_verification(verification_id VARCHAR PRIMARY KEY,risk_case_id VARCHAR,event_id VARCHAR,event_version BIGINT,conclusion VARCHAR,basis_kind VARCHAR,trigger_mode VARCHAR,actor_id VARCHAR,automation_run_id VARCHAR,created_at BIGINT)");
        jdbc.execute("CREATE TABLE uav_event_verification(history_id VARCHAR PRIMARY KEY,event_id VARCHAR,version BIGINT,previous_state VARCHAR,resulting_state VARCHAR,conclusion VARCHAR,actor_id VARCHAR,automation_run_id VARCHAR,created_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE disposal_authorization(authorization_id VARCHAR PRIMARY KEY,subject_kind VARCHAR,subject_id VARCHAR,target_id VARCHAR)");
        jdbc.execute("CREATE TABLE disposal_authorization_event(event_id VARCHAR PRIMARY KEY,authorization_id VARCHAR,event_kind VARCHAR,occurred_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE audit_log(action VARCHAR,object_type VARCHAR,object_id VARCHAR,detail VARCHAR,result VARCHAR)");
        video = mock(TargetVideoService.class);
        evidence = mock(EvidenceAssociationService.class);
        audit = mock(AuditService.class);
        doAnswer(call -> {
            jdbc.update("INSERT INTO audit_log(action,object_type,object_id,detail,result) VALUES(?,?,?,?,?)",
                    call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7), call.getArgument(8));
            return null;
        }).when(audit).record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @AfterEach void cleanup() {
        if (root != null && schema != null) root.execute("DROP SCHEMA " + schema + " CASCADE");
        else if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @ParameterizedTest @CsvSource({"PREDICTION,live", "PREDICTION,replay", "OBSERVED,live", "OBSERVED,replay"})
    void riskOnlyConfirmationRetainsFrozenEventCaseAndBasis(String basis, String mode) {
        var fact = fact(mode);
        String verification = risk(fact, basis, "MANUAL", id(), null, now, 4);
        String currentTarget = id();
        jdbc.update("INSERT INTO target_current_alias VALUES(?,?)", fact.target(), currentTarget);
        assertThat(service().pending()).singleElement().satisfies(trigger -> {
            assertThat(trigger.triggerId()).isEqualTo(verification);
            assertThat(trigger.kind()).isEqualTo(EoAutoCaptureService.RISK);
            assertThat(trigger.eventId()).isEqualTo(fact.event());
            assertThat(trigger.targetId()).isEqualTo(currentTarget);
            assertThat(trigger.riskCaseId()).isEqualTo(fact.riskCase());
            assertThat(trigger.basisKind()).isEqualTo(basis);
            assertThat(trigger.objectType()).isEqualTo("uav_risk_case_verification");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_verification", Integer.class)).isZero();
    }

    @Test void manualDoubleWriteCapturesOnceAndKeepsDifferentAreasIndependent() {
        var first = fact("live");
        String actor = id();
        String verification = risk(first, "OBSERVED", "MANUAL", actor, null, now, 8);
        legacy(first, actor, null, now, 9);
        // The second case shares a target and event but represents another independently verified area.
        var other = anotherArea(first);
        String second = risk(other, "PREDICTION", "MANUAL", actor, null, now.plusMillis(1), 9);
        assertThat(service().pending()).extracting(EoAutoCaptureService.Trigger::triggerId)
                .containsExactlyInAnyOrder(verification, second);
    }

    @Test void automaticDoubleWriteUsesTheExactRunEvenWhenTimestampsDiffer() {
        var fact = fact("live");
        String run = id();
        String verification = risk(fact, "OBSERVED", "AUTO", null, run, now, 3);
        legacy(fact, null, run, now.plusMillis(35), 4);
        String otherRun = legacy(fact, null, id(), now.plusMillis(35), 4);
        assertThat(service().pending()).extracting(EoAutoCaptureService.Trigger::triggerId)
                .containsExactlyInAnyOrder(verification, otherRun);
    }

    @ParameterizedTest @ValueSource(strings = {"actor", "time", "version"})
    void anotherLegacyConfirmationIsNotSuppressed(String difference) {
        var fact = fact("live");
        String actor = id();
        String verification = risk(fact, "OBSERVED", "MANUAL", actor, null, now, 2);
        String history = legacy(fact, difference.equals("actor") ? id() : actor, null,
                difference.equals("time") ? now.plusSeconds(1) : now, difference.equals("version") ? 4 : 3);
        assertThat(service().pending()).extracting(EoAutoCaptureService.Trigger::triggerId)
                .containsExactlyInAnyOrder(verification, history);
    }

    @ParameterizedTest @ValueSource(strings = {"MANUAL", "AUTO"})
    void oldLegacyCaptureAuditPreventsRecaptureAfterUpgrade(String mode) {
        var fact = fact("live");
        String actor = mode.equals("MANUAL") ? id() : null;
        String run = mode.equals("AUTO") ? id() : null;
        risk(fact, "OBSERVED", mode, actor, run, now, 7);
        String history = legacy(fact, actor, run, now, 8);
        jdbc.update("INSERT INTO audit_log(action,object_type,object_id) VALUES(?,?,?)",
                EoAutoCaptureService.ACTION, "uav_event_verification", history);
        if (mode.equals("MANUAL")) {
            // A prediction confirmation can never be the original event verdict, even if metadata coincide.
            String prediction = risk(anotherArea(fact), "PREDICTION", mode, actor, run, now, 7);
            assertThat(service().pending()).extracting(EoAutoCaptureService.Trigger::triggerId).containsExactly(prediction);
        } else assertThat(service().pending()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"target", "org", "district", "source", "member", "eventOrg", "eventDistrict"})
    void brokenRiskBindingCannotScheduleOrHideTheLegacyConfirmation(String broken) {
        var fact = fact("replay");
        String actor = id();
        risk(fact, "OBSERVED", "MANUAL", actor, null, now, 3);
        String history = legacy(fact, actor, null, now, 4);
        switch (broken) {
            case "target" -> jdbc.update("UPDATE uav_risk_case SET target_id=?", id());
            case "org" -> jdbc.update("UPDATE uav_risk_case SET owner_org_id=?", id());
            case "district" -> jdbc.update("UPDATE uav_risk_case SET district_id=?", id());
            case "source" -> jdbc.update("UPDATE uav_risk_case SET source_mode='live'");
            case "member" -> jdbc.update("DELETE FROM uav_risk_case_member");
            case "eventOrg" -> jdbc.update("UPDATE uav_event SET owner_org_id=?", id());
            case "eventDistrict" -> jdbc.update("UPDATE uav_event SET district_id=?", id());
            default -> throw new IllegalArgumentException(broken);
        }
        assertThat(service().pending()).extracting(EoAutoCaptureService.Trigger::triggerId).containsExactly(history);
    }

    @Test void falseAndStaleRiskConfirmationsAreNotNewCaptureTriggers() {
        var falseFact = fact("live");
        String falseId = risk(falseFact, "PREDICTION", "MANUAL", id(), null, now, 1);
        jdbc.update("UPDATE uav_risk_case_verification SET conclusion='FALSE_POSITIVE' WHERE verification_id=?", falseId);
        risk(fact("replay"), "OBSERVED", "MANUAL", id(), null, now.minusSeconds(121), 2);
        assertThat(service().pending()).isEmpty();
    }

    @Test void missingVideoIsAuditedOnceWithCaseBasisAndSurvivesServiceRestart() {
        var fact = fact("replay");
        String verification = risk(fact, "PREDICTION", "MANUAL", id(), null, now, 0);
        service().poll();
        assertThat(jdbc.queryForMap("SELECT * FROM audit_log")).satisfies(row -> {
            assertThat(row.get("object_id")).isEqualTo(verification);
            assertThat(row.get("result")).isEqualTo("SKIPPED");
            assertThat(row.get("detail").toString()).contains("risk_case_id=" + fact.riskCase(), "basis_kind=PREDICTION");
        });
        assertThat(service().pending()).isEmpty();
        verify(video).autoCaptureSource(fact.target());
        verifyNoInteractions(evidence);
    }

    @Test void counterStartRemainsIndependentFromRiskConfirmation() {
        var fact = fact("live");
        String verification = risk(fact, "OBSERVED", "MANUAL", id(), null, now, 0);
        String authorization = id(), start = id();
        jdbc.update("INSERT INTO disposal_authorization VALUES(?,'UAV_EVENT',?,?)", authorization, fact.event(), fact.target());
        jdbc.update("INSERT INTO disposal_authorization_event VALUES(?,?,'EXECUTE',?)", start, authorization, now.atOffset(ZoneOffset.UTC));
        assertThat(service().pending()).extracting(EoAutoCaptureService.Trigger::triggerId)
                .containsExactlyInAnyOrder(verification, start);
        assertThat(service().pending()).filteredOn(trigger -> trigger.kind().equals(EoAutoCaptureService.COUNTER))
                .singleElement().satisfies(trigger -> {
                    assertThat(trigger.authorizationId()).isEqualTo(authorization);
                    assertThat(trigger.eventId()).isEqualTo(fact.event());
                    assertThat(trigger.targetId()).isEqualTo(fact.target());
                });
    }

    private EoAutoCaptureService service() {
        return new EoAutoCaptureService(jdbc, video, evidence, audit,
                new TransactionTemplate(new DataSourceTransactionManager(database)),
                new AppClock(Clock.fixed(now, ZoneOffset.UTC)), true, "ffmpeg", 30);
    }

    private Fact fact(String mode) {
        var fact = new Fact(id(), id(), id(), id(), id(), id(), mode);
        jdbc.update("INSERT INTO alarm VALUES(?,?,?,?,?)", fact.alarm(), fact.target(), fact.org(), fact.district(), mode);
        jdbc.update("INSERT INTO uav_event VALUES(?,?,?,?)", fact.event(), fact.alarm(), fact.org(), fact.district());
        insertCase(fact);
        return fact;
    }

    private Fact anotherArea(Fact original) {
        var fact = new Fact(original.alarm(), original.event(), original.target(), original.org(), original.district(), id(), original.mode());
        insertCase(fact);
        return fact;
    }

    private void insertCase(Fact fact) {
        jdbc.update("INSERT INTO uav_risk_case VALUES(?,?,?,?,?,?)", fact.riskCase(), fact.target(), fact.org(), fact.district(), fact.mode(), id());
        jdbc.update("INSERT INTO uav_risk_case_member VALUES(?,?)", fact.riskCase(), fact.event());
    }

    private String risk(Fact fact, String basis, String mode, String actor, String run, Instant at, long eventVersion) {
        String verification = id();
        jdbc.update("INSERT INTO uav_risk_case_verification VALUES(?,?,?,?,'CONFIRMED',?,?,?,?,?)",
                verification, fact.riskCase(), fact.event(), eventVersion, basis, mode, actor, run, at.toEpochMilli());
        return verification;
    }

    private String legacy(Fact fact, String actor, String run, Instant at, long version) {
        String history = id();
        jdbc.update("INSERT INTO uav_event_verification VALUES(?,?,?,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED',?,?,?)",
                history, fact.event(), version, actor, run, at.atOffset(ZoneOffset.UTC));
        return history;
    }

    private record Fact(String alarm, String event, String target, String org, String district, String riskCase, String mode) { }
    private static String id() { return UUID.randomUUID().toString(); }
}
