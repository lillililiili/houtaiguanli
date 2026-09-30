package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties={"app.qa.device-setup.enabled=true","app.network.allow-loopback-when-listed=true"})
class LocalQaDeviceApiTest extends LocalInterfaceSimulatorApiTest {
    @org.springframework.beans.factory.annotation.Autowired com.uav.lowaltitude.modules.device.infrastructure.ProtocolDataRepository protocol;
    static final String PATH=BASE+"/countermeasure-device";
    @Test void createsOnlyLoopbackSimulatedDeviceWithoutGrantingDisposalAuthority() throws Exception {
        String plan=send("/plans",plan("qa-cm-device"),200).path("subject_id").asText();
        long grants=jdbc.queryForObject("select count(*) from disposal_authorization",Long.class);
        var result=prepare(plan,200);String id=result.path("device").path("device_id").asText();
        assertThat(result.path("device").path("simulated").asBoolean()).isTrue();
        assertThat(result.path("device").path("connectivity").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("select host from device_connection_profile where device_id=?",String.class,id)).isEqualTo("127.0.0.1");
        assertThat(jdbc.queryForObject("select port from device_connection_profile where device_id=?",Integer.class,id)).isEqualTo(10006);
        assertThat(jdbc.queryForObject("select count(*) from device_business_scope where ops_device_id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization",Long.class)).isEqualTo(grants);
        assertThat(prepare(plan,200).path("device").path("device_id").asText()).isEqualTo(id);
        String anotherPlan=send("/plans",plan("qa-cm-device-reuse"),200).path("subject_id").asText();
        assertThat(prepare(anotherPlan,200).path("device").path("device_id").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("select count(*) from ops_integration_source where source_code='QA-LOCAL-CM4'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ops_device where device_no='QA-LOCAL-CM4'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization",Long.class)).isEqualTo(grants);
    }
    @Test void existingDeviceWithDifferentScopeOrHostCannotBeReused() throws Exception {
        String plan=send("/plans",plan("qa-cm-mismatch"),200).path("subject_id").asText();
        String id=prepare(plan,200).path("device").path("device_id").asText();
        jdbc.update("update device_connection_profile set host='127.0.0.2' where device_id=?",id);
        prepare(plan,409);
        jdbc.update("update device_connection_profile set host='127.0.0.1' where device_id=?",id);
        jdbc.update("update device_business_scope set owner_org_id=(select org_id from app_org where org_id<>owner_org_id fetch first 1 rows only) where ops_device_id=?",id);
        prepare(plan,409);
        assertThat(jdbc.queryForObject("select count(*) from ops_integration_source where source_code='QA-LOCAL-CM4'",Long.class)).isEqualTo(1);
    }
    @Test void rejectsLivePlanAndLeavesDeviceInventoryUnchanged() throws Exception {
        String plan=send("/plans",plan("qa-cm-live"),200).path("subject_id").asText();
        jdbc.update("update flight_plan set source_mode='live' where plan_id=?",plan);
        long before=jdbc.queryForObject("select count(*) from ops_device",Long.class);
        prepare(plan,409);assertThat(jdbc.queryForObject("select count(*) from ops_device",Long.class)).isEqualTo(before);
    }
    @Test void unauthenticatedAndMissingPlanRequestsFail() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isUnauthorized());
        prepare("",400);
    }
    @Test void deviceOperationWithoutAuthorizationCannotProvision() throws Exception {
        String plan=send("/plans",plan("qa-cm-denied"),200).path("subject_id").asText();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES('ROLE-QA-CM-OP','QA设备操作','',FALSE,TRUE,0,0,0,FALSE)");
        for(String permission:List.of("interfaces","devices"))jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES('ROLE-QA-CM-OP',?,'OP',TRUE)",permission);
        jdbc.update("UPDATE app_user SET role_code='ROLE-QA-CM-OP' WHERE account='admin1'");
        prepare(plan,403);
    }
    @Test void firstProtocolReplyInitializesMissingStateAndKeepsSimulationLabels() throws Exception {
        String plan=send("/plans",plan("qa-first-reply"),200).path("subject_id").asText();
        String id=prepare(plan,200).path("device").path("device_id").asText();
        assertThat(jdbc.queryForObject("select count(*) from ops_device_state where device_id=?",Long.class,id)).isZero();
        protocol.saveCountermeasureState(id,"ASCII_HEX_SPACED",0,Map.of("900M",false,"1.5G",false,"2.4G",false,"5.8G",false),System.currentTimeMillis());
        assertThat(jdbc.queryForObject("select connectivity from ops_device_state where device_id=?",String.class,id)).isEqualTo("ONLINE");
        assertThat(jdbc.queryForObject("select simulated from ops_device_state where device_id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from ops_device_state_history where device_id=? and simulated=false",Long.class,id)).isZero();
    }
    @Test void firstConnectionFailureCreatesUnknownHealthAndNeverClaimsOnline() throws Exception {
        String plan=send("/plans",plan("qa-first-failure"),200).path("subject_id").asText();
        String id=prepare(plan,200).path("device").path("device_id").asText();
        protocol.markConnection(id,"COUNTERMEASURE_TCP_4CH_V2_0","OFFLINE",null,"QA模拟连接失败",System.currentTimeMillis(),false);
        assertThat(jdbc.queryForObject("select connectivity from ops_device_state where device_id=?",String.class,id)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("select health_code from ops_device_state where device_id=?",String.class,id)).isEqualTo("UNKNOWN");
    }
    com.fasterxml.jackson.databind.JsonNode prepare(String plan,int status) throws Exception {
        String response=mvc.perform(post(PATH).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("plan_id",plan))))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
}
