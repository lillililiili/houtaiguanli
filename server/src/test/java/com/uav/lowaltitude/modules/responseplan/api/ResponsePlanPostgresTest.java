package com.uav.lowaltitude.modules.responseplan.api;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/response_plan_verify_[a-z0-9_]+")
class ResponsePlanPostgresTest extends ResponsePlanApiTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/response_plan_verify_[a-z0-9_]+"))throw new IllegalArgumentException("仅允许隔离预案测试库");
        r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));r.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentBindingCreatesOnlyOneCurrentAssociation()throws Exception{
        var v=publish(create());String id=v.path("version_id").asText();var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<Integer> call=()->{ready.countDown();start.await();return mvc.perform(write(put("/api/v1/response-plans/airspaces/"+airspace),Map.of("version_id",id,"reason","并发关联测试"))).andReturn().getResponse().getStatus();};
        try{var a=pool.submit(call);var b=pool.submit(call);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM airspace_response_plan_binding WHERE airspace_id=? AND ended_at IS NULL",Long.class,airspace)).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();}
    }
}
