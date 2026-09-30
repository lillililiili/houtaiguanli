package com.uav.lowaltitude.modules.assessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mock.LocalStage7RuleEngineSeeder;
import com.uav.lowaltitude.integration.mock.RuleReplayRunner;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** Real PostGIS calculations over explicitly synthetic replay facts. No external device or notification writes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@ActiveProfiles({"test", "postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class SimulatorScenarioPostgresTest {
    static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @Autowired JdbcTemplate jdbc;
    @Autowired RuleReplayRunner replay;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.dev-seed.password", () -> "changeme");
        registry.add("app.rule-engine.allow-demo-active", () -> true);
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }

    @Test void matchingPlanRouteAndAreaProduceTheExpectedReplayConclusions() throws Exception {
        replay.replay();
        Map<String,String> expected = Map.of("legal", "LEGAL", "deviation", "ABNORMAL", "airspace-limit", "ILLEGAL",
                "no-plan", "ILLEGAL", "datum-mismatch", "UNDETERMINED");
        for (var entry : expected.entrySet()) {
            String target = LocalStage7RuleEngineSeeder.targetId(entry.getKey());
            assertThat(jdbc.queryForList("select legal_status from rule_evaluation where target_id=?", String.class, target))
                    .isNotEmpty().containsOnly(entry.getValue());
        }
        assertThat(jdbc.queryForObject("select count(*) from rule_evaluation e join target t on t.target_id=e.target_id where t.object_type_code<>'UAV'", Integer.class)).isZero();
        Path directory = Path.of("target", "simulator-scenario-browser").toAbsolutePath(); Files.createDirectories(directory);
        var scenarios = jdbc.queryForList("""
                select t.target_id,t.target_no,t.object_type_code,e.legal_status,e.violation_reasons,e.unknown_reasons,
                  e.plan_id,e.route_version_id,ST_X(s.location) as longitude,ST_Y(s.location) as latitude,
                  s.height_agl_m,s.altitude_amsl_m,e.observed_at,e.as_of
                from target t join rule_evaluation e on e.target_id=t.target_id
                join target_latest_state s on s.target_id=t.target_id
                where t.owner_org_id=? order by t.target_no
                """, LocalStage7RuleEngineSeeder.ORG);
        Files.writeString(directory.resolve("scenario-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(scenarios));
    }

    @Test @EnabledIfSystemProperty(named="qa.simulator.scenarios.browser", matches="true")
    void serveReplayAndBrowserCapabilities() throws Exception {
        matchingPlanRouteAndAreaProduceTheExpectedReplayConclusions();
        jdbc.update("""
                insert into app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,must_change_password,permission_version,created_at,updated_at,version)
                select 'qa-first-unblock','qa-first-unblock','隔离首次改密测试','ROLE-ADMIN','ACTIVE',password_hash,0,'ALL',true,0,0,0,0
                from app_user where account='admin1'
                """);
        Path directory=Path.of("target", "simulator-scenario-browser").toAbsolutePath();
        Path stop=directory.resolve("stop"); Files.deleteIfExists(stop);
        Files.writeString(directory.resolve("manifest.json"),json.writeValueAsString(Map.of("port",port,"synthetic_fixture",true,
                "org_id",LocalStage7RuleEngineSeeder.ORG,"first_password_account","qa-first-unblock",
                "scenarios",List.of("legal","deviation","airspace-limit","no-plan","datum-mismatch"))));
        long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
        while(!Files.exists(stop) && System.nanoTime()<deadline) Thread.sleep(500);
        assertThat(Files.exists(stop)).as("Browser fixture must be explicitly ended after review").isTrue();
        Files.writeString(directory.resolve("final.json"),json.writeValueAsString(Map.of("first_password_changed",
                !jdbc.queryForObject("select must_change_password from app_user where user_id='qa-first-unblock'",Boolean.class),
                "evaluations",jdbc.queryForObject("select count(*) from rule_evaluation",Integer.class))));
        Files.deleteIfExists(stop);
    }

    @Test void measureRepresentativeReadsWithoutDroppingRows() throws Exception {
        String org=java.util.UUID.randomUUID().toString();
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,"隔离性能样本");
        var at=java.time.OffsetDateTime.now();
        jdbc.update("""
                insert into target(target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at)
                select md5(? || ':' || n)::uuid::text,? || '-' || n,'UAV',
                  case when n<=100 then ?::timestamptz else ?::timestamptz end,
                  case when n<=100 then ?::timestamptz else ?::timestamptz end,
                  'replay',?,'seed-stage3-district',?::timestamptz,?::timestamptz from generate_series(1,10100) n
                """,org,org,at,at.minusDays(1),at,at.minusDays(1),org,at,at);
        jdbc.update("""
                insert into target_latest_state(target_id,location,observed_at,received_at,created_at,updated_at)
                select target_id,ST_SetSRID(ST_MakePoint(118.02,37.02),4326),last_seen_at,last_seen_at,created_at,updated_at
                from target where owner_org_id=? and last_seen_at>=?
                """,org,at.minusSeconds(1));
        var client=java.net.http.HttpClient.newHttpClient();
        String origin="http://127.0.0.1:"+port;
        var login=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/v1/auth/login"))
                .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"account\":\"admin1\",\"password\":\"changeme\"}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String token=json.readTree(login.body()).path("data").path("session_id").asText();
        String id=jdbc.queryForObject("select target_id from target where owner_org_id=? order by last_seen_at desc limit 1",String.class,org);
        String scope="owner_org_id="+org+"&size=100";
        var paths=Map.of("list","/api/v1/targets?"+scope,"filter","/api/v1/targets?"+scope+"&seen_from="+at.minusSeconds(1).toInstant().toEpochMilli()
                +"&seen_to="+at.plusSeconds(1).toInstant().toEpochMilli(),"detail","/api/v1/targets/"+id);
        var result=new java.util.LinkedHashMap<String,Object>();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(10);
        try {
            for(var entry:paths.entrySet()) {
                var times=new java.util.concurrent.CopyOnWriteArrayList<Double>();
                var jobs=new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
                for(int i=0;i<50;i++) jobs.add(() -> {
                    long start=System.nanoTime();
                    var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+entry.getValue()))
                            .header("Authorization","Bearer "+token).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
                    times.add((System.nanoTime()-start)/1_000_000d);
                    assertThat(response.statusCode()).as("%s response: %s",entry.getKey(),response.statusCode()==200?"ok":response.body()).isEqualTo(200);
                    var data=json.readTree(response.body()).path("data");
                    if(!"detail".equals(entry.getKey())) {
                        assertThat(data.path("total").asInt()).isEqualTo("list".equals(entry.getKey())?10100:100);
                        assertThat(data.path("items")).hasSize(100);
                    } else assertThat(data.path("target_id").asText()).isEqualTo(id);
                    return null;
                });
                for(var job:pool.invokeAll(jobs)) job.get();
                var sorted=times.stream().sorted().toList();
                result.put(entry.getKey(),Map.of("requests",50,"p95_ms",sorted.get(47),"max_ms",sorted.get(49),"all_rows_checked",true));
            }
        } finally { pool.shutdownNow(); }
        result.put("concurrency",10); result.put("historical_targets",10000); result.put("current_targets",100);
        result.put("threshold_status","measurement_only_pending_user_acceptance"); result.put("synthetic_fixture",true);
        Path directory=Path.of("target","representative-performance"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
