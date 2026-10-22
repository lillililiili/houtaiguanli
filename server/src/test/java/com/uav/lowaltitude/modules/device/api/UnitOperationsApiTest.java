package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.application.DeviceOperationsProcessor;
import com.uav.lowaltitude.platform.security.UnitOperationsContext;

/** Management grants deliberately coexist with an empty business role. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UnitOperationsApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired DeviceOperationsProcessor processor;
    private static final String BASE="/api/v1/unit-admin/operations";
    String org,other,child,user,role,token,inside,outside,childDevice,district;

    @BeforeEach void fixture() throws Exception {
        String tag=UUID.randomUUID().toString().substring(0,8);
        org="operations-a-"+tag;other="operations-b-"+tag;child="operations-c-"+tag;
        user="operations-user-"+tag;role="operations-role-"+tag;
        for(String id:new String[]{org,other,child}) jdbc.update("INSERT INTO app_org(org_id,org_code,name,parent_id,enabled,created_at,updated_at,version) VALUES(?,?,?,?,true,0,0,0)",id,id,id,id.equals(child)?org:null);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,owner_org_id,name_scope) VALUES(?,?,'',false,true,0,0,0,?,?)",role,role,org,org);
        jdbc.update("INSERT INTO app_user(user_id,account,name,org_id,role_code,status,password_hash,fail_count,scope_mode,scope_org_rule,must_change_password,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,?,'ACTIVE',?,0,'ASSIGNED','OWN_ORG',false,0,0,0,0)",user,user,user,org,role,passwords.encode("UnitOperations#2026"));
        jdbc.update("INSERT INTO app_org_delegation(org_id,administrator_user_id,version,created_at,updated_at) VALUES(?,?,0,0,0)",org,user);
        district=jdbc.queryForObject("SELECT district_id FROM app_district WHERE enabled=TRUE ORDER BY district_id FETCH FIRST 1 ROWS ONLY",String.class);
        // Include a foreign tuple deliberately: restricted endpoints must use exact unit ownership, not this role scope.
        for(String id:new String[]{org,other,child}) jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,id,district);
        inside=device("DEV-MOCK-001");outside=device("DEV-MOCK-002");childDevice=device("DEV-MOCK-003");
        for(String[] entry:new String[][]{{inside,org},{outside,other},{childDevice,child}}) {
            jdbc.update("DELETE FROM device_business_scope WHERE ops_device_id=?",entry[0]);
            jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",entry[0],entry[1],district);
        }
        token=json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("account",user).put("password","UnitOperations#2026").put("client_type","BACKEND").toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("session_id").asText();
    }
    private String device(String no){return jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no=?",String.class,no);}
    private String bearer(){return "Bearer "+token;}
    private void grant(String module,String level){jdbc.update("DELETE FROM app_org_management_permission WHERE org_id=? AND module_key=?",org,module);jdbc.update("INSERT INTO app_org_management_permission VALUES(?,?,?)",org,module,level);}
    private JsonNode body(org.springframework.test.web.servlet.ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");}

    @Test void deviceGrantsFilterListsCountsAndDetailWithoutGrantingBusinessOrPlatformAccess() throws Exception {
        grant("devices","READ");
        JsonNode list=body(mvc.perform(get(BASE+"/devices").param("size","1").header("Authorization",bearer())).andExpect(status().isOk()));
        assertThat(list.path("total").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("device_id").asText()).isEqualTo(inside);
        mvc.perform(get(BASE+"/devices/overview").header("Authorization",bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        for(String id:new String[]{outside,childDevice}) mvc.perform(get(BASE+"/devices/"+id).header("Authorization",bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/devices").header("Authorization",bearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/device-monitor/overview").header("Authorization",bearer())).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"/device-monitor/overview").header("Authorization",bearer())).andExpect(status().isForbidden());
        assertThat(UnitOperationsContext.active()).isFalse();
    }

    @Test void monitoringAloneCanReadSelectorsAndTrendsButCannotEditOrCommission() throws Exception {
        grant("monitoring","READ");
        for(String path:new String[]{"/devices","/devices/"+inside,"/device-monitor/overview","/device-monitor/devices/"+inside+"/trends","/devices/"+inside+"/protocol-status"})
            mvc.perform(get(BASE+path).header("Authorization",bearer())).andExpect(status().isOk());
        mvc.perform(get(BASE+"/device-monitor/devices/"+outside+"/trends").header("Authorization",bearer())).andExpect(status().isNotFound());
        mvc.perform(patch(BASE+"/devices/"+inside+"/enabled").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"version\":0,\"reason\":\"越权测试\"}")).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"/commission-tasks").header("Authorization",bearer())).andExpect(status().isForbidden());
    }

    @Test void deviceCreationCannotChooseAnotherUnitOrAnArbitraryExistingSource() throws Exception {
        grant("devices","OP");
        for(String id:new String[]{outside,childDevice}) {
            mvc.perform(put(BASE+"/devices/"+id).header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
            mvc.perform(patch(BASE+"/devices/"+id+"/enabled").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":false,\"version\":0,\"reason\":\"越权测试\"}")).andExpect(status().isNotFound());
        }
        var request=json.createObjectNode().put("protocol_code","RADAR_TCP_V3_0_0").put("owner_org_id",other);
        mvc.perform(post(BASE+"/devices/onboard").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("UNIT_OPERATION_FORBIDDEN"));
        request.put("owner_org_id",org).put("protocol_code","LINGYUN_MQTT_V8_6");
        mvc.perform(post(BASE+"/devices/onboard").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("UNIT_PROTOCOL_FORBIDDEN"));
        JsonNode scopes=body(mvc.perform(get(BASE+"/scopes").header("Authorization",bearer())).andExpect(status().isOk()));
        scopes.forEach(row->assertThat(row.path("org_id").asText()).isEqualTo(org));
        assertThat(UnitOperationsContext.active()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"RADAR_TCP_V3_0_0","COUNTERMEASURE_TCP_4CH_V2_0"})
    void onboardCreatesDedicatedSourceAndBindsSessionUnitWithoutOpeningInterfaceManagement(String protocol) throws Exception {
        grant("devices","OP");
        String no="UNIT-TCP-"+UUID.randomUUID().toString().substring(0,8);
        var request=json.createObjectNode().put("protocol_code",protocol).put("device_no",no).put("name","本单位登记设备")
                .put("host","192.0.2.88").put("port",5001).put("allowed_cidrs","192.0.2.0/24").put("district_id",district);
        JsonNode created=body(mvc.perform(post(BASE+"/devices/onboard").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(request.toString())).andExpect(status().isOk()));
        String id=created.path("device").path("device_id").asText();
        assertThat(jdbc.queryForObject("SELECT owner_org_id FROM device_business_scope WHERE ops_device_id=?",String.class,id)).isEqualTo(org);
        assertThat(jdbc.queryForObject("SELECT source_code FROM ops_integration_source WHERE source_id=(SELECT source_id FROM ops_device WHERE device_id=?)",String.class,id)).isEqualTo(no);
        mvc.perform(get("/api/v1/integration-sources").header("Authorization",bearer())).andExpect(status().isForbidden());
    }

    @Test void commissionAloneUsesOwnDeviceAndRetainsActualExecutingPerson() throws Exception {
        grant("commissioning","OP");
        mvc.perform(get(BASE+"/devices").header("Authorization",bearer())).andExpect(status().isOk());
        Set<String> before=ownCommissionIds();
        String platformToken=body(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"admin1\",\"password\":\"changeme\",\"client_type\":\"BACKEND\"}"))
                .andExpect(status().isOk())).path("session_id").asText();
        Set<String> excluded=new HashSet<>();
        for(String id:new String[]{outside,childDevice}) {
            String foreignTask=body(mvc.perform(post("/api/v1/commission-tasks").header("Authorization","Bearer "+platformToken)
                    .contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode().put("device_id",id).toString()))
                    .andExpect(status().isOk())).path("commission_id").asText();
            excluded.add(foreignTask);
            mvc.perform(get(BASE+"/commission-tasks/"+foreignTask).header("Authorization",bearer())).andExpect(status().isNotFound());
            mvc.perform(get(BASE+"/commission-tasks").param("device_id",id).param("size","1").header("Authorization",bearer()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
        }
        assertThat(ownCommissionIds()).containsExactlyInAnyOrderElementsOf(before);
        String task=createCommission(inside);
        assertThat(jdbc.queryForObject("SELECT management_org_id FROM commission_task WHERE commission_id=?",String.class,task)).isEqualTo(org);
        assertThat(jdbc.queryForObject("SELECT requested_by FROM commission_task WHERE commission_id=?",String.class,task)).isEqualTo(user);
        assertThat(jdbc.queryForObject("SELECT execution_requested_by FROM commission_task WHERE commission_id=?",String.class,task)).isEqualTo(user);
        for(String id:new String[]{outside,childDevice}) mvc.perform(post(BASE+"/commission-tasks").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("device_id",id).toString())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/commission-tasks").header("Authorization",bearer())).andExpect(status().isForbidden());
        assertThat(ownCommissionIds()).hasSize(before.size()+1).containsAll(before).contains(task).doesNotContainAnyElementsOf(excluded);
    }

    @ParameterizedTest @ValueSource(strings={"permission","appointment","ownership"})
    void queuedCommissionRechecksCurrentManagementAuthorityBeforeAdapterCall(String revoked) throws Exception {
        grant("commissioning","OP");String task=createCommission(inside);
        mvc.perform(post(BASE+"/commission-tasks/"+task+"/connect").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isAccepted());
        if(revoked.equals("permission"))grant("commissioning","READ");
        if(revoked.equals("appointment"))jdbc.update("UPDATE app_org_delegation SET administrator_user_id=NULL WHERE org_id=?",org);
        if(revoked.equals("ownership"))jdbc.update("UPDATE device_business_scope SET owner_org_id=? WHERE ops_device_id=?",other,inside);
        processor.process("commission.connect",task);
        assertThat(jdbc.queryForObject("SELECT status FROM commission_task WHERE commission_id=?",String.class,task)).isEqualTo("UNTESTABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_task_event WHERE commission_id=? AND stage_code='CONNECTED'",Integer.class,task)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_task_event WHERE commission_id=? AND message LIKE '%调测未发送%'",Integer.class,task)).isEqualTo(1);
        processor.process("commission.connect",task);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commission_task_event WHERE commission_id=? AND message LIKE '%调测未发送%'",Integer.class,task)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"permission","connection"})
    void queuedRunIsBlockedButCancellationDoesNotRequireBusinessDevicePermission(String changed) throws Exception {
        grant("commissioning","OP");String task=createCommission(inside);
        mvc.perform(post(BASE+"/commission-tasks/"+task+"/connect").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isAccepted());
        processor.process("commission.connect",task);
        mvc.perform(put(BASE+"/commission-tasks/"+task+"/configuration").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2,\"host\":\"192.0.2.222\",\"credential_ref\":\"another-unit-secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("UNIT_COMMISSION_CONFIGURATION_FIXED"));
        mvc.perform(put(BASE+"/commission-tasks/"+task+"/configuration").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}")).andExpect(status().isOk());
        mvc.perform(post(BASE+"/commission-tasks/"+task+"/start").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}")).andExpect(status().isAccepted());
        if(changed.equals("permission"))grant("commissioning","READ");
        else jdbc.update("UPDATE device_connection_profile SET host='192.0.2.222' WHERE device_id=?",inside);
        processor.process("commission.run",task);
        assertThat(jdbc.queryForObject("SELECT results_json FROM commission_task WHERE commission_id=?",String.class,task)).contains("MANAGEMENT_PERMISSION_REVOKED");
        grant("commissioning","OP");String second=createCommission(inside);
        mvc.perform(post(BASE+"/commission-tasks/"+second+"/cancel").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM commission_task WHERE commission_id=?",String.class,second)).isEqualTo("CANCELLED");
    }

    @Test void maintenanceMessagesAndWorkflowAreExactOwnUnitAndIndependentOfMonitoring() throws Exception {
        grant("maintenance","OP");String own=maintenance(inside),foreign=maintenance(outside),nested=maintenance(childDevice);
        mvc.perform(get(BASE+"/device-maintenance-messages").header("Authorization",bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get(BASE+"/device-maintenance-tasks/"+own+"/workflow").header("Authorization",bearer())).andExpect(status().isOk());
        for(String id:new String[]{foreign,nested}) {
            mvc.perform(get(BASE+"/device-maintenance-tasks/"+id+"/workflow").header("Authorization",bearer())).andExpect(status().isNotFound());
            mvc.perform(post(BASE+"/device-maintenance-messages/"+id+"/read").header("Authorization",bearer())).andExpect(status().isNotFound());
        }
        mvc.perform(post(BASE+"/device-maintenance-tasks/"+own+"/workflow/actions").header("Authorization",bearer()).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":1,\"action\":\"START\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=?",String.class,own)).isEqualTo("PROCESSING");
        grant("maintenance","READ");
        mvc.perform(post(BASE+"/device-maintenance-tasks/"+own+"/workflow/actions").header("Authorization",bearer()).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":2,\"action\":\"SAVE_PROGRESS\"}")).andExpect(status().isForbidden());
    }
    private String createCommission(String device) throws Exception {
        return body(mvc.perform(post(BASE+"/commission-tasks").header("Authorization",bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("device_id",device).toString())).andExpect(status().isOk())).path("commission_id").asText();
    }
    private Set<String> ownCommissionIds() throws Exception {
        JsonNode first=body(mvc.perform(get(BASE+"/commission-tasks").param("size","1").header("Authorization",bearer())).andExpect(status().isOk()));
        int total=first.path("total").asInt();
        Set<String> ids=new HashSet<>();
        for(int page=1;page<=Math.max(total,1);page++) {
            JsonNode data=page==1?first:body(mvc.perform(get(BASE+"/commission-tasks").param("size","1").param("page",Integer.toString(page))
                    .header("Authorization",bearer())).andExpect(status().isOk()));
            assertThat(data.path("total").asInt()).isEqualTo(total);
            assertThat(data.path("items").size()).isEqualTo(total==0?0:1);
            for(JsonNode item:data.path("items")) {
                assertThat(item.path("device_id").asText()).isEqualTo(inside);
                assertThat(ids.add(item.path("commission_id").asText())).isTrue();
            }
        }
        assertThat(ids).hasSize(total);
        return ids;
    }
    private String maintenance(String device) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ops_device_maintenance_task(task_id,device_id,device_no,device_name,reason,simulated,status,active_key,reported_by,reported_by_name,reported_at,version,workflow_version,workflow_state,workflow_source_mode,workflow_source_id) SELECT ?,device_id,device_no,name,'单位异常测试',simulated,'PENDING',?,?,?,0,1,2,'PENDING',source_mode,source_id FROM ops_device WHERE device_id=?",id,id,user,user,device);
        return id;
    }
}
