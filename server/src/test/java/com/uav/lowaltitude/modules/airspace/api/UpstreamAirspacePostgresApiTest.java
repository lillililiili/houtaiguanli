package com.uav.lowaltitude.modules.airspace.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same HTTP assertions on real PostGIS, in a newly created schema of an explicit TEST database. */
@EnabledIfEnvironmentVariable(named="AIRSPACE_TEST_DB_URL",matches="jdbc:postgresql:.*airspace_verify_.*")
class UpstreamAirspacePostgresApiTest extends UpstreamAirspaceApiTest {
    private static final String SCHEMA="airspace_push_"+UUID.randomUUID().toString().replace("-","");
    private static JdbcTemplate root;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url=System.getenv("AIRSPACE_TEST_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://[^/]+/airspace_verify_[a-zA-Z0-9_]+"))throw new IllegalStateException("Dedicated airspace_verify database required");
        String user=System.getenv("AIRSPACE_TEST_DB_USER"),password=System.getenv("AIRSPACE_TEST_DB_PASSWORD");
        root=new JdbcTemplate(new DriverManagerDataSource(url,user,password));root.execute("CREATE SCHEMA "+SCHEMA);
        p.add("spring.datasource.url",()->url+"?currentSchema="+SCHEMA+",public");
        p.add("spring.datasource.username",()->user);p.add("spring.datasource.password",()->password);
        p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        p.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.flyway.schemas",()->SCHEMA);
    }
    @AfterAll static void cleanupSchema(){if(root!=null)root.execute("DROP SCHEMA "+SCHEMA+" CASCADE");}

    @org.springframework.beans.factory.annotation.Autowired
    com.uav.lowaltitude.modules.assessment.engine.PostgisSpatialFactAdapter spatial;

    @Test void simulatedAirspaceCannotEnterDefaultLiveSpatialFacts() throws Exception {
        var result=send(input(1)).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(200);
        String id=json.readTree(result.getContentAsString()).path("data").path("airspace_id").asText();
        var now=java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        var state=new com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState("test-target",null,null,
            new java.math.BigDecimal("118.005"),new java.math.BigDecimal("37.005"),null,null,null,null,null,now,now);
        assertThat(spatial.airspaceHits(state,now)).noneMatch(hit->id.equals(hit.airspaceId()));
        assertThat(spatial.airspaceHits(state,now,"live")).noneMatch(hit->id.equals(hit.airspaceId()));
        assertThat(spatial.airspaceHits(state,now,null)).noneMatch(hit->id.equals(hit.airspaceId()));
        assertThat(spatial.airspaceHits(state,now,"mock")).anyMatch(hit->id.equals(hit.airspaceId()));
        assertThat(spatial.airspaceHits(state,now,"replay")).anyMatch(hit->id.equals(hit.airspaceId()));
        // Deliberately conflicting fixture stays in this isolated test schema; live ambiguity must ignore it too.
        jdbc.update("INSERT INTO airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) SELECT ?,airspace_id,2,kind_code,boundary,valid_from,created_at FROM airspace_version WHERE airspace_id=? AND version_no=1",UUID.randomUUID().toString(),id);
        assertThat(spatial.ambiguousEffectiveAirspaceVersion(now,"live")).isFalse();
        assertThat(spatial.ambiguousEffectiveAirspaceVersion(now,"mock")).isTrue();
    }
}
