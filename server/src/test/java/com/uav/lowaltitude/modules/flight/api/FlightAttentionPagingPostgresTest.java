package com.uav.lowaltitude.modules.flight.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only a randomized schema in the explicitly selected disposable PostgreSQL database is written. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = "jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class FlightAttentionPagingPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.enabled", () -> false);
    }
    @AfterAll static void close() { DATABASE.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean FlightReadRepository repository;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceRepository maintenance;
    private String org, district, route, role, session, prefix;
    private OffsetDateTime now;
    private List<Plan> population;
    private record Plan(String id, String number, String state, String mode, OffsetDateTime start) { }
    private static final Comparator<Plan> SOONEST = Comparator.comparing(Plan::start,
            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Plan::id);
    private static final Comparator<Plan> NEWEST = Comparator.comparing(Plan::start,
            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Plan::id);

    @BeforeEach void fixture() {
        org=id(); district=id(); route=id(); role="ROLE-"+id().substring(0,20); session=id(); prefix=id().substring(0,8);
        now=OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        organization(org); district(district);
        route=route(org,district);
        jdbc.update("insert into app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role)"
                + " values(?,?,'',false,true,0,0,0,false)",role,role);
        for(String permission:List.of("flight:read","assessment:read","risk:read"))
            jdbc.update("insert into app_role_permission(role_code,permission_code,permission_level,menu_enabled,created_at)"
                    + " values(?,?,'READ',false,current_timestamp)",role,permission);
        String user=id();
        jdbc.update("insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version)"
                + " values(?,?,?,?,'ACTIVE','unused',0,'ASSIGNED',0,0,0,0)",user,user,"任务读取测试",role);
        jdbc.update("insert into app_user_data_scope(user_id,org_id,district_id,created_at) values(?,?,?,current_timestamp)",user,org,district);
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) values(?,?,?,'127.0.0.1',0)",session,user,System.currentTimeMillis()+3600000);
    }

    @ParameterizedTest
    @ValueSource(strings={"live","replay"})
    void allPagesFiltersAndTotalsIncludeTasksBeyondTwoThousand(String mode) throws Exception {
        seedPopulation(mode);
        assertAllPages("", population, 97);
        List<Plan> aircraft=population.stream().filter(p->p.state.equals("EXECUTING")).toList();
        assertAllPages("&attention=DEVICE",List.of(),4);
        assertAllPages("&attention=RISK",List.of(),4);
        assertAllPages("&status_code=PENDING&source_mode="+mode,
                population.stream().filter(p->p.state.equals("PENDING")&&p.mode.equals(mode)).toList(),100);
        assertAllPages("&status_code=COMPLETED",population.stream().filter(p->p.state.equals("COMPLETED")).toList(),3);
        assertAllPages("&keyword="+prefix+"-absent",List.of(),20);
        long from=now.plusHours(1).toInstant().toEpochMilli(), to=now.plusHours(2).toInstant().toEpochMilli();
        List<Plan> timed=population.stream().filter(p->p.start!=null&&!p.start.isAfter(now.plusHours(2))
                &&!p.start.plusHours(1).isBefore(now.plusHours(1))).toList();
        assertAllPages("&window_from="+from+"&window_to="+to,timed,100);
        Plan oldest=aircraft.get(aircraft.size()-1);
        assertAllPages("&keyword="+oldest.number,List.of(oldest),20);
        JsonNode beyond=read("&page=2147483647&size=1");
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("total").asInt()).isEqualTo(population.size());
        mvc.perform(get("/api/v1/flight-plans?order=attention&page=2147483647&size=100")
                .header("Authorization","Bearer "+session)).andExpect(status().isBadRequest());
    }

    @Test void permissionsStillApplyToTheCompletePopulation() throws Exception {
        seedPopulation("replay");
        jdbc.update("delete from app_role_permission where role_code=? and permission_code in ('assessment:read','risk:read')",role);
        JsonNode page=read("&size=100");
        assertThat(page.path("total").asInt()).isEqualTo(population.size());
        assertThat(page.path("attention_summary").path("needs_action").asInt()).isZero();
        assertThat(page.path("attention_summary").path("other_executing").asInt()).isEqualTo(11);
        assertThat(read("&attention=RISK").path("total").asInt()).isZero();
        jdbc.update("delete from app_role_permission where role_code=? and permission_code='flight:read'",role);
        mvc.perform(get("/api/v1/flight-plans?order=attention").header("Authorization","Bearer "+session))
                .andExpect(status().isForbidden());
    }

    @Test void concurrentStatusChangeAndInsertCannotShiftBatchesWithinOneRequest() throws Exception {
        seedPopulation("live");
        Plan changed=population.stream().filter(p->p.state.equals("PENDING")&&p.start!=null).max(SOONEST).orElseThrow();
        Plan inserted=new Plan(id(),prefix+"-new","PENDING","live",null);
        AtomicBoolean wrote=new AtomicBoolean();
        doAnswer(call->{
            Object rows=call.callRealMethod();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            if(wrote.compareAndSet(false,true)) {
                var writer=Executors.newSingleThreadExecutor();
                try { writer.submit(()->{
                    jdbc.update("update flight_plan set status_code='COMPLETED' where plan_id=?",changed.id);
                    insert(List.of(inserted),org,district);
                }).get(10,TimeUnit.SECONDS); } finally {writer.shutdownNow();}
            }
            return rows;
        }).when(repository).listPlansInStatuses(any(),any(),anyList(),eq(true),anyInt(),anyInt());
        List<Plan> expected=ordered(population);
        JsonNode before=read("&page=21&size=100");
        assertThat(wrote).isTrue();
        assertThat(before.path("total").asInt()).isEqualTo(expected.size());
        assertThat(before.path("items").findValuesAsText("plan_id"))
                .containsExactlyElementsOf(expected.subList(2000,expected.size()).stream().map(Plan::id).toList());
        JsonNode after=read("&keyword="+inserted.number);
        assertThat(after.path("total").asInt()).isEqualTo(1);
        assertThat(after.path("items").findValuesAsText("plan_id")).containsExactly(inserted.id);
        assertThat(read("&size=100").path("total").asInt()).isEqualTo(population.size()+1);
    }

    private void seedPopulation(String mode) {
        population=new ArrayList<>();
        for(int i=0;i<2013;i++) population.add(new Plan(id(),prefix+"-pending-"+i,i%3==0?"APPROVED":"PENDING",
                i%2==0?mode:(mode.equals("live")?"replay":"live"),i==2012?null:now.plusHours(1).plusSeconds(i/3)));
        for(int i=0;i<11;i++) population.add(new Plan(id(),prefix+"-executing-"+i,"EXECUTING",mode,now.minusHours(2).plusSeconds(i/2)));
        for(int i=0;i<9;i++) population.add(new Plan(id(),prefix+"-ended-"+i,i%2==0?"COMPLETED":"CANCELLED",mode,now.minusDays(2).plusSeconds(i/2)));
        insert(population,org,district);
        String otherOrg=id(),otherDistrict=id(); organization(otherOrg); district(otherDistrict);
        String ownRoute=route;
        route=route(otherOrg,otherDistrict);
        insert(List.of(new Plan(id(),prefix+"-forbidden","EXECUTING",mode,now)),otherOrg,otherDistrict);
        route=ownRoute;
        insert(List.of(new Plan(id(),prefix+"-unscoped","EXECUTING",mode,now)),null,null);
        Plan duplicate=new Plan(id(),prefix+"-duplicate","EXECUTING",mode,now);
        insert(List.of(duplicate),org,district);
        jdbc.update("insert into flight_plan_duplicate(duplicate_plan_id,canonical_plan_id,reason) values(?,?,'test duplicate')",duplicate.id,population.get(0).id);
    }

    private void assertAllPages(String filter,List<Plan> expected,int size) throws Exception {
        List<String> collected=new ArrayList<>();
        List<Plan> ranked=ordered(expected);
        for(int page=1;page<=(expected.size()+size-1)/size+1;page++) {
            JsonNode result=read(filter+"&size="+size+"&page="+page);
            assertThat(result.path("total").asInt()).as(filter+" total page "+page).isEqualTo(expected.size());
            assertThat(result.path("attention_summary").path("aircraft").asLong()).isZero();
            assertThat(result.path("attention_summary").path("needs_action").asLong()).isZero();
            int offset=(page-1)*size;
            List<String> ids=result.path("items").findValuesAsText("plan_id");
            assertThat(ids).containsExactlyElementsOf(ranked.subList(Math.min(offset,ranked.size()),Math.min(offset+size,ranked.size()))
                    .stream().map(Plan::id).toList());
            collected.addAll(ids);
        }
        assertThat(collected).doesNotHaveDuplicates().containsExactlyElementsOf(ranked.stream().map(Plan::id).toList());
    }

    private static List<Plan> ordered(List<Plan> plans) {
        List<Plan> result=new ArrayList<>();
        result.addAll(plans.stream().filter(p->p.state.equals("EXECUTING")).sorted(NEWEST).toList());
        result.addAll(plans.stream().filter(p->List.of("PENDING","APPROVED").contains(p.state)).sorted(SOONEST).toList());
        result.addAll(plans.stream().filter(p->List.of("COMPLETED","CANCELLED").contains(p.state)).sorted(NEWEST).toList());
        return result;
    }
    @ParameterizedTest
    @ValueSource(strings={"live","replay"})
    void unlinkedDeviceCaseProjectionUsesFrozenSourceAndOpenState(String mode) {
        String sensor=id(),task=id();
        boolean simulated="replay".equals(mode);
        jdbc.update("INSERT INTO ops_device(device_id,device_no,name,device_type_code,device_type_name,channel,enabled,source_mode,simulated,version,created_at,updated_at)"
                +" VALUES(?,?,?,'RADAR','雷达','隔离测试',TRUE,?,?,0,0,0)",sensor,sensor,"设备",mode,simulated);
        maintenance.insert(new com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceRepository.Row(
                task,null,sensor,org,district,null,sensor,"设备","故障","ONLINE","BAD",1L,1L,simulated,
                "PENDING",null,"系统自动检查",1L,null,null,null,1));
        assertThat(repository.openDeviceCases(List.of(sensor))).singleElement().satisfies(row->assertThat(row.taskId()).isEqualTo(task));
        assertThat(repository.openDeviceCases(List.of(id()))).isEmpty();
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_state='READY_TO_COMPLETE' WHERE task_id=?",task);
        assertThat(repository.openDeviceCases(List.of(sensor))).singleElement().satisfies(row->assertThat(row.workflowState()).isEqualTo("READY_TO_COMPLETE"));
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_source_mode=? WHERE task_id=?","live".equals(mode)?"replay":"live",task);
        assertThat(repository.openDeviceCases(List.of(sensor))).isEmpty();
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_source_mode=?,active_key=NULL,status='HANDLED',workflow_state='COMPLETED',"
                +"handled_by=(SELECT user_id FROM app_session WHERE session_id=?),handled_at=2,handling_note='确认恢复' WHERE task_id=?",mode,session,task);
        assertThat(repository.openDeviceCases(List.of(sensor))).isEmpty();
    }

    private JsonNode read(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/flight-plans?order=attention"+query).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private void insert(List<Plan> plans,String owner,String area) {
        jdbc.batchUpdate("insert into flight_plan(plan_id,plan_no,status_code,source_mode,start_at,end_at,route_version_id,owner_org_id,district_id,created_at,updated_at,version)"
                + " values(?,?,?,?,?,?,?,?,?,current_timestamp,current_timestamp,0)",plans.stream().map(p->new Object[]{p.id,p.number,p.state,p.mode,
                    p.start==null?null:Timestamp.from(p.start.toInstant()),p.start==null?null:Timestamp.from(p.start.plusHours(1).toInstant()),route,owner,area}).toList());
    }
    private void organization(String value) {jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",value,value,value);}
    private void district(String value) {jdbc.update("insert into app_district(district_id,district_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",value,value,value);}
    private String route(String owner,String area) {
        String root=id(),version=id();
        jdbc.update("insert into route(route_id,route_no,name,source_mode,owner_org_id,district_id,created_at,updated_at,version)"
                + " values(?,?,?,'live',?,?,current_timestamp,current_timestamp,0)",root,root,"任务航线",owner,area);
        jdbc.update("insert into route_version(route_version_id,route_id,version_no,centerline,valid_from,created_at)"
                + " values(?,?,1,ST_GeomFromText('LINESTRING(118 37,118.01 37.01)',4326),?,current_timestamp)",version,root,Timestamp.from(now.minusDays(10).toInstant()));
        return version;
    }
    private static String id() {return UUID.randomUUID().toString();}
}
