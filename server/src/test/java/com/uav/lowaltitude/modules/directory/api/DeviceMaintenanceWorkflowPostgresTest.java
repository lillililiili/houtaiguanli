package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/maintenance_flow_verify_[a-z0-9_]+")
class DeviceMaintenanceWorkflowPostgresTest extends DeviceMaintenanceWorkflowApiTest {
    @Autowired PlatformTransactionManager transactions;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/maintenance_flow_verify_[a-z0-9_]+"))throw new IllegalArgumentException("只允许隔离运维流程测试库");
        registry.add("spring.datasource.url",()->url);
        registry.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void parallelStartAndReadReceiptAreSerialized() throws Exception {
        create();String target=taskId;String route=path();String actorSession=session;
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->mvc.perform(write(post(route),Map.of("action","START","expected_version",1),UUID.randomUUID().toString())).andReturn().getResponse().getStatus());
            var b=pool.submit(()->mvc.perform(write(post(route),Map.of("action","START","expected_version",1),UUID.randomUUID().toString())).andReturn().getResponse().getStatus());
            assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
            var c=pool.submit(()->mvc.perform(post("/api/v1/device-maintenance-messages/"+target+"/read").header("Authorization","Bearer "+actorSession)).andReturn().getResponse().getStatus());
            var d=pool.submit(()->mvc.perform(post("/api/v1/device-maintenance-messages/"+target+"/read").header("Authorization","Bearer "+actorSession)).andReturn().getResponse().getStatus());
            assertThat(java.util.List.of(c.get(),d.get())).containsExactly(200,200);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_workflow_event WHERE task_id=?",Long.class,target)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_message_read WHERE task_id=?",Long.class,target)).isEqualTo(1);
        }finally{
            pool.shutdownNow();
            jdbc.update("DELETE FROM ops_maintenance_workflow_request WHERE task_id=?",target);
            jdbc.update("DELETE FROM ops_maintenance_workflow_event WHERE task_id=?",target);
            jdbc.update("DELETE FROM ops_maintenance_message_read WHERE task_id=?",target);
        }
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void inboxGetWorksInsideReadOnlyTransactionWithoutCreatingReceipts() throws Exception {
        create();long before=jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_message_read",Long.class);
        TransactionTemplate readOnly=new TransactionTemplate(transactions);readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status->{try{data(auth(get("/api/v1/device-maintenance-messages")));}catch(Exception error){throw new RuntimeException(error);}});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_maintenance_message_read",Long.class)).isEqualTo(before);
    }
    @Test void upgradingOldHandledRowsDoesNotInventRecovery() throws Exception {
        String schema="maintenance_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=jdbc.getDataSource().getConnection();var sql=connection.createStatement()){
            sql.execute("CREATE SCHEMA "+schema);connection.setSchema(schema);
            try{
                sql.execute("CREATE TABLE app_user(user_id VARCHAR(36) PRIMARY KEY)");
                sql.execute("CREATE TABLE ops_device(device_id VARCHAR(36) PRIMARY KEY,source_mode VARCHAR(16))");
                sql.execute("CREATE TABLE commission_task(commission_id VARCHAR(36) PRIMARY KEY)");
                sql.execute("CREATE TABLE ops_device_maintenance_task(task_id VARCHAR(36) PRIMARY KEY,device_id VARCHAR(36),status VARCHAR(16),reported_at BIGINT,handling_note TEXT)");
                sql.execute("INSERT INTO ops_device VALUES('device','live')");
                sql.execute("INSERT INTO ops_device_maintenance_task VALUES('old','device','HANDLED',1234,'原处理反馈')");
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V202609270002__device_maintenance_workflow.sql"));
                try(var rows=sql.executeQuery("SELECT workflow_state,handling_note,recovery_result,recovery_checked_at FROM ops_device_maintenance_task")){
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("LEGACY_HANDLED");assertThat(rows.getString(2)).isEqualTo("原处理反馈");assertThat(rows.getObject(3)).isNull();assertThat(rows.getObject(4)).isNull();
                }
            }finally{connection.setSchema("public");sql.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
