package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mqtt_eo_manual;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.mqtt.enabled=false", "app.outbox.enabled=false", "app.fusion.enabled=false", "app.rule-engine.enabled=false",
        "app.eo-edge.auto-track.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EoManualTrackApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MqttConfigurationService configuration;
    @Autowired EoEdgeRepository edges;
    @Autowired MqttRepository mqtt;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AppClock clock;
    @Autowired com.uav.lowaltitude.modules.device.application.EoEdgeCommandService edgeCommands;
    @Autowired com.uav.lowaltitude.modules.device.application.DeviceOperationsProcessor operations;
    @Autowired com.uav.lowaltitude.modules.device.application.EoEdgeIngressService edgeIngress;
    @Autowired com.uav.lowaltitude.modules.device.application.EoTrackingScheduler scheduler;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.EoTrackingRepository trackingRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.uav.lowaltitude.modules.device.application.EoTrackingPolicy trackingPolicy;
    String org, district, token, owner, brokerId;
    Binding binding;

    @BeforeEach void setup() throws Exception {
        String user = jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'", String.class);
        AuthContext.set(new AuthUser(user, "admin1", "EO manual", "ROLE-ADMIN", 1, false, "ALL"));
        org = jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROW ONLY", String.class);
        district = jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY", String.class);
        token = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        owner = UUID.randomUUID().toString();
        brokerId = configuration.create(new BrokerInput("EO manual", "127.0.0.1", 1883, false, null, null,
                "127.0.0.1/32", "replay", org, district, null), key()).brokerId();
        configuration.enable(brokerId, 0, true, key());
        assertThat(mqtt.claim(brokerId, owner, clock.nowMillis())).isTrue();
        binding = register("edge-man-" + UUID.randomUUID().toString().substring(0, 6), "eo-man-1");
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',last_heartbeat_at=? WHERE device_id=?",
                clock.nowMillis(), binding.opsDeviceId());
        jdbc.update("UPDATE eo_device_binding SET work_state=0,last_heartbeat_at=? WHERE ops_device_id=?",clock.nowMillis(),binding.opsDeviceId());
    }

    @AfterEach void cleanup() {
        jdbc.update("UPDATE mqtt_broker SET enabled=FALSE");
        jdbc.update("UPDATE outbox_event SET processed_at=? WHERE processed_at IS NULL AND topic LIKE 'eo.%'", clock.nowMillis());
        AuthContext.clear();
    }

    @Test void unifiedStatusIsReadOnlyAndPauseSurvivesRepeatedRequests() throws Exception {
        String target = insertTarget(true);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class);
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-status", target).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_enabled").value(false));
        String key = key();
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/v1/targets/{id}/eo-tracking-pause", target)
                .header("Authorization", bearer()).header("Idempotency-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_paused").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class)).isEqualTo(before);
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-resume", target)
                .header("Authorization", bearer()).header("Idempotency-Key", key()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_enabled").value(false))
                .andExpect(jsonPath("$.data.auto_paused").value(false));
    }

    @Test void unknownClassAndExpiredPositionCannotSteerCamera() throws Exception {
        String target = insertTarget(true);
        jdbc.update("UPDATE target SET object_type_code='UNKNOWN' WHERE target_id=?", target);
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", target)
                .header("Authorization", bearer()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("EO_CLASS_UNSUPPORTED"));
        jdbc.update("UPDATE target SET object_type_code='UAV' WHERE target_id=?", target);
        jdbc.update("UPDATE target_latest_state SET observed_at=TIMESTAMP '2020-01-01 00:00:00' WHERE target_id=?", target);
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", target)
                .header("Authorization", bearer()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("TARGET_POSITION_STALE"));
    }

    @Test void automaticAndManualRaceShareOneTaskAndPauseSurvivesManualBegin() throws Exception {
        doReturn(true).when(trackingPolicy).enabled();
        String target=insertTarget(true);
        String alarm=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) "
                + "VALUES (?,?,?,?,'UAV_INTRUSION','HIGH',CURRENT_TIMESTAMP,'replay',?,?,CURRENT_TIMESTAMP)",alarm,target,binding.sourceId(),alarm,org,district);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) "
                + "VALUES (?,?,'PENDING_VERIFICATION',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",UUID.randomUUID().toString(),alarm,org,district);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var auto=executor.submit(() -> scheduler.poll());
            var manual=executor.submit(() -> mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks",target)
                    .header("Authorization",bearer()).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
            auto.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(manual.get(10,java.util.concurrent.TimeUnit.SECONDS)).isIn(202,409);
        } finally {executor.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task WHERE target_id=?",Long.class,target)).isOne();
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-pause",target).header("Authorization",bearer()).header("Idempotency-Key",key()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_paused").value(true));
        String task=String.valueOf(edges.openTaskByTarget(target).get("task_id"));
        edges.updateTask(task,"ENDING","ENDED",null,clock.nowMillis());
        scheduler.poll();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task WHERE target_id=?",Long.class,target)).isOne();
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks",target).header("Authorization",bearer())
                .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-status",target).header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.auto_paused").value(true))
                .andExpect(jsonPath("$.data.allowed_actions").value(hasItem("PAUSE")));
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-pause",target).header("Authorization",bearer()).header("Idempotency-Key",key()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ENDING"))
                .andExpect(jsonPath("$.data.auto_paused").value(true));
    }

    @Test void pausedReadAndWriteRequireCurrentPermissionsAndQueuedStateNeverClaimsTracking() throws Exception {
        String target=insertTarget(true);
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-status",target)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks",target).header("Authorization",bearer())
                .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-status",target).header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("STARTING"));
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        try {
            mvc.perform(post("/api/v1/targets/{id}/eo-tracking-pause",target).header("Authorization",bearer()).header("Idempotency-Key",key()))
                    .andExpect(status().isForbidden());
        } finally {jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'");}
    }

    @Test void manualIllegalReviewIsScheduledWithoutAlarmOrRisk() {
        doReturn(true).when(trackingPolicy).enabled();
        String target=insertTarget(true), run=UUID.randomUUID().toString(), evaluation=UUID.randomUUID().toString();
        assertThat(jdbc.update("""
                INSERT INTO rule_run(run_id,rule_set_id,rule_set_version_id,mode,trigger_kind,as_of,started_at,status,source_mode,created_at)
                SELECT ?,rule_set_id,rule_set_version_id,'ACTIVE','MANUAL',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'DONE','replay',CURRENT_TIMESTAMP
                FROM rule_set_version ORDER BY rule_set_version_id FETCH FIRST 1 ROW ONLY
                """,run)).isOne();
        jdbc.update("""
                INSERT INTO rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,observed_at,as_of,evaluated_at,
                freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,
                owner_org_id,district_id,source_mode,created_at)
                SELECT ?,run_id,rule_set_version_id,'ACTIVE','TARGET',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,
                'REPLAY','UNDETERMINED','UNDETERMINED',CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('[]' AS JSON),CAST('{}' AS JSON),
                ?,?,'replay',CURRENT_TIMESTAMP FROM rule_run WHERE run_id=?
                """,evaluation,target,org,district,run);
        assertThat(trackingRepository.candidates(clock.nowMillis()-15000,clock.nowMillis(),20)).doesNotContain(target);
        jdbc.update("INSERT INTO legality_review(evaluation_id,review_state,manual_status,version,owner_org_id,district_id,created_at,updated_at) "
                + "VALUES (?,'OVERRIDDEN','ILLEGAL',1,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",evaluation,org,district);
        assertThat(trackingRepository.candidates(clock.nowMillis()-15000,clock.nowMillis(),20)).contains(target);
        scheduler.poll();
        assertThat(edges.openTaskByTarget(target)).isNotNull();
    }

    @Test void legacyFailedButSentTimeoutBlocksTargetAndDeviceUntilExplicitStop() throws Exception {
        String target=insertTarget(true), command=UUID.randomUUID().toString(), task=UUID.randomUUID().toString();
        long now=clock.nowMillis();
        edges.insertCommand(command,"OLD-"+command.substring(0,6),binding.opsDeviceId(),null,"EO_BEGIN_TRACK","old","replay",true,now+1000,now);
        edges.insertTask(task,target,null,binding.opsDeviceId(),command,"old","{}",now);
        edges.updateCommand(command,"QUEUED","SENT",now,null,null);
        edges.updateCommand(command,"SENT","TIMED_OUT",now,"OLD_TIMEOUT","unknown");
        edges.updateTask(task,"OPEN","FAILED",null,now);
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-status",target).header("Authorization",bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("LOST"))
                .andExpect(jsonPath("$.data.allowed_actions").value(hasItem("PAUSE")));
        assertThat(edges.idleDeviceForMode(org,district,null,"replay",now-30000,clock.nowMillis())).isNull();
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks",target).header("Authorization",bearer())
                .header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("EO_RESULT_UNKNOWN"));
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-pause",target).header("Authorization",bearer()).header("Idempotency-Key",key()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ENDING"));
        assertThat(edges.task(task).get("status")).isEqualTo("ENDING");
    }

    @Test void beginRequiresLoginThenPositionThenIdleCamera() throws Exception {
        String noLocation = insertTarget(false);
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", noLocation)
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", noLocation)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TARGET_POSITION_UNAVAILABLE"));
        jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?", binding.opsDeviceId());
        String located = insertTarget(true);
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", located)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("EO_DEVICE_UNAVAILABLE"));
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", UUID.randomUUID())
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TARGET_NOT_FOUND"));
        String idle = insertTarget(true);
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-tasks", idle).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test void busyDeviceAndMissingPositionCannotCreateCommands() throws Exception {
        String located = insertTarget(true), noLocation = insertTarget(false);
        long tasks = jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class);
        long commands = jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class);
        jdbc.update("UPDATE eo_device_binding SET work_state=1 WHERE ops_device_id=?", binding.opsDeviceId());
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-availability", located).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.block_reason").value("当前范围无心跳有效的空闲可追踪设备"));
        for (String body : new String[]{"{}", "{\"device_id\":\"" + binding.opsDeviceId() + "\"}"}) {
            mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", located)
                    .header("Authorization", bearer()).header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("EO_DEVICE_UNAVAILABLE"));
        }
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", noLocation)
                .header("Authorization", bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("TARGET_POSITION_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class)).isEqualTo(tasks);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class)).isEqualTo(commands);
    }

    @Test void deviceReadPermissionCannotBeginTrackingOrProbeAvailability() throws Exception {
        String target = insertTarget(true), role = "ROLE-TRACK-READ-" + UUID.randomUUID().toString().substring(0, 8);
        long tasks = jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class);
        long commands = jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,'跟踪只读测试','',FALSE,TRUE,0,0,0,FALSE)", role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES (?,'devices','READ',TRUE),(?,'target:read','READ',FALSE)", role, role);
        jdbc.update("UPDATE app_user SET role_code=? WHERE account='admin1'", role);
        try {
            mvc.perform(get("/api/v1/targets/{id}/eo-tracking-availability", target).header("Authorization", bearer()))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", target)
                    .header("Authorization", bearer()).header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE app_user SET role_code='ROLE-ADMIN' WHERE account='admin1'"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class)).isEqualTo(tasks);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class)).isEqualTo(commands);
    }

    @Test void operatorBeginThenGetThenSecondBeginConflictsThenEndReleases() throws Exception {
        String targetId = insertTarget(true);
        JsonNode created = mapper.readTree(mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", targetId)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"值班员点选\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.device_id").value(binding.opsDeviceId()))
                .andReturn().getResponse().getContentAsString()).path("data");
        String taskId = created.path("task_id").asText();
        assertThat(jdbc.queryForObject("SELECT reason FROM device_command WHERE command_id=?", String.class,
                created.path("command_id").asText())).isEqualTo("OPERATOR_BEGIN_TRACK");
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-tasks", targetId).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.task_id").value(taskId));
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", targetId)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACK_ALREADY_OPEN"));
        mvc.perform(post("/api/v1/eo-tracking-tasks/{id}/end", taskId)
                        .header("Authorization", bearer()).header("Idempotency-Key", key()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("ENDING"));
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-tasks", targetId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDING"));
        String firstEnd=String.valueOf(edges.task(taskId).get("end_command_id"));
        jdbc.update("UPDATE device_command SET status='TIMED_OUT' WHERE command_id=?",firstEnd);
        JsonNode retried=mapper.readTree(mvc.perform(post("/api/v1/eo-tracking-tasks/{id}/end",taskId)
                        .header("Authorization",bearer()).header("Idempotency-Key",key()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("ENDING"))
                .andReturn().getResponse().getContentAsString()).path("data");
        String retryCommand=retried.path("command_id").asText();
        assertThat(retryCommand).isNotEqualTo(firstEnd);
        assertThat(edges.task(taskId).get("end_command_id")).isEqualTo(retryCommand);
        assertThat(edges.command(firstEnd).get("status")).isEqualTo("TIMED_OUT");
        mvc.perform(post("/api/v1/eo-tracking-tasks/{id}/end",taskId)
                        .header("Authorization",bearer()).header("Idempotency-Key",key()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.command_id").value(retryCommand));
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", targetId)
                        .header("Authorization", bearer()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRACK_ALREADY_OPEN"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"UAV","BIRD"})
    void timedOutStopAutomaticallyRetriesButOnlyReceiptReleasesDevice(String objectType) throws Exception {
        String task = stoppedTask(objectType);
        String original = String.valueOf(edges.task(task).get("end_command_id"));
        timeoutStopAndAdvance(task);
        scheduler.poll();
        String retry = String.valueOf(edges.task(task).get("end_command_id"));
        assertThat(retry).isNotEqualTo(original);
        assertThat(edges.command(original).get("status")).isEqualTo("TIMED_OUT");
        assertThat(edges.task(task).get("status")).isEqualTo("ENDING");
        scheduler.poll();
        assertThat(edges.task(task).get("end_command_id")).isEqualTo(retry);
        String nextTarget = insertTarget(true);
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", nextTarget)
                .header("Authorization", bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("EO_DEVICE_UNAVAILABLE"));
        edges.updateCommand(retry, "QUEUED", "SENT", clock.nowMillis(), null, null);
        receiveStop(task, 200);
        assertThat(edges.task(task).get("status")).isEqualTo("ENDED");
        assertThat(edges.command(retry).get("status")).isEqualTo("SUCCEEDED");
        mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", nextTarget)
                .header("Authorization", bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.device_id").value(binding.opsDeviceId()));
        // An old task's duplicate receipt must not release the new task.
        String nextTask = String.valueOf(edges.openTask(binding.opsDeviceId()).get("task_id"));
        receiveStop(task, 200);
        assertThat(edges.task(nextTask).get("status")).isEqualTo("OPEN");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"UAV","BIRD"})
    void automaticStopRetriesAreBoundedAcrossPollsAndKeepOccupancy(String objectType) throws Exception {
        String task = stoppedTask(objectType);
        for (int attempt = 1; attempt <= 3; attempt++) {
            String previous = String.valueOf(edges.task(task).get("end_command_id"));
            timeoutStopAndAdvance(task);
            scheduler.poll();
            assertThat(edges.task(task).get("end_command_id")).isNotEqualTo(previous);
            assertThat(((Number) edges.task(task).get("stop_retry_count")).intValue()).isEqualTo(attempt);
        }
        timeoutStopAndAdvance(task);
        String last = String.valueOf(edges.task(task).get("end_command_id"));
        for (int i = 0; i < 3; i++) scheduler.poll();
        assertThat(edges.task(task).get("end_command_id")).isEqualTo(last);
        assertThat(edges.task(task).get("status")).isEqualTo("ENDING");
        String target = String.valueOf(edges.task(task).get("target_id"));
        mvc.perform(get("/api/v1/targets/{id}/eo-tracking-status", target).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("END_UNCONFIRMED"))
                .andExpect(jsonPath("$.data.message").value(containsString("重试已达上限")));
        // Reproduce the outbox timeout sweep between the status read and the late stop receipt.
        operations.expireCommands(clock.nowMillis());
        assertThat(edges.command(String.valueOf(edges.task(task).get("begin_command_id"))).get("status"))
                .isEqualTo("TIMED_OUT");
        assertThat(edges.task(task).get("status")).isEqualTo("ENDING");
        receiveStop(task, 200);
        assertThat(edges.task(task).get("status")).isEqualTo("ENDED");
        assertThat(edges.command(last).get("status")).isEqualTo("SUCCEEDED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"mixed_target","unmarked_stop","disabled","offline","broker_disabled","future_heartbeat","cancelled"})
    void automaticStopRecoveryRejectsUnsafeContext(String scenario) throws Exception {
        String task = stoppedTask("UAV");
        timeoutStopAndAdvance(task);
        String stop = String.valueOf(edges.task(task).get("end_command_id"));
        switch (scenario) {
            case "mixed_target" -> jdbc.update("UPDATE target SET source_mode='live' WHERE target_id=?", edges.task(task).get("target_id"));
            case "unmarked_stop" -> jdbc.update("UPDATE device_command SET simulated=FALSE WHERE command_id=?", stop);
            case "disabled" -> jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?", binding.opsDeviceId());
            case "offline" -> jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE' WHERE device_id=?", binding.opsDeviceId());
            case "broker_disabled" -> jdbc.update("UPDATE mqtt_broker SET enabled=FALSE WHERE broker_id=?", brokerId);
            case "future_heartbeat" -> jdbc.update("UPDATE eo_device_binding SET last_heartbeat_at=? WHERE ops_device_id=?", clock.nowMillis()+1000, binding.opsDeviceId());
            case "cancelled" -> edges.updateCommand(stop, "TIMED_OUT", "CANCELLED", clock.nowMillis()-31000, null, null);
        }
        scheduler.poll();
        assertThat(edges.task(task).get("end_command_id")).isEqualTo(stop);
        assertThat(((Number) edges.task(task).get("stop_retry_count")).intValue()).isZero();
        assertThat(edges.task(task).get("status")).isEqualTo("ENDING");
    }

    @Test void automaticStopRetryWaitsForFreshHeartbeatAndRetryDelay() throws Exception {
        String task = stoppedTask("BIRD");
        String original = String.valueOf(edges.task(task).get("end_command_id"));
        edges.updateCommand(original, "QUEUED", "SENT", clock.nowMillis(), null, null);
        edgeCommands.timeout(original, "missing stop receipt");
        scheduler.poll();
        assertThat(edges.task(task).get("end_command_id")).isEqualTo(original);
        doReturn(clock.nowMillis() + 31_000).when(clock).nowMillis();
        scheduler.poll();
        assertThat(edges.task(task).get("end_command_id")).isEqualTo(original);
        refreshStopDevice();
        scheduler.poll();
        assertThat(edges.task(task).get("end_command_id")).isNotEqualTo(original);
    }

    @Test void concurrentAutomaticStopRecoveryCreatesOneRetry() throws Exception {
        String task = stoppedTask("UAV");
        timeoutStopAndAdvance(task);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> scheduler.poll());
            var second = executor.submit(() -> scheduler.poll());
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(((Number) edges.task(task).get("stop_retry_count")).intValue()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=? AND reason='AUTO_STOP_RECOVERY'",
                Long.class, binding.opsDeviceId())).isOne();
    }

    @Test void automaticRecoveryHandsDeviceToWaitingTargetWithoutResumingPausedTarget() throws Exception {
        String task = stoppedTask("BIRD");
        timeoutStopAndAdvance(task);
        String nextTarget = insertTarget(true), alarm = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) "
                + "VALUES (?,?,?,?,'UAV_INTRUSION','HIGH',CURRENT_TIMESTAMP,'replay',?,?,CURRENT_TIMESTAMP)",alarm,nextTarget,binding.sourceId(),alarm,org,district);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) "
                + "VALUES (?,?,'PENDING_VERIFICATION',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",UUID.randomUUID().toString(),alarm,org,district);
        doReturn(true).when(trackingPolicy).enabled();
        scheduler.poll();
        assertThat(edges.openTaskByTarget(nextTarget)).isNull();
        String retry = String.valueOf(edges.task(task).get("end_command_id"));
        edges.updateCommand(retry, "QUEUED", "SENT", clock.nowMillis(), null, null);
        receiveStop(task, 200);
        scheduler.poll();
        var next = edges.openTaskByTarget(nextTarget);
        assertThat(next).isNotNull();
        assertThat(next.get("ops_device_id")).isEqualTo(binding.opsDeviceId());
        assertThat(next.get("origin")).isEqualTo("AUTO");
        assertThat(trackingRepository.paused(String.valueOf(edges.task(task).get("target_id")))).isTrue();
    }

    private String stoppedTask(String objectType) throws Exception {
        String target = insertTarget(true);
        jdbc.update("UPDATE target SET object_type_code=? WHERE target_id=?", objectType, target);
        JsonNode created = mapper.readTree(mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", target)
                .header("Authorization", bearer()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");
        String task = created.path("task_id").asText();
        mvc.perform(post("/api/v1/eo-tracking-tasks/{id}/end", task)
                .header("Authorization", bearer()).header("Idempotency-Key", key()))
                .andExpect(status().isAccepted());
        return task;
    }

    private void timeoutStopAndAdvance(String task) {
        String command = String.valueOf(edges.task(task).get("end_command_id"));
        edges.updateCommand(command, "QUEUED", "SENT", clock.nowMillis(), null, null);
        edgeCommands.timeout(command, "missing stop receipt");
        doReturn(clock.nowMillis() + 31_000).when(clock).nowMillis();
        refreshStopDevice();
    }

    private void refreshStopDevice() {
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',last_heartbeat_at=? WHERE device_id=?",
                clock.nowMillis(), binding.opsDeviceId());
        jdbc.update("UPDATE eo_device_binding SET last_heartbeat_at=? WHERE ops_device_id=?",
                clock.nowMillis(), binding.opsDeviceId());
        assertThat(mqtt.claim(brokerId, owner, clock.nowMillis())).isTrue();
    }

    private void receiveStop(String task, int resultCode) {
        long now = clock.nowMillis();
        byte[] receipt = EoEdgeEnvelope.encode("EndTracking", binding.edgeId(), now,
                java.util.Map.of("deviceId", binding.externalDeviceId(), "taskId", task, "codeStatus", resultCode, "workState", 0));
        edgeIngress.receive(brokerId, owner, binding.reportingTopic(), receipt, 1, 1, false, false, now);
    }

    @Test void videoReadIsScopedAndDoesNotCreateWork() throws Exception {
        String target = insertTarget(true);
        long tasks = jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class);
        long commands = jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class);
        mvc.perform(get("/api/v1/targets/{id}/video", target)).andExpect(status().isUnauthorized());
        video(target, "NO_TASK", "NONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class)).isEqualTo(tasks);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class)).isEqualTo(commands);
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE account='admin1'");
        try {
            mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                    .andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'"); }
        jdbc.update("UPDATE app_user SET scope_mode='ASSIGNED' WHERE account='admin1'");
        try {
            mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                    .andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE account='admin1'"); }
        String role = "ROLE-VIDEO-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO app_role (role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES (?,'视频只读测试','',FALSE,TRUE,0,0,0,FALSE)", role);
        jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,menu_enabled) VALUES (?,'devices','READ',TRUE)", role);
        jdbc.update("UPDATE app_user SET role_code=? WHERE account='admin1'", role);
        try {
            mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                    .andExpect(status().isForbidden());
            // OBS-03：看画面只是读取。设备查看权限加目标读取资格即可，不需要设备操作权限；发起跟踪仍要 devices.op。
            jdbc.update("INSERT INTO app_role_permission (role_code,permission_code,permission_level,menu_enabled) VALUES (?,'target:read','READ',FALSE)", role);
            mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NO_TASK"));
            mvc.perform(post("/api/v1/targets/{id}/eo-tracking-tasks", target)
                    .header("Authorization", bearer()).header("Idempotency-Key", key())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        } finally { jdbc.update("UPDATE app_user SET role_code='ROLE-ADMIN' WHERE account='admin1'"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task", Long.class)).isEqualTo(tasks);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_command", Long.class)).isEqualTo(commands);
    }

    @Test void noTaskVideoSaysWhetherTheAreaHasAnyOnlineEo() throws Exception {
        // ZT-18：没有跟踪任务时说清光电本身的状况，而不是只说“没有跟踪任务”。
        // 光电设备类型有两种写法：接入登记的 EO，和种子/协议缩写 oe，两种都算光电。
        String target = insertTarget(true);
        assertThat(videoReason(target)).isEqualTo("当前目标没有光电跟踪任务，暂无可查看画面。");
        java.util.List<String> online = jdbc.queryForList("""
                SELECT d.device_id FROM ops_device d JOIN device_business_scope bs ON bs.ops_device_id=d.device_id
                JOIN ops_device_state st ON st.device_id=d.device_id
                WHERE UPPER(d.device_type_code) IN ('EO','OE') AND bs.owner_org_id=? AND bs.district_id=? AND st.connectivity='ONLINE'
                """, String.class, org, district);
        assertThat(online).contains(binding.opsDeviceId());
        try {
            for (String device : online) jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE' WHERE device_id=?", device);
            assertThat(videoReason(target)).contains("光电设备都不在线", "离线、异常或状态未知").doesNotContain("共 0 台");
        } finally {
            for (String device : online) jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE' WHERE device_id=?", device);
        }
        String emptyDistrict = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES (?,?,?,TRUE,0,0,0)",
                emptyDistrict, "NO-EO-" + emptyDistrict.substring(0, 8), "无光电测试区域" + emptyDistrict.substring(0, 8));
        String elsewhere = insertTarget(true);
        jdbc.update("UPDATE target SET district_id=? WHERE target_id=?", emptyDistrict, elsewhere);
        assertThat(videoReason(elsewhere)).contains("目标所在区域没有光电设备");
    }

    private String videoReason(String target) throws Exception {
        JsonNode data = mapper.readTree(mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NO_TASK"))
                .andReturn().getResponse().getContentAsString()).path("data");
        return data.path("reason").asText();
    }

    @Test void videoRequiresMatchingReceiptAndDoesNotManufactureCanvasVideo() throws Exception {
        String target = insertTarget(true);
        String command = UUID.randomUUID().toString(), task = UUID.randomUUID().toString();
        long now = clock.nowMillis();
        edges.insertCommand(command, "VIDEO-" + command.substring(0, 6), binding.opsDeviceId(), null,
                "EO_BEGIN_TRACK", "video fixture", "replay", true, now + 10000, now);
        edges.insertTask(task, target, null, binding.opsDeviceId(), command, "video fixture", "{}", now);
        video(target, "QUEUED", "NONE");
        edges.updateCommand(command, "QUEUED", "SENT", now, null, null);
        video(target, "WAITING", "NONE");
        edges.updateCommand(command, "SENT", "SUCCEEDED", now, "200", null);
        video(target, "RECEIPT_UNAVAILABLE", "NONE");
        String payload = mapper.writeValueAsString(java.util.Map.of("event", "BeginTracking", "edgeId", binding.edgeId(), "timestamp", now,
                "metadata", java.util.Map.of("taskId", task, "deviceId", binding.externalDeviceId(), "codeStatus", 200)));
        String inbox = edges.inbox(binding, EoEdgeEnvelope.decode(binding.reportingTopic(),
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)), now);
        edges.addReceipt(command, inbox, "200", now, payload);
        edges.trackingReport(task,now,now);
        video(target, "TRACKING", "NONE");
        mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.reason").value("设备已确认跟踪，等待模拟器光电设备推送视频流。"));
        jdbc.update("UPDATE command_receipt SET payload=? WHERE command_id=?", payload.replace(task, UUID.randomUUID().toString()), command);
        video(target, "RECEIPT_UNAVAILABLE", "NONE");
        jdbc.update("UPDATE command_receipt SET payload=? WHERE command_id=?", payload, command);
        jdbc.update("UPDATE target SET source_mode='live' WHERE target_id=?", target);
        video(target, "NOT_INTEGRATED", "NONE");
        jdbc.update("UPDATE target SET source_mode='replay' WHERE target_id=?", target);
        jdbc.update("UPDATE device_command SET simulated=FALSE WHERE command_id=?", command);
        video(target, "NOT_INTEGRATED", "NONE");
        jdbc.update("UPDATE device_command SET simulated=TRUE,status='TIMED_OUT' WHERE command_id=?", command);
        video(target, "TIMED_OUT", "NONE");
        jdbc.update("UPDATE device_command SET status='FAILED' WHERE command_id=?", command);
        video(target, "FAILED", "NONE");
        jdbc.update("UPDATE eo_tracking_task SET status='ENDED' WHERE task_id=?", task);
        video(target, "ENDED", "NONE");
    }

    private void video(String target, String expected, String playback) throws Exception {
        mvc.perform(get("/api/v1/targets/{id}/video", target).header("Authorization", bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.target_id").value(target))
                .andExpect(jsonPath("$.data.status").value(expected))
                .andExpect(jsonPath("$.data.playback_type").value(playback))
                .andExpect(jsonPath("$.data.checked_at").isNumber());
    }

    private Binding register(String edgeId, String deviceId) {
        String opsId = configuration.register(new Registration(EoEdgeEnvelope.PROTOCOL, brokerId, null, deviceId, null,
                "replay", org, district, "EO-" + UUID.randomUUID(), "光电夹具", null, null, null, edgeId), key());
        return edges.binding(opsId, false);
    }

    protected String insertTarget(boolean withLocation) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version)
                VALUES (?,?,'UAV','replay',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """, id, "EO-T-" + id.substring(0, 8), org, district);
        if (withLocation) {
            jdbc.update("""
                    INSERT INTO target_latest_state
                        (target_id,location,altitude_amsl_m,speed_mps,heading_deg,observed_at,received_at,created_at,updated_at,version)
                    VALUES (?,GEOMETRY 'SRID=4326;POINT (118.5 37.4)',40,12,90,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                    """, id, new java.sql.Timestamp(clock.nowMillis()-1), new java.sql.Timestamp(clock.nowMillis()-1));
        }
        return id;
    }

    private String bearer() { return "Bearer " + token; }
    private static String key() { return UUID.randomUUID().toString(); }
}
