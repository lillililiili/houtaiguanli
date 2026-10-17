package com.uav.lowaltitude.modules.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.RunRow;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.disposal.api.CounterEvidenceFixture;
import com.uav.lowaltitude.modules.disposal.application.AutomaticCounterAuthorization;
import com.uav.lowaltitude.modules.disposal.application.DisposalAuthorizationService;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationInsert;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.platform.security.AuthContext;

/** Persist the stop/cancellation race outcome, then exercise real PostgreSQL retry and manual-request guards. */
@SpringBootTest
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AutomaticCounterStopCoveragePostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void close() { DATABASE.close(); }
    @AfterEach void clearActor() { AuthContext.clear(); }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DisposalRepository authorizations;
    @Autowired AutomationRuntimeRepository runs;
    @Autowired AutomaticCounterAuthorization automatic;
    @Autowired EmergencyStopRepository stops;
    @Autowired DisposalAuthorizationService manual;

    @ParameterizedTest
    @ValueSource(strings = {"DEVICE_NOT_OPERABLE", "AUTHORIZATION_STOPPED"})
    void coveredFailedStartCannotAutomaticallyRetryButManualReapplicationRemainsAvailable(String cancellation) throws Exception {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
        String event = event(at), device = controller(at.toInstant().toEpochMilli());
        String operator = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        RunRow original = passingRun(event, at);
        AuthorizationRow failed = failedBeforeIssue(event, device, original, cancellation, at);

        // Positive control: this exact persisted cancellation is a valid bounded retry before an emergency covers it.
        assertThat(automatic.belongsToVersion(failed, original)).isTrue();
        assertThat(authorizations.automaticStartNeverIssued(failed)).isTrue();
        assertThat(history(event, original, at).next()).isEqualTo(CounterHistory.Next.LAUNCH);

        // EmergencyStopService may observe EXECUTING, wait for the event lock, then cover the now-FAILED row.
        // The stop cannot enqueue an off command while the device is offline; terminal status stays FAILED.
        jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE' WHERE device_id=?", device);
        String stop = key();
        stops.insert(stop, event, operator, at.toInstant().toEpochMilli(), "isolated stop/cancellation race");
        stops.cover(stop, failed.authorizationId());
        stops.task(stop, device, failed.authorizationId(), "isolated controller", "COUNTERMEASURE_4CH", "live", true,
                null, "OFFLINE", "device unavailable; no off command was queued");
        assertThat(stops.covered(failed.authorizationId())).isTrue();
        assertThat(authorizations.findUnlocked(failed.authorizationId()).status()).isEqualTo("FAILED");
        assertThat(authorizations.automaticStartNeverIssued(authorizations.findUnlocked(failed.authorizationId()))).isFalse();
        assertThat(history(event, original, at).next()).isEqualTo(CounterHistory.Next.NOTHING);
        assertOnlyUnissuedCancellation(device, failed.executionCommandId(), cancellation);

        // Recovery does not revive the old automatic grant, and no site-registration step is required for a new human request.
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',health_code='GOOD',observed_at=?,received_at=? WHERE device_id=?",
                at.toInstant().toEpochMilli(), at.toInstant().toEpochMilli(), device);
        assertThat(history(event, original, at).next()).isEqualTo(CounterHistory.Next.NOTHING);
        AuthContext.set(authorizations.actor(operator));
        var next = manual.create(json.writeValueAsString(Map.of("subject_kind", "UAV_EVENT", "subject_id", event,
                "action_type", "COUNTERMEASURE", "channel", "COUNTERMEASURE_4CH", "device_id", device,
                "reason", "isolated manual reapplication after emergency stop")), key());
        assertThat(next.authorizationId()).isNotEqualTo(failed.authorizationId());
        assertThat(next.status()).isIn("REQUESTED", "APPROVED");
        assertThat(authorizations.findUnlocked(next.authorizationId()).requestedBy()).isEqualTo(operator);
        assertThat(stops.covered(next.authorizationId())).isFalse();
        assertThat(authorizations.counterAttempts(event)).hasSize(2);
        assertThat(authorizations.findUnlocked(failed.authorizationId()).resultCode()).isEqualTo(cancellation);
        assertOnlyUnissuedCancellation(device, failed.executionCommandId(), cancellation);
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM disposal_emergency_stop_device WHERE stop_id=? AND device_id=?",
                Long.class, stop, device)).isNull();
    }

    private CounterHistory history(String event, RunRow run, OffsetDateTime at) {
        return CounterHistory.of(authorizations.counterAttempts(event), at,
                row -> automatic.belongsToVersion(row, run), authorizations::automaticStartNeverIssued);
    }

    private void assertOnlyUnissuedCancellation(String device, String command, String cancellation) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?", Integer.class, device)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?", String.class, command)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT result_code FROM device_command WHERE command_id=?", String.class, command)).isEqualTo(cancellation);
        assertThat(jdbc.queryForObject("SELECT issued_at FROM device_command WHERE command_id=?", Long.class, command)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM command_receipt WHERE command_id=?", Integer.class, command)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM countermeasure_start_attempt WHERE command_id=?", Integer.class, command)).isZero();
    }

    private RunRow passingRun(String event, OffsetDateTime at) {
        String target = jdbc.queryForObject("SELECT a.target_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?",
                String.class, event);
        long beforeRequest = at.toInstant().toEpochMilli() - 1000;
        RunRow run = new RunRow(key(), "counter", event, target, 23, "PASS", "", beforeRequest, beforeRequest, "mock", "[]");
        runs.insertRun(run, "{}");
        return run;
    }

    private AuthorizationRow failedBeforeIssue(String event, String device, RunRow run, String cancellation, OffsetDateTime at) {
        String id = key(), command = key();
        String policy = jdbc.queryForObject("SELECT policy_code FROM disposal_policy WHERE status='ACTIVE' ORDER BY policy_code LIMIT 1", String.class);
        authorizations.insert(new AuthorizationInsert(id, "AUTO-" + id.substring(0, 12), "COUNTERMEASURE", "UAV_EVENT", event,
                run.targetId(), device, "COUNTERMEASURE_4CH", "isolated race", AutomationPrincipal.USER_ID, at,
                "REQUESTED", policy, ORG, DISTRICT, "mock"));
        assertThat(authorizations.authorizeDirect(id, 0, at, at.plusMinutes(5), "isolated rule grant")).isEqualTo(1);
        authorizations.insertEvent(key(), id, "DIRECT_AUTHORIZE", AutomationPrincipal.USER_ID, "isolated rule grant",
                "{\"automation_contract\":\"RULE_COUNTER_V2\",\"group_version\":23,\"automation_run_id\":\"" + run.id() + "\"}", at);
        jdbc.update("INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,source_mode,simulated,result_code,authorization_id,created_at,updated_at) VALUES (?,?,?,?,'COUNTERMEASURE_4CH','isolated cancellation','CANCELLED','live',TRUE,?,?,?,?)",
                command, command, device, AutomationPrincipal.USER_ID, cancellation, id, at.toInstant().toEpochMilli(), at.toInstant().toEpochMilli());
        assertThat(authorizations.transition(id, 1, "EXECUTING", at, command, null, null)).isEqualTo(1);
        assertThat(authorizations.transitionFromStatus(id, "EXECUTING", "FAILED", at, cancellation, "cancelled before issue")).isEqualTo(1);
        return authorizations.findUnlocked(id);
    }

    private String event(OffsetDateTime at) {
        String source = key(), alarm = key(), event = key();
        Timestamp timestamp = Timestamp.from(at.toInstant());
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES (?,?,?,TRUE,'mock',?,?,0)",
                source, source, "isolated emergency coverage", timestamp, timestamp);
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES (?,NULL,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",
                alarm, source, alarm, timestamp, timestamp, ORG, DISTRICT, timestamp);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES (?,?,'CONFIRMED',?,?,?,?,1)",
                event, alarm, ORG, DISTRICT, timestamp, timestamp);
        CounterEvidenceFixture.seed(jdbc, event, at.toInstant());
        return event;
    }

    private String controller(long at) {
        String device = key(), source = key();
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,enabled,allowed_cidrs,simulated,version,created_at,updated_at) VALUES (?,?,?,'COUNTERMEASURE_TCP_4CH_V2_0','2.0','live',TRUE,'127.0.0.1/32',TRUE,0,?,?)",
                source, source, "isolated controller", at, at);
        jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) VALUES (?,?,?,?,?,'countermeasure','反制','反制直连',TRUE,'live',TRUE,0,?,?)",
                device, source, device, device, "isolated controller", at, at);
        jdbc.update("INSERT INTO device_connection_profile(device_id,transport,host,port,timeout_millis,retry_count,version,updated_at) VALUES (?,'TCP','127.0.0.1',15003,1000,0,0,?)", device, at);
        jdbc.update("INSERT INTO ops_device_state(device_id,connectivity,has_alarm,health_code,observed_at,received_at,simulated,version) VALUES (?,'ONLINE',FALSE,'UNKNOWN',?,?,TRUE,0)", device, at, at);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", device, ORG, DISTRICT);
        jdbc.update("INSERT INTO audit_log(audit_id,action,object_type,object_id,occurred_at,result) VALUES (?,'local_qa_device_prepare','device',?,?,'SUCCESS')", key(), device, at);
        return device;
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
