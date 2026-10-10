package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Frontend identities read plan-bound device facts, never backend monitoring or controls. */
@SpringBootTest(properties={"app.dev-seed.enabled=false", "app.flight.status-advance.enabled=false", "app.flight-device-check.schedule.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class FlightDeviceReadPostgresTest {
    private static final String SCHEMA="flight_device_read_"+UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate root;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String org,district,otherOrg,otherDistrict,route,routeVersion,plan,device,session,role;
    long now;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null || !url.matches("jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Dedicated test database required");
        String user=System.getenv("POSTGRES_TEST_USER"),password=System.getenv("POSTGRES_TEST_PASSWORD");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        root.execute("CREATE SCHEMA "+SCHEMA);
        p.add("spring.datasource.url",()->url+"?currentSchema="+SCHEMA+",public");
        p.add("spring.datasource.username",()->user);p.add("spring.datasource.password",()->password);
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        p.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.flyway.schemas",()->SCHEMA);
    }
    @AfterAll static void cleanup(@Autowired org.springframework.context.ConfigurableApplicationContext context) {
        context.getBeansOfType(ThreadPoolTaskScheduler.class).values().forEach(ThreadPoolTaskScheduler::shutdown);
        if(root!=null)root.execute("DROP SCHEMA "+SCHEMA+" CASCADE");
    }
    @BeforeEach void fixture() {
        now=System.currentTimeMillis();
        org=id();district=id();otherOrg=id();otherDistrict=id();route=id();routeVersion=id();plan=id();
        catalog(org,district);catalog(otherOrg,otherDistrict);
        jdbc.update("INSERT INTO route(route_id,route_no,name,enabled,source_mode,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,?,TRUE,'live',?,?,current_timestamp,current_timestamp,0)",route,route,"只读检查航线",org,district);
        jdbc.update("INSERT INTO route_version(route_version_id,route_id,version_no,centerline,corridor_width_m,valid_from,created_at) VALUES(?,?,1,ST_GeomFromText('LINESTRING(118 37,118.01 37)',4326),100,current_timestamp,current_timestamp)",routeVersion,route);
        jdbc.update("INSERT INTO flight_plan(plan_id,plan_no,status_code,source_mode,route_version_id,owner_org_id,district_id,start_at,end_at,created_at,updated_at,version) VALUES(?,?,'PENDING','live',?,?,?,?,?,current_timestamp,current_timestamp,0)",plan,plan,routeVersion,org,district,new Timestamp(now+3600000),new Timestamp(now+7200000));
        session=reader(org,district);
        device=sensor(org,district,"live",false,118.005,37.0);
    }
    @ParameterizedTest @ValueSource(strings={"live","mock","replay"})
    void frontendReadsOnlySamePlanTupleAndSourceWithoutMonitoring(String mode) throws Exception {
        double longitude="live".equals(mode)?118.005:"mock".equals(mode)?118.004:118.006;
        jdbc.update("UPDATE flight_plan SET source_mode=? WHERE plan_id=?",mode,plan);
        jdbc.update("UPDATE ops_device SET source_mode=?,simulated=?,longitude=? WHERE device_id=?",mode,!"live".equals(mode),longitude,device);
        sensor(otherOrg,otherDistrict,mode,!"live".equals(mode),longitude,37.0);
        sensor(org,district,mode,!"live".equals(mode),119.0,37.0);
        sensor(org,district,"live".equals(mode)?"replay":"live",false,longitude,37.0);
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_verification",Long.class);
        JsonNode result=read();
        assertThat(result.path("rows")).hasSize(1);
        assertThat(result.path("rows").get(0).path("device_id").asText()).isEqualTo(device);
        assertThat(result.path("conclusion").asText()).isEqualTo("PREFLIGHT_DEVICE_NORMAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flight_plan_verification",Long.class)).isEqualTo(before);
        assertThat(result.toString()).doesNotContain("connection", "password", "credential", "command");
    }
    @Test void evenAllScopeStillChecksOnlyPlanOrganizationAndDistrict() throws Exception {
        jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE role_code=?",role);
        sensor(otherOrg,otherDistrict,"live",false,118.005,37.0);
        assertThat(read().path("rows")).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"flight:read","route:read","devices"})
    void requiredBusinessPermissionsCannotBeBypassed(String permission) throws Exception {
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=? AND permission_code=?",role,permission);
        mvc.perform(get(endpoint()).header("Authorization","Bearer "+session)).andExpect(status().isForbidden());
    }
    @Test void inaccessiblePlanOrItsRouteReturnsNotFound() throws Exception {
        String allowedSession=session;
        session=reader(otherOrg,otherDistrict);
        mvc.perform(get(endpoint()).header("Authorization","Bearer "+session)).andExpect(status().isNotFound());
        session=allowedSession;
        jdbc.update("UPDATE route SET owner_org_id=?,district_id=? WHERE route_id=?",otherOrg,otherDistrict,route);
        mvc.perform(get(endpoint()).header("Authorization","Bearer "+session)).andExpect(status().isNotFound());
    }
    @Test void backendMonitoringStaysForbiddenEvenWithLegacyPermissionRow() throws Exception {
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,'monitoring','READ',FALSE,current_timestamp)",role);
        assertThat(read().path("rows")).hasSize(1);
        for(String path:List.of("/api/v1/device-events","/api/v1/device-incidents","/api/v1/devices/"+device+"/state"))
            mvc.perform(get(path).header("Authorization","Bearer "+session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BACKEND_ACCESS_DENIED"));
    }
    @Test void unknownLocationAndOpenFaultRemainDistinctAndReadDoesNotCreateWork() throws Exception {
        String unknown=sensor(org,district,"live",false,118.005,37.0);
        jdbc.update("UPDATE ops_device SET longitude=NULL,latitude=NULL,coordinate_system=NULL WHERE device_id=?",unknown);
        jdbc.update("UPDATE ops_device_state SET health_code='BAD',has_alarm=TRUE WHERE device_id=?",device);
        String incident=id();
        jdbc.update("INSERT INTO device_incident(incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,reason,simulated) VALUES(?,?,?,'FAULT','HIGH','PENDING',?,'已有设备故障',FALSE)",incident,device,incident,now-2000);
        long tasks=jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task",Long.class);
        JsonNode result=read();
        assertThat(result.path("unchecked_locations").asInt()).isEqualTo(1);
        assertThat(result.path("complete").asBoolean()).isFalse();
        assertThat(result.path("rows").get(0).path("incidents").get(0).path("incident_id").asText()).isEqualTo(incident);
        assertThat(result.path("conclusion").asText()).isEqualTo("PREFLIGHT_DEVICE_ABNORMAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task",Long.class)).isEqualTo(tasks);
    }
    @Test void anonymousAndNoScopeReadersRemainBlocked() throws Exception {
        mvc.perform(get(endpoint())).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE role_code=?",role);
        mvc.perform(get(endpoint()).header("Authorization","Bearer "+session)).andExpect(status().isForbidden());
    }
    private JsonNode read() throws Exception {
        return json.readTree(mvc.perform(get(endpoint()).header("Authorization","Bearer "+session))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private String endpoint(){return "/api/v1/flight-plans/"+plan+"/verifications/device-check";}
    private static String id(){return UUID.randomUUID().toString();}
    private void catalog(String organization,String area) {
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",organization,organization,organization);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",area,area,area);
    }
    private String reader(String organization,String area) {
        role="ROLE-FD-"+UUID.randomUUID().toString().substring(0,8);
        String user=id(),token=id();
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)",role,role);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",user,user,"计划设备读取测试",role);
        jdbc.update("INSERT INTO app_user_data_scope(user_id,org_id,district_id) VALUES(?,?,?)",user,organization,area);
        for(String permission:List.of("flight:read","route:read","devices"))
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,?,'READ',FALSE,current_timestamp)",role,permission);
        jdbc.update("INSERT INTO app_session(session_id,user_id,expire_at,ip,permission_version) VALUES(?,?,?,'127.0.0.1',0)",token,user,now+3600000);
        return token;
    }
    private String sensor(String organization,String area,String mode,boolean simulated,double longitude,double latitude) {
        String id=id();
        jdbc.update("INSERT INTO ops_device(device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,longitude,latitude,coordinate_system,version,created_at,updated_at) VALUES(?,?,?,'RADAR','雷达','只读测试',TRUE,?,?,?,?,'WGS-84',0,0,0)",id,id,"已有观测设备",mode,simulated,longitude,latitude);
        jdbc.update("INSERT INTO device_business_scope(ops_device_id,owner_org_id,district_id,created_at,updated_at) VALUES(?,?,?,current_timestamp,current_timestamp)",id,organization,area);
        jdbc.update("INSERT INTO ops_device_state(device_id,connectivity,health_code,has_alarm,observed_at,received_at,last_heartbeat_at,simulated,version) VALUES(?,'ONLINE','GOOD',FALSE,?,?,?,?,0)",id,now-1000,now-1000,now-1000,simulated);
        return id;
    }
}
