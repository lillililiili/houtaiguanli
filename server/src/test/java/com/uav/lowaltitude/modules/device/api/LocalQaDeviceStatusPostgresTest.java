package com.uav.lowaltitude.modules.device.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql:.*stage456_verify_.*")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LocalQaDeviceStatusPostgresTest extends LocalQaDeviceStatusApiTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){DATABASE.springProperties(registry);}
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentIdenticalFactsAreAppliedOnce() throws Exception {
        var body=input();
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try{
            java.util.concurrent.Callable<com.fasterxml.jackson.databind.JsonNode> submit=()->{
                ready.countDown();
                if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Start timed out");
                return send(body,200);
            };
            var first=workers.submit(submit);var second=workers.submit(submit);
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message WHERE source=?",Long.class,
                    "local-qa-status:"+device.opsDeviceId())).isEqualTo(1);
            long version=jdbc.queryForObject("SELECT version FROM ops_device_state WHERE device_id=?",Long.class,device.opsDeviceId());
            send(body,200);
            assertThat(jdbc.queryForObject("SELECT version FROM ops_device_state WHERE device_id=?",Long.class,device.opsDeviceId())).isEqualTo(version);
        }finally{start.countDown();workers.shutdownNow();}
    }
    @AfterAll static void cleanupDatabase(){DATABASE.close();}
}
