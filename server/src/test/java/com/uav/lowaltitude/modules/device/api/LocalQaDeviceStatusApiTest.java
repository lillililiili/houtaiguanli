package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.modules.device.application.DeviceRecoveryService;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"app.qa.device-setup.enabled=true","app.mqtt.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class LocalQaDeviceStatusApiTest {
    static final String PATH="/api/v1/local-interface-simulator/device-status";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttRepository mqtt;
    @Autowired EoEdgeRepository edges;
    @Autowired DeviceRecoveryService recovery;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository deviceRepository;
    @Autowired com.uav.lowaltitude.platform.time.AppClock clock;
    String token;
    Binding device;

    @BeforeEach void setup() throws Exception {
        token="Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        AuthContext.set(new AuthUser(user,"admin1","QA status test","ROLE-ADMIN",1,false,"ALL"));
        String org=jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROW ONLY",String.class);
        String district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY",String.class);
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String broker=configuration.create(new BrokerInput("QA status "+suffix,"127.0.0.1",1883,false,null,null,
                "127.0.0.1/32","replay",org,district,null),UUID.randomUUID().toString()).brokerId();
        String id=configuration.register(new Registration(LingyunEnvelope.PROTOCOL,broker,"qa-status",suffix,"radar",
                "replay",org,district,"QA-STATUS-"+suffix,"模拟状态设备",null,null,null),UUID.randomUUID().toString());
        device=mqtt.binding(id,false);
        jdbc.update("UPDATE ops_device SET enabled=TRUE WHERE device_id=?",id);
        jdbc.update("UPDATE ops_integration_source SET enabled=TRUE WHERE source_id=?",device.opsSourceId());
        AuthContext.clear();
    }
    @AfterEach void cleanup(){AuthContext.clear();}

    @Test void explicitFactsPersistWithProvenanceButNeverCompleteBusinessWork() throws Exception {
        long incidents=count("device_incident"),authorizations=count("disposal_authorization");
        var body=input();var result=send(body,200);
        assertThat(result.path("simulated").asBoolean()).isTrue();
        assertThat(result.path("source_type").asText()).isEqualTo("LOCAL_QA_STATUS");
        assertThat(jdbc.queryForObject("SELECT health_code FROM ops_device_state WHERE device_id=?",String.class,device.opsDeviceId())).isEqualTo("GOOD");
        assertThat(jdbc.queryForObject("SELECT last_heartbeat_at FROM ops_device_state WHERE device_id=?",Long.class,device.opsDeviceId())).isEqualTo(body.get("observed_at"));
        var evidence=jdbc.queryForMap("SELECT health_observed_at,alarm_observed_at,health_source_id,alarm_source_id,health_source_mode,alarm_source_mode,health_simulated,alarm_simulated FROM ops_device_state WHERE device_id=?",device.opsDeviceId());
        assertThat(evidence.get("health_observed_at")).isEqualTo(body.get("observed_at"));
        assertThat(evidence.get("alarm_observed_at")).isEqualTo(body.get("observed_at"));
        assertThat(evidence.get("health_source_id")).isEqualTo(device.opsSourceId());
        assertThat(evidence.get("alarm_source_id")).isEqualTo(device.opsSourceId());
        assertThat(evidence.get("health_source_mode")).isEqualTo("replay");
        assertThat(evidence.get("alarm_source_mode")).isEqualTo("replay");
        assertThat(evidence.get("health_simulated")).isEqualTo(true);
        assertThat(evidence.get("alarm_simulated")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message WHERE source=?",Long.class,"local-qa-status:"+device.opsDeviceId())).isEqualTo(1);
        assertThat(count("device_incident")).isEqualTo(incidents);
        assertThat(count("disposal_authorization")).isEqualTo(authorizations);
    }
    @Test void identicalReplayDoesNotRefreshHeartbeatOrVersionAndChangedMessageConflicts() throws Exception {
        var body=input();var original=send(body,200);
        long version=jdbc.queryForObject("SELECT version FROM ops_device_state WHERE device_id=?",Long.class,device.opsDeviceId());
        assertThat(send(body,200)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT version FROM ops_device_state WHERE device_id=?",Long.class,device.opsDeviceId())).isEqualTo(version);
        body.put("health_code","BAD");send(body,409);
        assertThat(jdbc.queryForObject("SELECT health_code FROM ops_device_state WHERE device_id=?",String.class,device.opsDeviceId())).isEqualTo("GOOD");
    }
    @Test void oldFutureAndOutOfOrderReportsCannotReplaceCurrentState() throws Exception {
        var body=input();body.put("observed_at",System.currentTimeMillis()-31000);send(body,409);
        body.put("observed_at",System.currentTimeMillis()+10000);send(body,409);
        body=input();send(body,200);
        body.put("message_id",UUID.randomUUID().toString());body.put("observed_at",((Long)body.get("observed_at"))-1);send(body,409);
    }
    @Test void newerHeartbeatDoesNotRejectHealthOrRenewItsEvidenceAndLateGoodCannotHealNewFault() throws Exception {
        long at=System.currentTimeMillis();
        var healthy=input();healthy.put("observed_at",at-5000);send(healthy,200);
        jdbc.update("UPDATE ops_device_state SET observed_at=?,last_heartbeat_at=?,connectivity='ONLINE' WHERE device_id=?",at,at,device.opsDeviceId());
        assertThat(jdbc.queryForObject("SELECT health_observed_at FROM ops_device_state WHERE device_id=?",Long.class,device.opsDeviceId())).isEqualTo(at-5000);
        var bad=input();bad.put("observed_at",at-1000);bad.put("health_code","BAD");bad.put("has_alarm",true);send(bad,200);
        assertThat(jdbc.queryForObject("SELECT health_code FROM ops_device_state WHERE device_id=?",String.class,device.opsDeviceId())).isEqualTo("BAD");
        assertThat(jdbc.queryForObject("SELECT last_heartbeat_at FROM ops_device_state WHERE device_id=?",Long.class,device.opsDeviceId())).isEqualTo(at);
        var late=input();late.put("observed_at",at-2000);send(late,409);
        assertThat(jdbc.queryForObject("SELECT health_code FROM ops_device_state WHERE device_id=?",String.class,device.opsDeviceId())).isEqualTo("BAD");
        assertThat(jdbc.queryForObject("SELECT alarm_observed_at FROM ops_device_state WHERE device_id=?",Long.class,device.opsDeviceId())).isEqualTo(at-1000);
    }
    @ParameterizedTest @ValueSource(strings={"live","mock","disabled","source-disabled","not-simulated","wrong-source","unbound"})
    void refusesNonQaOrMismatchedDevices(String mode) throws Exception {
        var body=input();
        switch(mode){
            case "live","mock" -> {
                var binding=jdbc.queryForMap("SELECT * FROM mqtt_device_binding WHERE ops_device_id=?",device.opsDeviceId());
                jdbc.update("DELETE FROM mqtt_device_binding WHERE ops_device_id=?",device.opsDeviceId());
                jdbc.update("UPDATE ops_device SET source_mode=? WHERE device_id=?",mode,device.opsDeviceId());
                jdbc.update("UPDATE device SET source_mode=? WHERE device_id=?",mode,device.deviceId());
                // MQTT deliberately has no mock binding; a live binding still must be refused by QA input.
                if("live".equals(mode)){
                    jdbc.update("UPDATE mqtt_broker SET source_mode='live' WHERE broker_id=?",device.brokerId());
                    binding.put("source_mode",mode);
                    String columns=String.join(",",binding.keySet());
                    String placeholders=String.join(",",java.util.Collections.nCopies(binding.size(),"?"));
                    jdbc.update("INSERT INTO mqtt_device_binding ("+columns+") VALUES ("+placeholders+")",binding.values().toArray());
                }
            }
            case "disabled" -> jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?",device.opsDeviceId());
            case "source-disabled" -> jdbc.update("UPDATE ops_integration_source SET enabled=FALSE WHERE source_id=?",device.opsSourceId());
            case "not-simulated" -> jdbc.update("UPDATE ops_device SET simulated=FALSE WHERE device_id=?",device.opsDeviceId());
            case "wrong-source" -> body.put("source_id",UUID.randomUUID().toString());
            case "unbound" -> jdbc.update("DELETE FROM mqtt_device_binding WHERE ops_device_id=?",device.opsDeviceId());
        }
        send(body,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message WHERE source=?",Long.class,"local-qa-status:"+device.opsDeviceId())).isZero();
    }
    @Test void missingAuthInvalidFactsAndInsufficientPermissionsAreRejected() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input()))).andExpect(status().isUnauthorized());
        var body=input();body.put("health_code","ONLINE");send(body,400);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-QA-STATUS-READ','QA status read','',FALSE,TRUE,0,0,0,FALSE)");
        jdbc.update("UPDATE app_user SET role_code='ROLE-QA-STATUS-READ' WHERE account='admin1'");
        send(input(),403);
    }
    @ParameterizedTest @ValueSource(longs={1000,5000})
    void eoExplicitEvidenceSurvivesHeartbeatsButActiveTrackingStillBlocksRecovery(long age) throws Exception {
        var eo=registerEo("replay");
        long at=System.currentTimeMillis()-age;
        var body=eoInput(eo);body.put("observed_at",at);
        send(body,200);
        long version=jdbc.queryForObject("SELECT version FROM ops_device_state WHERE device_id=?",Long.class,eo.opsDeviceId());
        send(body,200);
        assertThat(jdbc.queryForObject("SELECT version FROM ops_device_state WHERE device_id=?",Long.class,eo.opsDeviceId())).isEqualTo(version);
        long heartbeat=System.currentTimeMillis()-1;
        var envelope=EoEdgeEnvelope.decode(eo.reportingTopic(),EoEdgeEnvelope.encode("HeartBeat",eo.edgeId(),heartbeat,
                Map.of("deviceId",eo.externalDeviceId(),"codeStatus",200,"workState",2,"cameraStatus",Map.of())));
        edges.heartbeat(eo,envelope,"{}",heartbeat);
        var evidence=jdbc.queryForMap("SELECT health_code,has_alarm,health_observed_at,alarm_observed_at,health_source_id,alarm_source_id,health_source_mode,health_simulated FROM ops_device_state WHERE device_id=?",eo.opsDeviceId());
        assertThat(evidence).containsEntry("health_code","GOOD").containsEntry("has_alarm",false)
                .containsEntry("health_observed_at",at).containsEntry("alarm_observed_at",at)
                .containsEntry("health_source_id",eo.opsSourceId()).containsEntry("alarm_source_id",eo.opsSourceId())
                .containsEntry("health_source_mode","replay").containsEntry("health_simulated",true);
        assertThat(recovery.evaluate(eo.opsDeviceId(),"replay",eo.opsSourceId(),true,at-1).passed()).isTrue();
        String command=UUID.randomUUID().toString(),task=UUID.randomUUID().toString();
        edges.insertCommand(command,"EO-"+command.substring(0,6),eo.opsDeviceId(),null,"EO_BEGIN_TRACK","test","replay",true,heartbeat+10000,heartbeat);
        jdbc.update("UPDATE device_command SET status='SUCCEEDED' WHERE command_id=?",command);
        edges.insertTask(task,null,null,eo.opsDeviceId(),command,"test","{}",heartbeat);
        // A successful start command does not mean an ongoing tracking task has finished.
        assertThat(recovery.evaluate(eo.opsDeviceId(),"replay",eo.opsSourceId(),true,at-1).reasonCodes()).contains("ACTIVE_DEVICE_WORK");
        var bad=eoInput(eo);bad.put("observed_at",at+1);bad.put("health_code","BAD");bad.put("has_alarm",true);send(bad,200);
        var late=eoInput(eo);late.put("observed_at",at);send(late,409);
        assertThat(jdbc.queryForObject("SELECT health_code FROM ops_device_state WHERE device_id=?",String.class,eo.opsDeviceId())).isEqualTo("BAD");
    }
    @ParameterizedTest @ValueSource(strings={"live","disabled","source-disabled","wrong-source","not-simulated","unbound"})
    void eoQaInputRetainsSourceAndEnvironmentRestrictions(String condition) throws Exception {
        var eo=registerEo("replay");
        var body=eoInput(eo);
        switch(condition){
            case "live" -> {
                // Keep a valid live identity in the isolated fixture without opening a live connection.
                var binding=jdbc.queryForMap("SELECT * FROM eo_device_binding WHERE ops_device_id=?",eo.opsDeviceId());
                var edge=jdbc.queryForMap("SELECT * FROM eo_edge WHERE edge_id=?",eo.edgeId());
                jdbc.update("DELETE FROM eo_device_binding WHERE ops_device_id=?",eo.opsDeviceId());
                jdbc.update("DELETE FROM eo_edge WHERE edge_id=?",eo.edgeId());
                jdbc.update("UPDATE mqtt_broker SET source_mode='live' WHERE broker_id=?",eo.brokerId());
                jdbc.update("UPDATE ops_device SET source_mode='live' WHERE device_id=?",eo.opsDeviceId());
                jdbc.update("UPDATE device SET source_mode='live' WHERE device_id=?",eo.deviceId());
                edge.put("source_mode","live");binding.put("source_mode","live");
                restoreFixtureRow("eo_edge",edge);restoreFixtureRow("eo_device_binding",binding);
            }
            case "disabled" -> jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?",eo.opsDeviceId());
            case "source-disabled" -> jdbc.update("UPDATE ops_integration_source SET enabled=FALSE WHERE source_id=?",eo.opsSourceId());
            case "wrong-source" -> body.put("source_id",device.opsSourceId());
            case "not-simulated" -> jdbc.update("UPDATE ops_device SET simulated=FALSE WHERE device_id=?",eo.opsDeviceId());
            case "unbound" -> jdbc.update("DELETE FROM eo_device_binding WHERE ops_device_id=?",eo.opsDeviceId());
        }
        send(body,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message WHERE source=?",Long.class,"local-qa-status:"+eo.opsDeviceId())).isZero();
    }
    static final String PLANNED=PATH+"/planned-offline";
    @Test void plannedStopDoesNotOpenHeartbeatIncidentUntilReportsResume() throws Exception {
        heardAt(clock.nowMillis()-31_000);
        var declared=plan(device.opsDeviceId(),device.opsSourceId(),200);
        assertThat(declared.path("simulated").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_event_log WHERE device_id=? AND event_type='PLANNED_OFFLINE' AND simulated=TRUE",Long.class,device.opsDeviceId())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='device_planned_offline' AND object_id=?",Long.class,device.opsDeviceId())).isOne();
        offline(device.opsDeviceId());
        reconcile();reconcile();
        assertThat(heartbeatIncidents(device.opsDeviceId())).isZero();
        // The simulator starts again: a heartbeat after the declaration ends the planned window.
        heardAt(declared.path("declared_at").asLong()+6_000);offline(device.opsDeviceId());
        reconcile();
        assertThat(heartbeatIncidents(device.opsDeviceId())).isOne();
        assertThat(jdbc.queryForObject("SELECT simulated FROM device_incident WHERE device_id=?",Boolean.class,device.opsDeviceId())).isTrue();
    }
    @Test void heartbeatStillInFlightAtDeclarationDoesNotEndThePlannedWindow() throws Exception {
        long declared=plan(device.opsDeviceId(),device.opsSourceId(),200).path("declared_at").asLong();
        heardAt(declared+1_000);offline(device.opsDeviceId());
        reconcile();
        assertThat(heartbeatIncidents(device.opsDeviceId())).isZero();
    }
    @Test void unplannedSimulatorStopStillOpensSimulatedIncidentAndDeclarationIsPerDevice() throws Exception {
        var other=registerMqtt("5ga");
        heardAt(clock.nowMillis()-31_000);
        jdbc.update("UPDATE ops_device_state SET last_heartbeat_at=? WHERE device_id=?",clock.nowMillis()-31_000,other.opsDeviceId());
        plan(device.opsDeviceId(),device.opsSourceId(),200);
        offline(device.opsDeviceId());offline(other.opsDeviceId());
        reconcile();
        assertThat(heartbeatIncidents(device.opsDeviceId())).isZero();
        assertThat(heartbeatIncidents(other.opsDeviceId())).isOne();
        assertThat(jdbc.queryForObject("SELECT simulated FROM device_incident WHERE device_id=?",Boolean.class,other.opsDeviceId())).isTrue();
    }
    @Test void deviceNoLongerMarkedSimulatedIsNeverExemptedEvenWithALeftoverPlannedEvent() throws Exception {
        heardAt(clock.nowMillis()-31_000);
        plan(device.opsDeviceId(),device.opsSourceId(),200);
        jdbc.update("UPDATE ops_device SET simulated=FALSE WHERE device_id=?",device.opsDeviceId());
        offline(device.opsDeviceId());
        reconcile();
        assertThat(heartbeatIncidents(device.opsDeviceId())).isOne();
    }
    @ParameterizedTest @ValueSource(strings={"disabled","source-disabled","not-simulated","wrong-source","unbound","unauthorized"})
    void plannedOfflineRefusesNonQaDevicesAndUnauthorizedCallers(String mode) throws Exception {
        String source=device.opsSourceId();int expected=409;
        switch(mode){
            case "disabled" -> jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?",device.opsDeviceId());
            case "source-disabled" -> jdbc.update("UPDATE ops_integration_source SET enabled=FALSE WHERE source_id=?",device.opsSourceId());
            case "not-simulated" -> jdbc.update("UPDATE ops_device SET simulated=FALSE WHERE device_id=?",device.opsDeviceId());
            case "wrong-source" -> source=UUID.randomUUID().toString();
            case "unbound" -> jdbc.update("DELETE FROM mqtt_device_binding WHERE ops_device_id=?",device.opsDeviceId());
            case "unauthorized" -> {
                jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-QA-PLANNED-READ','QA planned read','',FALSE,TRUE,0,0,0,FALSE)");
                jdbc.update("UPDATE app_user SET role_code='ROLE-QA-PLANNED-READ' WHERE account='admin1'");
                expected=403;
            }
        }
        plan(device.opsDeviceId(),source,expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_event_log WHERE device_id=? AND event_type='PLANNED_OFFLINE'",Long.class,device.opsDeviceId())).isZero();
    }
    private Binding registerMqtt(String type){
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        AuthContext.set(new AuthUser(user,"admin1","QA planned test","ROLE-ADMIN",1,false,"ALL"));
        try{
            String suffix=UUID.randomUUID().toString().substring(0,8);
            String id=configuration.register(new Registration(LingyunEnvelope.PROTOCOL,device.brokerId(),"qa-status",suffix,type,
                    "replay",device.ownerOrgId(),device.districtId(),"QA-PLANNED-"+suffix,"模拟计划下线设备",null,null,null),UUID.randomUUID().toString());
            var other=mqtt.binding(id,false);
            jdbc.update("UPDATE ops_device SET enabled=TRUE WHERE device_id=?",id);
            jdbc.update("UPDATE ops_integration_source SET enabled=TRUE WHERE source_id=?",other.opsSourceId());
            return other;
        }finally{AuthContext.clear();}
    }
    private void heardAt(long at){jdbc.update("UPDATE ops_device_state SET last_heartbeat_at=?,connectivity='ONLINE' WHERE device_id=?",at,device.opsDeviceId());}
    private void offline(String id){
        jdbc.update("UPDATE mqtt_broker SET enabled=TRUE WHERE broker_id=?",device.brokerId());
        jdbc.update("UPDATE ops_device_state SET connectivity='OFFLINE' WHERE device_id=?",id);
    }
    private void reconcile(){new com.uav.lowaltitude.modules.device.application.MqttConnectivityIncidentJob(deviceRepository,clock).reconcile();}
    private long heartbeatIncidents(String id){return jdbc.queryForObject("SELECT count(*) FROM device_incident WHERE device_id=? AND incident_type='MQTT_HEARTBEAT_TIMEOUT'",Long.class,id);}
    private JsonNode plan(String id,String source,int expected) throws Exception {
        return json.readTree(mvc.perform(post(PLANNED).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("device_id",id,"source_id",source)))).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    private EoEdgeConfiguration.Binding registerEo(String mode){
        String user=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        AuthContext.set(new AuthUser(user,"admin1","QA EO status test","ROLE-ADMIN",1,false,"ALL"));
        try{
            String suffix=UUID.randomUUID().toString().substring(0,8);
            String broker=configuration.create(new BrokerInput("QA EO "+suffix,"127.0.0.1",1883,false,null,null,
                    "127.0.0.1/32",mode,device.ownerOrgId(),device.districtId(),null),UUID.randomUUID().toString()).brokerId();
            String id=configuration.register(new Registration(EoEdgeEnvelope.PROTOCOL,broker,null,"eo-"+suffix,null,
                    mode,device.ownerOrgId(),device.districtId(),"EO-"+suffix,"模拟光电状态设备",null,null,null,"edge-"+suffix),UUID.randomUUID().toString());
            var eo=edges.binding(id,false);
            jdbc.update("UPDATE ops_device SET enabled=TRUE WHERE device_id=?",id);
            jdbc.update("UPDATE ops_integration_source SET enabled=TRUE WHERE source_id=?",eo.opsSourceId());
            return eo;
        }finally{AuthContext.clear();}
    }
    private Map<String,Object> eoInput(EoEdgeConfiguration.Binding eo){
        var body=input();body.put("device_id",eo.opsDeviceId());body.put("source_id",eo.opsSourceId());return body;
    }
    private void restoreFixtureRow(String table,Map<String,Object> row){
        jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("
                +String.join(",",java.util.Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    }
    private long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    Map<String,Object> input(){
        var body=new LinkedHashMap<String,Object>();body.put("message_id",UUID.randomUUID().toString());body.put("device_id",device.opsDeviceId());
        body.put("source_id",device.opsSourceId());body.put("observed_at",System.currentTimeMillis()-100);
        body.put("connectivity","ONLINE");body.put("health_code","GOOD");body.put("has_alarm",false);return body;
    }
    JsonNode send(Map<String,Object> input,int expected) throws Exception {
        return json.readTree(mvc.perform(post(PATH).header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(input))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
}
