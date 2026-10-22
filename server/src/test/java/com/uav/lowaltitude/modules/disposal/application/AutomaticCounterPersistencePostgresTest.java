package com.uav.lowaltitude.modules.disposal.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.RunRow;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationInsert;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;

/** Real PostgreSQL JSON/SQL checks; no business database or external device is contacted. */
@SpringBootTest
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class AutomaticCounterPersistencePostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void close() { DATABASE.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired DisposalRepository repository;
    @Autowired AutomationRuntimeRepository runs;
    @Autowired AutomaticCounterAuthorization automatic;
    private static final String ORG = "seed-stage3-org", DISTRICT = "seed-stage3-district";
    private final long now = System.currentTimeMillis();

    @Test void simulationNeverSelectsRealUnregisteredOrOutOfScopeControllers() {
        String simulated = controller(true), real = controller(false);
        assertThat(repository.automaticCounterDevices("replay", ORG, DISTRICT, now, 30000, true)).isEmpty();
        register(simulated);
        assertThat(repository.automaticCounterDevices("replay", ORG, DISTRICT, now, 30000, true)).containsExactly(simulated);
        assertThat(repository.automaticCounterDevices("mock", ORG, DISTRICT, now, 30000, true)).containsExactly(simulated);
        assertThat(repository.automaticCounterDevices("live", ORG, DISTRICT, now, 30000, true)).containsExactly(real);
        assertThat(repository.automaticCounterDevices("replay", ORG, DISTRICT, now, 30000, false)).isEmpty();
        assertThat(repository.automaticCounterDevices("mock", ORG, DISTRICT, now, 30000, false)).isEmpty();
        assertThat(repository.automaticCounterDevices("unknown", ORG, DISTRICT, now, 30000, true)).isEmpty();
        assertThat(repository.automaticCounterDevices("replay", "other-org", DISTRICT, now, 30000, true)).isEmpty();
        assertThat(repository.automaticCounterDevices("replay", ORG, "other-district", now, 30000, true)).isEmpty();
    }

    @Test void registeredSimulationRequiresMatchingLiveSourceAndLocalController() {
        String id = controller(true); register(id);
        assertCandidate(id);
        jdbc.update("UPDATE device_connection_profile SET host='192.0.2.18' WHERE device_id=?", id);
        assertNoCandidate();
        jdbc.update("UPDATE device_connection_profile SET host='127.0.0.1' WHERE device_id=?", id);
        jdbc.update("UPDATE ops_integration_source SET simulated=FALSE WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)", id);
        assertNoCandidate();
        jdbc.update("UPDATE ops_integration_source SET simulated=TRUE,source_mode='replay' WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)", id);
        assertNoCandidate();
        jdbc.update("UPDATE ops_integration_source SET source_mode='live',enabled=FALSE WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)", id);
        assertNoCandidate();
    }

    @Test void staleOfflineBadOrRetiredDevicesNeverBecomeCandidates() {
        String id = controller(true); register(id);
        assertCandidate(id); // UNKNOWN health is allowed for four-channel; a status read is not a recovery fact.
        jdbc.update("UPDATE ops_device_state SET observed_at=? WHERE device_id=?", now - 30001, id);
        assertNoCandidate();
        jdbc.update("UPDATE ops_device_state SET observed_at=?,connectivity='OFFLINE' WHERE device_id=?", now, id);
        assertNoCandidate();
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',health_code='BAD' WHERE device_id=?", id);
        assertNoCandidate();
        jdbc.update("UPDATE ops_device_state SET health_code='GOOD' WHERE device_id=?", id);
        jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?", id);
        assertNoCandidate();
        jdbc.update("UPDATE ops_device SET enabled=FALSE,deleted_at=? WHERE device_id=?", now, id);
        assertNoCandidate();
    }

    @Test void retryNeedsPersistedProofNoIssueNoReceiptAndNoOtherCommand() {
        String device = controller(true), authorization = key(), command = key();
        var unsent = row(authorization, device, null);
        assertThat(repository.automaticStartNeverIssued(unsent)).isTrue();
        var cancelled = row(authorization, device, command);
        assertThat(repository.automaticStartNeverIssued(cancelled)).isFalse();
        command(command, authorization, device);
        assertThat(repository.automaticStartNeverIssued(cancelled)).isTrue();
        assertThat(repository.automaticStartNeverIssued(unsent)).isFalse();
        jdbc.update("UPDATE device_command SET issued_at=? WHERE command_id=?", now, command);
        assertThat(repository.automaticStartNeverIssued(cancelled)).isFalse();
        jdbc.update("UPDATE device_command SET issued_at=NULL WHERE command_id=?", command);
        String inbox = key();
        jdbc.update("INSERT INTO inbox_message(inbox_id,source,source_msg_id,received_at) VALUES (?,'counter-test',?,?)", inbox, inbox, now);
        jdbc.update("INSERT INTO command_receipt(receipt_id,command_id,inbox_id,receipt_kind,received_at,payload) VALUES (?,?,?,'RESULT',?,'{}')", key(), command, inbox, now);
        assertThat(repository.automaticStartNeverIssued(cancelled)).isFalse();
        jdbc.update("DELETE FROM command_receipt WHERE command_id=?", command);
        command(key(), authorization, device);
        assertThat(repository.automaticStartNeverIssued(cancelled)).isFalse();
    }

    @Test void durableJsonGrantBindsOriginalRunAndCannotReviveLegacyAuthorization() {
        String device = controller(true);
        String event = jdbc.queryForObject("SELECT e.event_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE a.target_id IS NOT NULL ORDER BY e.event_id LIMIT 1", String.class);
        String target = jdbc.queryForObject("SELECT a.target_id FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?", String.class, event);
        String mode = jdbc.queryForObject("SELECT a.source_mode FROM uav_event e JOIN alarm a ON a.alarm_id=e.alarm_id WHERE e.event_id=?", String.class, event);
        String id = key(), runId = key();
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
        RunRow run = new RunRow(runId, "counter", event, target, 23, "PASS", "", at.toInstant().toEpochMilli() - 1000,
                at.toInstant().toEpochMilli() - 1000, mode, "[]");
        runs.insertRun(run, "{}");
        String policy = jdbc.queryForObject("SELECT policy_code FROM disposal_policy WHERE status='ACTIVE' ORDER BY policy_code LIMIT 1", String.class);
        repository.insert(new AuthorizationInsert(id, "AUTO-" + id.substring(0, 12), "COUNTERMEASURE", "UAV_EVENT", event, target, device,
                "COUNTERMEASURE_4CH", "test", AutomationPrincipal.USER_ID, at, "REQUESTED", policy, ORG, DISTRICT, mode));
        repository.authorizeDirect(id, 0, at, at.plusMinutes(5), "test");
        var stored = repository.findUnlocked(id);
        assertThat(automatic.belongsToVersion(stored, run)).isFalse();
        repository.insertEvent(key(), id, "DIRECT_AUTHORIZE", AutomationPrincipal.USER_ID, "test", "{\"automation_contract\":\"RULE_COUNTER_V2\",\"group_version\":23,\"automation_run_id\":\"" + runId + "\"}", at);
        assertThat(automatic.belongsToVersion(repository.findUnlocked(id), run)).isTrue();
        assertThat(automatic.belongsToVersion(stored, new RunRow(runId, "counter", event, target, 24, "PASS", "", now, now, mode, "[]"))).isFalse();
    }

    private String controller(boolean simulated) {
        String id = key(), source = key();
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,enabled,allowed_cidrs,simulated,version,created_at,updated_at) VALUES (?,?,?,'COUNTERMEASURE_TCP_4CH_V2_0','2.0','live',TRUE,'127.0.0.1/32',?,0,?,?)", source, source, "counter-test", simulated, now, now);
        jdbc.update("INSERT INTO ops_device(device_id,source_id,external_device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at) VALUES (?,?,?,?,?,'countermeasure','反制','反制直连',TRUE,'live',?,0,?,?)", id, source, id, id, "counter-test", simulated, now, now);
        jdbc.update("INSERT INTO device_connection_profile(device_id,transport,host,port,timeout_millis,retry_count,version,updated_at) VALUES (?,'TCP','127.0.0.1',15003,1000,0,0,?)", id, now);
        jdbc.update("INSERT INTO ops_device_state(device_id,connectivity,has_alarm,health_code,observed_at,received_at,simulated,version) VALUES (?,'ONLINE',FALSE,'UNKNOWN',?,?,?,0)", id, now, now, simulated);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, ORG, DISTRICT);
        return id;
    }
    private void register(String device) {
        jdbc.update("INSERT INTO audit_log(audit_id,action,object_type,object_id,occurred_at,result) VALUES (?,'local_qa_device_prepare','device',?,?,'SUCCESS')", key(), device, now);
    }
    private void assertCandidate(String id) { assertThat(repository.automaticCounterDevices("replay", ORG, DISTRICT, now, 30000, true)).containsExactly(id); }
    private void assertNoCandidate() { assertThat(repository.automaticCounterDevices("replay", ORG, DISTRICT, now, 30000, true)).isEmpty(); }
    private void command(String id, String authorization, String device) {
        jdbc.update("INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,source_mode,simulated,result_code,authorization_id,created_at,updated_at) VALUES (?,?,?,(SELECT user_id FROM app_user WHERE account='admin1'),'COUNTERMEASURE_4CH','test','CANCELLED','live',TRUE,'AUTHORIZATION_STOPPED',?,?,?)", id, id, device, authorization, now, now);
    }
    private AuthorizationRow row(String authorization, String device, String command) {
        var at = OffsetDateTime.now(ZoneOffset.UTC);
        return new AuthorizationRow(authorization, authorization, "COUNTERMEASURE", "UAV_EVENT", "event", "target", device,
                "COUNTERMEASURE_4CH", "", AutomationPrincipal.USER_ID, at, null, null, null, at, at.plusMinutes(5), "FAILED", command,
                "AUTHORIZATION_STOPPED", null, "policy", ORG, DISTRICT, "replay", 1, null, null, "DIRECT", null);
    }
    private static String key() { return UUID.randomUUID().toString(); }
}
