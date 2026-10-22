package com.uav.lowaltitude.modules.identity.api;

import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class UnitDelegationPostgresApiTest extends UnitDelegationApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { DATABASE.springProperties(registry); }
    @AfterAll static void cleanup() { DATABASE.close(); }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentConfirmationOfTwoValidPreviewsCommitsExactlyOneMigration() throws Exception {
        var first=delegation(0,"OP",true);
        var second=delegation(0,"READ",false);
        first.put("preview_token",preview(org,first).path("preview_token").asText());
        second.put("preview_token",preview(org,second).path("preview_token").asText());
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return mvc.perform(put("/api/v1/organizations/"+org+"/delegation").header("Authorization","Bearer "+platform)
                    .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                    .content(first.toString())).andReturn().getResponse().getStatus();
            });
            var b=pool.submit(()->{
                start.await(10,TimeUnit.SECONDS);
                return mvc.perform(put("/api/v1/organizations/"+org+"/delegation").header("Authorization","Bearer "+platform)
                    .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                    .content(second.toString())).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(java.util.List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT version FROM app_org_delegation WHERE org_id=?",Integer.class,org)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_role WHERE owner_org_id=?",Integer.class,org)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='unit_delegation_updated' AND object_id=?",Integer.class,org)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void administratorRevokedWhileWaitingForUnitLockCannotContinueWithMyBatisCachedAppointment() throws Exception {
        enable();
        String token=login(administrator,"UnitMembers#2026",true);
        String role=jdbc.queryForObject("SELECT role_code FROM app_user WHERE user_id=?",String.class,administrator);
        String account="waiting-"+UUID.randomUUID().toString().substring(0,8);
        var body=json.createObjectNode().put("account",account).put("name","锁后不可创建").put("org_id",org)
                .put("role_code",role).put("user_type","FRONTEND").put("data_scope","OWN_ORG")
                .put("temporary_password","WaiterUnit#2026");
        var pool=Executors.newSingleThreadExecutor();
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT org_id FROM app_org WHERE org_id=? FOR UPDATE")) {
                lock.setString(1,org); try(var ignored=lock.executeQuery()) { assertThat(ignored.next()).isTrue(); }
            }
            var pending=pool.submit(()->mvc.perform(post("/api/v1/unit-admin/users")
                    .header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn().getResponse().getStatus());
            boolean waiting=false;
            for(int attempt=0;attempt<100;attempt++) {
                Integer count=jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                      AND wait_event_type='Lock' AND query LIKE '%SELECT org_id FROM app_org WHERE org_id=%'
                    """,Integer.class);
                if(count!=null&&count>0) { waiting=true; break; }
                Thread.sleep(50);
            }
            assertThat(waiting).as("unit mutation must have read appointment and be blocked on organization lock").isTrue();
            try(var revoke=connection.prepareStatement("UPDATE app_org_delegation SET administrator_user_id=NULL,version=version+1 WHERE org_id=?")) {
                revoke.setString(1,org); assertThat(revoke.executeUpdate()).isEqualTo(1);
            }
            connection.commit();
            assertThat(pending.get(15,TimeUnit.SECONDS)).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE account=?",Integer.class,account)).isZero();
        } finally { pool.shutdownNow(); }
    }
}
