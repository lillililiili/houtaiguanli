package com.uav.lowaltitude.modules.disposal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AlarmRuleCounter;
import com.uav.lowaltitude.modules.alarm.application.UavAdvisoryService;
import com.uav.lowaltitude.modules.alarm.application.AutomaticCounterNotificationGate;
import com.uav.lowaltitude.modules.alarm.application.AutoSmsPolicy;
import com.uav.lowaltitude.modules.alarm.application.AutoVoicePolicy;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording;
import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch;
import com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Presence;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeService;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeWorker;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.device.application.Countermeasure4ChControlService;
import com.uav.lowaltitude.modules.device.infrastructure.Countermeasure4ChControlRepository;
import com.uav.lowaltitude.modules.disposal.api.CounterEvidenceFixture;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.directory.application.NotificationDirectoryService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

/** Full automatic transaction and actual loopback protocol; every authorization, lock and dispatch guard is real. */
@SpringBootTest(properties = {"app.network.allow-loopback-when-listed=true"})
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AutomaticCounterFlowPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.automation-rules.enabled", () -> true);
        registry.add("app.qa.device-setup.enabled", () -> true);
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AutomationRuntimeService service;
    @Autowired AutomationRuntimeWorker worker;
    @Autowired AutomationRuntimeRepository runs;
    @Autowired AlarmRuleCounter counter;
    @Autowired UavAdvisoryService advisory;
    @Autowired DisposalRepository repository;
    @Autowired DisposalAuthorizationService manual;
    @Autowired Countermeasure4ChControlService controls;
    @Autowired DisposalReceiptSync receipts;
    @Autowired DisposalDeviceRunTimer timer;
    @Autowired EmergencyStopService stops;
    @Autowired DisposalExecutionGateway gateway;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRows;
    @MockitoSpyBean Countermeasure4ChControlRepository commandRows;
    @MockitoSpyBean AutoSmsPolicy smsPolicy;
    @MockitoSpyBean AutoVoicePolicy voicePolicy;
    @MockitoSpyBean AdvisoryVoiceRecording recordings;
    @MockitoSpyBean PilotDepartureWatch departure;
    @MockitoSpyBean NotificationDirectoryService directory;
    @MockitoSpyBean com.uav.lowaltitude.platform.time.AppClock clock;
    @Autowired AutomaticCounterNotificationGate notificationGate;
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";
    private String event, device;
    private AuthUser operator;
    private ServerSocket receiver;
    private java.util.concurrent.ExecutorService network;
    private volatile String failureReply;
    private final List<String> frames = new CopyOnWriteArrayList<>();

    @BeforeEach void fixture() throws Exception {
        receiver = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        network = Executors.newSingleThreadExecutor();
        network.submit(() -> {
            while (!receiver.isClosed()) {
                try (Socket socket = receiver.accept()) {
                    socket.setSoTimeout(3000);
                    String frame = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    frames.add(frame);
                    int mask = Integer.parseInt(frame.trim().split("\\s+")[6], 16);
                    if (mask != 0 && "ADAPTER_TIMEOUT".equals(failureReply)) continue;
                    if (mask != 0 && "COUNTERMEASURE_STATE_MISMATCH".equals(failureReply)) mask = 0;
                    String response = String.format("22 01 13 00 00 00 %02X %02X\r\n", mask, (0x22 + 1 + 0x13 + mask) & 255);
                    if (mask != 0 && "PROTOCOL_FRAME_INVALID".equals(failureReply)) response = "22 01 13 00 00 00 0F 00\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (java.io.IOException closed) { if (!receiver.isClosed()) throw new IllegalStateException(closed); }
            }
        });
        long now = System.currentTimeMillis();
        String source = key(), alarm = key(); event = key(); device = key();
        Timestamp at = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES (?,?,?,TRUE,'mock',?,?,0)", source, source, "isolated counter flow", at, at);
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES (?,NULL,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)", alarm, source, alarm, at, at, ORG, DISTRICT, at);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,'CONFIRMED',?,?,?,?,1)", event, alarm, ORG, DISTRICT, at, at);
        CounterEvidenceFixture.seed(jdbc, event);
        String riskRun = key();
        jdbc.update("INSERT INTO rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,source_mode,created_at) SELECT ?,r.rule_set_id,r.rule_set_version_id,'ACTIVE','SCHEDULED',r.as_of,CURRENT_TIMESTAMP,'DONE','mock',CURRENT_TIMESTAMP FROM rule_run r JOIN rule_evaluation e ON e.run_id=r.run_id WHERE e.alarm_id=? ORDER BY e.evaluated_at DESC LIMIT 1", riskRun, alarm);
        jdbc.update("INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons) SELECT ?,?,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,CURRENT_TIMESTAMP,freshness_code,plan_match_code,legal_status,90,'HIGH',violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,CURRENT_TIMESTAMP,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons FROM rule_evaluation WHERE alarm_id=? ORDER BY evaluated_at DESC LIMIT 1", key(), riskRun, alarm);
        String opsSource = key();
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,enabled,allowed_cidrs,simulated,version,created_at,updated_at) VALUES (?,?,?,'COUNTERMEASURE_TCP_4CH_V2_0','2.0','live',TRUE,'127.0.0.1/32',TRUE,0,?,?)", opsSource, opsSource, "isolated controller", now, now);
        jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) VALUES (?,?,?,?,?,'countermeasure','反制','反制直连',TRUE,'live',TRUE,0,?,?)", device, opsSource, device, device, "isolated controller", now, now);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", device, ORG, DISTRICT);
        jdbc.update("INSERT INTO device_connection_profile(device_id,transport,host,port,timeout_millis,retry_count,version,updated_at) VALUES (?,'TCP','127.0.0.1',?,3000,0,0,?)", device, receiver.getLocalPort(), now);
        jdbc.update("INSERT INTO countermeasure_4ch_profile(device_id,device_address,wire_encoding,poll_interval_millis,version,updated_at) VALUES (?,1,'ASCII_HEX_SPACED',5000,0,?)", device, now);
        jdbc.update("INSERT INTO ops_device_state(device_id,connectivity,has_alarm,health_code,observed_at,received_at,simulated,version) VALUES (?,'ONLINE',FALSE,'UNKNOWN',?,?,TRUE,0)", device, now, now);
        jdbc.update("INSERT INTO audit_log(audit_id,action,object_type,object_id,occurred_at,result) VALUES (?,'local_qa_device_prepare','device',?,?,'SUCCESS')", key(), device, now);
        jdbc.update("UPDATE automation_rule_condition SET enabled=FALSE");
        jdbc.update("UPDATE automation_rule_group SET version=version+1,scope_mode='ALL',schedule_mode='ALL_DAY',actions_json='[]' WHERE category='counter'");
        rule("counterFreshness", "60", now); rule("riskLevel", "高风险", now);
        operator = repository.actor(jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class));
        service.evaluate("counter", event);
        assertThat(runs.state("counter", event).status()).isEqualTo("PASS");
        assertThat(advisory.phaseForAutomation(event)).isEqualTo(NotifyFlow.Phase.AWAIT_COUNTER);
    }

    @AfterEach void cleanup() throws Exception {
        AuthContext.clear();
        if (device != null) jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?", device);
        if (event != null) jdbc.update("UPDATE uav_event SET state_code='FALSE_POSITIVE' WHERE event_id=?", event);
        if (receiver != null) receiver.close();
        if (network != null) { network.shutdownNow(); network.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void workerCreatesOneBoundAuthorizationAndRealStartAndStopReceipts() {
        worker.poll(); worker.poll();
        var row = onlyAuthorization();
        assertThat(row.status()).isEqualTo("EXECUTING");
        assertThat(row.requestedBy()).isEqualTo(AutomationPrincipal.USER_ID);
        assertThat(row.approvedBy()).isNull();
        assertThat(repository.events(row.authorizationId()).get(0).snapshot()).contains("RULE_COUNTER_V2", "group_version", "automation_run_id");
        controls.dispatch(row.executionCommandId());
        receipts.syncByCommand(row.executionCommandId());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, row.executionCommandId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_receipt WHERE command_id=?", Integer.class, row.executionCommandId())).isEqualTo(1);
        assertThat(onlyAuthorization().status()).isEqualTo("EXECUTING");
        jdbc.update("UPDATE disposal_device_run SET on_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes',off_due_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE authorization_id=?", row.authorizationId());
        timer.tick();
        String off = onlyAuthorization().executionCommandId();
        assertThat(off).isNotEqualTo(row.executionCommandId());
        controls.dispatch(off); receipts.syncByCommand(off);
        assertThat(onlyAuthorization().status()).isEqualTo("COMPLETED");
        worker.poll();
        assertThat(repository.counterAttempts(event)).hasSize(1);
        assertThat(frames).hasSize(2);
        assertThat(frames.get(0)).contains("00 0F");
        assertThat(frames.get(1)).contains("00 00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE subject_id=? AND action_type='JAMMING'", Integer.class, event)).isZero();
    }

    @Test void twoWorkersCompetingForOneEventCreateOneAuthorizationAndOneCommand() throws Exception {
        race(() -> launch(), () -> launch());
        assertOneCommand();
    }

    @Test void manualAndAutomaticLaunchShareTheEventLockAndNeverDoubleIssue() throws Exception {
        race(() -> launch(), () -> {
            AuthContext.set(operator);
            try { manual.directExecute(request(), key()); }
            catch (ApiException conflict) { assertThat(conflict.getCode()).isEqualTo("ACTIVE_AUTHORIZATION_EXISTS"); }
            finally { AuthContext.clear(); }
        });
        assertOneCommand();
    }

    @Test void ruleVersionChangeCancelsQueuedStartWithoutNetworkTrafficOrRevivingIt() {
        launch();
        var row = onlyAuthorization();
        jdbc.update("UPDATE automation_rule_group SET version=version+1 WHERE category='counter'");
        service.evaluate("counter", event);
        controls.dispatch(row.executionCommandId()); receipts.syncByCommand(row.executionCommandId());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, row.executionCommandId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT issued_at FROM device_command WHERE command_id=?", Long.class, row.executionCommandId())).isNull();
        launch();
        assertThat(repository.counterAttempts(event)).hasSize(1);
        assertThat(frames).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RULE_DISABLED", "RULE_CHANGED", "OBSERVATION_STALE", "DEVICE_BAD", "DEVICE_OFFLINE", "DEVICE_DISABLED"})
    void queuedStartRechecksSavedRulesEvidenceAndDeviceBeforeAnySocketWrite(String change) {
        launch();
        var row = onlyAuthorization();
        var originalState = runs.state("counter", event);
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, row.executionCommandId())).isEqualTo("QUEUED");
        switch (change) {
            case "RULE_DISABLED" -> {
                jdbc.update("UPDATE automation_rule_condition SET enabled=FALSE WHERE category='counter'");
                jdbc.update("UPDATE automation_rule_group SET version=version+1 WHERE category='counter'");
            }
            case "RULE_CHANGED" -> {
                jdbc.update("UPDATE automation_rule_condition SET value_text='0' WHERE category='counter' AND item_code='counterFreshness'");
                jdbc.update("UPDATE automation_rule_group SET version=version+1 WHERE category='counter'");
            }
            case "OBSERVATION_STALE" -> {
                Instant later = clock.now().plusSeconds(61);
                doReturn(later).when(clock).now();
                doReturn(false).when(clock).usesSystemClock();
                // Keep device freshness and the authorization window valid, isolating target evidence expiry.
                jdbc.update("UPDATE ops_device_state SET observed_at=?,received_at=? WHERE device_id=?", later.toEpochMilli(), later.toEpochMilli(), device);
                assertThat(row.validUntil().toInstant()).isAfter(later);
            }
            case "DEVICE_BAD" -> jdbc.update("UPDATE ops_device_state SET health_code='BAD' WHERE device_id=?", device);
            case "DEVICE_OFFLINE" -> jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE' WHERE device_id=?", device);
            case "DEVICE_DISABLED" -> jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?", device);
            default -> throw new IllegalArgumentException(change);
        }
        // No evaluator poll between configuration/evidence changes and dispatch: the stored PASS is still old.
        assertThat(runs.state("counter", event).runId()).isEqualTo(originalState.runId());
        assertThat(runs.state("counter", event).status()).isEqualTo("PASS");
        controls.dispatch(row.executionCommandId());
        receipts.syncByCommand(row.executionCommandId());
        assertCancelledBeforeSend(row.executionCommandId());
        launch();
        assertThat(repository.counterAttempts(event)).hasSize(1);
        assertThat(frames).isEmpty();
    }

    @Test void threeNeverSentAuthorizationsExhaustTheAutomaticAttemptLimit() throws Exception {
        var originalState = runs.state("counter", event);
        var authorizationIds = new java.util.HashSet<String>();
        var commands = new java.util.ArrayList<String>();
        for (int attempt = 1; attempt <= 3; attempt++) {
            launch();
            var rows = repository.counterAttempts(event);
            assertThat(rows).hasSize(attempt);
            var row = rows.stream().filter(candidate -> !authorizationIds.contains(candidate.authorizationId())).findFirst().orElseThrow();
            assertThat(authorizationIds.add(row.authorizationId())).isTrue();
            commands.add(row.executionCommandId());
            var grants = repository.events(row.authorizationId()).stream().filter(record -> "DIRECT_AUTHORIZE".equals(record.eventKind())).toList();
            assertThat(grants).hasSize(1);
            var grant = json.readTree(grants.get(0).snapshot());
            assertThat(grant.path("automation_contract").asText()).isEqualTo("RULE_COUNTER_V2");
            assertThat(grant.path("automation_run_id").asText()).isEqualTo(originalState.runId());
            assertThat(grant.path("group_version").asLong()).isEqualTo(originalState.version());
            assertThat(grant.path("automatic_attempt").asInt()).isEqualTo(attempt);

            jdbc.update("UPDATE ops_device_state SET health_code='BAD' WHERE device_id=?", device);
            controls.dispatch(row.executionCommandId());
            receipts.syncByCommand(row.executionCommandId());
            assertCancelledBeforeSend(row.executionCommandId());
            var failed = repository.findUnlocked(row.authorizationId());
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(repository.automaticStartNeverIssued(failed)).isTrue();
            jdbc.update("UPDATE ops_device_state SET health_code='UNKNOWN' WHERE device_id=?", device);
        }
        // Conditions recover, but the same event has already consumed all three total attempts.
        launch(); launch();
        assertThat(repository.counterAttempts(event)).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?", Integer.class, device)).isEqualTo(3);
        for (String command : commands) { controls.dispatch(command); assertCancelledBeforeSend(command); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM countermeasure_start_attempt WHERE device_id=?", Integer.class, device)).isZero();
        assertThat(frames).isEmpty();
    }

    private void assertCancelledBeforeSend(String command) {
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, command)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT issued_at FROM device_command WHERE command_id=?", Long.class, command)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM countermeasure_start_attempt WHERE command_id=?", Integer.class, command)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_receipt WHERE command_id=?", Integer.class, command)).isZero();
    }

    @Test void deliveredSmsWithNoLaterPositionDoesNotAuthorizeAndRecordedDepartureKeepsItBlocked() {
        long deliveredAt = notificationEvidence(false);
        presence(Presence.UNKNOWN, Presence.UNKNOWN);
        // This manual-decision phase used to trigger an automatic start in the SMS/new-position gap.
        assertThat(advisory.phaseForAutomation(event)).isEqualTo(NotifyFlow.Phase.AWAIT_COUNTER);
        assertThat(notificationGate.allows(event)).isFalse();
        launch();
        assertNoAutomaticStart();

        presence(Presence.LEFT, Presence.LEFT);
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE uav_auto_voice_task SET departure_channel='SMS',departure_started_at=?,departure_deadline_at=?,departure_evaluated_at=?,departure_observed_at=?,departure_longitude=118.63,departure_latitude=37.43,departure_basis='SMS_AIRSPACE' WHERE event_id=?",
                deliveredAt, deliveredAt, now, now - 1, event);
        assertThat(notificationGate.allows(event)).isFalse();
        launch();
        assertNoAutomaticStart();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM uav_auto_voice_task WHERE event_id=?", Integer.class, event)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 17})
    void configuredPhoneWaitBlocksCommandsUntilExactDeadline(int seconds) {
        notificationEvidence(true);
        presence(Presence.STILL_PRESENT, Presence.STILL_PRESENT);
        long completed=jdbc.queryForObject("SELECT playback_completed_at FROM uav_auto_voice_task WHERE event_id=?",Long.class,event);
        rule("counterVoiceWait",Integer.toString(seconds),clock.nowMillis());
        jdbc.update("UPDATE automation_rule_group SET version=version+1 WHERE category='counter'");
        doReturn(Instant.ofEpochMilli(completed+seconds*1000L-1)).when(clock).now();
        doReturn(false).when(clock).usesSystemClock();
        service.evaluate("counter",event);
        assertThat(runs.state("counter",event).status()).isEqualTo("WAITING");
        launch(); assertNoAutomaticStart();

        doReturn(Instant.ofEpochMilli(completed+seconds*1000L)).when(clock).now();
        doReturn(false).when(clock).usesSystemClock();
        service.evaluate("counter",event);
        assertThat(runs.state("counter",event).status()).isEqualTo("PASS");
        launch(); launch();
        var row=onlyAuthorization();
        controls.dispatch(row.executionCommandId()); receipts.syncByCommand(row.executionCommandId());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?",String.class,row.executionCommandId())).isEqualTo("SUCCEEDED");
        assertThat(frames).hasSize(1);
    }

    @ParameterizedTest @ValueSource(ints = {2, 600})
    void noAssociatedPilotSkipsPhoneWaitAndStartsExactlyOnceWithoutFakeNotification(int seconds) {
        doReturn(true).when(smsPolicy).enabled();
        doReturn(true).when(voicePolicy).enabled();
        String run=key();
        jdbc.update("INSERT INTO rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,source_mode,created_at) SELECT ?,r.rule_set_id,r.rule_set_version_id,'ACTIVE','SCHEDULED',r.as_of,CURRENT_TIMESTAMP,'DONE','mock',CURRENT_TIMESTAMP FROM rule_run r JOIN rule_evaluation e ON e.run_id=r.run_id WHERE e.alarm_id=(SELECT alarm_id FROM uav_event WHERE event_id=?) ORDER BY e.evaluated_at DESC LIMIT 1",run,event);
        jdbc.update("INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons) SELECT ?,?,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,CURRENT_TIMESTAMP,freshness_code,'NONE',legal_status,score,grade,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,CURRENT_TIMESTAMP,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons FROM rule_evaluation WHERE alarm_id=(SELECT alarm_id FROM uav_event WHERE event_id=?) ORDER BY evaluated_at DESC LIMIT 1",key(),run,event);
        assertThat(directory.cannotNotifyPilotFromConfiguration("ADVISORY_SMS",event)).isTrue();
        rule("counterVoiceWait",Integer.toString(seconds),clock.nowMillis());
        service.evaluate("counter",event);
        assertThat(runs.state("counter",event).status()).isEqualTo("PASS");
        launch(); launch();
        var row=onlyAuthorization();
        controls.dispatch(row.executionCommandId()); receipts.syncByCommand(row.executionCommandId());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?",String.class,row.executionCommandId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_event_voice_advisory WHERE event_id=?",Integer.class,event)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uav_auto_voice_task WHERE event_id=?",Integer.class,event)).isZero();
        assertThat(frames).hasSize(1);
    }

    @Test void unavailableVoiceConfigurationSkipsWaitAfterSmsButIsRecheckedBeforeDispatch() {
        notificationEvidence(false);
        presence(Presence.STILL_PRESENT,Presence.STILL_PRESENT);
        doReturn(true).when(directory).cannotNotifyPilotFromConfiguration("ADVISORY_VOICE",event);
        rule("counterVoiceWait","600",clock.nowMillis());
        service.evaluate("counter",event);
        assertThat(runs.state("counter",event).status()).isEqualTo("PASS");
        launch();
        var row=onlyAuthorization();
        doReturn(false).when(directory).cannotNotifyPilotFromConfiguration("ADVISORY_VOICE",event);
        controls.dispatch(row.executionCommandId()); receipts.syncByCommand(row.executionCommandId());
        assertCancelledBeforeSend(row.executionCommandId());
        assertThat(frames).isEmpty();
    }

    @Test void configuredPhoneWaitDoesNotChangeManualCounterEligibility() {
        notificationEvidence(false);
        presence(Presence.STILL_PRESENT,Presence.STILL_PRESENT);
        rule("counterVoiceWait","60",clock.nowMillis());
        jdbc.update("UPDATE automation_rule_group SET version=version+1 WHERE category='counter'");
        service.evaluate("counter",event);
        assertThat(runs.state("counter",event).status()).isNotEqualTo("PASS");
        launch(); assertNoAutomaticStart();
        AuthContext.set(operator);
        try { manual.directExecute(request(),key()); }
        finally { AuthContext.clear(); }
        assertThat(onlyAuthorization().requestedBy()).isEqualTo(operator.userId());
    }

    @Test void completedPlaybackNeedsANewStillPresentObservationBeforeAutomaticStart() {
        notificationEvidence(true);
        presence(Presence.STILL_PRESENT, Presence.UNKNOWN);
        assertThat(notificationGate.allows(event)).isFalse();
        launch();
        assertNoAutomaticStart();
        presence(Presence.STILL_PRESENT, Presence.LEFT);
        launch();
        assertNoAutomaticStart();

        // The neighboring case changes only the later observation, without adding a fixed wait.
        presence(Presence.STILL_PRESENT, Presence.STILL_PRESENT);
        assertThat(notificationGate.allows(event)).isTrue();
        launch();
        var row = onlyAuthorization();
        controls.dispatch(row.executionCommandId());
        receipts.syncByCommand(row.executionCommandId());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, row.executionCommandId())).isEqualTo("SUCCEEDED");
        assertThat(frames).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "LEFT"})
    void queuedStartRechecksNotificationObservationBeforeAnySocketWrite(String nextPresence) {
        notificationEvidence(true);
        presence(Presence.STILL_PRESENT, Presence.STILL_PRESENT);
        assertThat(notificationGate.allows(event)).isTrue();
        launch();
        var row = onlyAuthorization();
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, row.executionCommandId())).isEqualTo("QUEUED");

        presence(Presence.STILL_PRESENT, Presence.valueOf(nextPresence));
        assertThat(notificationGate.allows(event)).isFalse();
        controls.dispatch(row.executionCommandId());
        receipts.syncByCommand(row.executionCommandId());
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, row.executionCommandId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT issued_at FROM device_command WHERE command_id=?", Long.class, row.executionCommandId())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM countermeasure_start_attempt WHERE command_id=?", Integer.class, row.executionCommandId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_receipt WHERE command_id=?", Integer.class, row.executionCommandId())).isZero();
        launch();
        assertThat(repository.counterAttempts(event)).hasSize(1);
        assertThat(frames).isEmpty();
    }

    @Test void emergencyStopLeavesManualReapplicationAvailableWithoutSiteRegistration() {
        launch(); var original = onlyAuthorization();
        controls.dispatch(original.executionCommandId()); receipts.syncByCommand(original.executionCommandId());
        AuthContext.set(operator);
        try {
            stops.stop(event, key(), "{}");
            assertThat(repository.findUnlocked(original.authorizationId()).status()).isEqualTo("STOPPED");
            launch();
            assertThat(repository.counterAttempts(event)).hasSize(1);
            var next = manual.directExecute(request(), key());
            assertThat(next.authorizationId()).isNotEqualTo(original.authorizationId());
            assertThat(next.status()).isEqualTo("APPROVED");
            assertThat(next.executionBlockReason()).isEqualTo("DEVICE_BUSY");
            String off = jdbc.queryForObject("SELECT c.command_id FROM device_command c JOIN countermeasure_4ch_command p ON p.command_id=c.command_id WHERE c.device_id=? AND p.mask=0 ORDER BY c.created_at DESC LIMIT 1", String.class, device);
            controls.dispatch(off); receipts.syncByCommand(off);
            manual.execute(next.authorizationId(), "{\"expected_version\":" + next.version() + ",\"operation_params\":{}}", key());
            var restarted = repository.findUnlocked(next.authorizationId());
            controls.dispatch(restarted.executionCommandId()); receipts.syncByCommand(restarted.executionCommandId());
            assertThat(repository.findUnlocked(next.authorizationId()).status()).isEqualTo("EXECUTING");
        } finally { AuthContext.clear(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM disposal_authorization WHERE subject_id=?", Integer.class, event)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulSocketSendThenReceiptRollbackNeverResendsOrCreatesAnotherAutomaticAuthorization(boolean timeoutFirst) {
        launch();
        var row = onlyAuthorization(); String command = row.executionCommandId();
        doThrow(new IllegalStateException("isolated receipt persistence failure"))
                .when(commandRows).addReceipt(eq(command), anyString(), anyString(), anyLong(), anyString());
        assertThatThrownBy(() -> controls.dispatch(command)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt persistence failure");
        assertThat(frames).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, command)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT issued_at FROM device_command WHERE command_id=?", Long.class, command)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM countermeasure_start_attempt WHERE command_id=?", Integer.class, command)).isEqualTo(1);
        assertThat(repository.automaticStartNeverIssued(repository.findUnlocked(row.authorizationId()))).isFalse();
        doCallRealMethod().when(commandRows).addReceipt(eq(command), anyString(), anyString(), anyLong(), anyString());

        // Same rule version changes from not matched back to PASS; the committed send evidence still wins.
        jdbc.update("UPDATE automation_rule_condition SET value_text='0' WHERE category='counter' AND item_code='counterFreshness' AND enabled=TRUE");
        service.evaluate("counter", event);
        assertThat(runs.state("counter", event).status()).isEqualTo("NOT_MATCHED");
        if (timeoutFirst) controls.timeout(command, "isolated deadline after receipt rollback");
        else controls.dispatch(command);
        receipts.syncByCommand(command);
        assertThat(jdbc.queryForObject("SELECT result_code FROM device_command WHERE command_id=?", String.class, command)).isEqualTo("COUNTERMEASURE_OUTCOME_UNKNOWN");
        jdbc.update("UPDATE automation_rule_condition SET value_text='60' WHERE category='counter' AND item_code='counterFreshness' AND enabled=TRUE");
        service.evaluate("counter", event); launch(); controls.dispatch(command);
        assertThat(repository.counterAttempts(event)).hasSize(1);
        assertThat(frames).hasSize(1);

        assertThat(repository.deviceRunningOther(device, null)).isTrue();
        assertThat(deviceRows.hasActiveWork(device)).isTrue();
        assertThat(deviceRows.hasCatalogBlockingWork(device)).isTrue();
        assertThat(deviceRows.hasRecoveryBlockingWork(device, System.currentTimeMillis() - 30000, System.currentTimeMillis())).isTrue();
        String off;
        AuthContext.set(operator);
        try {
            assertThat(stops.overview(event).allowedActions()).contains("EMERGENCY_STOP");
            stops.stop(event, key(), "{}");
            off = jdbc.queryForObject("SELECT c.command_id FROM device_command c JOIN countermeasure_4ch_command p ON p.command_id=c.command_id WHERE c.device_id=? AND p.mask=0 ORDER BY c.created_at DESC LIMIT 1", String.class, device);
            assertThat(deviceRows.hasActiveWork(device)).isTrue();
            assertThat(deviceRows.hasCatalogBlockingWork(device)).isTrue();
            assertThat(deviceRows.hasRecoveryBlockingWork(device, System.currentTimeMillis() - 30000, System.currentTimeMillis())).isTrue();
            controls.dispatch(off);
        } finally { AuthContext.clear(); }
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, off)).isEqualTo("SUCCEEDED");
        assertThat(repository.deviceRunningOther(device, null)).isFalse();
        assertThat(deviceRows.hasActiveWork(device)).isFalse();
        assertThat(deviceRows.hasCatalogBlockingWork(device)).isFalse();
        assertThat(deviceRows.hasRecoveryBlockingWork(device, System.currentTimeMillis() - 30000, System.currentTimeMillis())).isFalse();
        assertThat(frames).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM countermeasure_start_attempt WHERE device_id=?", Integer.class, device)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADAPTER_TIMEOUT", "PROTOCOL_FRAME_INVALID", "COUNTERMEASURE_STATE_MISMATCH"})
    void unconfirmedReplyAfterActualStartRemainsBusyAndAllowsSafeAllOff(String originalCode) {
        failureReply = originalCode;
        launch(); var row = onlyAuthorization();
        controls.dispatch(row.executionCommandId()); receipts.syncByCommand(row.executionCommandId());
        var failed = onlyAuthorization();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.resultCode()).isEqualTo("COUNTERMEASURE_OUTCOME_UNKNOWN");
        assertThat(failed.resultDetail()).contains(originalCode);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_receipt WHERE command_id=?", Integer.class, row.executionCommandId())).isZero();
        assertThat(deviceRows.hasActiveWork(device)).isTrue();
        launch(); controls.dispatch(row.executionCommandId());
        assertThat(repository.counterAttempts(event)).hasSize(1);
        assertThat(frames).hasSize(1);
        AuthContext.set(operator);
        try {
            assertThat(stops.overview(event).allowedActions()).contains("EMERGENCY_STOP");
            stops.stop(event, key(), "{}");
            String off = jdbc.queryForObject("SELECT c.command_id FROM device_command c JOIN countermeasure_4ch_command p ON p.command_id=c.command_id WHERE c.device_id=? AND p.mask=0 ORDER BY c.created_at DESC LIMIT 1", String.class, device);
            controls.dispatch(off);
            assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, off)).isEqualTo("SUCCEEDED");
        } finally { AuthContext.clear(); }
        assertThat(deviceRows.hasActiveWork(device)).isFalse();
        assertThat(frames).hasSize(2);
    }

    @Test void rejectedNetworkBeforeSocketSendRemainsAnExplicitFailure() {
        jdbc.update("UPDATE ops_integration_source SET allowed_cidrs='192.0.2.0/24' WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)", device);
        launch(); var row = onlyAuthorization();
        controls.dispatch(row.executionCommandId()); receipts.syncByCommand(row.executionCommandId());
        assertThat(onlyAuthorization().resultCode()).isEqualTo("NETWORK_TARGET_FORBIDDEN");
        assertThat(frames).isEmpty();
        assertThat(deviceRows.hasActiveWork(device)).isFalse();
        launch(); assertThat(repository.counterAttempts(event)).hasSize(1);
    }

    private void assertOneCommand() {
        var row = onlyAuthorization();
        assertThat(row.status()).isEqualTo("EXECUTING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?", Integer.class, device)).isEqualTo(1);
    }
    private void assertNoAutomaticStart() {
        assertThat(repository.counterAttempts(event)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?", Integer.class, device)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM countermeasure_start_attempt WHERE device_id=?", Integer.class, device)).isZero();
        assertThat(frames).isEmpty();
    }
    private void presence(Presence afterSms, Presence afterCall) {
        doReturn(afterSms).when(departure).assess(eq(event), anyLong(), anyLong());
        doReturn(afterCall).when(departure).assess(eq(event), anyLong(), anyLong(), anyLong());
    }
    /** Durable notification facts in the isolated PG schema; only configuration and spatial answers are spied. */
    private long notificationEvidence(boolean played) {
        doReturn(true).when(smsPolicy).enabled();
        doReturn(true).when(voicePolicy).enabled();
        doReturn(false).when(directory).cannotNotifyPilotFromConfiguration(anyString(), eq(event));
        String recordingId = key();
        var recording = new AdvisoryVoiceRecording.Recording(recordingId, "isolated notice", "isolated transcript", "a".repeat(64));
        doReturn(recording).when(recordings).current();
        long now = System.currentTimeMillis(), delivered = now - 3000, answered = now - 2000, completed = now - 1000;
        String smsRecord = key();
        jdbc.update("INSERT INTO uav_event_advisory(record_id,event_id,event_version,kind,created_at,actor_id,recipient_name,contact_basis,content,urgent,simulated,delivery_status,trigger_mode,actor_label,policy_code) VALUES (?,?,1,'SMS_SIMULATED',?,NULL,'isolated pilot','isolated verified task','isolated notice',FALSE,TRUE,'SIMULATED_DELIVERED','AUTO','isolated automatic SMS',?)",
                smsRecord, event, delivered, AutoSmsPolicy.CODE);
        jdbc.update("INSERT INTO uav_auto_sms_task(event_id,status,policy_code,reason,trigger_source,triggered_at,updated_at,attempt_count,delivery_record_id,provider_key) VALUES (?,'SIMULATED_DELIVERED',?,'isolated delivered receipt','ALARM_EVENT',?,?,1,?,?)",
                event, AutoSmsPolicy.CODE, delivered - 100, delivered, smsRecord, "auto-advisory:" + event);
        jdbc.update("INSERT INTO uav_auto_voice_task(event_id,status,policy_code,reason,trigger_source,updated_at,attempt_count,provider_key,recording_id,recording_name,recording_sha256,recording_transcript) VALUES (?,'BLOCKED',?,'no newer position after SMS','SMS_THEN_WATCH',?,0,?,?,?,?,?)",
                event, AutoVoicePolicy.CODE, now, "auto-advisory-voice:" + event, recording.id(), recording.name(), recording.sha256(), recording.transcript());
        if (played) {
            String voiceRecord = key(), providerCall = "isolated-call-" + key();
            jdbc.update("INSERT INTO uav_event_voice_advisory(record_id,event_id,event_version,created_at,recording_id,recording_name,recording_sha256,transcript,provider_call_id,answered_at,playback_completed_at,policy_code,simulated,delivery_status) VALUES (?,?,2,?,?,?,?,?,?,?,?,?,TRUE,'SIMULATED_PLAYED')",
                    voiceRecord, event, completed, recording.id(), recording.name(), recording.sha256(), recording.transcript(), providerCall, answered, completed, AutoVoicePolicy.CODE);
            jdbc.update("UPDATE uav_auto_voice_task SET status='SIMULATED_PLAYED',reason='isolated played receipt',triggered_at=?,updated_at=?,attempt_count=1,delivery_record_id=?,provider_call_id=?,answered_at=?,playback_completed_at=? WHERE event_id=?",
                    answered - 100, completed, voiceRecord, providerCall, answered, completed, event);
        }
        return delivered;
    }
    private void launch() { counter.launchIfPassed(event, runs.state("counter", event).runId()); }
    private DisposalRepository.AuthorizationRow onlyAuthorization() {
        var rows = repository.counterAttempts(event); assertThat(rows).hasSize(1); return rows.get(0);
    }
    private String request() {
        try { return json.writeValueAsString(Map.of("subject_kind", "UAV_EVENT", "subject_id", event,
                "action_type", "COUNTERMEASURE", "channel", "COUNTERMEASURE_4CH", "device_id", device, "reason", "isolated manual fallback")); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void rule(String item, String value, long now) {
        jdbc.update("INSERT INTO automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) VALUES (?,'counter',?,?,?,0,TRUE,?,?,'isolated-test') ON CONFLICT(category,item_code) DO UPDATE SET value_text=EXCLUDED.value_text,hold_seconds=0,enabled=TRUE,updated_at=EXCLUDED.updated_at", key(), item, item, value, now, now);
    }
    private static void race(Runnable a, Runnable b) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { await(gate); a.run(); });
            var second = pool.submit(() -> { await(gate); b.run(); });
            gate.countDown(); first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
    private static void await(CountDownLatch gate) { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    private static String key() { return UUID.randomUUID().toString(); }
}
