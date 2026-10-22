package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import com.uav.lowaltitude.modules.assessment.engine.PostgisSpatialFactAdapter;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceReadRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;

@EnabledIfEnvironmentVariable(named="AIRSPACE_TEST_DB_URL",matches="jdbc:postgresql:.*airspace_verify_.*")
class LocalAirspacePostgresApiTest extends LocalAirspaceApiTest {
    static final String SCHEMA="local_airspace_"+UUID.randomUUID().toString().replace("-","");
    static JdbcTemplate root;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("AIRSPACE_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/airspace_verify_[a-zA-Z0-9_]+")) throw new IllegalStateException("Dedicated airspace_verify database required");
        String user=System.getenv("AIRSPACE_TEST_DB_USER"),password=System.getenv("AIRSPACE_TEST_DB_PASSWORD");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));root.execute("CREATE SCHEMA "+SCHEMA);
        p.add("spring.datasource.url",()->url+"?currentSchema="+SCHEMA+",public");p.add("spring.datasource.username",()->user);p.add("spring.datasource.password",()->password);
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");p.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.flyway.schemas",()->SCHEMA);
    }
    @AfterAll static void cleanup(){if(root!=null)root.execute("DROP SCHEMA "+SCHEMA+" CASCADE");}
    @Autowired PostgisSpatialFactAdapter spatial;
    @Autowired AirspaceReadRepository reads;

    @Test void deletionCancelsScheduledFutureAndPreservesHistoricalSpatialFacts() throws Exception {
        String id=createId();
        OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
        TargetState point=new TargetState("t-"+UUID.randomUUID(),null,null,new BigDecimal("118.01"),new BigDecimal("37.01"),null,null,null,null,null,now,now);
        assertThat(spatial.airspaceHits(point,now)).anyMatch(h->id.equals(h.airspaceId()));
        var scheduled=update().put("valid_from",from+86400000);
        putBody(id,scheduled,UUID.randomUUID().toString()).andExpect(status().isOk());
        OffsetDateTime future=now.plusDays(2);
        assertThat(spatial.airspaceHits(point,future)).anyMatch(h->id.equals(h.airspaceId()));
        deleteId(id,1).andExpect(status().isOk());
        OffsetDateTime deleted=jdbc.queryForObject("SELECT deleted_at FROM airspace WHERE airspace_id=?",OffsetDateTime.class,id);
        assertThat(spatial.airspaceHits(point,deleted)).noneMatch(h->id.equals(h.airspaceId()));
        assertThat(spatial.airspaceHits(point,future)).noneMatch(h->id.equals(h.airspaceId()));
        assertThat(spatial.airspaceHits(point,now)).anyMatch(h->id.equals(h.airspaceId()));
        assertThat(reads.effectiveVersions(id,future,new AccessDecision(user,ScopeMode.ASSIGNED))).isEmpty();
        assertThat(reads.effectiveVersions(id,now,new AccessDecision(user,ScopeMode.ASSIGNED))).hasSize(1);
        mvc.perform(get("/api/v1/airspaces").param("owner_org_id",org).param("valid_at",Long.toString(now.toInstant().toEpochMilli())).header("Authorization","Bearer "+session)).andExpect(jsonPath("$.data.total").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM airspace_version WHERE airspace_id=?",Long.class,id)).isEqualTo(2);
    }
    @Test void topologyFailureIsAValidationErrorAndDoesNotWrite() throws Exception {
        var n=input(); n.set("boundary",json.readTree("{\"type\":\"Polygon\",\"coordinates\":[[[118,37],[118.02,37.02],[118.02,37],[118,37.02],[118,37]]]}"));
        create(n).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_GEOJSON"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM airspace WHERE airspace_no=?",Long.class,n.path("airspace_no").asText())).isZero();
    }
    @Test
    @org.springframework.transaction.annotation.Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentEditorsCannotBothAdvanceTheSameHead() throws Exception {
        String id=createId(); var n=update();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try {
            java.util.concurrent.Callable<Integer> attempt=()->{barrier.await();return putBody(id,n,UUID.randomUUID().toString()).andReturn().getResponse().getStatus();};
            var first=pool.submit(attempt);var second=pool.submit(attempt);
            assertThat(java.util.List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM airspace_version WHERE airspace_id=?",Long.class,id)).isEqualTo(2);
        } finally {pool.shutdownNow();}
    }
}
