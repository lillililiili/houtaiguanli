package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;

class DeviceMaintenanceWorkflowApiTest extends DeviceMaintenanceNoticeApiTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"-1,0", "-300001,0", "1,0", "0,-1", "0,-300001", "0,1"})
    void recoveryRejectsPreReportStaleAndFutureEvidence(long observedOffset,long heartbeatOffset) throws Exception {
        create();action("START",1,null);action("SUBMIT_VERIFICATION",2,null);healthy();
        jdbc.update("UPDATE ops_device_state SET observed_at=?,last_heartbeat_at=? WHERE device_id=?",now+observedOffset,now+heartbeatOffset,device);
        JsonNode checked=action("VERIFY_RECOVERY",3,null);
        assertThat(checked.path("recovery").path("result").asText()).isEqualTo("UNKNOWN");
        assertThat(checked.path("allowed_actions").toString()).doesNotContain("COMPLETE");
        mvc.perform(write(post(path()),Map.of("action","COMPLETE","expected_version",4),UUID.randomUUID().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_RECOVERY_REQUIRED"));
        assertThat(workflow().path("state").asText()).isEqualTo("PENDING_VERIFICATION");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,ONLINE,GOOD,false,FAIL", "true,OFFLINE,GOOD,false,FAIL", "true,ONLINE,BAD,false,FAIL", "true,ONLINE,DEGRADED,false,FAIL", "true,ONLINE,GOOD,true,FAIL", "true,ONLINE,UNKNOWN,false,UNKNOWN"})
    void recoveryRejectsEachCurrentUnsafeState(boolean enabled,String connectivity,String health,boolean alarm,String expected) throws Exception {
        create();action("START",1,null);action("SUBMIT_VERIFICATION",2,null);healthy();
        jdbc.update("UPDATE ops_device SET enabled=? WHERE device_id=?",enabled,device);
        jdbc.update("UPDATE ops_device_state SET connectivity=?,health_code=?,has_alarm=? WHERE device_id=?",connectivity,health,alarm,device);
        JsonNode checked=action("VERIFY_RECOVERY",3,null);
        assertThat(checked.path("recovery").path("result").asText()).isEqualTo(expected);
        assertThat(checked.path("state").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(checked.path("allowed_actions").toString()).doesNotContain("COMPLETE");
    }
    @Test void freshDeviceCannotExtendAnExpiredRecoveryDecision() throws Exception {
        create();action("START",1,null);action("SUBMIT_VERIFICATION",2,null);healthy();
        assertThat(action("VERIFY_RECOVERY",3,null).path("recovery").path("result").asText()).isEqualTo("PASS");
        now+=300001;
        clock.setNow(java.time.Instant.ofEpochMilli(now));healthy();
        mvc.perform(write(post(path()),Map.of("action","COMPLETE","expected_version",4),UUID.randomUUID().toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_RECOVERY_REQUIRED"));
        assertThat(workflow().path("state").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(action("VERIFY_RECOVERY",4,null).path("recovery").path("result").asText()).isEqualTo("PASS");
        assertThat(action("COMPLETE",5,null).path("state").asText()).isEqualTo("COMPLETED");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"replay,true", "mock,false", "live,false"})
    void changingSourceCannotProveTheReportedDeviceRecovered(String mode,boolean simulated) throws Exception {
        create();action("START",1,null);action("SUBMIT_VERIFICATION",2,null);healthy();
        jdbc.update("UPDATE ops_device SET source_mode=?,simulated=? WHERE device_id=?",mode,simulated,device);
        JsonNode checked=action("VERIFY_RECOVERY",3,null);
        assertThat(checked.path("recovery").path("result").asText()).isEqualTo("UNKNOWN");
        assertThat(checked.path("recovery").path("reason").asText()).contains("来源");
        assertThat(checked.path("allowed_actions").toString()).doesNotContain("COMPLETE");
    }
    @Test void optionalNotesDoNotBlockProgressOrVerifiedCompletion() throws Exception {
        create(); action("START",1,null); action("SAVE_PROGRESS",2,"");
        action("SUBMIT_VERIFICATION",3,null); healthy();
        assertThat(action("VERIFY_RECOVERY",4,null).path("recovery").path("result").asText()).isEqualTo("PASS");
        assertThat(action("COMPLETE",5,"").path("state").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=? AND actor_id IS NOT NULL", Long.class, taskId)).isEqualTo(5L);
    }
    @Test void notStartedFilterExcludesProcessingWhileLegacyPendingIncludesIt() throws Exception {
        create();
        JsonNode before=data(auth(get("/api/v1/device-maintenance-tasks").param("status","NOT_STARTED")));
        assertThat(before.path("total").asLong()).isEqualTo(1);
        assertThat(before.path("items")).hasSize(1);
        action("START",1,null);
        JsonNode notStarted=data(auth(get("/api/v1/device-maintenance-tasks").param("status","NOT_STARTED")));
        assertThat(notStarted.path("total").asLong()).isZero();
        assertThat(notStarted.path("items")).isEmpty();
        for(String filter:java.util.List.of("ACTIVE","PENDING")){
            JsonNode active=data(auth(get("/api/v1/device-maintenance-tasks").param("status",filter)));
            assertThat(active.path("total").asLong()).isEqualTo(1);
            assertThat(active.path("items")).hasSize(1);
            assertThat(active.path("items").get(0).path("task_id").asText()).isEqualTo(taskId);
            assertThat(active.path("items").get(0).path("workflow_state").asText()).isEqualTo("PROCESSING");
        }
    }
    @Test void fullWorkflowRequiresFreshRecoveryAndKeepsProgressHistory() throws Exception {
        create();
        assertThat(workflow().path("state").asText()).isEqualTo("PENDING");
        action("START",1,"开始排查");
        action("SAVE_PROGRESS",2,"已检查供电与连接");
        action("SUBMIT_VERIFICATION",3,"处理完成，请核验");
        healthy();
        JsonNode checked=action("VERIFY_RECOVERY",4,null);
        assertThat(checked.path("recovery").path("result").asText()).isEqualTo("PASS");
        assertThat(checked.path("state").asText()).isEqualTo("PENDING_VERIFICATION");
        JsonNode done=action("COMPLETE",5,"恢复核验通过，完成运维");
        assertThat(done.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(done.path("task").path("status").asText()).isEqualTo("HANDLED");
        assertThat(done.path("events")).hasSize(5);
    }
    @Test void idempotentReplayAndVersionConflictDoNotDuplicateEvents() throws Exception {
        create();String key=UUID.randomUUID().toString();var body=Map.of("action","START","expected_version",1);
        JsonNode first=data(write(post(path()),body,key));
        JsonNode replay=data(write(post(path()),body,key));assertThat(replay).isEqualTo(first);
        mvc.perform(write(post(path()),Map.of("action","START","expected_version",2),key)).andExpect(status().isConflict());
        mvc.perform(write(post(path()),Map.of("action","SAVE_PROGRESS","expected_version",1,"note","延迟提交"),UUID.randomUUID().toString())).andExpect(status().isConflict());
        assertThat(workflow().path("events")).hasSize(1);
        assertThat(jdbc.queryForList("SELECT module_code FROM audit_log WHERE object_id=? AND action IN ('device_maintenance_start','device_maintenance_workflow_rejected')",String.class,taskId))
                .hasSize(3).containsOnly("devices");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE object_id=? AND action='device_maintenance_workflow_rejected' AND result='FAILURE'",Long.class,taskId)).isEqualTo(2);
    }
    @Test void staleUnknownAndActiveIncidentsCannotCloseAndResumeRetainsHistory() throws Exception {
        create();action("START",1,null);action("SUBMIT_VERIFICATION",2,"处理后检查");healthy();
        jdbc.update("UPDATE ops_device_state SET observed_at=? WHERE device_id=?",now-301000,device);
        assertThat(action("VERIFY_RECOVERY",3,null).path("recovery").path("result").asText()).isEqualTo("UNKNOWN");
        mvc.perform(write(post(path()),Map.of("action","COMPLETE","expected_version",4,"note","尝试完成"),UUID.randomUUID().toString())).andExpect(status().isConflict());
        assertThat(action("RESUME",4,"继续处理").path("state").asText()).isEqualTo("PROCESSING");
    }
    @Test void replayOldStartCannotBypassCurrentBackendIdentity() throws Exception {
        create();String key=UUID.randomUUID().toString();var body=Map.of("action","START","expected_version",1);
        data(write(post(path()),body,key));action("SUBMIT_VERIFICATION",2,"提交恢复核验");
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-MAINT-OP','运维操作测试','',FALSE,TRUE,0,0,0,FALSE)");
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-MAINT-OP','monitoring','OP',TRUE)");
        jdbc.update("UPDATE app_user SET role_code='ROLE-MAINT-OP' WHERE account='admin1'");sqlSession.clearCache();
        mvc.perform(write(post(path()),body,key)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/device-maintenance-tasks/"+taskId+"/workflow"))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=?",String.class,taskId)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=?",Long.class,taskId)).isEqualTo(2L);
    }
    @Test void inboxReadReceiptIsSeparateAndLegacyHandlingCannotBypassWorkflow() throws Exception {
        create();JsonNode before=data(auth(get("/api/v1/device-maintenance-messages")));
        assertThat(before.path("unread_count").asLong()).isGreaterThan(0);
        long version=workflow().path("version").asLong();
        data(auth(post("/api/v1/device-maintenance-messages/"+taskId+"/read")));
        data(auth(post("/api/v1/device-maintenance-messages/"+taskId+"/read")));
        assertThat(workflow().path("version").asLong()).isEqualTo(version);
        assertThat(data(auth(get("/api/v1/device-maintenance-messages"))).path("unread_count").asLong()).isEqualTo(before.path("unread_count").asLong()-1);
        mvc.perform(write(post("/api/v1/device-maintenance-tasks/"+taskId+"/handling"),Map.of("expected_version",1,"note","已处理"),UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_WORKFLOW_REQUIRED"));
    }
    @Test void completionRechecksCurrentHealthAfterPassingVerification() throws Exception {
        create();action("START",1,null);action("SUBMIT_VERIFICATION",2,"等待恢复核验");healthy();
        assertThat(action("VERIFY_RECOVERY",3,null).path("recovery").path("result").asText()).isEqualTo("PASS");
        jdbc.update("UPDATE ops_device_state SET has_alarm=TRUE WHERE device_id=?",device);
        mvc.perform(write(post(path()),Map.of("action","COMPLETE","expected_version",4,"note","办结尝试"),UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_RECOVERY_BLOCKED"));
        assertThat(workflow().path("state").asText()).isEqualTo("PENDING_VERIFICATION");
    }
    @Test void commissionLinkChecksDeviceSourceAndActiveWorkBlocksSubmission() throws Exception {
        create();action("START",1,null);
        String same=commission(device,"mock",true,"CREATED");
        JsonNode linked=data(write(post(path()),Map.of("action","LINK_COMMISSION","expected_version",2,"commission_id",same),UUID.randomUUID().toString()));
        assertThat(linked.path("commission_tasks").get(0).path("commission_id").asText()).isEqualTo(same);
        mvc.perform(write(post(path()),Map.of("action","SUBMIT_VERIFICATION","expected_version",3,"note","提交核验"),UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_ACTIVE_WORK"));
        String otherDevice=jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_id<>? AND deleted_at IS NULL FETCH FIRST 1 ROW ONLY",String.class,device);
        String other=commission(otherDevice,"mock",true,"PASSED");
        mvc.perform(write(post(path()),Map.of("action","LINK_COMMISSION","expected_version",3,"commission_id",other),UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_COMMISSION_MISMATCH"));
        String wrongSource=commission(device,"live",false,"PASSED");
        mvc.perform(write(post(path()),Map.of("action","LINK_COMMISSION","expected_version",3,"commission_id",wrongSource),UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_SOURCE_MISMATCH"));
    }
    @Test void unresolvedIncidentAndUnknownHealthStayOpen() throws Exception {
        create();action("START",1,null);action("SUBMIT_VERIFICATION",2,"等待核验");healthy();
        String incident=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_incident(incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated) VALUES(?,?,?,'OTHER','LOW','PENDING',?,'异常尚未核验',TRUE)",incident,device,"INC-"+incident,now);
        assertThat(action("VERIFY_RECOVERY",3,null).path("recovery").path("result").asText()).isEqualTo("FAIL");
        jdbc.update("UPDATE ops_device_state SET health_code='UNKNOWN' WHERE device_id=?",device);
        assertThat(action("VERIFY_RECOVERY",4,null).path("recovery").path("result").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT stage FROM device_incident WHERE incident_id=?",String.class,incident)).isEqualTo("PENDING");
    }
    @Test void legacyFeedbackRemainsReadonlyAndTaskScopeAppliesToInboxAndActions() throws Exception {
        create();
        jdbc.update("UPDATE ops_device_maintenance_task SET status='HANDLED',workflow_state='LEGACY_HANDLED',active_key=NULL,handled_by=reported_by,handled_by_name=reported_by_name,handled_at=?,handling_note='旧反馈' WHERE task_id=?",now,taskId);
        JsonNode historical=workflow();assertThat(historical.path("state").asText()).isEqualTo("LEGACY_HANDLED");assertThat(historical.path("allowed_actions")).isEmpty();
        mvc.perform(write(post(path()),Map.of("action","START","expected_version",1),UUID.randomUUID().toString())).andExpect(status().isConflict());
        jdbc.update("UPDATE app_org SET enabled=FALSE WHERE org_id=(SELECT owner_org_id FROM ops_device_maintenance_task WHERE task_id=?)",taskId);
        mvc.perform(auth(get("/api/v1/device-maintenance-tasks/"+taskId+"/workflow"))).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/v1/device-maintenance-messages/"+taskId+"/read"))).andExpect(status().isNotFound());
        assertThat(data(auth(get("/api/v1/device-maintenance-messages"))).path("items").toString()).doesNotContain(taskId);
    }
    @Test void frontendMonitoringReadCannotReadMutateOrReceiveBackendInbox() throws Exception {
        create();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-MAINT-READ','运维只读测试','',FALSE,TRUE,0,0,0,FALSE)");
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-MAINT-READ','monitoring','READ',TRUE)");
        jdbc.update("UPDATE app_user SET role_code='ROLE-MAINT-READ' WHERE account='admin1'");sqlSession.clearCache();
        mvc.perform(auth(get("/api/v1/device-maintenance-tasks/"+taskId+"/workflow"))).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/device-maintenance-messages"))).andExpect(status().isForbidden());
        mvc.perform(write(post(path()),Map.of("action","START","expected_version",1),UUID.randomUUID().toString())).andExpect(status().isForbidden());
    }
    String commission(String target,String mode,boolean simulated,String status){String id=UUID.randomUUID().toString();String actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);jdbc.update("INSERT INTO commission_task(commission_id,commission_no,device_id,requested_by,status,source_mode,simulated,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,?,?)",id,"CT-"+id,target,actor,status,mode,simulated,now,now);return id;}
    JsonNode workflow() throws Exception{return data(auth(get("/api/v1/device-maintenance-tasks/"+taskId+"/workflow")));}
    String path(){return "/api/v1/device-maintenance-tasks/"+taskId+"/workflow/actions";}
    JsonNode action(String action,long version,String note)throws Exception {var body=new java.util.HashMap<String,Object>();body.put("action",action);body.put("expected_version",version);if(note!=null)body.put("note",note);return data(write(post(path()),body,UUID.randomUUID().toString()));}
    void healthy(){jdbc.update("UPDATE ops_device SET enabled=TRUE,source_mode='mock',simulated=TRUE WHERE device_id=?",device);jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',health_code='GOOD',has_alarm=FALSE,observed_at=?,last_heartbeat_at=? WHERE device_id=?",now,now,device);jdbc.update("UPDATE device_incident SET stage='RECOVERED',closed_at=? WHERE device_id=?",now,device);}
}
