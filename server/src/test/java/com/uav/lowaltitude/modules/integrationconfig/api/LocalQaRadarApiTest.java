package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"app.qa.device-setup.enabled=true","app.network.allow-loopback-when-listed=true",
        "app.fusion.live-promotion.enabled=true","app.fusion.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class LocalQaRadarApiTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired com.uav.lowaltitude.modules.fusion.FusionContracts.SourceObservationPort port;
    @Autowired com.uav.lowaltitude.modules.device.application.LiveRadarFrameIngestService radarIngest;
    static final String PATH="/api/v1/local-interface-simulator/radar-device";
    String token;
    @BeforeEach void login() throws Exception {
        token="Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\"}")).andReturn().getResponse().getContentAsString())
                .path("data").path("session_id").asText();
    }
    Map<String,Object> input() {
        var body=new LinkedHashMap<String,Object>();
        body.put("owner_org_id",jdbc.queryForObject("select org_id from app_org where enabled order by org_id fetch first 1 rows only",String.class));
        body.put("district_id",jdbc.queryForObject("select district_id from app_district where enabled order by district_id fetch first 1 rows only",String.class));
        body.put("longitude",110+Math.random()*10);body.put("latitude",30+Math.random()*10);body.put("coordinate_system","WGS84");return body;
    }
    JsonNode send(Map<String,Object> body,int status) throws Exception {
        return json.readTree(mvc.perform(post(PATH).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void registersOneIndependentDeviceReusesIdentityAndDoesNotManufactureHealth() throws Exception {
        var mqttBefore=jdbc.queryForList("select * from ops_device where channel='MQTT' order by device_id");
        var body=input();var device=send(body,200).path("device");String id=device.path("device_id").asText();
        assertThat(device.path("simulated").asBoolean()).isTrue();
        assertThat(device.path("source_mode").asText()).isEqualTo("live");
        assertThat(device.path("connectivity").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select port from device_connection_profile where device_id=?",Integer.class,id)).isEqualTo(15001);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='local_qa_device_prepare' and object_id=?",Long.class,id)).isEqualTo(1);
        body.put("longitude",101);body.put("latitude",25);
        assertThat(send(body,200).path("device").path("device_id").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("select longitude from ops_device where device_id=?",java.math.BigDecimal.class,id)).isNotEqualByComparingTo("101");
        assertThat(jdbc.queryForObject("select count(*) from device where device_id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from ops_device where channel='MQTT' order by device_id")).isEqualTo(mqttBefore);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"1","4294967295"})
    void rtkWireFrameCanRepeatAcrossConnectionsWithoutReplacingHistory(String frame) throws Exception {
        String id=send(input(),200).path("device").path("device_id").asText();
        String source=jdbc.queryForObject("select source_id from ops_device where device_id=?",String.class,id);
        long now=System.currentTimeMillis();
        var latitude=new java.math.BigDecimal("32.1234");
        var longitude=new java.math.BigDecimal("115.5678");
        var rtk=new com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.Rtk(latitude,longitude,java.math.BigDecimal.ZERO,18,0);
        String historical=UUID.randomUUID().toString();
        jdbc.update("insert into radar_rtk_sample(sample_id,device_id,frame_id,latitude_deg,longitude_deg,heading_deg,satellite_count,received_at) values(?,?,?,?,?,?,?,?)",
                historical,id,frame,latitude,longitude,0,12,now-60000);
        var before=jdbc.queryForMap("select * from radar_rtk_sample where sample_id=?",historical);
        String first=id+":rtk:"+UUID.randomUUID()+":"+frame;
        String second=id+":rtk:"+UUID.randomUUID()+":"+frame;
        assertThat(radarIngest.ingestRtk(source,id,first,frame,rtk,new byte[]{1},now)).isTrue();
        assertThat(radarIngest.ingestRtk(source,id,first,frame,rtk,new byte[]{1},now+1)).isFalse();
        assertThat(radarIngest.ingestRtk(source,id,second,frame,rtk,new byte[]{2},now+2)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from radar_rtk_sample where device_id=? and frame_id=?",Long.class,id,frame)).isEqualTo(3);
        assertThat(jdbc.queryForMap("select * from radar_rtk_sample where sample_id=?",historical)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=? and processing_status='PROCESSED'",Long.class,"live-device:"+id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select health_code from ops_device_state where device_id=?",String.class,id)).isEqualTo("UNKNOWN");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"host","source","disabled","scope"})
    void rejectsConflictingOrDisabledIdentityWithoutOverwritingIt(String changed) throws Exception {
        var body=input();String id=send(body,200).path("device").path("device_id").asText();
        switch(changed) {
            case "host" -> jdbc.update("update device_connection_profile set host='127.0.0.2' where device_id=?",id);
            case "source" -> jdbc.update("update ops_integration_source set simulated=false where source_id=(select source_id from ops_device where device_id=?)",id);
            case "disabled" -> jdbc.update("update ops_device set enabled=false where device_id=?",id);
            case "scope" -> body.put("owner_org_id",jdbc.queryForObject("select org_id from app_org where enabled and org_id<>? fetch first 1 rows only",String.class,body.get("owner_org_id")));
        }
        var before=jdbc.queryForList("select * from ops_device where device_id=?",id);
        send(body,409);assertThat(jdbc.queryForList("select * from ops_device where device_id=?",id)).isEqualTo(before);
    }
    @Test void requiresValidPositionAndExistingScope() throws Exception {
        var body=input();body.put("longitude",181);send(body,400);
        body.put("longitude",110);body.put("latitude",null);send(body,400);
        body.put("latitude",31);body.put("owner_org_id",UUID.randomUUID().toString());send(body,400);
        assertThat(jdbc.queryForObject("select count(*) from ops_device where device_no='QA-LOCAL-RADAR-TCP'",Long.class)).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings={" ", "GCJ-02", "BD09", "CGCS2000"})
    void requiresDeclaredWgs84BeforeRegisteringRadar(String coordinateSystem) throws Exception {
        var body=input();
        if(coordinateSystem==null)body.remove("coordinate_system");else body.put("coordinate_system",coordinateSystem);
        send(body,400);
        assertThat(jdbc.queryForObject("select count(*) from ops_device where device_no='QA-LOCAL-RADAR-TCP'",Long.class)).isZero();
    }
    @Test void deniesUnauthenticatedAndBusinessAccounts() throws Exception {
        mvc.perform(post(PATH).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(input()))).andExpect(status().isUnauthorized());
        String user=jdbc.queryForObject("select user_id from app_session where session_id=?",String.class,token.substring(7));
        String businessRole=jdbc.queryForObject("select role_code from app_role where role_code not in ('ROLE-ADMIN','ROLE-BACKEND') order by role_code fetch first 1 rows only",String.class);
        jdbc.update("update app_user set role_code=? where user_id=?",businessRole,user);
        send(input(),403);
    }
    @Test void liveSessionSelectionExcludesSimulationInProductionButKeepsRealRadar() throws Exception {
        String id=send(input(),200).path("device").path("device_id").asText();
        var environment=new org.springframework.mock.env.MockEnvironment();environment.setActiveProfiles("local","qa","production");
        var policy=new com.uav.lowaltitude.platform.config.SimulationPolicy(environment);
        var repo=new com.uav.lowaltitude.modules.device.infrastructure.LiveDeviceRepository(jdbc,policy);
        assertThat(repo.stillEnabled(id)).isFalse();
        assertThat(repo.enabledDevices()).noneMatch(row->id.equals(row.get("device_id")));
        environment.setActiveProfiles("local","qa");assertThat(repo.stillEnabled(id)).isTrue();
        environment.setActiveProfiles("production");
        jdbc.update("update ops_device set simulated=false where device_id=?",id);
        jdbc.update("update ops_integration_source set simulated=false where source_id=(select source_id from ops_device where device_id=?)",id);
        assertThat(repo.stillEnabled(id)).isTrue();
        assertThat(repo.enabledDevices()).anyMatch(row->id.equals(row.get("device_id")));
    }
    @Test void simulatedNativeFramesCannotEnterRealPromotionChannelEvenWhenGlobalSwitchIsOn() throws Exception {
        var device=send(input(),200).path("device");String code=device.path("device_no").asText();
        port.accept(List.of(Map.of("device_id",code,"boot_micros",1L,"frame_id",2L,"items",List.of())));
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source=?",Long.class,"live-radar:"+code)).isZero();
    }

    Map<String,Object> manualInput(boolean simulated, int tcpPort) {
        var body=input();
        body.put("protocol_code","RADAR_TCP_V3_0_0"); body.put("device_no","manual-"+UUID.randomUUID());
        body.put("name","手动新增雷达"); body.put("host","127.0.0.1"); body.put("port",tcpPort);
        body.put("allowed_cidrs","127.0.0.1/32"); body.put("simulated",simulated);
        body.put("rtk_enabled",true); body.put("coordinate_transform_enabled",true);
        return body;
    }
    JsonNode manualSend(Map<String,Object> body,int expected) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/devices/onboard").header("Authorization",token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={15329,16743})
    void manuallyOnboardsIndependentSimulatedRadarWithoutChangingExistingDevices(int tcpPort) throws Exception {
        send(input(),200);
        var before=jdbc.queryForList("select * from ops_device order by device_id");
        var body=manualInput(true,tcpPort); var created=manualSend(body,200); var device=created.path("device");
        String id=device.path("device_id").asText();
        assertThat(device.path("simulated").asBoolean()).isTrue();
        assertThat(device.path("source_mode").asText()).isEqualTo("live");
        assertThat(created.path("connection").path("port").asInt()).isEqualTo(tcpPort);
        assertThat(device.path("connectivity").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='local_qa_device_prepare' and object_id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from ops_device where device_id<>? order by device_id",id)).isEqualTo(before);
        mvc.perform(get("/api/v1/devices/"+id+"/protocol-status").header("Authorization",token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/device-monitor/devices/"+id+"/information").header("Authorization",token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/commission-tasks/device-information/"+id).header("Authorization",token)).andExpect(status().isOk());
        // A constraint rejection aborts PostgreSQL's surrounding test transaction; assert it last.
        manualSend(body,409);
    }
    @Test void realOnboardingIsUnchangedAndInvalidSimulationLeavesNoSource() throws Exception {
        var body=manualInput(false,19457);
        assertThat(manualSend(body,200).path("device").path("simulated").asBoolean()).isFalse();
        for (String invalid : List.of("host","allowed_cidrs","protocol_code")) {
            var bad=manualInput(true,19345);
            bad.put(invalid,switch(invalid){case "host"->"192.0.2.27";case "allowed_cidrs"->"0.0.0.0/0";default->"COUNTERMEASURE_TCP_4CH_V2_0";});
            manualSend(bad,400);
            assertThat(jdbc.queryForObject("select count(*) from ops_device where device_no=?",Long.class,bad.get("device_no"))).isZero();
        }
        mvc.perform(get("/api/v1/devices/onboarding-options").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.manual_radar_simulation_allowed").value(true));
    }
}
