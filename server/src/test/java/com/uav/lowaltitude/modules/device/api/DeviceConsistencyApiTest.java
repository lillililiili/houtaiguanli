package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.platform.query.StatisticsScope;
import com.uav.lowaltitude.platform.config.SimulationPolicy;

@SpringBootTest(properties = "app.device-monitor-events.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DeviceConsistencyApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    String org, district, backend, frontend;
    private static final String SCOPE = "enabled=true&statistics_scope=true";

    @BeforeEach void setup() {
        org = UUID.randomUUID().toString(); district = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)", org, org, org);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)", district, district, district);
        String role = "ROLE-CATALOG-" + UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)",role,role);
        for (String permission : new String[]{"devices", "dashboard", "statistics", "device:read"})
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,'READ',TRUE,CURRENT_TIMESTAMP)",role,permission);
        frontend = session(role); backend = session("ROLE-ADMIN");
    }

    @Test void sameScopedPopulationAcrossFrontendBackendDashboardAndStatistics() throws Exception {
        device("live", false, true, true, "ONLINE");
        device("replay", true, true, true, "OFFLINE");
        String unlocated = device("live", false, true, false, "UNKNOWN");
        device("mock", true, true, true, "ONLINE");
        device("live", true, true, true, "ONLINE");
        device("replay", true, true, false, "UNKNOWN");
        device("live", false, false, true, "ONLINE");
        String deleted = device("live", false, true, true, "ONLINE");
        jdbc.update("UPDATE ops_device SET deleted_at=1,enabled=FALSE WHERE device_id=?",deleted);
        String hidden = device("live", false, true, true, "ONLINE");
        jdbc.update("DELETE FROM device_business_scope WHERE ops_device_id=?",hidden);
        jdbc.update("UPDATE ops_device SET longitude=NULL,latitude=NULL WHERE device_id=?",unlocated);
        JsonNode front = read("/devices?" + SCOPE, frontend);
        assertThat(front.path("total").asInt()).isEqualTo(3);
        assertThat(index(front)).isEqualTo(index(read("/devices?" + SCOPE, backend)))
                .isEqualTo(index(read("/device-monitor/tree?" + SCOPE, backend)));
        for (String path : new String[]{"/devices/overview?", "/device-monitor/overview?"}) {
            JsonNode overview = read(path + SCOPE, backend);
            assertThat(overview.path("total").asInt()).isEqualTo(3);
            assertThat(overview.path("online").asInt() + overview.path("offline").asInt()
                    + overview.path("abnormal").asInt() + overview.path("unknown").asInt()).isEqualTo(3);
        }
        assertThat(read("/devices/overview?" + SCOPE, frontend).path("total").asInt()).isEqualTo(3);
        assertThat(read("/dashboard/snapshot", frontend).path("devices").path("total").asInt()).isEqualTo(3);
        assertThat(read("/stats/operations", frontend).path("devices").path("total").asInt()).isEqualTo(3);
        mvc.perform(get("/api/v1/device-monitor/overview").header("Authorization", "Bearer " + frontend)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/devices/overview")).andExpect(status().isUnauthorized());
        jdbc.update("DELETE FROM app_user_data_scope WHERE org_id=?",org);
        assertThat(read("/devices/overview?" + SCOPE, frontend).path("total").asInt()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"ONLINE", "ABNORMAL"})
    void stateAndCatalogUpdatesAreIdenticalAcrossReadModels(String connectivity) throws Exception {
        String id = device("live",false,true,true,connectivity);
        long observation = System.currentTimeMillis() - 2000;
        jdbc.update("UPDATE ops_device SET name=?,longitude=?,latitude=? WHERE device_id=?", "更新-" + UUID.randomUUID(),118.17,37.12,id);
        jdbc.update("UPDATE ops_device_state SET health_code='BAD',has_alarm=TRUE,observed_at=?,received_at=?,version=version+1 WHERE device_id=?",observation,observation+100,id);
        JsonNode row = read("/devices?"+SCOPE,frontend).path("items").get(0);
        assertThat(read("/devices/"+id,backend).path("device")).isEqualTo(row);
        assertThat(read("/device-monitor/tree?"+SCOPE,backend).path("items").get(0)).isEqualTo(row);
        JsonNode state = read("/devices/"+id+"/state",backend);
        for (String field : new String[]{"connectivity","health_code","has_alarm","observed_at","received_at","last_heartbeat_at"})
            assertThat(state.path(field)).as(field).isEqualTo(row.path(field));
        JsonNode monitor = read("/device-monitor/devices/"+id+"/information",backend);
        JsonNode commission = read("/commission-tasks/device-information/"+id,backend);
        assertThat(monitor.path("sections")).isEqualTo(commission.path("sections"));
        assertThat(monitor.path("name")).isEqualTo(row.path("name"));
        String filter = SCOPE + "&connectivity="+connectivity+"&type_code=radar&channel=consistent&region=test-region&vendor=test-vendor";
        assertThat(read("/devices/overview?"+filter,backend).path("total").asInt()).isEqualTo(1);
        assertThat(read("/devices/overview?"+filter+"&keyword=missing",backend).path("total").asInt()).isZero();
        jdbc.update("UPDATE ops_device SET enabled=FALSE WHERE device_id=?",id);
        assertThat(read("/device-monitor/tree?"+SCOPE,backend).path("total").asInt()).isZero();
        assertThat(read("/devices/overview?enabled=false&statistics_scope=true",backend).path("total").asInt()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(ints={101,501})
    void monitorAndCatalogPaginationIncludeEveryDevice(int count) throws Exception {
        for(int i=0;i<count;i++) device("live",false,true,false,"UNKNOWN");
        Map<String,JsonNode> catalog=new HashMap<>(), tree=new HashMap<>();
        for(int page=1;page<=(count+99)/100;page++) {
            String query=SCOPE+"&page="+page+"&size=100&sort=device_no_asc";
            catalog.putAll(index(read("/devices?"+query,frontend)));
            tree.putAll(index(read("/device-monitor/tree?"+query,backend)));
        }
        assertThat(catalog).hasSize(count).isEqualTo(tree);
    }

    @ParameterizedTest @ValueSource(strings={"aoa","bsc","dcd","dec","ifr","rid","AOA"})
    void removedCategoriesCannotBeRegisteredAgain(String type) throws Exception {
        String body=json.writeValueAsString(Map.of("device_no", UUID.randomUUID().toString(),"name","不可登记设备",
                "device_type_code",type,"device_type_name","旧类型","channel","consistent"));
        JsonNode error=json.readTree(mvc.perform(post("/api/v1/devices").header("Authorization","Bearer "+backend)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("DEVICE_TYPE_RETIRED");
        String mqtt=json.writeValueAsString(Map.of("protocol_code","LINGYUN_MQTT_V8_6", "device_type_abbr",type));
        JsonNode mqttError=json.readTree(mvc.perform(post("/api/v1/devices/onboard").header("Authorization","Bearer "+backend)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(mqtt))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString()).path("error");
        assertThat(mqttError.path("code").asText()).isEqualTo("DEVICE_TYPE_RETIRED");
        String historical=device("live",false,false,true,"OFFLINE");
        jdbc.update("UPDATE ops_device SET device_type_code=? WHERE device_id=?",type,historical);
        assertThat(read("/devices/"+historical,backend).path("device").path("enabled").asBoolean()).isFalse();
        JsonNode enableError=json.readTree(mvc.perform(patch("/api/v1/devices/"+historical+"/enabled")
                .header("Authorization","Bearer "+backend).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":0,\"reason\":\"类型已移除\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString()).path("error");
        assertThat(enableError.path("code").asText()).isEqualTo("DEVICE_TYPE_RETIRED");
        assertThat(jdbc.queryForObject("SELECT enabled FROM ops_device WHERE device_id=?",Boolean.class,historical)).isFalse();
    }

    private JsonNode read(String path,String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1"+path).header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private Map<String,JsonNode> index(JsonNode page) {
        Map<String,JsonNode> result=new HashMap<>();
        page.path("items").forEach(row->result.put(row.path("device_id").asText(),row));
        return result;
    }

    @ParameterizedTest @ValueSource(strings={"countermeasure", "radar"})
    void explicitlyPreparedProtocolSimulatorsRemainVisibleWithSimulationLabels(String type) throws Exception {
        String id=device("live",true,true,true,"OFFLINE");
        String source=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,?,'live',TRUE,0,0)",
                source,source,source,type.equals("countermeasure")?"COUNTERMEASURE_TCP_4CH_V2_0":"RADAR_TCP_V3_0_0");
        jdbc.update("UPDATE ops_device SET source_id=?,external_device_id=?,device_type_code=?,name=? WHERE device_id=?",
                source,UUID.randomUUID().toString(),type,"登记模拟设备-"+UUID.randomUUID(),id);
        // Source/report facts alone must not turn a built-in sample into a prepared simulator.
        assertThat(read("/devices?"+SCOPE,frontend).path("total").asInt()).isZero();
        jdbc.update("INSERT INTO audit_log(audit_id,action,object_type,object_id,occurred_at,result) VALUES(?,'local_qa_device_prepare','device',?,?,'SUCCESS')",
                UUID.randomUUID().toString(),id,System.currentTimeMillis());
        JsonNode front=read("/devices?"+SCOPE,frontend);
        assertThat(front.path("total").asInt()).isEqualTo(1);
        assertThat(front.path("items").get(0).path("simulated").asBoolean()).isTrue();
        assertThat(front.path("items").get(0).path("device_type_code").asText()).isEqualTo(type);
        assertThat(index(front)).isEqualTo(index(read("/devices?"+SCOPE,backend)))
                .isEqualTo(index(read("/device-monitor/tree?"+SCOPE,backend)));
        assertThat(read("/device-monitor/overview?"+SCOPE,backend).path("total").asInt()).isEqualTo(1);
        assertThat(read("/devices/overview?"+SCOPE,frontend).path("simulated").asBoolean()).isTrue();
        JsonNode snapshot=read("/dashboard/snapshot",frontend);
        assertThat(snapshot.path("devices").path("total").asInt()).isEqualTo(1);
        assertThat(snapshot.path("simulated_included").path("devices").asInt()).isEqualTo(1);
        assertThat(read("/stats/operations",frontend).path("devices").path("total").asInt()).isEqualTo(1);
        assertThat(read("/devices/overview?enabled=true&formal_only=true",frontend).path("total").asInt()).isZero();
        for(String[] profiles:new String[][]{{"local","qa"},{"prod"},{"local","qa","production"}}) {
            MockEnvironment env=new MockEnvironment(); env.setActiveProfiles(profiles);
            String predicate=new StatisticsScope(new SimulationPolicy(env)).deviceSql("d");
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM ops_device d WHERE d.device_id=? AND "+predicate,Long.class,id);
            assertThat(count).isEqualTo(profiles.length==2?1:0);
        }
        jdbc.update("UPDATE ops_device_state SET observed_at=NULL,last_heartbeat_at=NULL WHERE device_id=?",id);
        assertThat(read("/devices?"+SCOPE,frontend).path("total").asInt()).isZero();
        jdbc.update("UPDATE ops_device_state SET observed_at=? WHERE device_id=?",System.currentTimeMillis(),id);
        jdbc.update("UPDATE ops_integration_source SET simulated=FALSE WHERE source_id=?",source);
        assertThat(read("/devices?"+SCOPE,frontend).path("total").asInt()).isZero();
        jdbc.update("UPDATE ops_integration_source SET simulated=TRUE WHERE source_id=?",source);
        jdbc.update("DELETE FROM device_business_scope WHERE ops_device_id=?",id);
        assertThat(read("/devices?"+SCOPE,frontend).path("total").asInt()).isZero();
    }

    private String session(String role) {
        String id=UUID.randomUUID().toString(), token=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",id,id,id,role);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",id,org,district);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",token,id,System.currentTimeMillis()+3600000);
        return token;
    }
    private String device(String mode,boolean simulated,boolean enabled,boolean reported,String connectivity) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ops_device(device_id,device_no,name,device_type_code,device_type_name,channel,region_name,vendor,longitude,latitude,coordinate_system,enabled,source_mode,simulated,version,created_at,updated_at) VALUES(?,?,?,'radar','雷达','consistent','test-region','test-vendor',118.51,37.4,'WGS84',?,?,?,0,0,0)",id,id,id,enabled,mode,simulated);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,org,district);
        jdbc.update("INSERT INTO ops_device_state(device_id,connectivity,health_code,has_alarm,observed_at,received_at,last_heartbeat_at,simulated,version) VALUES(?,?,'UNKNOWN',FALSE,?,?,?,?,0)",id,connectivity,reported?System.currentTimeMillis():null,System.currentTimeMillis(),reported?System.currentTimeMillis():null,simulated);
        return id;
    }
}
