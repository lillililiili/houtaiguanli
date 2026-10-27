package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.domain.UavVerificationBasis;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavVerificationBasisRepository;
import com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.prediction.application.PredictionEventGate;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Real verification ledger writes with mocked scope/evidence boundaries; no jobs or business seed. */
class UavRiskCaseServiceTest {
    private final UavRiskCaseIdentity identities=mock(UavRiskCaseIdentity.class);
    private final UavEventRepository events=mock(UavEventRepository.class);
    private final UavEventVerificationService rawVerification=mock(UavEventVerificationService.class);
    private final UavVerificationBasisRepository basis=mock(UavVerificationBasisRepository.class);
    private final PredictionEventGate predictions=mock(PredictionEventGate.class);
    private final AccessControlService access=mock(AccessControlService.class);
    private final IdempotencyGuard idempotency=mock(IdempotencyGuard.class);
    private final ResponseOrderService responseOrder=mock(ResponseOrderService.class);
    private final AutoSmsVerificationDispatch verifiedSms=mock(AutoSmsVerificationDispatch.class);
    private final AuditService audit=mock(AuditService.class);
    private final ObjectMapper json=new ObjectMapper();
    private final String risk=id(),event=id(),alarm=id(),area=id(),prediction=id(),actor=id();
    private final long now=1_799_999_000_000L;
    private final AtomicReference<String> selected=new AtomicReference<>();
    private String stage="PREDICTED";
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private UavRiskCaseService service;
    private UavEventRepository.EventRow eventRow;
    private AccessDecision scope;

