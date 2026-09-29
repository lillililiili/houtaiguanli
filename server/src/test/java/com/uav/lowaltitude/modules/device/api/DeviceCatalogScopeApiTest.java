package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class DeviceCatalogScopeApiTest extends CommissionScopeApiTest {
    @org.springframework.beans.factory.annotation.Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    private void onlyReadModule(String module) {
        String role="ROLE-OVERVIEW-"+java.util.UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)",role,role);
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,'READ',TRUE,CURRENT_TIMESTAMP)",role,module);
        jdbc.update("UPDATE app_user SET role_code=? WHERE user_id=?",role,user);
        sqlSession.clearCache();
    }
    @ParameterizedTest @ValueSource(strings={"devices","monitoring"})
    void overviewSupportsEitherPageReadPermissionAndStillEnforcesScope(String module) throws Exception {
        onlyReadModule(module);
        mvc.perform(get("/api/v1/device-monitor/overview").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/mqtt-brokers").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        String other="devices".equals(module)?"/device-monitor/tree":"/devices";
        mvc.perform(get("/api/v1"+other).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        mvc.perform(get("/api/v1/device-monitor/overview").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE user_id=?",user);
        // JDBC fixture edits bypass MyBatis cache invalidation within this rollback transaction.
        sqlSession.clearCache();
        mvc.perform(get("/api/v1/device-monitor/overview").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void unrelatedReadPermissionCannotReadDeviceOverview() throws Exception {
        onlyReadModule("commissioning");
        mvc.perform(get("/api/v1/device-monitor/overview").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @BeforeEach void isolateCatalogScopeFixture() {
        // The transaction rolls back these test-seed mappings; only one fixture device is assigned.
        jdbc.update("DELETE FROM device_business_scope WHERE ops_device_id<>?",inside);
    }
    @Test void catalogFiltersLegacyTcpDevicesAndCount() throws Exception {
        var result=mapper.readTree(mvc.perform(get("/api/v1/devices?size=100").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(result.path("total").asLong()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("device_id").asText()).isEqualTo(inside);
        mvc.perform(get("/api/v1/devices?keyword=DEV-MOCK-002").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty());
    }
    @Test void typeOptionsExposeScopedCodesThatCanBeUsedForFiltering() throws Exception {
        jdbc.update("UPDATE ops_device SET device_type_code='countermeasure',device_type_name='反制' WHERE device_id=?",inside);
        jdbc.update("UPDATE ops_device SET device_type_code='hidden_type',device_type_name='范围外类型' WHERE device_id=?",outside);
        mvc.perform(get("/api/v1/devices/options").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.type_options.length()").value(1))
                .andExpect(jsonPath("$.data.type_options[0].code").value("countermeasure"))
                .andExpect(jsonPath("$.data.type_options[0].name").value("反制"));
        mvc.perform(get("/api/v1/devices?type_code=countermeasure").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        mvc.perform(get("/api/v1/devices/options").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.type_options").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"","/protocol-status"})
    void directCatalogAndProtocolReadsCannotBypassTuple(String suffix) throws Exception {
        mvc.perform(get("/api/v1/devices/"+outside+suffix).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
    }
    @Test void outOfScopeDeviceCannotBeEnabledOrEdited() throws Exception {
        long version=jdbc.queryForObject("SELECT version FROM ops_device WHERE device_id=?",Long.class,outside);
        mvc.perform(patch("/api/v1/devices/"+outside+"/enabled").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"version\":"+version+",\"reason\":\"范围负向验证\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/devices/"+outside).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"device_no\":\"DEV-MOCK-002\",\"name\":\"不应修改\",\"device_type_name\":\"雷达\",\"channel\":\"测试\",\"version\":"+version+"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT version FROM ops_device WHERE device_id=?",Long.class,outside)).isEqualTo(version);
    }
    @Test void disabledTupleAndRevocationRemoveDeviceImmediately() throws Exception {
        mvc.perform(get("/api/v1/devices/"+inside).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        jdbc.update("UPDATE app_district SET enabled=FALSE WHERE district_id=?",district);
        mvc.perform(get("/api/v1/devices/"+inside).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        jdbc.update("UPDATE app_district SET enabled=TRUE WHERE district_id=?",district);
        jdbc.update("DELETE FROM app_user_data_scope WHERE user_id=?",user);
        mvc.perform(get("/api/v1/devices").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
}
