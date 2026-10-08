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
