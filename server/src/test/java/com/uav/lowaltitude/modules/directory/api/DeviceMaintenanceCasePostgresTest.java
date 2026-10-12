package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** All API cases plus PostgreSQL locking/unique constraints and actual read-only transaction enforcement. */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DeviceMaintenanceCasePostgresTest extends DeviceMaintenanceDiscoveryApiTest {
    private static final String SCHEMA="maintenance_case_"+UUID.randomUUID().toString().replace("-","");
    private static JdbcTemplate root;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Dedicated advisory_verify test database required");
        String user=System.getenv("POSTGRES_TEST_USER"),password=System.getenv("POSTGRES_TEST_PASSWORD");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));root.execute("CREATE SCHEMA "+SCHEMA);
        registry.add("spring.datasource.url",()->url+"?currentSchema="+SCHEMA+",public");
        registry.add("spring.datasource.username",()->user);registry.add("spring.datasource.password",()->password);
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
        registry.add("spring.flyway.default-schema",()->SCHEMA);registry.add("spring.flyway.schemas",()->SCHEMA);
    }
    @AfterAll static void cleanup(@Autowired ConfigurableApplicationContext context){
        context.getBeansOfType(ThreadPoolTaskScheduler.class).values().forEach(ThreadPoolTaskScheduler::shutdown);
        if(root!=null)root.execute("DROP SCHEMA "+SCHEMA+" CASCADE");
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void simultaneousMapAndPlanReportsShareOneCase() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var map=pool.submit(()->data(deviceReport(device,id())).path("task").path("task_id").asText());
            var flight=pool.submit(()->create(plan,device,id()).path("task_id").asText());
            String task=map.get(30,TimeUnit.SECONDS);
            assertThat(flight.get(30,TimeUnit.SECONDS)).isEqualTo(task);
            assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(1);
            assertThat(count("ops_maintenance_report","task_id",task)).isEqualTo(2);
            assertThat(count("ops_maintenance_plan","task_id",task)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void simultaneousPlanReportsCreateOneCaseAndTwoAssociations() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->create(plan,device,id()).path("task_id").asText());
            var second=pool.submit(()->create(secondPlan,device,id()).path("task_id").asText());
            String task=first.get(30,TimeUnit.SECONDS);
            assertThat(second.get(30,TimeUnit.SECONDS)).isEqualTo(task);
            assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(1);
            assertThat(count("ops_maintenance_plan","task_id",task)).isEqualTo(2);
            assertThat(count("ops_maintenance_report","task_id",task)).isEqualTo(2);
            assertThat(count("ops_device_maintenance_notice_attempt","task_id",task)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentConfirmationIsSerializedAndRecordsOneHumanCompletion() throws Exception {
        String task=create();healthy();auto(task);long expected=version(task);
        var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->mvc.perform(actionRequest(task,"CONFIRM_COMPLETE",expected,id())).andReturn().getResponse().getStatus());
            var second=pool.submit(()->mvc.perform(actionRequest(task,"CONFIRM_COMPLETE",expected,id())).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(workflow(task).path("state").asText()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=? AND action='CONFIRM_COMPLETE'",Long.class,task)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentStopIsRecheckedAfterDeviceLockBeforeConfirmingCompletion() throws Exception {
        useEoDevice("replay");String task=create();healthyEo();String tracking=normalAutoTracking();auto(task);
        long expected=version(task);
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try{
            var stop=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.queryForList("SELECT device_id FROM ops_device WHERE device_id=? FOR UPDATE",device);
                jdbc.queryForList("SELECT device_id FROM ops_device_state WHERE device_id=? FOR UPDATE",device);
                String command=eoCommand("EO_END_TRACK","SENT");
                jdbc.update("UPDATE eo_tracking_task SET status='ENDING',end_command_id=? WHERE task_id=?",command,tracking);
                locked.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var confirmation=pool.submit(()->mvc.perform(actionRequest(task,"CONFIRM_COMPLETE",expected,id())).andReturn().getResponse());
            release.countDown();stop.get(15,TimeUnit.SECONDS);
            var response=confirmation.get(15,TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getContentAsString()).contains("MAINTENANCE_RECOVERY_BLOCKED");
            assertThat(workflow(task).path("state").asText()).isNotEqualTo("COMPLETED");
        }finally{release.countDown();pool.shutdownNow();}
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void allRefreshEndpointsWorkInAnEnforcedReadOnlyTransaction() throws Exception {
        String task=create();healthy();long version=version(task);
        var transaction=new TransactionTemplate(transactions);transaction.setReadOnly(true);
        transaction.executeWithoutResult(status->{
            try{
                workflow(task);
                data(auth(get("/api/v1/device-maintenance-tasks").param("device_id",device),session));
                data(auth(get(reportPath(plan)).param("device_id",device),reporterSession));
                data(auth(get("/api/v1/device-maintenance-messages"),session));
            }catch(Exception error){throw new IllegalStateException(error);}
        });
        assertThat(version(task)).isEqualTo(version);
        assertThat(count("ops_maintenance_workflow_event","task_id",task)).isZero();
        assertThat(count("ops_maintenance_message_read","task_id",task)).isZero();
        assertThat(jdbc.queryForObject("SELECT recovery_snapshot FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ops_device","ops_device_state","ops_device_maintenance_task"})
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void busyDeviceOrCaseDoesNotDelayOtherRecoveryAndIsRetriedAfterUnlock(String lockedTable) throws Exception {
        String task=create(),second=sensor();stubPlan(secondPlan,second);
        String secondTask=create(secondPlan,second,id()).path("task_id").asText();
        healthy();
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',health_code='GOOD',has_alarm=FALSE,observed_at=?,received_at=?,last_heartbeat_at=?,health_observed_at=?,alarm_observed_at=? WHERE device_id=?",now,now,now,now,now,second);
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        String column=lockedTable.equals("ops_device_maintenance_task")?"task_id":"device_id";
        String lockedId=column.equals("task_id")?task:device;
        var holding=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.queryForList("SELECT "+column+" FROM "+lockedTable+" WHERE "+column+"=? FOR UPDATE",lockedId);
            locked.countDown();
            try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Test lock was not released");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
        }));
        try {
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var checked=pool.submit(()->{com.uav.lowaltitude.platform.security.AuthContext.clear();workflows.checkDeviceAutomatically(device);workflows.checkDeviceAutomatically(second);});
            checked.get(8,TimeUnit.SECONDS); // Must finish while the independent lock is still held.
            assertThat(jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=?",String.class,secondTask)).isEqualTo("READY_TO_COMPLETE");
            assertThat(jdbc.queryForObject("SELECT recovery_checked_at FROM ops_device_maintenance_task WHERE task_id=?",Long.class,task)).isNull();
            assertThat(version(task)).isEqualTo(1);
        } finally {
            release.countDown();holding.get(5,TimeUnit.SECONDS);pool.shutdownNow();
        }
        advance(5000);workflows.checkDeviceAutomatically(device);
        assertThat(jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo("READY_TO_COMPLETE");
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void discoveryRotatesPastTwoHundredRepeatedFailures() throws Exception {
        for(int i=0;i<200;i++)sensor(); // Together with this test's original sensor there are at least 201 candidates.
        var failed=cases.undispatchedDevices();assertThat(failed).hasSize(200);
        String later=cases.undispatchedDevices(failed.get(failed.size()-1)).stream().filter(id->!failed.contains(id)).findFirst().orElseThrow();
        String suffix=UUID.randomUUID().toString().replace("-","");
        String rejected="discovery_rejected_"+suffix, function="reject_discovery_"+suffix, trigger="reject_discovery_"+suffix;
        jdbc.execute("CREATE TABLE "+rejected+" (device_id VARCHAR(36) PRIMARY KEY)");
        for(String id:failed)jdbc.update("INSERT INTO "+rejected+" (device_id) VALUES (?)",id);
        jdbc.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS (SELECT 1 FROM "+rejected+" WHERE device_id=NEW.device_id) THEN RAISE EXCEPTION 'Controlled discovery persistence failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON ops_device_maintenance_task FOR EACH ROW EXECUTE FUNCTION "+function+"()");
        try {
            ReflectionTestUtils.setField(workflows,"discoveryCursor",null);
            com.uav.lowaltitude.platform.security.AuthContext.clear();workflows.runAutomaticBatch();
            assertThat(count("ops_device_maintenance_task","device_id",later)).isZero();
            for(String id:failed)assertThat(count("ops_device_maintenance_task","device_id",id)).isZero();
            workflows.runAutomaticBatch();
            assertThat(count("ops_device_maintenance_task","device_id",later)).isEqualTo(1);
            assertThat(cases.undispatchedDevices()).containsAll(failed); // Failed candidates were not deleted or hidden.
        } finally {
            jdbc.execute("DROP TRIGGER "+trigger+" ON ops_device_maintenance_task");
            jdbc.execute("DROP FUNCTION "+function+"()");
            jdbc.execute("DROP TABLE "+rejected);
        }
    }

    @Test void dueRoundRobinIncludesLaterCasesEvenWhenFirstPageCannotBeDeferred() throws Exception {
        // The cursor must progress from returned IDs, not from successful checks or changed timestamps.
        var ids=new java.util.ArrayList<String>();
        for(int i=0;i<201;i++) {
            String task=id();ids.add(task);
            jdbc.update("""
                INSERT INTO ops_device_maintenance_task(task_id,plan_id,device_id,owner_org_id,district_id,device_name,reason,
                  simulated,status,active_key,reported_by,reported_by_name,reported_at,version,workflow_version,workflow_source_mode)
                VALUES(?,?,?,?,?,'历史设备','保留的未办结上报',FALSE,'PENDING',?,?,'历史上报人',?,1,1,'live')
                """,task,plan,device,org,district,"legacy:"+task,handler,now);
        }
        var first=cases.due(now,null);assertThat(first).hasSize(200);
        var second=cases.due(now,first.get(first.size()-1));
        assertThat(second.stream().anyMatch(id->!first.contains(id))).isTrue();
        var eligible=jdbc.queryForList("SELECT task_id FROM ops_device_maintenance_task WHERE status='PENDING' AND (next_recovery_check_at IS NULL OR next_recovery_check_at<=?)",String.class,now);
        String cursor=first.get(first.size()-1);
        eligible.sort(java.util.Comparator.<String>comparingInt(id->id.compareTo(cursor)>0?0:1).thenComparing(String::compareTo));
        assertThat(second).containsExactlyElementsOf(eligible.subList(0,200));
    }

    @Test void oneDeviceRoundProcessesHundredsOfLegacyCasesWithoutMergingTheirRecords() throws Exception {
        String current=create();
        var oldTasks=new java.util.ArrayList<String>();
        for(int i=0;i<450;i++)oldTasks.add(groupedLegacyCase(now-i-1));
        String newer=groupedLegacyCase(now+2000);
        String otherSource=groupedLegacyCase(now-500);
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_source_mode='replay' WHERE task_id=?",otherSource);
        healthy();
        com.uav.lowaltitude.platform.security.AuthContext.clear();workflows.checkDeviceAutomatically(device);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task WHERE device_id=? AND workflow_state='READY_TO_COMPLETE'",Long.class,device)).isEqualTo(451);
        for(String blocked:List.of(newer,otherSource))
            assertThat(jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=?",String.class,blocked)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT recovery_reason FROM ops_device_maintenance_task WHERE task_id=?",String.class,newer)).contains("异常之后");
        assertThat(jdbc.queryForObject("SELECT recovery_reason FROM ops_device_maintenance_task WHERE task_id=?",String.class,otherSource)).contains("来源");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_device_maintenance_task WHERE device_id=?",Long.class,device)).isEqualTo(453);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event e JOIN ops_device_maintenance_task t USING(task_id) WHERE t.device_id=?",Long.class,device)).isEqualTo(453);
        var before=jdbc.queryForList("SELECT task_id,version,workflow_state FROM ops_device_maintenance_task WHERE device_id=? ORDER BY task_id",device);
        advance(5000);workflows.checkDeviceAutomatically(device);
        assertThat(jdbc.queryForList("SELECT task_id,version,workflow_state FROM ops_device_maintenance_task WHERE device_id=? ORDER BY task_id",device)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event e JOIN ops_device_maintenance_task t USING(task_id) WHERE t.device_id=?",Long.class,device)).isEqualTo(453);
        assertThat(jdbc.queryForObject("SELECT MIN(recovery_checked_at) FROM ops_device_maintenance_task WHERE device_id=?",Long.class,device)).isEqualTo(now);
        assertThat(version(current)).isEqualTo(2);
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void deviceGroupSkipsLockedCaseAndRecoversItsSiblingWithoutWaiting() throws Exception {
        String lockedCase=create(),sibling=groupedLegacyCase(now-1);healthy();
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        var holding=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            jdbc.queryForList("SELECT task_id FROM ops_device_maintenance_task WHERE task_id=? FOR UPDATE",lockedCase);
            locked.countDown();
            try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Test lock was not released");}
            catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
        }));
        try{
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            pool.submit(()->{com.uav.lowaltitude.platform.security.AuthContext.clear();workflows.checkDeviceAutomatically(device);}).get(8,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=?",String.class,sibling)).isEqualTo("READY_TO_COMPLETE");
            assertThat(jdbc.queryForObject("SELECT recovery_checked_at FROM ops_device_maintenance_task WHERE task_id=?",Long.class,lockedCase)).isNull();
            assertThat(version(lockedCase)).isEqualTo(1);
        }finally{release.countDown();holding.get(5,TimeUnit.SECONDS);pool.shutdownNow();}
        workflows.checkDeviceAutomatically(device);
        assertThat(jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=?",String.class,lockedCase)).isEqualTo("READY_TO_COMPLETE");
        assertThat(version(sibling)).isEqualTo(2);
    }

    private String groupedLegacyCase(long reportedAt){
        String task=id();
        jdbc.update("""
            INSERT INTO ops_device_maintenance_task(task_id,plan_id,device_id,owner_org_id,district_id,device_name,reason,
              simulated,status,active_key,reported_by,reported_by_name,reported_at,version,workflow_version,workflow_source_mode)
            VALUES(?,?,?,?,?,'历史设备','保留的未办结上报',FALSE,'PENDING',?,?,'历史上报人',?,1,1,'live')
            """,task,plan,device,org,district,"legacy:"+task,handler,reportedAt);
        return task;
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void appendedMigrationsPreserveLegacyCasesAndDoNotInventHealthEvidence() throws Exception {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Dedicated advisory_verify test database required");
        String schema="maintenance_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema+",public",
                System.getenv("POSTGRES_TEST_USER"),System.getenv("POSTGRES_TEST_PASSWORD"));
        root.execute("CREATE SCHEMA "+schema);
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                    .createSchemas(false).cleanDisabled(true).locations("classpath:db/migration","classpath:db/postgresql")
                    .target("202610090001").load().migrate();
            var old=new JdbcTemplate(source);
            // Copy only this test's independent prerequisite rows into the genuinely pre-upgrade schema.
            for(String[] item:new String[][]{{"app_org","org_id",org},{"app_district","district_id",district},
                    {"app_user","user_id",handler},{"route","route_id",jdbc.queryForObject("SELECT route_id FROM route_version WHERE route_version_id=?",String.class,routeVersion)},
                    {"route_version","route_version_id",routeVersion},{"flight_plan","plan_id",plan},{"ops_device","device_id",device},
                    {"ops_device_state","device_id",device}}) {
                var columns=old.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position",String.class,schema,item[0]);
                assertThat(columns).allMatch(column->column.matches("[a-z_][a-z0-9_]*"));
                String names=String.join(",",columns);
                old.update("INSERT INTO "+item[0]+" ("+names+") SELECT "+names+" FROM "+SCHEMA+"."+item[0]+" WHERE "+item[1]+"=?",item[2]);
            }
            old.update("UPDATE ops_device_state SET health_code='GOOD',has_alarm=FALSE WHERE device_id=?",device);
            String completed=id(),pending=id(),incident=id(),check=id();
            var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(old);
            for(String task:List.of(completed,pending)) {
                boolean closed=task.equals(completed);
                var values=new java.util.HashMap<String,Object>();
                values.put("id",task);values.put("plan",plan);values.put("device",device);values.put("org",org);values.put("district",district);
                values.put("actor",handler);values.put("at",now);values.put("status",closed?"HANDLED":"PENDING");
                values.put("active",closed?null:"legacy:"+task);values.put("state",closed?"COMPLETED":"PENDING_VERIFICATION");
                values.put("handled_by",closed?handler:null);values.put("handled_at",closed?now:null);
                named.update("""
                    INSERT INTO ops_device_maintenance_task(task_id,plan_id,device_id,owner_org_id,district_id,
                      device_name,reason,connectivity,health_code,observed_at,last_heartbeat_at,simulated,status,active_key,
                      reported_by,reported_by_name,reported_at,version,workflow_state,workflow_source_mode,
                      assigned_to,assigned_to_name,recovery_result,recovery_reason,recovery_checked_at,
                      handled_by,handled_by_name,handled_at,handling_note,trigger_type)
                    VALUES(:id,:plan,:device,:org,:district,'升级前设备','升级前原始异常','ONLINE','GOOD',:at,:at,FALSE,:status,:active,
                      :actor,'原上报人',:at,7,:state,'live',:actor,'原接手人','PASS','旧规则核验说明',:at,
                      :handled_by,'原办理人',:handled_at,'原始处理说明','USER')
                    """,values);
                old.update("INSERT INTO ops_maintenance_workflow_event(event_id,task_id,action,note,actor_id,actor_name,occurred_at,task_version) VALUES(?,?,'VERIFY_RECOVERY','旧办理历史',?,'原接手人',?,7)",id(),task,handler,now);
                old.update("INSERT INTO ops_maintenance_workflow_request(actor_id,request_key,task_id,request_body,response_body) VALUES(?,?,?,?,?)",handler,id(),task,"{\"action\":\"VERIFY_RECOVERY\"}","{\"historical\":true,\"result\":\"PASS\"}");
            }
            old.update("INSERT INTO ops_device_maintenance_submission(actor_id,request_key,task_id) VALUES(?,?,?)",handler,id(),pending);
            old.update("INSERT INTO device_incident(incident_id,device_id,incident_no,incident_type,severity,stage,detected_at,closed_at,reason,simulated) VALUES(?,?,?,'OTHER','LOW','RECOVERED',?,?,'旧恢复事件',FALSE)",incident,device,incident,now-1000,now);
            old.update("INSERT INTO device_recovery_check(check_id,incident_id,checked_by,checked_at,result,rule_version,state_snapshot,reason) VALUES(?,?,?,?,'PASS','historic-rule',?,'旧恢复依据')",check,incident,handler,now,"{\"health_code\":\"GOOD\",\"origin\":\"legacy\"}");
            var tasksBefore=old.queryForList("SELECT * FROM ops_device_maintenance_task ORDER BY task_id");
            var eventsBefore=old.queryForList("SELECT * FROM ops_maintenance_workflow_event ORDER BY event_id");
            var requestsBefore=old.queryForList("SELECT * FROM ops_maintenance_workflow_request ORDER BY request_key");
            var checkBefore=old.queryForMap("SELECT * FROM device_recovery_check WHERE check_id=?",check);
            var stateBefore=old.queryForMap("SELECT * FROM ops_device_state WHERE device_id=?",device);
            var migration=Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                    .createSchemas(false).cleanDisabled(true).locations("classpath:db/migration","classpath:db/postgresql").load().migrate();
            assertThat(migration.migrationsExecuted).isGreaterThanOrEqualTo(2);
            assertThat(old.queryForList("SELECT version FROM flyway_schema_history WHERE success=TRUE",String.class))
                    .contains("202610100001","202610100002");
            var tasksAfter=old.queryForList("SELECT * FROM ops_device_maintenance_task ORDER BY task_id");
            for(int i=0;i<tasksBefore.size();i++) {
                assertThat(tasksAfter.get(i)).containsAllEntriesOf(tasksBefore.get(i));
                assertThat(tasksAfter.get(i).get("workflow_version")).isEqualTo(1);
                for(String field:List.of("workflow_source_id","recovery_since_at","recovery_snapshot","recovery_fingerprint","next_recovery_check_at"))
                    assertThat(tasksAfter.get(i).get(field)).as(field).isNull();
            }
            assertThat(old.queryForList("SELECT * FROM ops_maintenance_workflow_event ORDER BY event_id")).isEqualTo(eventsBefore);
            assertThat(old.queryForList("SELECT * FROM ops_maintenance_workflow_request ORDER BY request_key")).isEqualTo(requestsBefore);
            var checkAfter=old.queryForMap("SELECT * FROM device_recovery_check WHERE check_id=?",check);
            assertThat(checkAfter).containsAllEntriesOf(checkBefore);assertThat(checkAfter.get("actor_type")).isEqualTo("USER");
            var stateAfter=old.queryForMap("SELECT * FROM ops_device_state WHERE device_id=?",device);
            assertThat(stateAfter).containsAllEntriesOf(stateBefore);
            for(String field:List.of("health_observed_at","health_source_id","health_source_mode","health_simulated",
                    "alarm_observed_at","alarm_source_id","alarm_source_mode","alarm_simulated"))assertThat(stateAfter.get(field)).as(field).isNull();
            assertThat(old.queryForObject("SELECT plan_id FROM ops_device_maintenance_submission WHERE task_id=?",String.class,pending)).isEqualTo(plan);
        } finally {
            if(!schema.matches("maintenance_upgrade_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe disposable schema");
            root.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void coldStartResumesAutomaticRecoveryFromCommittedCasesWithoutBrowserRequests() throws Exception {
        now=System.currentTimeMillis()-5000;clock.set(now);
        // Cold-start integrity checks require an explicitly configured administrator. Keep the existing
        // scoped handler fixture unchanged and create this test-only account in the disposable schema.
        String restartAdmin=id();user(restartAdmin,"ROLE-ADMIN",org,district);
        jdbc.update("UPDATE app_user SET scope_mode='ALL' WHERE user_id=?",restartAdmin);
        String task=create();
        java.nio.file.Path output=java.nio.file.Path.of("target","maintenance-case-restart",UUID.randomUUID().toString()).toAbsolutePath();
        java.nio.file.Files.createDirectories(output);
        var manifest=new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION,"1.0");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH,java.util.Arrays.stream(classpath.split(java.io.File.pathSeparator))
                .map(value->java.nio.file.Path.of(value).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" ")));
        var classpathJar=output.resolve("classpath.jar");
        try(var archive=new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(classpathJar),manifest)) { }
        String javaExe=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        long firstPid=-1;
        for(int run=0;run<2;run++) {
            if(run==1) {
                // A real fresh report arrives while the first backend is stopped; no workflow fields are changed.
                now=System.currentTimeMillis()-1000;clock.set(now);observe(false);
            }
            String expected=run==0?"PENDING":"READY_TO_COMPLETE";
            var marker=output.resolve("run-"+run+".done");var log=output.resolve("run-"+run+".log");
            var builder=new ProcessBuilder(javaExe,"-Dspring.devtools.restart.enabled=false","-Dfile.encoding=UTF-8","-cp",classpathJar.toString(),
                    DeviceMaintenanceRestartWorker.class.getName(),marker.toString(),task,expected,restartAdmin)
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("MAINTENANCE_RESTART_DB_URL",System.getenv("POSTGRES_TEST_URL")+"?currentSchema="+SCHEMA+",public");
            Process child=builder.start();
            try {
                assertThat(child.waitFor(90,TimeUnit.SECONDS)).withFailMessage("Restart worker timed out; log: %s",log).isTrue();
                assertThat(child.exitValue()).withFailMessage("Restart worker failed; log: %s",log).isZero();
                assertThat(java.nio.file.Files.exists(marker)).isTrue();
                assertThat(java.nio.file.Files.readString(marker)).startsWith(child.pid()+":"+expected+":");
                if(run==0)firstPid=child.pid();else assertThat(child.pid()).isNotEqualTo(firstPid);
            } finally {
                if(child.isAlive()){child.destroyForcibly();assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();}
            }
            assertThat(jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT assigned_to FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT recovery_result FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo("PASS");
        assertThat(jdbc.queryForObject("SELECT status FROM ops_device_maintenance_task WHERE task_id=?",String.class,task)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=? AND action='AUTO_RECOVERED' AND actor_id IS NULL",Long.class,task)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=? AND actor_id IS NOT NULL",Long.class,task)).isZero();
    }
}