    @BeforeEach void setup() {
        database=new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc=new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE uav_risk_case(risk_case_id VARCHAR PRIMARY KEY,version BIGINT,episode_id VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_case_verification(verification_id VARCHAR PRIMARY KEY,risk_case_id VARCHAR,case_version BIGINT,event_id VARCHAR,event_version BIGINT,conclusion VARCHAR,basis_kind VARCHAR,prediction_id VARCHAR,actor_id VARCHAR,note VARCHAR,created_at BIGINT,basis_json VARCHAR,trigger_mode VARCHAR DEFAULT 'MANUAL',automation_run_id VARCHAR)");
        jdbc.execute("CREATE TABLE prediction_actual_entry_link(event_id VARCHAR,airspace_id VARCHAR,evaluation_id VARCHAR,observed_at BIGINT,airspace_version_id VARCHAR)");
        jdbc.execute("CREATE TABLE uav_risk_case_member(risk_case_id VARCHAR,event_id VARCHAR)");
        jdbc.execute("CREATE TABLE uav_event(event_id VARCHAR PRIMARY KEY,state_code VARCHAR,version BIGINT,updated_at TIMESTAMP WITH TIME ZONE,alarm_id VARCHAR)");
        jdbc.execute("CREATE TABLE alarm(alarm_id VARCHAR PRIMARY KEY,alarm_type VARCHAR)");
        jdbc.execute("CREATE TABLE app_user(user_id VARCHAR PRIMARY KEY,name VARCHAR)");
        jdbc.execute("CREATE TABLE prediction_event_link(event_id VARCHAR,episode_id VARCHAR,role VARCHAR)");
        jdbc.execute("CREATE TABLE uav_event_verification(history_id VARCHAR PRIMARY KEY,event_id VARCHAR,version BIGINT,previous_state VARCHAR,resulting_state VARCHAR,conclusion VARCHAR,note VARCHAR,actor_id VARCHAR,created_at TIMESTAMP WITH TIME ZONE)");
        jdbc.update("INSERT INTO uav_risk_case(risk_case_id,version) VALUES (?,4)",risk);
        jdbc.update("INSERT INTO uav_event(event_id,state_code,version,alarm_id) VALUES (?,'PENDING_VERIFICATION',7,?)",event,alarm);
        jdbc.update("INSERT INTO alarm VALUES (?,'RULE_LEGALITY')",alarm);
        jdbc.update("INSERT INTO app_user VALUES (?,'Operator')",actor);
        jdbc.update("INSERT INTO uav_risk_case_member VALUES (?,?)",risk,event);
        scope=new AccessDecision(actor,ScopeMode.ALL);
        when(access.require(any())).thenReturn(scope);
        eventRow=new UavEventRepository.EventRow(event,alarm,id(),"PENDING_VERIFICATION",id(),id(),null,null,7,"replay");
        when(events.find(event,scope)).thenReturn(eventRow);
        when(events.lock(event,scope)).thenReturn(eventRow);
        var persistedEvents=new UavEventRepository(jdbc);
        when(events.update(anyString(),anyLong(),anyString(),any())).thenAnswer(call -> persistedEvents.update(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3)));
        doAnswer(call -> {
            persistedEvents.appendHistory(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6),call.getArgument(7),call.getArgument(8));
            return null;
        }).when(events).appendHistory(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),any());
        when(identities.find(risk)).thenAnswer(call -> new UavRiskCaseIdentity.CaseRef(risk,risk,area,stage,event,List.of(event),false,true,
                jdbc.queryForObject("SELECT version FROM uav_risk_case WHERE risk_case_id=?",Long.class,risk)));
        when(identities.details(risk)).thenAnswer(call -> {
            var results=jdbc.queryForList("SELECT conclusion FROM uav_risk_case_verification WHERE risk_case_id=? ORDER BY case_version DESC",String.class,risk);
            return Map.of("verification_status",results.isEmpty()?"PENDING_VERIFICATION":results.get(0),"latest_prediction_id",prediction);
        });
        when(identities.withCase(anyString(),any())).thenAnswer(call -> {
            String previous=selected.get();selected.set(call.getArgument(0));
            try{return ((Supplier<?>)call.getArgument(1)).get();}finally{selected.set(previous);}
        });
        var clock=new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));
        service=spy(new UavRiskCaseService(identities,rawVerification,events,null,null,basis,predictions,access,idempotency,jdbc,json,clock,audit));
        doReturn(Map.of("risk_case_id",risk)).when(service).detail(risk);
        service.responseOrder(responseOrder);
        service.verifiedSms(verifiedSms);
        AuthContext.set(new AuthUser(actor,"isolated-operator","Operator",id(),0,false,"ALL"));
    }
    @AfterEach void close(){AuthContext.clear();database.shutdown();}

    @ParameterizedTest @MethodSource("invalidInputs")
    void strictParametersAndVersionsRejectBeforeRecording(String raw,HttpStatus status) {
        assertThatThrownBy(() -> service.verify(risk,raw,id())).isInstanceOfSatisfying(ApiException.class,ex -> assertThat(ex.getStatus()).isEqualTo(status));
        unchanged();verifyNoInteractions(responseOrder);
    }
    static Stream<Arguments> invalidInputs(){
        String valid="{\"conclusion\":\"CONFIRMED\",\"expected_version\":7,\"expected_case_version\":4}";
        return Stream.of(
                Arguments.of("[]",HttpStatus.BAD_REQUEST),Arguments.of(valid+" {}",HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("7,","7,\"unexpected\":true,"),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("7,","7,\"conclusion\":\"FALSE_POSITIVE\","),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("7,","7,\"risk_case_id\":\"other\","),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("7,","7,\"risk_case_id\":null,"),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("7,","7,\"note\":false,"),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("7,","7,\"prediction_id\":13,"),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("\"CONFIRMED\"","true"),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("CONFIRMED","AUTO_CONFIRMED"),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace("7,","7,\"note\":\""+"x".repeat(1001)+"\","),HttpStatus.BAD_REQUEST),
                Arguments.of(valid.replace(":7,",":6,"),HttpStatus.CONFLICT),
                Arguments.of(valid.replace(":7,",":\"7\","),HttpStatus.CONFLICT),
                Arguments.of(valid.replace(":4}",":4.0}"),HttpStatus.CONFLICT),
                Arguments.of(valid.replace(":4}",":3}"),HttpStatus.CONFLICT),
                Arguments.of(valid.replace(":4}",":9223372036854775808}"),HttpStatus.CONFLICT),
                Arguments.of("{\"conclusion\":\"CONFIRMED\"}",HttpStatus.CONFLICT));
    }

    @Test void permissionAndDataScopeAreRequiredBeforeEvidenceOrMutation() {
        when(access.require(PermissionCode.ALARM_VERIFY)).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","denied"));
        assertThatThrownBy(() -> service.verify(risk,body("CONFIRMED",4),id())).isInstanceOfSatisfying(ApiException.class,ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(identities,never()).find(anyString());verifyNoInteractions(idempotency,predictions,responseOrder);
        doReturn(scope).when(access).require(PermissionCode.ALARM_VERIFY);
        when(events.find(event,scope)).thenReturn(null);
        assertThatThrownBy(() -> service.verify(risk,body("CONFIRMED",4),id())).isInstanceOfSatisfying(ApiException.class,ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        unchanged();
    }

    @Test void confirmationIsOncePerCaseAndNeverRewritesOriginalVerdict() throws Exception {
        when(predictions.confirmationBlock(event)).thenReturn("");
        doAnswer(call -> {
            assertThat(selected.get()).isEqualTo(risk);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification",Long.class)).isEqualTo(1);
            return null;
        }).when(responseOrder).ensureLocked(eventRow);
        service.verify(risk,body("CONFIRMED",4),id());
        var row=jdbc.queryForMap("SELECT * FROM uav_risk_case_verification");
        assertThat(row.get("actor_id")).isEqualTo(actor);
        assertThat(row.get("basis_kind")).isEqualTo("PREDICTION");
        var frozen=json.readTree(row.get("basis_json").toString());
        assertThat(frozen.path("disposition_only").asBoolean()).isTrue();
        assertThat(frozen.path("selected_prediction_id").asText()).isEqualTo(prediction);
        assertThat(frozen.path("checked_prediction_id").asText()).isEqualTo(prediction);
        assertThat(selected.get()).isNull();
        stage="ENTERED";
        assertThatThrownBy(() -> service.verify(risk,body("CONFIRMED",5),id())).isInstanceOf(ApiException.class).hasMessageContaining("已核实");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event",String.class)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT version FROM uav_event",Long.class)).isEqualTo(7);
        verifyNoInteractions(rawVerification,basis);verify(responseOrder).ensureLocked(eventRow);
        verify(verifiedSms,times(1)).verified(event,risk);
    }

    @Test void measuredConfirmationRequiresTheSelectedAreaAndFreezesOnlyItsEntryEvidence() throws Exception {
        stage="ENTERED";
        when(basis.facts(event,now)).thenReturn(new UavVerificationBasis.Facts(true,now-1000,now,now,60,0,now));
        when(identities.currentMeasuredArea(any())).thenReturn(false);
        assertThatThrownBy(() -> service.verify(risk,body("CONFIRMED",4),id())).isInstanceOf(ApiException.class).hasMessageContaining("本禁飞区");
        unchanged();
        String evaluation=id(),areaVersion=id();
        jdbc.update("INSERT INTO prediction_actual_entry_link VALUES (?,?,?,?,?)",event,area,evaluation,now,areaVersion);
        jdbc.update("INSERT INTO prediction_actual_entry_link VALUES (?,?,?,?,?)",event,id(),id(),now+1,id());
        when(identities.currentMeasuredArea(any())).thenAnswer(call -> area.equals(((UavRiskCaseIdentity.CaseRef)call.getArgument(0)).airspaceId()));
        service.verify(risk,body("CONFIRMED",4),id());
        var frozen=json.readTree(jdbc.queryForObject("SELECT basis_json FROM uav_risk_case_verification",String.class));
        assertThat(frozen.path("basis_kind").asText()).isEqualTo("OBSERVED");
        assertThat(frozen.path("measured_entries").size()).isEqualTo(1);
        assertThat(frozen.path("measured_entries").get(0).toString()).contains(evaluation,areaVersion);
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,event)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_verification",Integer.class)).isZero();
        verifyNoInteractions(predictions,rawVerification);
    }

    @ParameterizedTest @ValueSource(strings={"CONFIRMED","FALSE_POSITIVE"})
    void uniqueIndependentMeasuredVerificationPreservesOriginalVerdictAndReadableHumanHistory(String conclusion) throws Exception {
        independentMeasured();
        String request=body(conclusion,4).replace("\"conclusion\":", "\"note\":\"已核对本次实测依据\",\"conclusion\":");
        service.verify(risk,request,id());
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,event)).isEqualTo(conclusion);
        assertThat(jdbc.queryForObject("SELECT version FROM uav_event WHERE event_id=?",Long.class,event)).isEqualTo(8);
        var history=new com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository(jdbc).eventVerifications(event);
        assertThat(history).singleElement().satisfies(row -> {
            assertThat(row.conclusion()).isEqualTo(conclusion);
            assertThat(row.resultingState()).isEqualTo(conclusion);
            assertThat(row.actorId()).isEqualTo(actor);
            assertThat(row.actorName()).isEqualTo("Operator");
            assertThat(row.note()).isEqualTo("已核对本次实测依据");
            assertThat(row.version()).isEqualTo(8);
            assertThat(row.createdAt().toInstant().toEpochMilli()).isEqualTo(now);
        });
        assertThat(jdbc.queryForObject("SELECT conclusion FROM uav_risk_case_verification",String.class)).isEqualTo(conclusion);
        var frozen=json.readTree(jdbc.queryForObject("SELECT basis_json FROM uav_risk_case_verification",String.class));
        assertThat(frozen.path("disposition_only").asBoolean()).isFalse();
        assertThatThrownBy(() -> service.verify(risk,body(conclusion,5),id())).isInstanceOf(ApiException.class).hasMessageContaining("已核实");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_verification",Integer.class)).isEqualTo(1);
        if("CONFIRMED".equals(conclusion)) {
            verify(basis).facts(event,now);
            var handoffs=mock(com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.class);
            var completions=mock(com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort.class);
            when(completions.punishmentCompletion(event)).thenReturn(new com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort.PunishmentCompletion(actor,"COUNTERMEASURE_COMPLETED"));
            var confirmed=new UavEventRepository.EventRow(event,alarm,eventRow.targetId(),conclusion,eventRow.ownerOrgId(),eventRow.districtId(),null,null,8,"replay");
            when(events.lock(eq(event),any())).thenReturn(confirmed);
            var handoffService=new com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService(access,handoffs,null,null,idempotency,
                    new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),audit,json,completions,events,null,null,null,null,null,null,null,null,null,
                    mock(com.uav.lowaltitude.modules.handoff.infrastructure.HandoffFollowupRepository.class));
            // It now passes the actual-event verdict guard and reaches the independent recipient check.
            assertThatThrownBy(() -> handoffService.create("{\"source_kind\":\"UAV_EVENT\",\"source_id\":\""+event+"\",\"handoff_type\":\"UAV_PUNISHMENT\",\"expected_version\":8}",id()))
                    .isInstanceOfSatisfying(ApiException.class,ex -> assertThat(ex.getCode()).isEqualTo("RECIPIENT_NOT_CONFIGURED"));
        } else verifyNoInteractions(basis);
    }

    @ParameterizedTest @ValueSource(strings={"CONFIRMED","FALSE_POSITIVE"})
    void oneOfMultipleMeasuredAreasNeverRewritesTheSharedEventVerdict(String conclusion) {
        independentMeasured();
        var current=identities.find(risk);
        doReturn(List.of(current,new UavRiskCaseIdentity.CaseRef(id(),null,id(),"ENTERED",event,List.of(event),false,false,0))).when(identities).allCases(event);
        service.verify(risk,body(conclusion,4),id());
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,event)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_verification",Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"PREDICTION_LINK","HISTORICAL_CASE","PREDICTED_ALARM"})
    void HistoricalPredictionMembersCannotBePromotedToMeasuredPunishmentEvidence(String origin) {
        independentMeasured();
        String historicalEvent=id(),historicalAlarm=id();
        jdbc.update("INSERT INTO uav_event(event_id,state_code,version,alarm_id) VALUES (?,'PENDING_VERIFICATION',0,?)",historicalEvent,historicalAlarm);
        jdbc.update("INSERT INTO alarm VALUES (?,?)",historicalAlarm,"PREDICTED_ALARM".equals(origin)?"PREDICTED_AIRSPACE_ENTRY":"RULE_LEGALITY");
        jdbc.update("INSERT INTO uav_risk_case_member VALUES (?,?)",risk,historicalEvent);
        if("PREDICTION_LINK".equals(origin)) jdbc.update("INSERT INTO prediction_event_link VALUES (?,?,'OBSERVED')",historicalEvent,id());
        if("HISTORICAL_CASE".equals(origin)) {
            String oldCase=id();
            jdbc.update("INSERT INTO uav_risk_case(risk_case_id,version,episode_id) VALUES (?,0,?)",oldCase,id());
            jdbc.update("INSERT INTO uav_risk_case_member VALUES (?,?)",oldCase,historicalEvent);
        }
        service.verify(risk,body("CONFIRMED",4),id());
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event WHERE event_id=?",String.class,event)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_verification",Integer.class)).isZero();
    }

    private void independentMeasured() {
        stage="ENTERED";
        when(identities.find(risk)).thenAnswer(call -> new UavRiskCaseIdentity.CaseRef(risk,null,area,stage,event,List.of(event),false,false,
                jdbc.queryForObject("SELECT version FROM uav_risk_case WHERE risk_case_id=?",Long.class,risk)));
        when(identities.allCases(event)).thenAnswer(call -> List.of(identities.find(risk)));
        when(identities.currentMeasuredArea(any())).thenReturn(true);
        when(basis.facts(event,now)).thenReturn(new UavVerificationBasis.Facts(true,now-1000,now,now,60,0,now));
    }

    @ParameterizedTest @ValueSource(strings={"UNKNOWN","CLEARED","ENDED"})
    void invalidRiskCannotBeConfirmedButHumanFalsePositiveRemainsRecordable(String unavailable) {
        stage=unavailable;
        assertThatThrownBy(() -> service.verify(risk,body("CONFIRMED",4),id())).isInstanceOf(ApiException.class).hasMessageContaining("依据不足");
        unchanged();
        service.verify(risk,body("FALSE_POSITIVE",4),id());
        assertThat(jdbc.queryForObject("SELECT conclusion FROM uav_risk_case_verification",String.class)).isEqualTo("FALSE_POSITIVE");
        verifyNoInteractions(responseOrder,basis,predictions,rawVerification);
    }

    @Test void caseConfirmationCannotSupplyTheActualVerdictForAutomaticOrManualPunishment() {
        service.verify(risk,body("CONFIRMED",4),id());
        var handoffs=mock(com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.class);
        var completions=mock(com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort.class);
        var materials=mock(com.uav.lowaltitude.modules.handoff.application.HandoffMaterialAssembler.class);
        var automation=mock(com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy.class);
        var legacy=mock(com.uav.lowaltitude.modules.handoff.infrastructure.HandoffFollowupRepository.class);
        // Even an otherwise qualifying completion cannot borrow the risk-only human confirmation.
        when(completions.punishmentCompletion(event)).thenReturn(new com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort.PunishmentCompletion(actor,"COUNTERMEASURE_COMPLETED"));
        when(handoffs.enabledRecipients("UAV_PUNISHMENT")).thenReturn(List.of(mock(com.uav.lowaltitude.modules.handoff.infrastructure.HandoffRepository.RecipientRow.class)));
        when(events.lock(eq(event),any())).thenReturn(eventRow);
        var handoffService=new com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService(access,handoffs,null,null,
                idempotency,new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),audit,json,completions,events,materials,
                null,null,null,automation,null,null,null,null,legacy);
        handoffService.automaticAfterDisposal(event);
        assertThatThrownBy(() -> handoffService.create("{\"source_kind\":\"UAV_EVENT\",\"source_id\":\""+event+"\",\"handoff_type\":\"UAV_PUNISHMENT\",\"expected_version\":7}",id()))
                .isInstanceOfSatisfying(ApiException.class,ex -> assertThat(ex.getCode()).isEqualTo("INVALID_TRANSITION"));
        verify(handoffs,never()).insertHandoff(any());
        verifyNoInteractions(materials);
        assertThat(jdbc.queryForObject("SELECT conclusion FROM uav_risk_case_verification",String.class)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event",String.class)).isEqualTo("PENDING_VERIFICATION");
    }
    private String body(String conclusion,long version){return "{\"conclusion\":\""+conclusion+"\",\"expected_version\":7,\"expected_case_version\":"+version+",\"prediction_id\":\""+prediction+"\",\"risk_case_id\":\""+risk+"\"}";}
    private void unchanged(){
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_risk_case_verification",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM uav_risk_case",Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT state_code FROM uav_event",String.class)).isEqualTo("PENDING_VERIFICATION");
    }
    private static String id(){return UUID.randomUUID().toString();}
}
