package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceWorkflowService;
import com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** V2 cases use real HTTP authorization, persistence and recovery rules with independent dynamic fixtures. */
@SpringBootTest(properties={"app.dev-seed.enabled=false", "app.bootstrap-admin.enabled=false",
        "app.handoff.channel=none", "app.flight.status-advance.enabled=false",
        "app.flight-device-check.schedule.enabled=false", "app.device-maintenance.recovery.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(DeviceMaintenanceCaseApiTest.ClockConfiguration.class)
class DeviceMaintenanceCaseApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired DeviceMaintenanceWorkflowService workflows;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository eoEdges;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.MqttRepository mqttDevices;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceFacts;
    @Autowired CaseClock clock;
    @SpyBean FlightDeviceCheckService checks;
    String org, district, otherOrg, otherDistrict, routeVersion, plan, secondPlan, device;
    String reporter, reporterRole, reporterSession, handler, session;
    long now;

    @BeforeEach void fixture() throws Exception {
        now=System.currentTimeMillis();clock.set(now);
        org=id();district=id();otherOrg=id();otherDistrict=id();
        catalog(org,district);catalog(otherOrg,otherDistrict);
        String route=id();routeVersion=id();
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,?,TRUE,'live',?,?,current_timestamp,current_timestamp,0)",route,route,"独立处置测试航线",org,district);
        boolean postgres;
        try(var connection=jdbc.getDataSource().getConnection()){
            postgres=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        }
        String geometry=postgres?"ST_GeomFromText('LINESTRING(118 37,118.01 37)',4326)"
                :"GEOMETRY 'SRID=4326;LINESTRING(118 37,118.01 37)'";
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) VALUES(?,?,1,"+geometry+",100,current_timestamp,current_timestamp)",routeVersion,route);
        device=sensor();
        plan=plan();secondPlan=plan();
        reporterRole="ROLE-CASE-"+id().substring(0,8);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)",reporterRole,reporterRole);
        for(String permission:List.of("flight:read","route:read","devices","handoff:create"))
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,'OP',FALSE,current_timestamp)",reporterRole,permission);
        reporter=id();handler=id();
        reporterSession=user(reporter,reporterRole,org,district);
        session=user(handler,"ROLE-BACKEND",org,district);
    }

    @Test void sensingMapReportNeedsNoPlanPermissionAndSharesThePlanCase() throws Exception {
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=? AND permission_code IN ('flight:read','route:read')",reporterRole);
        JsonNode before=data(auth(get(deviceReportPath(device)),reporterSession));
        assertThat(before.path("can_report").asBoolean()).isTrue();
        String key=id();JsonNode created=data(deviceReport(device,key));
        String task=created.path("task").path("task_id").asText();
        assertThat(created.path("reused").asBoolean()).isFalse();
        assertThat(created.path("task").path("my_reported_at").asLong()).isEqualTo(now);
        assertThat(data(deviceReport(device,key)).path("task").path("task_id").asText()).isEqualTo(task);
        assertThat(count("ops_maintenance_plan","task_id",task)).isZero();
        assertThat(count("ops_maintenance_report","task_id",task)).isEqualTo(1);
        assertThat(count("ops_device_maintenance_notice_attempt","task_id",task)).isEqualTo(1);
        for(String permission:List.of("flight:read","route:read"))
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,'OP',FALSE,current_timestamp)",reporterRole,permission);
        assertThat(create(plan,device,id()).path("task_id").asText()).isEqualTo(task);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(1);
        assertThat(count("ops_maintenance_plan","task_id",task)).isEqualTo(1);
        healthy();auto(task);
        assertThat(data(auth(get(deviceReportPath(device)),reporterSession)).path("task").path("workflow_state").asText()).isEqualTo("READY_TO_COMPLETE");
        mvc.perform(auth(post(workflowPath(task)+"/actions"),reporterSession).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"CONFIRM_COMPLETE\",\"expected_version\":"+version(task)+"}").header("Idempotency-Key",id())).andExpect(status().isForbidden());
    }

    @Test void sensingReportAssociatesExistingCaseAndDoesNotLeakPlanData() throws Exception {
        String task=create();
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",reporter);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",reporter,otherOrg,otherDistrict);
        jdbc.update("UPDATE device_business_scope SET owner_org_id=?,district_id=? WHERE ops_device_id=?",otherOrg,otherDistrict,device);
        JsonNode result=data(deviceReport(device,id()));
        assertThat(result.path("reused").asBoolean()).isTrue();
        assertThat(result.path("task").path("task_id").asText()).isEqualTo(task);
        assertThat(result.toString()).doesNotContain(plan,org,district,"notification_attempts","related_plans");
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(1);
    }

    @Test void sensingReportCannotReusePlanOrOtherDeviceIdempotencyKey() throws Exception {
        String key=id();create(plan,device,key);
        mvc.perform(deviceReport(device,key)).andExpect(status().isConflict());
        String deviceKey=id();data(deviceReport(device,deviceKey));
        mvc.perform(deviceReport(sensor(),deviceKey)).andExpect(status().isConflict());
        mvc.perform(report(plan,device,deviceKey)).andExpect(status().isConflict());
    }

    @Test void sensingReportEnforcesDeviceScopeAndActionPermission() throws Exception {
        jdbc.update("DELETE FROM device_business_scope WHERE ops_device_id=?",device);
        mvc.perform(auth(get(deviceReportPath(device)),reporterSession)).andExpect(status().isNotFound());
        mvc.perform(deviceReport(device,id())).andExpect(status().isNotFound());
        device=sensor();
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=? AND permission_code='handoff:create'",reporterRole);
        assertThat(data(auth(get(deviceReportPath(device)),reporterSession)).path("can_report").asBoolean()).isFalse();
        mvc.perform(deviceReport(device,id())).andExpect(status().isForbidden());
        assertThat(count("ops_device_maintenance_task","device_id",device)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"GOOD","UNKNOWN"})
    void sensingReportDoesNotInventFaultFromMissingHealth(String health) throws Exception {
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',health_code=?,has_alarm=FALSE,health_observed_at=NULL,alarm_observed_at=NULL WHERE device_id=?",health,device);
        assertThat(data(auth(get(deviceReportPath(device)),reporterSession)).path("can_report").asBoolean()).isFalse();
        mvc.perform(deviceReport(device,id())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEVICE_NOT_ABNORMAL"));
    }

    @Test void sensingReportIgnoresOldOrDifferentSourceEvidence() throws Exception {
        jdbc.update("UPDATE ops_device_state SET health_observed_at=?,alarm_observed_at=? WHERE device_id=?",now-301000,now-301000,device);
        mvc.perform(deviceReport(device,id())).andExpect(status().isConflict());
        fault();
        jdbc.update("UPDATE ops_device_state SET health_source_mode='replay',alarm_source_mode='replay',health_simulated=TRUE,alarm_simulated=TRUE WHERE device_id=?",device);
        mvc.perform(deviceReport(device,id())).andExpect(status().isConflict());
        jdbc.update("UPDATE ops_device SET source_mode='replay',simulated=TRUE WHERE device_id=?",device);
        assertThat(data(deviceReport(device,id())).path("task").path("simulated").asBoolean()).isTrue();
        jdbc.update("UPDATE ops_device SET source_mode='live',simulated=FALSE WHERE device_id=?",device);
        assertThat(data(auth(get(deviceReportPath(device)),reporterSession)).has("task")).isFalse();
    }

    @Test void sensingReportCanAttachOpenOfflineIncidentAndNewFaultAfterCompletion() throws Exception {
        incident();jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE',health_code='UNKNOWN',has_alarm=FALSE,health_observed_at=NULL,alarm_observed_at=NULL WHERE device_id=?",device);
        String task=data(deviceReport(device,id())).path("task").path("task_id").asText();
        assertThat(data(deviceReport(device,id())).path("task").path("task_id").asText()).isEqualTo(task);
        healthy();auto(task);action(task,"CONFIRM_COMPLETE",version(task),id());
        assertThat(data(auth(get(deviceReportPath(device)),reporterSession)).path("task").path("workflow_state").asText()).isEqualTo("COMPLETED");
        fault();assertThat(data(deviceReport(device,id())).path("task").path("task_id").asText()).isNotEqualTo(task);
    }

    static String deviceReportPath(String device){return "/api/v1/devices/"+device+"/maintenance-report";}
    MockHttpServletRequestBuilder deviceReport(String device,String key){return auth(post(deviceReportPath(device)),reporterSession).header("Idempotency-Key",key);}

    @Test void reportsAcrossPlansShareOneCaseAndExactRetriesDoNotAppendReports() throws Exception {
        String firstKey=id();JsonNode first=create(plan,device,firstKey);
        String task=first.path("task_id").asText();
        assertThat(first.path("workflow_version").asInt()).isEqualTo(2);
        assertThat(first.path("can_handle").asBoolean()).isFalse();
        assertThat(create(plan,device,firstKey).path("task_id").asText()).isEqualTo(task);
        assertThat(create(secondPlan,device,id()).path("task_id").asText()).isEqualTo(task);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(1);
        assertThat(count("ops_maintenance_plan","task_id",task)).isEqualTo(2);
        assertThat(count("ops_maintenance_report","task_id",task)).isEqualTo(2);
        assertThat(count("ops_device_maintenance_notice_attempt","task_id",task)).isEqualTo(1);
        for(String linked:List.of(plan,secondPlan)){
            JsonNode result=data(auth(get(reportPath(linked)).param("device_id",device),reporterSession));
            assertThat(result.path("total").asInt()).isEqualTo(1);
            assertThat(result.path("items").get(0).path("task_id").asText()).isEqualTo(task);
        }
        JsonNode view=workflow(task);
        assertThat(view.path("related_plans")).hasSize(2);
        assertThat(view.path("related_reports")).hasSize(2);
        mvc.perform(report(secondPlan,device,firstKey)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test void normalCaseNeedsOnlyStartAndConfirmWithAutomaticRecoveryBetweenThem() throws Exception {
        String task=create();
        JsonNode started=action(task,"START",version(task),id());
        assertThat(started.path("state").asText()).isEqualTo("PROCESSING");
        assertThat(started.path("allowed_actions").toString()).doesNotContain("VERIFY_RECOVERY","SUBMIT_VERIFICATION");
        String incident=incident();healthy();auto(task);
        JsonNode ready=workflow(task);
        assertThat(ready.path("state").asText()).isEqualTo("READY_TO_COMPLETE");
        assertThat(ready.path("recovery").path("result").asText()).isEqualTo("PASS");
        assertThat(ready.path("primary_action").asText()).isEqualTo("CONFIRM_COMPLETE");
        assertThat(jdbc.queryForObject("SELECT stage FROM device_incident WHERE incident_id=?",String.class,incident)).isEqualTo("RECOVERED");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM ops_maintenance_workflow_event WHERE task_id=? AND action='AUTO_RECOVERED'",String.class,task)).isNull();
        String key=id();long readyVersion=version(task);
        JsonNode complete=action(task,"CONFIRM_COMPLETE",readyVersion,key);
        assertThat(complete.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(complete.path("task").path("status").asText()).isEqualTo("HANDLED");
        assertThat(complete.path("allowed_actions")).isEmpty();
        assertThat(action(task,"CONFIRM_COMPLETE",readyVersion,key).path("state").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=? AND actor_id IS NOT NULL",Long.class,task)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT handled_by FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo(handler);
        assertThat(jdbc.queryForObject("SELECT active_key FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void healthyAutomaticTrackingAllowsRecoveryAndCompletionWithoutStoppingTracking(String mode) throws Exception {
        useEoDevice(mode);
        String task=create();action(task,"START",version(task),id());
        healthyEo();String tracking=normalAutoTracking();
        assertThat(deviceFacts.hasActiveWork(device)).isTrue(); // Still busy for deletion/reboot/commissioning.
        assertThat(deviceFacts.hasRecoveryBlockingWork(device,now-15000,now)).isFalse();
        AuthContext.clear();workflows.checkDeviceAutomatically(device);
        assertThat(workflow(task).path("state").asText()).isEqualTo("READY_TO_COMPLETE");
        assertThat(workflow(task).path("recovery").path("result").asText()).isEqualTo("PASS");
        assertThat(action(task,"CONFIRM_COMPLETE",version(task),id()).path("state").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM eo_tracking_task WHERE task_id=?",String.class,tracking)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT end_command_id FROM eo_tracking_task WHERE task_id=?",String.class,tracking)).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"MANUAL","STARTING","BEGIN_FAILED","MISSING_BEGIN","MISSING_REPORT",
            "STALE_REPORT","FUTURE_REPORT","WRONG_SOURCE","MISSING_BINDING","ENDING","STOP_FAILED","STOP_TIMEOUT",
            "FAILED_UNKNOWN_BEGIN","FAILED_UNKNOWN_STOP","OTHER_COMMAND","TRACKING_FAILED"})
    void abnormalTrackingAndUnconfirmedStopsRejectCompletionAndRevokeReadyState(String condition) throws Exception {
        useEoDevice("live");String task=create();action(task,"START",version(task),id());
        healthyEo();String tracking=normalAutoTracking();auto(task);
        assertThat(workflow(task).path("state").asText()).isEqualTo("READY_TO_COMPLETE");
        long readyVersion=version(task);
        String begin=jdbc.queryForObject("SELECT begin_command_id FROM eo_tracking_task WHERE task_id=?",String.class,tracking);
        switch(condition){
            case "MANUAL" -> jdbc.update("UPDATE eo_tracking_task SET origin='MANUAL' WHERE task_id=?",tracking);
            case "STARTING" -> jdbc.update("UPDATE device_command SET status='SENT' WHERE command_id=?",begin);
            case "BEGIN_FAILED" -> jdbc.update("UPDATE device_command SET status='FAILED' WHERE command_id=?",begin);
            case "MISSING_BEGIN" -> jdbc.update("UPDATE eo_tracking_task SET begin_command_id=NULL WHERE task_id=?",tracking);
            case "MISSING_REPORT" -> jdbc.update("UPDATE eo_tracking_task SET last_report_observed_at=NULL WHERE task_id=?",tracking);
            case "STALE_REPORT" -> jdbc.update("UPDATE eo_tracking_task SET last_report_observed_at=? WHERE task_id=?",now-15001,tracking);
            case "FUTURE_REPORT" -> jdbc.update("UPDATE eo_tracking_task SET last_report_at=? WHERE task_id=?",now+1,tracking);
            case "WRONG_SOURCE" -> jdbc.update("UPDATE device_command SET source_mode='replay',simulated=TRUE WHERE command_id=?",begin);
            case "MISSING_BINDING" -> jdbc.update("DELETE FROM eo_device_binding WHERE ops_device_id=?",device);
            case "ENDING" -> jdbc.update("UPDATE eo_tracking_task SET status='ENDING' WHERE task_id=?",tracking);
            case "STOP_FAILED","STOP_TIMEOUT","FAILED_UNKNOWN_STOP" -> {
                String stop=eoCommand("EO_END_TRACK",condition.equals("STOP_FAILED")?"FAILED":"TIMED_OUT");
                jdbc.update("UPDATE eo_tracking_task SET status=?,end_command_id=? WHERE task_id=?",
                        condition.equals("FAILED_UNKNOWN_STOP")?"FAILED":"ENDING",stop,tracking);
            }
            case "FAILED_UNKNOWN_BEGIN" -> {
                jdbc.update("UPDATE eo_tracking_task SET status='FAILED' WHERE task_id=?",tracking);
                jdbc.update("UPDATE device_command SET status='TIMED_OUT' WHERE command_id=?",begin);
            }
            case "OTHER_COMMAND" -> eoCommand("REBOOT","QUEUED");
            case "TRACKING_FAILED" -> eoEdges.trackingFailure(tracking,now,now);
        }
        mvc.perform(actionRequest(task,"CONFIRM_COMPLETE",readyVersion,id()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_RECOVERY_BLOCKED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=? AND action='CONFIRM_COMPLETE'",Long.class,task)).isZero();
        auto(task);
        assertThat(workflow(task).path("state").asText()).isEqualTo("PROCESSING");
        assertThat(workflow(task).path("recovery").path("reason_codes").toString()).contains("ACTIVE_DEVICE_WORK");
    }

    @Test void confirmedStopClearsEarlierUncertaintyButFreshHeartbeatCannotClearIt() throws Exception {
        useEoDevice("replay");String task=create();healthyEo();String tracking=normalAutoTracking();
        String stop=eoCommand("EO_END_TRACK","TIMED_OUT");
        jdbc.update("UPDATE eo_tracking_task SET status='FAILED',end_command_id=? WHERE task_id=?",stop,tracking);
        healthyEo();auto(task);
        assertThat(workflow(task).path("recovery").path("reason_codes").toString()).contains("ACTIVE_DEVICE_WORK");
        jdbc.update("UPDATE device_command SET status='SUCCEEDED' WHERE command_id=?",stop);
        jdbc.update("UPDATE eo_tracking_task SET status='ENDED',ended_at=? WHERE task_id=?",now,tracking);
        auto(task);
        assertThat(workflow(task).path("state").asText()).isEqualTo("READY_TO_COMPLETE");
    }

    void useEoDevice(String mode){
        String broker=id();
        mqttDevices.insertBroker(broker,new com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput(
                "恢复核验光电", "127.0.0.1",1883,false,null,null,"127.0.0.1/32",mode,org,district,null),now);
        device=eoEdges.register(new com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration(
                com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope.PROTOCOL,broker,null,id(),null,mode,org,district,
                id(),"光电-"+id(),null,null,null,id()),now);
        jdbc.update("UPDATE ops_device SET enabled=TRUE WHERE device_id=?",device);
        observeEo(true);stubPlan(plan,device);stubPlan(secondPlan,device);
    }
    void healthyEo(){advance(1000);observeEo(false);}
    void observeEo(boolean fault){
        var source=jdbc.queryForMap("SELECT source_id,source_mode,simulated FROM ops_device WHERE device_id=?",device);
        jdbc.update("""
                UPDATE ops_device_state SET connectivity='ONLINE',health_code=?,has_alarm=?,observed_at=?,received_at=?,
                  last_heartbeat_at=?,health_observed_at=?,alarm_observed_at=?,health_source_id=?,alarm_source_id=?,
                  health_source_mode=?,alarm_source_mode=?,health_simulated=?,alarm_simulated=? WHERE device_id=?
                """,fault?"BAD":"GOOD",fault,now,now,now,now,now,source.get("source_id"),source.get("source_id"),
                source.get("source_mode"),source.get("source_mode"),source.get("simulated"),source.get("simulated"),device);
    }
    String eoCommand(String type,String status){
        var source=jdbc.queryForMap("SELECT source_mode,simulated FROM ops_device WHERE device_id=?",device);
        String command=id();
        eoEdges.insertCommand(command,command,device,null,type,"恢复核验测试",source.get("source_mode").toString(),
                Boolean.TRUE.equals(source.get("simulated")),now+30000,now-1000);
        jdbc.update("UPDATE device_command SET status=?,issued_at=?,completed_at=? WHERE command_id=?",status,now-900,now-500,command);
        return command;
    }
    String normalAutoTracking(){
        String tracking=id();
        eoEdges.insertTask(tracking,null,null,device,eoCommand("EO_BEGIN_TRACK","SUCCEEDED"),"自动追踪","{}",now-1000);
        jdbc.update("UPDATE eo_tracking_task SET origin='AUTO' WHERE task_id=?",tracking);
        eoEdges.trackingReport(tracking,now-100,now-50);
        return tracking;
    }

    @Test void systemDiscoveryAndHumanReportHaveIndependentStableTimesOnTheSameCase() throws Exception {
        incident();AuthContext.clear();workflows.runAutomaticBatch();
        String task=jdbc.queryForObject("SELECT task_id FROM ops_device_maintenance_task WHERE device_id=?",String.class,device);
        long discovered=now;
        JsonNode automatic=workflow(task).path("task").path("report_summary");
        assertThat(automatic.path("origin").asText()).isEqualTo("SYSTEM");
        assertThat(automatic.hasNonNull("latest_manual_report")).isFalse();
        advance(12000);long reported=now;String key=id();
        JsonNode submitted=create(plan,device,key);
        assertThat(submitted.path("task_id").asText()).isEqualTo(task);
        JsonNode summary=submitted.path("report_summary");
        assertThat(summary.path("latest_manual_report").path("reported_at").asLong()).isEqualTo(reported);
        assertThat(summary.path("latest_manual_report").path("plan_no").asText()).isEqualTo(plan);
        assertThat(summary.path("origin").asText()).isEqualTo("SYSTEM");
        for(int i=0;i<3;i++){
            advance(5000);auto(task);
            JsonNode item=data(auth(get("/api/v1/device-maintenance-tasks").param("device_id",device),session)).path("items").get(0);
            assertThat(item.path("reported_at").asLong()).isEqualTo(discovered);
            assertThat(item.path("report_summary")).isEqualTo(summary);
        }
        assertThat(create(plan,device,key).path("report_summary")).isEqualTo(summary);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(1);
    }

    @Test void reportSummaryUsesLatestVisibleManualReportAndNotNewerAutomaticChecks() throws Exception {
        String task=create();advance(10000);
        JsonNode latest=create(secondPlan,device,id()).path("report_summary").path("latest_manual_report");
        assertThat(latest.path("plan_id").asText()).isEqualTo(secondPlan);
        assertThat(latest.path("reported_at").asLong()).isEqualTo(now);
        // A newer scheduled report must not hide the operator's submission.
        advance(5000);
        jdbc.update("INSERT INTO ops_maintenance_report(report_id,task_id,plan_id,actor_id,actor_name,reason,reported_at,report_key) VALUES(?,?,?,NULL,'系统自动检查','明确设备异常',?,?)",id(),task,secondPlan,now,id());
        assertThat(workflow(task).path("task").path("report_summary").path("latest_manual_report")).isEqualTo(latest);
        jdbc.update("UPDATE flight_plan SET owner_org_id=?,district_id=? WHERE plan_id=?",otherOrg,otherDistrict,secondPlan);
        JsonNode visible=data(auth(get("/api/v1/device-maintenance-tasks").param("device_id",device),session)).path("items").get(0).path("report_summary");
        assertThat(visible.path("latest_manual_report").path("plan_id").asText()).isEqualTo(plan);
        assertThat(visible.toString()).doesNotContain(secondPlan);
        assertThat(visible.path("related_plans")).hasSize(1);
    }

    @Test void legacyManualReportSummaryKeepsOriginalFactsWithoutBackfillingHistory() throws Exception {
        String task=create();
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_version=1 WHERE task_id=?",task);
        jdbc.update("DELETE FROM ops_maintenance_report WHERE task_id=?",task);
        JsonNode original=workflow(task).path("task");
        JsonNode report=original.path("report_summary").path("latest_manual_report");
        assertThat(report.path("reported_at")).isEqualTo(original.path("reported_at"));
        assertThat(report.path("actor_name")).isEqualTo(original.path("reported_by_name"));
        assertThat(report.hasNonNull("report_id")).isFalse();
        assertThat(count("ops_maintenance_report","task_id",task)).isZero();
    }

    @ParameterizedTest @ValueSource(longs={1000,31000})
    void latestVisibleManualReportMovesOriginalCaseBeforePaginationWithoutMovingOnChecks(long interval) throws Exception {
        String original=create();long firstReported=now;
        advance(interval);
        String another=sensor();stubPlan(secondPlan,another);
        String newer=create(secondPlan,another,id()).path("task_id").asText();
        assertThat(firstListedCase()).isEqualTo(newer);

        advance(interval);String linkedPlan=plan(),key=id();
        JsonNode attached=create(linkedPlan,device,key);
        assertThat(attached.path("task_id").asText()).isEqualTo(original);
        assertThat(attached.path("reused").asBoolean()).isTrue();
        long manualAt=now;
        assertThat(firstListedCase()).isEqualTo(original);
        JsonNode secondPage=data(auth(get("/api/v1/device-maintenance-tasks").param("status","ACTIVE")
                .param("page","2").param("size","1"),session));
        assertThat(secondPage.path("items").get(0).path("task_id").asText()).isEqualTo(newer);

        advance(interval);auto(original);auto(newer);
        // Scheduled checks do not override the last human report or reorder cases.
        jdbc.update("INSERT INTO ops_maintenance_report(report_id,task_id,plan_id,actor_id,actor_name,reason,reported_at,report_key) VALUES(?,?,?,NULL,'系统自动检查','设备仍异常',?,?)",id(),newer,secondPlan,now,id());
        assertThat(firstListedCase()).isEqualTo(original);
        assertThat(create(linkedPlan,device,key).path("report_summary").path("latest_manual_report").path("reported_at").asLong()).isEqualTo(manualAt);
        assertThat(jdbc.queryForObject("SELECT reported_at FROM ops_device_maintenance_task WHERE task_id=?",Long.class,original)).isEqualTo(firstReported);

        // A report from an inaccessible plan cannot influence visible ordering either.
        jdbc.update("UPDATE flight_plan SET owner_org_id=?,district_id=? WHERE plan_id=?",otherOrg,otherDistrict,linkedPlan);
        assertThat(firstListedCase()).isEqualTo(newer);
    }

    private String firstListedCase() throws Exception {
        JsonNode first=data(auth(get("/api/v1/device-maintenance-tasks").param("status","ACTIVE")
                .param("page","1").param("size","1"),session));
        return first.path("items").get(0).path("task_id").asText();
    }

    @Test void naturalRecoveryBeforeTakingOwnershipCanBeConfirmedDirectly() throws Exception {
        String task=create();healthy();auto(task);
        assertThat(workflow(task).path("state").asText()).isEqualTo("READY_TO_COMPLETE");
        assertThat(jdbc.queryForObject("SELECT assigned_to FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
        assertThat(action(task,"CONFIRM_COMPLETE",version(task),id()).path("state").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=? AND actor_id IS NOT NULL",Long.class,task)).isEqualTo(1);
    }

    @Test void identicalChecksAndNewHealthyTimestampsDoNotSpamVersionsHistoryOrAudit() throws Exception {
        String task=create();auto(task);
        long faultVersion=version(task),faultEvents=count("ops_maintenance_workflow_event","task_id",task);
        long faultAudits=count("audit_log","object_id",task);
        for(int i=0;i<3;i++){advance(5000);auto(task);}
        assertThat(version(task)).isEqualTo(faultVersion);
        assertThat(count("ops_maintenance_workflow_event","task_id",task)).isEqualTo(faultEvents);
        assertThat(count("audit_log","object_id",task)).isEqualTo(faultAudits);
        healthy();auto(task);
        assertThat(workflow(task).path("state").asText()).isEqualTo("READY_TO_COMPLETE");
        long readyVersion=version(task),readyEvents=count("ops_maintenance_workflow_event","task_id",task);
        long readyAudits=count("audit_log","object_id",task);
        long checked=jdbc.queryForObject("SELECT recovery_checked_at FROM ops_device_maintenance_task WHERE task_id=?",Long.class,task);
        for(int i=0;i<3;i++){healthy();auto(task);}
        assertThat(version(task)).isEqualTo(readyVersion);
        assertThat(count("ops_maintenance_workflow_event","task_id",task)).isEqualTo(readyEvents);
        assertThat(count("audit_log","object_id",task)).isEqualTo(readyAudits);
        assertThat(jdbc.queryForObject("SELECT recovery_checked_at FROM ops_device_maintenance_task WHERE task_id=?",Long.class,task)).isGreaterThan(checked);
    }

    @Test void recurrenceBeforeCompletionReopensSameCaseAndAfterCompletionCreatesANewCase() throws Exception {
        String task=create();action(task,"START",version(task),id());healthy();auto(task);
        fault();auto(task);
        assertThat(workflow(task).path("state").asText()).isEqualTo("PROCESSING");
        assertThat(workflow(task).path("recovery").path("result").asText()).isNotEqualTo("PASS");
        assertThat(create(secondPlan,device,id()).path("task_id").asText()).isEqualTo(task);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=? AND action='RECOVERY_INVALIDATED'",Long.class,task)).isEqualTo(1);
        healthy();auto(task);action(task,"CONFIRM_COMPLETE",version(task),id());
        String frozen=jdbc.queryForObject("SELECT recovery_snapshot FROM ops_device_maintenance_task WHERE task_id=?",String.class,task);
        fault();String next=create();
        assertThat(next).isNotEqualTo(task);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(2);
        auto(task);
        JsonNode history=workflow(task);
        assertThat(history.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(history.path("recovery").path("result").asText()).isEqualTo("PASS");
        assertThat(history.path("recovery").path("evidence").path("health_code").asText()).isEqualTo("GOOD");
        assertThat(history.path("recovery").path("evidence").path("has_alarm").asBoolean()).isFalse();
        assertThat(history.path("current_device_state").path("health_code").asText()).isEqualTo("BAD");
        assertThat(history.path("allowed_actions")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT recovery_snapshot FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo(frozen);
    }

    @ParameterizedTest
    @CsvSource({"ONLINE,BAD,false,HEALTH_NOT_GOOD", "ONLINE,GOOD,true,ALARM_NOT_CLEARED", "OFFLINE,GOOD,false,CONNECTIVITY_NOT_ONLINE"})
    void onlineOrSuccessfulOperationsAloneCannotProveRecovery(String connection,String health,boolean alarm,String blocker) throws Exception {
        String task=create();healthy();
        jdbc.update("UPDATE ops_device_state SET connectivity=?,health_code=?,has_alarm=? WHERE device_id=?",connection,health,alarm,device);
        auto(task);JsonNode view=workflow(task);
        assertThat(view.path("state").asText()).isEqualTo("PENDING");
        assertThat(view.path("recovery").path("reason_codes").toString()).contains(blocker);
        assertThat(view.path("allowed_actions").toString()).doesNotContain("CONFIRM_COMPLETE");
    }

    @ParameterizedTest
    @ValueSource(strings={"missing_health","missing_alarm","stale_health","old_health","future_health","wrong_source"})
    void absentOldOrWrongSourceEvidencePreventsAutomaticRecovery(String variant) throws Exception {
        String task=create();long reported=now;healthy();String code;
        switch(variant){
            case "missing_health" -> {jdbc.update("UPDATE ops_device_state SET health_observed_at=NULL WHERE device_id=?",device);code="HEALTH_MISSING";}
            case "missing_alarm" -> {jdbc.update("UPDATE ops_device_state SET alarm_observed_at=NULL WHERE device_id=?",device);code="ALARM_MISSING";}
            case "old_health" -> {jdbc.update("UPDATE ops_device_state SET health_observed_at=? WHERE device_id=?",reported,device);code="HEALTH_BEFORE_ANOMALY";}
            case "future_health" -> {jdbc.update("UPDATE ops_device_state SET health_observed_at=? WHERE device_id=?",now+1000,device);code="HEALTH_FUTURE";}
            case "wrong_source" -> {jdbc.update("UPDATE ops_device_state SET health_source_mode='replay',health_simulated=TRUE WHERE device_id=?",device);code="HEALTH_SOURCE_MISMATCH";}
            default -> {advance(301000);jdbc.update("UPDATE ops_device_state SET observed_at=?,received_at=?,last_heartbeat_at=?,alarm_observed_at=? WHERE device_id=?",now,now,now,now,device);code="HEALTH_STALE";}
        }
        auto(task);JsonNode view=workflow(task);
        assertThat(view.path("recovery").path("result").asText()).isEqualTo("UNKNOWN");
        assertThat(view.path("recovery").path("reason_codes").toString()).contains(code);
        assertThat(view.path("state").asText()).isEqualTo("PENDING");
    }

    @Test void aFreshHeartbeatDoesNotRenewAnOldHealthObservation() throws Exception {
        String task=create();healthy();long healthAt=now;auto(task);advance(301000);
        jdbc.update("UPDATE ops_device_state SET last_heartbeat_at=?,observed_at=?,received_at=? WHERE device_id=?",now,now,now,device);
        auto(task);
        assertThat(workflow(task).path("recovery").path("reason_codes").toString()).contains("HEALTH_STALE","ALARM_STALE");
        assertThat(jdbc.queryForObject("SELECT health_observed_at FROM ops_device_state WHERE device_id=?",Long.class,device)).isEqualTo(healthAt);
        assertThat(workflow(task).path("state").asText()).isEqualTo("PENDING");
    }

    @Test void confirmationRechecksFactsAndRejectsStaleVersionsAndOldManualVerification() throws Exception {
        String task=create();long initialVersion=version(task);
        healthy();auto(task);long readyVersion=version(task);
        mvc.perform(actionRequest(task,"CONFIRM_COMPLETE",initialVersion,id())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_TASK_CHANGED"));
        fault();
        mvc.perform(actionRequest(task,"CONFIRM_COMPLETE",readyVersion,id())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_RECOVERY_BLOCKED"));
        assertThat(jdbc.queryForObject("SELECT status FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo("PENDING");
        mvc.perform(actionRequest(task,"VERIFY_RECOVERY",readyVersion,id())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_AUTOMATIC_RECOVERY"));
    }

    @Test void readsNeverRunRecoveryOrWriteReceiptsAndDeviceFilterDoesNotSelectAnotherDevice() throws Exception {
        String task=create();String other=sensor();stubPlan(plan,other);
        String otherTask=create(plan,other,id()).path("task_id").asText();
        healthy();long beforeVersion=version(task),events=count("ops_maintenance_workflow_event","task_id",task);
        long audits=count("audit_log","object_id",task);
        for(int i=0;i<3;i++){
            assertThat(workflow(task).path("state").asText()).isEqualTo("PENDING");
            JsonNode list=data(auth(get("/api/v1/device-maintenance-tasks").param("device_id",device),session));
            assertThat(list.path("total").asInt()).isEqualTo(1);
            assertThat(list.path("items").get(0).path("task_id").asText()).isEqualTo(task).isNotEqualTo(otherTask);
            data(auth(get("/api/v1/device-maintenance-messages"),session));
        }
        assertThat(version(task)).isEqualTo(beforeVersion);
        assertThat(count("ops_maintenance_workflow_event","task_id",task)).isEqualTo(events);
        assertThat(count("audit_log","object_id",task)).isEqualTo(audits);
        assertThat(count("ops_maintenance_message_read","task_id",task)).isZero();
        assertThat(jdbc.queryForObject("SELECT recovery_snapshot FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
    }

    @Test void frontendCannotHandleAndHiddenPlanAssociationsAreNotDisclosed() throws Exception {
        String task=create();create(secondPlan,device,id());
        mvc.perform(auth(get(workflowPath(task)),reporterSession)).andExpect(status().isForbidden());
        mvc.perform(auth(post(workflowPath(task)+"/actions"),reporterSession).header("Idempotency-Key",id())
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"START\",\"expected_version\":1}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE flight_plan SET owner_org_id=?,district_id=? WHERE plan_id=?",otherOrg,otherDistrict,secondPlan);
        JsonNode view=workflow(task);
        assertThat(view.path("related_plans")).hasSize(1);
        assertThat(view.path("related_reports")).hasSize(1);
        assertThat(view.path("related_plans").toString()).doesNotContain(secondPlan);
        mvc.perform(report(secondPlan,device,id())).andExpect(status().isNotFound());
        jdbc.update("UPDATE device_business_scope SET owner_org_id=?,district_id=? WHERE ops_device_id=?",otherOrg,otherDistrict,device);
        mvc.perform(auth(get(workflowPath(task)),session)).andExpect(status().isNotFound());
        assertThat(data(auth(get("/api/v1/device-maintenance-tasks").param("device_id",device),session)).path("total").asInt()).isZero();
    }

    @Test void closedLegacyHistoryIsNeverRewrittenAsVerifiedRecovery() throws Exception {
        String task=create();
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_version=1,workflow_state='LEGACY_HANDLED',status='HANDLED',active_key=NULL,handled_by=?,handled_by_name='历史处理人',handled_at=?,handling_note='原始历史说明' WHERE task_id=?",handler,now,task);
        var original=jdbc.queryForMap("SELECT workflow_version,workflow_state,status,version,handling_note,recovery_snapshot,recovery_result,recovery_checked_at FROM ops_device_maintenance_task WHERE task_id=?",task);
        healthy();auto(task);JsonNode view=workflow(task);
        assertThat(view.path("state").asText()).isEqualTo("LEGACY_HANDLED");
        assertThat(view.path("workflow_version").asInt()).isEqualTo(1);
        assertThat(view.path("allowed_actions")).isEmpty();
        assertThat(view.path("recovery").isMissingNode()||view.path("recovery").isNull()).isTrue();
        assertThat(jdbc.queryForMap("SELECT workflow_version,workflow_state,status,version,handling_note,recovery_snapshot,recovery_result,recovery_checked_at FROM ops_device_maintenance_task WHERE task_id=?",task)).isEqualTo(original);
    }

    @Test void backgroundDiscoveryWorksWithoutAPlanOrAuthenticatedUser() throws Exception {
        String incident=incident();AuthContext.clear();workflows.runAutomaticBatch();sqlSession.clearCache();
        String task=jdbc.queryForObject("SELECT task_id FROM ops_device_maintenance_task WHERE device_id=?",String.class,device);
        assertThat(jdbc.queryForObject("SELECT plan_id FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
        assertThat(jdbc.queryForObject("SELECT reported_by FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
        assertThat(workflow(task).path("related_incidents").toString()).contains(incident);
        healthy();auto(task);
        assertThat(action(task,"CONFIRM_COMPLETE",version(task),id()).path("state").asText()).isEqualTo("COMPLETED");
    }

    @Test void aDiscoveredPlanlessCaseAcceptsPlanAssociationAndScopedReminder() throws Exception {
        incident();AuthContext.clear();workflows.runAutomaticBatch();
        String task=jdbc.queryForObject("SELECT task_id FROM ops_device_maintenance_task WHERE device_id=?",String.class,device);
        assertThat(create(plan,device,id()).path("task_id").asText()).isEqualTo(task);
        assertThat(create(secondPlan,device,id()).path("task_id").asText()).isEqualTo(task);
        assertThat(jdbc.queryForObject("SELECT plan_id FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
        advance(61000);fault();
        jdbc.update("UPDATE flight_plan SET owner_org_id=?,district_id=? WHERE plan_id=?",otherOrg,otherDistrict,plan);
        JsonNode list=data(auth(get(reportPath(secondPlan)).param("device_id",device),reporterSession));
        assertThat(list.path("items").get(0).path("can_resend_notification").asBoolean()).isTrue();
        String key=id();
        var body=Map.of("expected_attempt_no",1,"plan_id",secondPlan);
        for(int i=0;i<2;i++){
            JsonNode result=data(auth(post("/api/v1/device-maintenance-tasks/"+task+"/notifications/resend"),reporterSession)
                    .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
            assertThat(result.path("task_id").asText()).isEqualTo(task);
            assertThat(result.path("latest_notification_attempt_no").asInt()).isEqualTo(2);
        }
        assertThat(count("ops_device_maintenance_notice_attempt","task_id",task)).isEqualTo(2);
        String unrelated=plan();
        mvc.perform(auth(post("/api/v1/device-maintenance-tasks/"+task+"/notifications/resend"),reporterSession)
                .header("Idempotency-Key",id()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expected_attempt_no",2,"plan_id",unrelated))))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void resendIdempotencyIncludesTheOptionalRequestedPlan(boolean explicitPlan) throws Exception {
        String task=create();
        assertThat(create(secondPlan,device,id()).path("task_id").asText()).isEqualTo(task);
        advance(61000);fault();
        String key=id(),path="/api/v1/device-maintenance-tasks/"+task+"/notifications/resend";
        var body=new java.util.LinkedHashMap<String,Object>();
        body.put("expected_attempt_no",1);
        if(explicitPlan)body.put("plan_id"," "+plan+" ");
        for(int i=0;i<2;i++){
            JsonNode result=data(auth(post(path),reporterSession).header("Idempotency-Key",key)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
            assertThat(result.path("latest_notification_attempt_no").asInt()).isEqualTo(2);
            if(explicitPlan)body.put("plan_id",plan);
        }
        assertThat(jdbc.queryForObject("SELECT request_plan_id FROM ops_device_maintenance_notice_attempt WHERE request_key=?",
                String.class,key)).isEqualTo(explicitPlan?plan:null);
        body.put("plan_id",secondPlan);
        mvc.perform(auth(post(path),reporterSession).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        if(explicitPlan){
            body.remove("plan_id");
            mvc.perform(auth(post(path),reporterSession).header("Idempotency-Key",key)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        }
        assertThat(count("ops_device_maintenance_notice_attempt","task_id",task)).isEqualTo(2);
    }

    @Test void sourceIdentityChangesDoNotMergeWithOrRecoverAnOldSourceCase() throws Exception {
        String original=create();healthy();auto(original);
        String source=id();
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,enabled,simulated,version,created_at,updated_at) VALUES(?,?,?,'TEST_INPUT','live',TRUE,FALSE,0,?,?)",source,source,"独立新来源",now,now);
        jdbc.update("UPDATE ops_device SET source_id=?,external_device_id=? WHERE device_id=?",source,id(),device);
        jdbc.update("UPDATE ops_device_state SET health_source_id=?,alarm_source_id=? WHERE device_id=?",source,source,device);
        fault();String next=create();
        assertThat(next).isNotEqualTo(original);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT workflow_source_id FROM ops_device_maintenance_task WHERE task_id=?",String.class,original)).isNull();
        assertThat(jdbc.queryForObject("SELECT workflow_source_id FROM ops_device_maintenance_task WHERE task_id=?",String.class,next)).isEqualTo(source);
        healthy();auto(original);auto(next);
        assertThat(workflow(original).path("recovery").path("reason_codes").toString()).contains("SOURCE_CHANGED");
        assertThat(workflow(original).path("state").asText()).isEqualTo("PENDING");
        assertThat(workflow(next).path("state").asText()).isEqualTo("READY_TO_COMPLETE");
    }

    @ParameterizedTest @ValueSource(strings={"PENDING","PROCESSING","READY_TO_COMPLETE"})
    void activeV2CaseStaysOnFirstPageAheadOfNewerLegacyProcessingReports(String currentState) throws Exception {
        String current=create();
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_state=? WHERE task_id=?",currentState,current);
        var legacy=new java.util.ArrayList<String>();
        for(int i=0;i<24;i++)legacy.add(copyMaintenanceHistory(current,1,"PROCESSING",now+i+1));
        var original=jdbc.queryForList("SELECT task_id,workflow_version,workflow_state,reported_at,version FROM ops_device_maintenance_task WHERE device_id=? ORDER BY task_id",device);

        for(String state:List.of("ACTIVE","ALL")){
            JsonNode first=data(auth(get("/api/v1/device-maintenance-tasks").param("status",state)
                    .param("device_id",device).param("page","1").param("size","20"),session));
            JsonNode second=data(auth(get("/api/v1/device-maintenance-tasks").param("status",state)
                    .param("device_id",device).param("page","2").param("size","20"),session));
            assertThat(first.path("total").asInt()).isEqualTo(25);
            assertThat(first.path("items")).hasSize(20);
            assertThat(first.path("items").get(0).path("task_id").asText()).isEqualTo(current);
            assertThat(first.path("items").get(0).path("workflow_version").asInt()).isEqualTo(2);
            assertThat(second.path("items")).hasSize(5);
            var listed=new java.util.ArrayList<String>();
            first.path("items").forEach(item->listed.add(item.path("task_id").asText()));
            second.path("items").forEach(item->listed.add(item.path("task_id").asText()));
            assertThat(listed).doesNotHaveDuplicates().containsAll(legacy).contains(current);
            assertThat(workflow(legacy.get(0)).path("workflow_version").asInt()).isEqualTo(1);
        }
        JsonNode planView=data(auth(get(reportPath(plan)).param("device_id",device).param("size","20"),reporterSession));
        assertThat(planView.path("total").asInt()).isEqualTo(25);
        assertThat(planView.path("items").get(0).path("task_id").asText()).isEqualTo(current);
        assertThat(jdbc.queryForList("SELECT task_id,workflow_version,workflow_state,reported_at,version FROM ops_device_maintenance_task WHERE device_id=? ORDER BY task_id",device)).isEqualTo(original);
    }

    @Test void completedV2DoesNotDisplaceOpenLegacyAndSameGroupUsesStableLatestReportOrder() throws Exception {
        String current=create();
        String oldOpen=copyMaintenanceHistory(current,1,"PROCESSING",now-1000);
        String newOpen=copyMaintenanceHistory(current,1,"READY_TO_COMPLETE",now-500);
        healthy();auto(current);action(current,"CONFIRM_COMPLETE",version(current),id());
        JsonNode all=data(auth(get("/api/v1/device-maintenance-tasks").param("status","ALL")
                .param("device_id",device),session));
        var ids=new java.util.ArrayList<String>();
        all.path("items").forEach(item->ids.add(item.path("task_id").asText()));
        assertThat(ids).containsExactly(newOpen,oldOpen,current);
        JsonNode active=data(auth(get("/api/v1/device-maintenance-tasks").param("status","ACTIVE")
                .param("device_id",device),session));
        assertThat(active.path("total").asInt()).isEqualTo(2);
        assertThat(active.path("items").get(0).path("task_id").asText()).isEqualTo(newOpen);
    }

    private String copyMaintenanceHistory(String source,int workflowVersion,String state,long reportedAt){
        String task=id();
        jdbc.update("""
                INSERT INTO ops_device_maintenance_task(task_id,plan_id,device_id,owner_org_id,district_id,plan_no,
                    device_no,device_name,reason,connectivity,health_code,observed_at,last_heartbeat_at,simulated,
                    status,active_key,reported_by,reported_by_name,reported_at,version,workflow_state,
                    workflow_source_mode,workflow_version)
                SELECT ?,plan_id,device_id,owner_org_id,district_id,plan_no,device_no,device_name,reason,
                    connectivity,health_code,observed_at,last_heartbeat_at,simulated,'PENDING',?,reported_by,
                    reported_by_name,?,1,?,workflow_source_mode,? FROM ops_device_maintenance_task WHERE task_id=?
                """,task,id(),reportedAt,state,workflowVersion,source);
        return task;
    }

    String create() throws Exception {return create(plan,device,id()).path("task_id").asText();}
    JsonNode create(String planId,String deviceId,String key) throws Exception {return data(report(planId,deviceId,key));}
    MockHttpServletRequestBuilder report(String planId,String deviceId,String key) throws Exception {
        return auth(post(reportPath(planId)),reporterSession).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("device_id",deviceId)));
    }
    JsonNode workflow(String task) throws Exception {return data(auth(get(workflowPath(task)),session));}
    JsonNode action(String task,String action,long expected,String key) throws Exception {return data(actionRequest(task,action,expected,key));}
    MockHttpServletRequestBuilder actionRequest(String task,String action,long expected,String key) throws Exception {
        return auth(post(workflowPath(task)+"/actions"),session).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("action",action,"expected_version",expected)));
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request,String token){sqlSession.clearCache();return request.header("Authorization","Bearer "+token);}
    JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    void auto(String task){AuthContext.clear();sqlSession.clearCache();workflows.checkAutomatically(task);sqlSession.clearCache();}
    long version(String task){return jdbc.queryForObject("SELECT version FROM ops_device_maintenance_task WHERE task_id=?",Long.class,task);}
    long count(String table,String column,String value){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Long.class,value);}
    static String id(){return UUID.randomUUID().toString();}
    static String reportPath(String plan){return "/api/v1/flight-plans/"+plan+"/device-maintenance-tasks";}
    static String workflowPath(String task){return "/api/v1/device-maintenance-tasks/"+task+"/workflow";}
    void advance(long millis){now+=millis;clock.set(now);}
    void healthy(){advance(1000);observe(false);}
    void fault(){advance(1000);observe(true);}
    void observe(boolean fault){
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',health_code=?,has_alarm=?,observed_at=?,received_at=?,last_heartbeat_at=?,health_observed_at=?,alarm_observed_at=?,health_source_mode='live',alarm_source_mode='live',health_simulated=FALSE,alarm_simulated=FALSE WHERE device_id=?",
                fault?"BAD":"GOOD",fault,now,now,now,now,now,device);
    }
    String incident(){
        String incident=id();jdbc.update("INSERT INTO device_incident(incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated) VALUES(?,?,?,'OTHER','LOW','PENDING',?,'明确设备故障',FALSE)",incident,device,"INC-"+incident,now);return incident;
    }
    String plan(){
        String id=id();jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,start_at,end_at,created_at,updated_at,version) VALUES(?,?,'PENDING','live',?,?,?,?,?,current_timestamp,current_timestamp,0)",id,id,routeVersion,org,district,new Timestamp(now+3600000),new Timestamp(now+7200000));
        stubPlan(id,device);return id;
    }
    void stubPlan(String planId,String sensor){
        // Only geometry selection is isolated here; HTTP permissions, scope, device facts and recovery are real.
        doAnswer(invocation->{
            var state=jdbc.queryForMap("SELECT connectivity,health_code,has_alarm,observed_at,last_heartbeat_at,simulated FROM ops_device_state WHERE device_id=?",sensor);
            String connection=String.valueOf(state.get("connectivity")),health=String.valueOf(state.get("health_code"));
            boolean abnormal=!"ONLINE".equals(connection)||"BAD".equals(health)||Boolean.TRUE.equals(state.get("has_alarm"));
            var row=new FlightDeviceCheckService.DeviceRow(sensor,"独立处置测试设备",Boolean.TRUE.equals(state.get("simulated")),BigDecimal.ONE,connection,health,
                    ((Number)state.get("last_heartbeat_at")).longValue(),((Number)state.get("observed_at")).longValue(),abnormal,true,List.of());
            return new FlightDeviceCheckService.Check(planId,abnormal?"AUTO_DEVICE_ABNORMAL":"DEVICE_NORMAL","测试扫描范围",now,"DEVICE_SCAN_COVERAGE",true,0,0,List.of(row),false);
        }).when(checks).read(planId);
    }
    String sensor(){
        String id=id();jdbc.update("INSERT INTO ops_device(device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,longitude,latitude,coordinate_system,version,created_at,updated_at) VALUES(?,?,?,'RADAR','雷达','隔离测试',TRUE,'live',FALSE,118.005,37,'WGS-84',0,0,0)",id,id,"设备-"+id.substring(0,8));
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,current_timestamp,current_timestamp)",id,org,district);
        jdbc.update("INSERT INTO ops_device_state(device_id,connectivity,health_code,has_alarm,observed_at,received_at,last_heartbeat_at,simulated,version,health_observed_at,alarm_observed_at,health_source_mode,alarm_source_mode,health_simulated,alarm_simulated) VALUES(?,'ONLINE','BAD',TRUE,?,?,?,FALSE,0,?,?,'live','live',FALSE,FALSE)",id,now,now,now,now,now);
        jdbc.update("INSERT INTO device_sensing_profile(device_id,coverage_kind,radius_m,source_label,version,updated_at) VALUES(?,'CIRCLE',1000,'isolated-test',0,?)",id,now);return id;
    }
    void catalog(String organization,String area){
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",organization,organization,organization);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",area,area,area);
    }
    String user(String user,String role,String organization,String area){
        String token=id();jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused-test-hash',0,'ASSIGNED',0,0,0,0)",user,user,"测试办理员-"+user.substring(0,8),role);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,organization,area);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",token,user,now+3600000);return token;
    }
    @TestConfiguration static class ClockConfiguration {
        @Bean @Primary CaseClock caseClock(){return new CaseClock();}
    }
    static class CaseClock extends AppClock {
        private volatile Instant current=Instant.now();
        void set(long millis){current=Instant.ofEpochMilli(millis);}
        @Override public Instant now(){return current;}
    }
}
