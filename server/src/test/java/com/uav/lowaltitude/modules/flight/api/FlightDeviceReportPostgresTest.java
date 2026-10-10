package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.http.MediaType;
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

/** Real request authorization, spatial/source checks and transactional deduplication in an isolated schema. */
@SpringBootTest(properties={"app.dev-seed.enabled=false", "app.flight-device-check.simulator-device-bridge-enabled=true", "app.flight.status-advance.enabled=false", "app.flight-device-check.schedule.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles({"local","test"}) @Transactional
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class FlightDeviceReportPostgresTest {
    private static final String SCHEMA="flight_device_report_"+UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate root;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
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
    @ParameterizedTest @ValueSource(strings={"live","mock","replay","bridge"})
    void frontendReportsAndRemindsOnlyItsPlanDeviceWithoutMonitoring(String mode) throws Exception {
        grant();
        boolean bridge="bridge".equals(mode);
        String planMode=bridge?"mock":mode, deviceMode=bridge?"replay":mode;
        if(bridge)jdbc.update("INSERT INTO integration_source(source_id,source_code,name,protocol_code,protocol_version,enabled,source_mode,created_at,updated_at,version) VALUES('local-flight-plan-simulator',?,'隔离模拟计划源','LOCAL_SIMULATOR','1',TRUE,'mock',current_timestamp,current_timestamp,0)",id());
        jdbc.update("UPDATE flight_plan SET source_mode=?,source_id=? WHERE plan_id=?",planMode,bridge?"local-flight-plan-simulator":null,plan);
        jdbc.update("UPDATE ops_device SET source_mode=?,simulated=?,longitude=? WHERE device_id=?",deviceMode,!"live".equals(mode),bridge?118.006:118.004,device);
        fault();
        String key=id();
        var result=data(mvc.perform(report(key)).andExpect(status().isOk()));
        String task=result.path("task_id").asText();
        assertThat(result.path("can_handle").asBoolean()).isFalse();
        assertThat(result.path("reason").asText()).contains("设备上报告警");
        assertThat(result.path("notification_delivery_status").asText()).isEqualTo("DELIVERED");
        assertThat(result.path("notification_attempts").get(0).path("recipient_snapshot").path("channel_type").asText()).isEqualTo("INTERNAL");
        assertThat(data(mvc.perform(report(key)).andExpect(status().isOk())).path("task_id").asText()).isEqualTo(task);
        assertThat(data(mvc.perform(report(id())).andExpect(status().isOk())).path("task_id").asText()).isEqualTo(task);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task WHERE plan_id=?",Integer.class,plan)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_notice_attempt WHERE task_id=?",Integer.class,task)).isEqualTo(1);
        mvc.perform(resend(task,id())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MAINTENANCE_RESEND_BLOCKED"));
        jdbc.update("UPDATE ops_device_maintenance_notice_attempt SET requested_at=? WHERE task_id=?",now-61000,task);
        mvc.perform(get(reportPath()).header("Authorization","Bearer "+session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].can_resend_notification").value(true));
        String retry=id();
        mvc.perform(resend(task,retry)).andExpect(status().isOk()).andExpect(jsonPath("$.data.latest_notification_attempt_no").value(2));
        mvc.perform(resend(task,retry)).andExpect(status().isOk()).andExpect(jsonPath("$.data.reused").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_notice_attempt WHERE task_id=?",Integer.class,task)).isEqualTo(2);
        String actor=jdbc.queryForObject("SELECT user_id FROM app_session WHERE session_id=?",String.class,session);
        assertThat(jdbc.queryForObject("SELECT reported_by FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo(actor);
    }
    @ParameterizedTest @ValueSource(strings={"handoff:create","flight:read","route:read","devices"})
    void missingBusinessPermissionBlocksSubmissionWithoutWriting(String permission) throws Exception {
        grant();fault();
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=? AND permission_code=?",role,permission);
        mvc.perform(report(id())).andExpect(status().isForbidden());
        assertNoTask();
    }
    @ParameterizedTest @ValueSource(strings={"plan","device","outside","source","normal"})
    void wrongScopeCoverageSourceOrNormalDeviceCannotBeReported(String variant) throws Exception {
        grant();fault();
        switch(variant) {
            case "plan" -> jdbc.update("UPDATE flight_plan SET owner_org_id=?,district_id=? WHERE plan_id=?",otherOrg,otherDistrict,plan);
            case "device" -> jdbc.update("UPDATE device_business_scope SET owner_org_id=?,district_id=? WHERE ops_device_id=?",otherOrg,otherDistrict,device);
            case "outside" -> jdbc.update("UPDATE ops_device SET longitude=119 WHERE device_id=?",device);
            case "source" -> jdbc.update("UPDATE ops_device SET source_mode='replay',simulated=TRUE WHERE device_id=?",device);
            case "normal" -> jdbc.update("UPDATE ops_device_state SET has_alarm=FALSE,health_code='GOOD' WHERE device_id=?",device);
        }
        mvc.perform(report(id())).andExpect(status().is(variant.equals("plan")||variant.equals("device")?404:409));
        assertNoTask();
    }
    @Test void frontendCannotProcessRecoveryEvenWithLegacyMonitoringGrant() throws Exception {
        grant();fault();
        jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,'monitoring','OP',FALSE,current_timestamp)",role);
        var result=data(mvc.perform(report(id())).andExpect(status().isOk()));
        String task=result.path("task_id").asText();
        assertThat(result.path("can_handle").asBoolean()).isFalse();
        for(String path:List.of("/api/v1/device-maintenance-tasks","/api/v1/device-maintenance-tasks/"+task+"/workflow","/api/v1/devices/"+device+"/state"))
            mvc.perform(get(path).header("Authorization","Bearer "+session)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BACKEND_ACCESS_DENIED"));
        mvc.perform(post("/api/v1/device-maintenance-tasks/"+task+"/workflow/actions").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",id()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BACKEND_ACCESS_DENIED"));
    }
    @Test void revokedPermissionExpiredSessionAndNoScopeCannotWrite() throws Exception {
        grant();fault();
        String task=data(mvc.perform(report(id())).andExpect(status().isOk())).path("task_id").asText();
        jdbc.update("DELETE FROM app_role_permission WHERE role_code=? AND permission_code='handoff:create'",role);
        mvc.perform(resend(task,id())).andExpect(status().isForbidden());
        mvc.perform(get(reportPath()).header("Authorization","Bearer "+session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].can_resend_notification").value(false));
        grant();
        jdbc.update("UPDATE app_user SET scope_mode='NONE' WHERE role_code=?",role);
        mvc.perform(report(id())).andExpect(status().isForbidden());
        jdbc.update("UPDATE app_session SET expire_at=? WHERE session_id=?",now-1000,session);
        mvc.perform(report(id())).andExpect(status().isUnauthorized());
        mvc.perform(post(reportPath()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_notice_attempt WHERE task_id=?",Integer.class,task)).isEqualTo(1);
    }
    private void grant(){jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at) VALUES(?,'handoff:create','OP',FALSE,current_timestamp)",role);}
    private void fault(){jdbc.update("UPDATE ops_device_state SET has_alarm=TRUE WHERE device_id=?",device);}
    private void assertNoTask(){assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task WHERE plan_id=?",Integer.class,plan)).isZero();}
    private String reportPath(){return "/api/v1/flight-plans/"+plan+"/device-maintenance-tasks";}
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder report(String key) throws Exception {
        sqlSession.clearCache();
        return post(reportPath()).header("Authorization","Bearer "+session).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("device_id",device)));
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resend(String task,String key) {
        sqlSession.clearCache();
        return post("/api/v1/device-maintenance-tasks/"+task+"/notifications/resend").header("Authorization","Bearer "+session).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"expected_attempt_no\":1}");
    }
    private JsonNode data(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }
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
        jdbc.update("INSERT INTO device_sensing_profile(device_id,coverage_kind,radius_m,source_label,version,updated_at) VALUES(?,'CIRCLE',1000,'test',0,?)",id,now);
        return id;
    }
}
