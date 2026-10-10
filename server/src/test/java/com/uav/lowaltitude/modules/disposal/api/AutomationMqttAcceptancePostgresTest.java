package com.uav.lowaltitude.modules.disposal.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.uav.lowaltitude.modules.automationrule.application.*;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Additional automatic-start acceptance scenarios on the existing real loopback MQTT fixture. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class AutomationMqttAcceptancePostgresTest extends AutomationMqttFixture {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.automation-rules.enabled",()->true);
        registry.add("app.automation-rules.fact-max-age-ms",()->300000);
        registry.add("app.mqtt.enabled",()->true);
        registry.add("app.outbox.enabled",()->true);
        registry.add("app.dev-seed.password",()->"changeme");
        registry.add("app.lingyun-control.command-timeout-millis",()->120000);
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
    @Autowired AutomationRuntimeWorker automatic;
    @Autowired AutomationRuntimeRepository runtime;
    @Autowired AutomationRuntimeService decisions;

    private void automaticFixture() {
        if(jdbc.queryForObject("select count(*) from app_role where role_code=?",Integer.class,AutomationPrincipal.ROLE)==0)
            jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) values(?,'自动规则隔离测试','',false,true,0,0,0,false)",AutomationPrincipal.ROLE);
        if(jdbc.queryForObject("select count(*) from app_user where user_id=?",Integer.class,AutomationPrincipal.USER_ID)==0)
            jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) values(?,?,?,?,'DISABLED','unused',0,'ALL',0,0,0,0)",AutomationPrincipal.USER_ID,AutomationPrincipal.ACCOUNT,"自动规则隔离测试",AutomationPrincipal.ROLE);
        String alarm=jdbc.queryForObject("select alarm_id from uav_event where event_id=?",String.class,eventId);
        String target=jdbc.queryForObject("select target_id from alarm where alarm_id=?",String.class,alarm);
        // Isolated fixture provenance must agree with the registered replay MQTT device.
        jdbc.update("update alarm set source_mode='replay' where alarm_id=?",alarm);
        jdbc.update("update target set source_mode='replay' where target_id=?",target);
        appendEvaluation(target,"ILLEGAL");
        // Earlier cases' targets stop being observed: an automatic counter whose start command never left is launched
        // again once its rule passes (2026-10-08 retest), and those events would take this case's only device.
        jdbc.update("update target_latest_state set observed_at=? where target_id<>?",Timestamp.from(clock.now().minusSeconds(90000)),target);
        jdbc.update("update automation_rule_condition set enabled=false");
        jdbc.update("update automation_rule_group set version=831,scope_mode='ALL',schedule_mode='ALL_DAY',wait_seconds=0 where category='counter'");
        jdbc.update("insert into automation_rule_condition(rule_id,category,name,item_code,value_text,hold_seconds,enabled,created_at,updated_at,updated_by) values(?,'counter','自动反制时效验收','counterFreshness','300',0,true,?,?,'qa-auto') on conflict(category,item_code) do update set enabled=true,value_text='300',hold_seconds=0",key(),clock.nowMillis(),clock.nowMillis());
        jdbc.update("update ops_device set enabled=false where device_type_code='ifr' and device_id<>?",binding.opsDeviceId());
        assertThat(advisory.counterBlockReason(eventId)).isEmpty();
    }

    private String queueAutomatic() {
        automaticFixture(); automatic.poll();
        assertThat(runtime.state("counter",eventId).status()).isEqualTo("PASS");
        List<String> ids=jdbc.queryForList("select authorization_id from disposal_authorization where subject_id=? and requested_by=?",String.class,eventId,AutomationPrincipal.USER_ID);
        assertThat(ids).hasSize(1);
        String id=ids.get(0);
        assertThat(statusOf(id)).isEqualTo("EXECUTING");
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?",String.class,commandOf(id))).isEqualTo("QUEUED");
        assertThat(frames).isEmpty();
        return id;
    }

    @Test void currentAutomaticPassSendsOneRealMqttCommand() throws Exception {
        String id=queueAutomatic();
        automatic.poll(); awaitWire(1); automatic.poll(); outbox.poll();
        assertThat(frames).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?",Integer.class,binding.opsDeviceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select approved_by from disposal_authorization where authorization_id=?",String.class,id)).isNull();
        saveEvidence("automatic-current-pass",id);
    }

    @ParameterizedTest @ValueSource(strings={"RULE_DISABLED","VERSION","SCHEDULE","AUTH_EXPIRED","AUTH_STOPPED","DEVICE_DISABLED","DEVICE_FAULT","POSITION_STALE","EVIDENCE_LEGAL","EMERGENCY"})
    void automaticQueuedCommandRechecksEveryCurrentBoundary(String change) throws Exception {
        String id=queueAutomatic(),command=commandOf(id);
        long queuedAt=clock.nowMillis();
        String target=jdbc.queryForObject("select target_id from disposal_authorization where authorization_id=?",String.class,id);
        switch(change) {
            case "RULE_DISABLED" -> jdbc.update("update automation_rule_condition set enabled=false where category='counter'");
            case "VERSION" -> jdbc.update("update automation_rule_group set version=832 where category='counter'");
            case "SCHEDULE" -> jdbc.update("update automation_rule_group set schedule_mode='DAILY',start_time='00:00',end_time='00:00' where category='counter'");
            case "AUTH_EXPIRED" -> jdbc.update("update disposal_authorization set valid_from=?,valid_until=? where authorization_id=?",Timestamp.from(clock.now().minusSeconds(120)),Timestamp.from(clock.now().minusSeconds(1)),id);
            case "AUTH_STOPPED" -> jdbc.update("update disposal_authorization set status='STOPPED' where authorization_id=?",id);
            case "DEVICE_DISABLED" -> jdbc.update("update ops_device set enabled=false where device_id=?",binding.opsDeviceId());
            case "DEVICE_FAULT" -> jdbc.update("update ops_device_state set health_code='BAD',has_alarm=true where device_id=?",binding.opsDeviceId());
            case "POSITION_STALE" -> jdbc.update("update target_latest_state set observed_at=? where target_id=?",Timestamp.from(clock.now().minusSeconds(90000)),target);
            case "EVIDENCE_LEGAL" -> appendEvaluation(target,"LEGAL");
            case "EMERGENCY" -> stop(operator,key()).andExpect(status().isOk());
            default -> throw new IllegalArgumentException(change);
        }
        long configurationSavedAt=clock.nowMillis();
        if ("RULE_DISABLED".equals(change))
            assertThat(jdbc.queryForObject("select count(*) from automation_rule_condition where category='counter' and enabled=true",Integer.class)).isZero();
        for(int i=0;i<5;i++) outbox.poll();
        assertThat(frames).isEmpty();
        assertThat(jdbc.queryForObject("select issued_at from device_command where command_id=?",Long.class,command)).isNull();
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?",String.class,command)).isEqualTo("CANCELLED");
        saveEvidence("automatic-queued-"+change,id);
        if ("RULE_DISABLED".equals(change)) {
            var chronology=new java.util.LinkedHashMap<String,Object>();
            chronology.put("synthetic_fixture",true);
            chronology.put("authorization",jdbc.queryForMap("select authorization_id,created_at,valid_from,valid_until,requested_by,approved_by from disposal_authorization where authorization_id=?",id));
            chronology.put("queued_observed_at",queuedAt);
            chronology.put("configuration_saved_and_read_back_at",configurationSavedAt);
            chronology.put("queue_checked_at",clock.nowMillis());
            chronology.put("command",jdbc.queryForMap("select command_id,created_at,issued_at,status,result_code from device_command where command_id=?",command));
            chronology.put("enabled_counter_rules",0);
            chronology.put("wire_frames",frames);
            assertThat(configurationSavedAt).isGreaterThanOrEqualTo(queuedAt);
            Path output=Path.of("target","disposal-mqtt-evidence","postgresql");
            Files.createDirectories(output);
            Files.writeString(output.resolve("automatic-queued-rule-disabled-chronology.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(chronology));
        }
    }

    private void appendEvaluation(String target,String legal) {
        String previous=jdbc.queryForObject("select evaluation_id from rule_evaluation where target_id=? order by evaluated_at desc,evaluation_id desc fetch first 1 rows only",String.class,target);
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons,supersedes_evaluation_id) "
                +"select ?,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,?,freshness_code,plan_match_code,?,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,'replay',created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons,evaluation_id from rule_evaluation where evaluation_id=?",
                key(),Timestamp.from(clock.now()),legal,previous);
    }

    @ParameterizedTest @ValueSource(strings={"VALID","RULE_DISABLED","VERSION","SCHEDULE","AUTH_EXPIRED","POSITION_STALE","EVIDENCE_LEGAL"})
    void automaticReceiptNeverCreatesSuccessorUnderAnyCurrentBoundary(String change) throws Exception {
        String id=queueAutomatic(), command=commandOf(id);
        awaitWire(1);
        String target=jdbc.queryForObject("select target_id from disposal_authorization where authorization_id=?",String.class,id);
        switch(change) {
            case "RULE_DISABLED" -> jdbc.update("update automation_rule_condition set enabled=false where category='counter'");
            case "VERSION" -> jdbc.update("update automation_rule_group set version=832 where category='counter'");
            case "SCHEDULE" -> jdbc.update("update automation_rule_group set schedule_mode='DAILY',start_time='00:00',end_time='00:00' where category='counter'");
            case "AUTH_EXPIRED" -> jdbc.update("update disposal_authorization set valid_from=?,valid_until=? where authorization_id=?",Timestamp.from(clock.now().minusSeconds(120)),Timestamp.from(clock.now().minusSeconds(1)),id);
            case "POSITION_STALE" -> jdbc.update("update target_latest_state set observed_at=? where target_id=?",Timestamp.from(clock.now().minusSeconds(90000)),target);
            case "EVIDENCE_LEGAL" -> appendEvaluation(target,"LEGAL");
            default -> { }
        }
        var authorizedUntil=disposalRepository.findUnlocked(id).validUntil();
        reply(command,0);
        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(statusOf(id)).isEqualTo("COMPLETED"));
        automatic.poll();
        outbox.poll();
        assertThat(children(id)).isEmpty();
        assertThat(frames).hasSize(1);
        var completed=disposalRepository.findUnlocked(id);
        assertThat(completed.requestedBy()).isEqualTo(AutomationPrincipal.USER_ID);
        assertThat(completed.approvedBy()).isNull();
        assertThat(completed.validUntil()).isEqualTo(authorizedUntil);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?",Integer.class,eventId)).isEqualTo(1);
        reply(command,0);
        automatic.poll();
        outbox.poll();
        assertThat(children(id)).isEmpty();
        assertThat(frames).hasSize(1);
        assertThat(commandOf(id)).isEqualTo(command);
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?",Integer.class,binding.opsDeviceId())).isEqualTo(1);
        saveEvidence("automatic-single-authorization-"+change,id);
    }

    @ParameterizedTest @ValueSource(strings={"DISABLED","OUT_OF_SCHEDULE"})
    void manualDirectActionRemainsUsableWithPausedAutomation(String pause) throws Exception {
        automaticFixture();
        if("DISABLED".equals(pause)) jdbc.update("update automation_rule_condition set enabled=false where category='counter'");
        else jdbc.update("update automation_rule_group set schedule_mode='DAILY',start_time='00:00',end_time='00:00' where category='counter'");
        decisions.evaluate("counter",eventId);
        assertThat(runtime.state("counter",eventId).status()).isEqualTo("DISABLED".equals(pause)?"PAUSED":"OUT_OF_SCHEDULE");
        String actor=user("disposal:direct","disposal:read","devices","target:read");
        String id=data(request("/api/v1/disposal-authorizations/direct-execute",actor,key(),body("自动规则暂停后人工明确发起"))
                .andExpect(status().isCreated())).path("authorization_id").asText();
        assertThat(statusOf(id)).isEqualTo("EXECUTING"); awaitWire(1);
        assertThat(frames.get(0).path("data").path("operationCmd").asInt()).isEqualTo(60003);
        saveEvidence("manual-after-auto-"+pause,id);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="qa.manual.pause.browser",matches="true")
    void serveManualActionWithPausedAutomation() throws Exception {
        clock.offset=0;
        automaticFixture();
        // Keep normal time moving: rewinding to the initial observation strands commands whose
        // outbox available_at was captured later by an HTTP thread. Refresh only this synthetic
        // sample's observations below; production freshness and authorization windows stay intact.
        String pause=System.getProperty("qa.manual.pause","DISABLED");
        assertThat(pause).isIn("DISABLED","OUT_OF_SCHEDULE");
        if("DISABLED".equals(pause)) jdbc.update("update automation_rule_condition set enabled=false where category='counter'");
        else jdbc.update("update automation_rule_group set schedule_mode='DAILY',start_time='00:00',end_time='00:00' where category='counter'");
        decisions.evaluate("counter",eventId);
        String expected="DISABLED".equals(pause)?"PAUSED":"OUT_OF_SCHEDULE";
        assertThat(runtime.state("counter",eventId).status()).isEqualTo(expected);
        String actor=user("disposal:direct","disposal:read","devices","target:read","alarm:verify","alarms");
        String userId=jdbc.queryForObject("select user_id from app_session where session_id=?",String.class,actor);
        String account="qa-manual-paused";
        jdbc.update("update app_user set account=?,name='隔离人工接管测试',password_hash=(select password_hash from app_user where account='admin1') where user_id=?",account,userId);
        jdbc.update("update app_role_permission set menu_enabled=true where role_code=(select role_code from app_user where user_id=?)",userId);
        String target=jdbc.queryForObject("select target_id from alarm where alarm_id=(select alarm_id from uav_event where event_id=?)",String.class,eventId);
        Path output=Path.of("target","manual-paused-browser").toAbsolutePath();Files.createDirectories(output);
        Path stop=output.resolve("stop");Files.deleteIfExists(stop);
        var manifest=new java.util.LinkedHashMap<String,Object>();
        manifest.put("port",port);manifest.put("simulated",true);manifest.put("pause",pause);
        manifest.put("event_id",eventId);manifest.put("target_id",target);manifest.put("device_id",binding.opsDeviceId());
        manifest.put("account",account);manifest.put("expected_automatic_state",expected);
        manifest.put("clock_mode","REALTIME");manifest.put("synthetic_observation_refresh_ms",1000);
        manifest.put("database",jdbc.queryForObject("select current_database()",String.class));
        manifest.put("schema",jdbc.queryForObject("select current_schema()",String.class));
        Files.writeString(output.resolve("manifest.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos(),nextObservation=0;
        while(!Files.exists(stop)&&System.nanoTime()<deadline) {
            long now=clock.nowMillis();
            if(now>=nextObservation) {
                // Explicit isolated inputs, not device receipts or renewed action authorization.
                CounterEvidenceFixture.seed(jdbc,eventId,clock.now());
                appendEvaluation(target,"ILLEGAL");
                jdbc.update("update ops_device_state set observed_at=?,received_at=?,last_heartbeat_at=? where device_id=?",
                        now,now,now,binding.opsDeviceId());
                nextObservation=now+1000;
            }
            supervisor.reconcile();outbox.poll();
            var ids=jdbc.queryForList("select authorization_id from disposal_authorization where subject_id=?",String.class,eventId);
            Files.writeString(output.resolve("state.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                    "automatic_state",runtime.state("counter",eventId).status(),"authorization_ids",ids,"wire_count",frames.size(),
                    "clock_ms",clock.nowMillis(),
                    "commands",jdbc.queryForList("select command_id,status,created_at,deadline_at,issued_at from device_command where device_id=?",binding.opsDeviceId()),
                    "outbox",jdbc.queryForList("select o.payload as command_id,o.available_at,o.processed_at,o.attempt_count from outbox_event o join device_command c on c.command_id=o.payload where o.topic='device.control.lingyun' and c.device_id=?",binding.opsDeviceId()))));
            Thread.sleep(250);
        }
        assertThat(Files.exists(stop)).as("Browser owner must finish the isolated manual flow").isTrue();
        var ids=jdbc.queryForList("select authorization_id from disposal_authorization where subject_id=?",String.class,eventId);
        assertThat(ids).hasSize(1);assertThat(frames).hasSize(1);
        assertThat(jdbc.queryForObject("select requested_by from disposal_authorization where authorization_id=?",String.class,ids.get(0))).isEqualTo(userId);
        assertThat(jdbc.queryForObject("select approved_by from disposal_authorization where authorization_id=?",String.class,ids.get(0))).isNull();
        assertThat(runtime.state("counter",eventId).status()).isEqualTo(expected);
        assertThat(frames.get(0).path("data").path("operationCmd").asInt()).isEqualTo(60003);
        saveEvidence("browser-manual-after-auto-"+pause,ids.get(0));
        Files.deleteIfExists(stop);
    }

    @Test void independentJvmFailureKillRecoveryAndRestartDoNotRepeatWireAction() throws Exception {
        automaticFixture();
        jdbc.update("update mqtt_broker set enabled=false where broker_id=?",brokerId); supervisor.reconcile();
        jdbc.update("update mqtt_broker set enabled=true where broker_id=?",brokerId);
        Path output=Path.of("target","automation-restart-evidence","restart-"+java.util.UUID.randomUUID()).toAbsolutePath(); Files.createDirectories(output);
        jdbc.execute("alter table target_latest_state rename to qa_unavailable_latest_state");
        try {
            runChild("FAIL",output);
            assertThat(runtime.lastError()).isNotBlank();
            assertThat(frames).isEmpty();
        } finally { jdbc.execute("alter table qa_unavailable_latest_state rename to target_latest_state"); }
        awaitCrashedLeaseExpiry();
        runChild("RECOVER",output);
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(frames).hasSize(1));
        assertThat(runtime.lastError()).isNull();
        String id=jdbc.queryForObject("select authorization_id from disposal_authorization where subject_id=? and requested_by=?",String.class,eventId,AutomationPrincipal.USER_ID);
        String command=commandOf(id);
        awaitCrashedLeaseExpiry();
        runChild("REPLAY",output);
        assertThat(frames).hasSize(1);
        assertThat(commandOf(id)).isEqualTo(command);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=? and requested_by=?",Integer.class,eventId,AutomationPrincipal.USER_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from device_command where device_id=?",Integer.class,binding.opsDeviceId())).isEqualTo(1);
        saveEvidence("automatic-independent-jvm-restart",id);
    }

    private void awaitCrashedLeaseExpiry() {
        Awaitility.await().atMost(Duration.ofSeconds(35)).until(()->jdbc.queryForObject(
                "select lease_until from mqtt_session_lease where broker_id=?",Long.class,brokerId)<System.currentTimeMillis());
    }

    @ParameterizedTest @ValueSource(strings={"CRASH_BEFORE_PUBLISH","CRASH_PUBLISH","CRASH_COMPLETE"})
    void crashAfterRealPublishNeverBlindlyRepeatsCommand(String failpoint) throws Exception {
        String id=queueAutomatic(),command=commandOf(id);
        jdbc.update("update mqtt_broker set enabled=false where broker_id=?",brokerId); supervisor.reconcile();
        jdbc.update("update mqtt_broker set enabled=true where broker_id=?",brokerId);
        Path output=Path.of("target","automation-restart-evidence",failpoint+"-"+java.util.UUID.randomUUID()).toAbsolutePath(); Files.createDirectories(output);
        runChild(failpoint,output);
        int expectedFrames="CRASH_BEFORE_PUBLISH".equals(failpoint)?0:1;
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(frames).hasSize(expectedFrames));
        awaitCrashedLeaseExpiry();
        runChild("REPLAY",output);
        assertThat(frames).as("crash recovery must not repeat an uncertain external action").hasSize(expectedFrames);
        assertThat(commandOf(id)).isEqualTo(command);
        assertThat(jdbc.queryForObject("select status from device_command where command_id=?",String.class,command)).isEqualTo("SENT");
        saveEvidence("automatic-"+failpoint,id);
    }

    private void runChild(String mode,Path output) throws Exception {
        Path ready=output.resolve(mode+".ready"),log=output.resolve(mode+".log"),classpath=output.resolve(mode+"-classpath.jar"); Files.deleteIfExists(ready);
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var manifest=new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION,"1.0");
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH,java.util.Arrays.stream(cp.split(java.io.File.pathSeparator))
                .map(value->Path.of(value).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" ")));
        try(var archive=new java.util.jar.JarOutputStream(Files.newOutputStream(classpath),manifest)) { }
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").startsWith("Windows")?"java.exe":"java").toString(),
                "-Dspring.devtools.restart.enabled=false","-Dspring.devtools.livereload.enabled=false","-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),
                "-Djdk.net.unixdomain.tmpdir="+System.getProperty("jdk.net.unixdomain.tmpdir",System.getProperty("java.io.tmpdir")),
                "-cp",classpath.toString(),AutomationRestartProcess.class.getName(),mode,ready.toString(),binding.opsDeviceId())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        try(var connection=jdbc.getDataSource().getConnection()) { builder.environment().put("AUTOMATION_RESTART_DB_URL",connection.getMetaData().getURL()); }
        Process process=builder.start();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(55)).until(()->Files.exists(ready)||!process.isAlive());
            assertThat(Files.exists(ready)).withFailMessage("Child %s did not reach marker: %s",mode,Files.readString(log)).isTrue();
            assertThat(Long.parseLong(Files.readString(ready))).isEqualTo(process.pid());
            if("REPLAY".equals(mode)) { assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
        } finally { if(process.isAlive()) { process.destroyForcibly(); assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue(); } }
    }
}
