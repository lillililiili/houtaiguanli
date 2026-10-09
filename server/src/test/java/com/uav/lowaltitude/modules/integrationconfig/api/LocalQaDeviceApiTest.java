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
    @Test void ownerUnitAndDistrictAloneProvisionTheDeviceWithoutAnyPlan() throws Exception {
        String org=jdbc.queryForObject("select org_id from app_org where enabled order by org_id fetch first 1 rows only",String.class);
        String district=jdbc.queryForObject("select district_id from app_district where enabled order by district_id fetch first 1 rows only",String.class);
        var device=prepareScope(Map.of("owner_org_id",org,"district_id",district),200).path("device");
        String id=device.path("device_id").asText();
        assertThat(jdbc.queryForObject("select owner_org_id from device_business_scope where ops_device_id=?",String.class,id)).isEqualTo(org);
        assertThat(jdbc.queryForObject("select district_id from device_business_scope where ops_device_id=?",String.class,id)).isEqualTo(district);
        assertThat(prepareScope(Map.of("owner_org_id",org,"district_id",district),200).path("device").path("device_id").asText()).isEqualTo(id);
        prepareScope(Map.of("owner_org_id",org),400);
        prepareScope(Map.of("owner_org_id","missing-org","district_id",district),409);
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
    @Test void restoresPreviouslyDisabledOrDeletedQaDeviceWhenConfigurationStillMatches() throws Exception {
        String plan=send("/plans",plan("qa-cm-device-restore"),200).path("subject_id").asText();
        String id=prepare(plan,200).path("device").path("device_id").asText();
        jdbc.update("update ops_device set enabled=false,deleted_at=? where device_id=?",System.currentTimeMillis(),id);
        jdbc.update("update ops_integration_source set enabled=false where source_id=(select source_id from ops_device where device_id=?)",id);
        var restored=prepare(plan,200).path("device");
        assertThat(restored.path("device_id").asText()).isEqualTo(id);
        assertThat(restored.path("enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select deleted_at from ops_device where device_id=?",Long.class,id)).isNull();
        assertThat(jdbc.queryForObject("select enabled from ops_integration_source where source_id=(select source_id from ops_device where device_id=?)",Boolean.class,id)).isTrue();
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
    @Test void newDeviceIsRegisteredAtTheGivenPositionSoTheMapCanDrawItsRange() throws Exception {
        var device=prepareScope(positioned(new java.math.BigDecimal("118.61040000000001"),new java.math.BigDecimal("37.464")),200).path("device");
        String id=device.path("device_id").asText();
        assertThat(jdbc.queryForObject("select longitude from ops_device where device_id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("118.6104");
        assertThat(jdbc.queryForObject("select latitude from ops_device where device_id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("37.464");
        assertThat(jdbc.queryForObject("select coordinate_system from ops_device where device_id=?",String.class,id)).isEqualTo("WGS-84");
        assertThat(device.path("longitude").decimalValue()).isEqualByComparingTo("118.6104");
        assertThat(jdbc.queryForObject("select detail from audit_log where action='local_qa_device_prepare' and object_id=? order by occurred_at desc fetch first 1 rows only",String.class,id))
            .contains("经度 118.6104000").contains("纬度 37.4640000");
    }
    @Test void registeredDeviceWithoutPositionGetsOneOnceAndAPositionSetLaterIsNeverMoved() throws Exception {
        String id=prepareScope(scope(),200).path("device").path("device_id").asText();
        assertThat(jdbc.queryForObject("select longitude from ops_device where device_id=?",java.math.BigDecimal.class,id)).isNull();
        long version=jdbc.queryForObject("select version from ops_device where device_id=?",Long.class,id);
        var placed=prepareScope(positioned(new java.math.BigDecimal("118.6"),new java.math.BigDecimal("37.46")),200).path("device");
        assertThat(placed.path("device_id").asText()).isEqualTo(id);
        assertThat(placed.path("longitude").decimalValue()).isEqualByComparingTo("118.6");
        assertThat(jdbc.queryForObject("select version from ops_device where device_id=?",Long.class,id)).isEqualTo(version+1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='local_qa_device_prepare' and object_id=? and detail like '补上%'",Long.class,id)).isEqualTo(1);
        // A second start with another scene radar, or a position someone set in 设备管理, leaves the device where it is.
        prepareScope(positioned(new java.math.BigDecimal("118.7"),new java.math.BigDecimal("37.5")),200);
        assertThat(jdbc.queryForObject("select longitude from ops_device where device_id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("118.6");
        jdbc.update("update ops_device set longitude=118.55,latitude=37.45 where device_id=?",id);
        prepareScope(positioned(new java.math.BigDecimal("118.6"),new java.math.BigDecimal("37.46")),200);
        assertThat(jdbc.queryForObject("select longitude from ops_device where device_id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("118.55");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='local_qa_device_prepare' and object_id=? and detail like '补上%'",Long.class,id)).isEqualTo(1);
    }
    @Test void restoredDeviceAlsoGetsTheMissingPosition() throws Exception {
        String id=prepareScope(scope(),200).path("device").path("device_id").asText();
        jdbc.update("update ops_device set enabled=false,deleted_at=? where device_id=?",System.currentTimeMillis(),id);
        var restored=prepareScope(positioned(new java.math.BigDecimal("118.6"),new java.math.BigDecimal("37.46")),200).path("device");
        assertThat(restored.path("device_id").asText()).isEqualTo(id);
        assertThat(restored.path("enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select latitude from ops_device where device_id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("37.46");
    }
    @Test void halfOrOutOfRangePositionIsRejectedBeforeAnythingIsRegistered() throws Exception {
        long before=jdbc.queryForObject("select count(*) from ops_device",Long.class);
        var half=new HashMap<String,Object>(scope());half.put("longitude",118.6);
        assertThat(rejected(half)).contains("经度和纬度要一起填");
        assertThat(rejected(positioned(new java.math.BigDecimal("181"),new java.math.BigDecimal("37.46")))).contains("经度必须在 -180 到 180 之间");
        assertThat(rejected(positioned(new java.math.BigDecimal("118.6"),new java.math.BigDecimal("-90.5")))).contains("纬度必须在 -90 到 90 之间");
        assertThat(jdbc.queryForObject("select count(*) from ops_device",Long.class)).isEqualTo(before);
    }
    Map<String,String> scope() {
        String org=jdbc.queryForObject("select org_id from app_org where enabled order by org_id fetch first 1 rows only",String.class);
        String district=jdbc.queryForObject("select district_id from app_district where enabled order by district_id fetch first 1 rows only",String.class);
        return Map.of("owner_org_id",org,"district_id",district);
    }
    Map<String,Object> positioned(java.math.BigDecimal longitude,java.math.BigDecimal latitude) {
        var body=new HashMap<String,Object>(scope());body.put("longitude",longitude);body.put("latitude",latitude);return body;
    }
    String rejected(Map<String,?> body) throws Exception {
        return mvc.perform(post(PATH).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
    }
    com.fasterxml.jackson.databind.JsonNode prepareScope(Map<String,?> body,int status) throws Exception {
        String response=mvc.perform(post(PATH).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
    com.fasterxml.jackson.databind.JsonNode prepare(String plan,int status) throws Exception {
        String response=mvc.perform(post(PATH).header("Authorization",token).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("plan_id",plan))))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data");
    }
}
