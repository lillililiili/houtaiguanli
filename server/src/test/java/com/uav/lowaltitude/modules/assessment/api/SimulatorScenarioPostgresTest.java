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
        prepareAndMeasureRepresentativeReads();
    }

    @Test @EnabledIfSystemProperty(named="qa.performance.browser", matches="true")
    void serveRepresentativePerformanceBrowser() throws Exception {
        var fixture=prepareAndMeasureRepresentativeReads();
        Path directory=Path.of("target","performance-browser").toAbsolutePath();
        Files.createDirectories(directory);
        Path stop=directory.resolve("stop"); Files.deleteIfExists(stop);
        var running=new java.util.concurrent.atomic.AtomicBoolean(true);
        var started=new java.util.concurrent.CountDownLatch(10);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(10);
        var jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
        var samples=new java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.ConcurrentLinkedQueue<Double>>();
        fixture.paths().keySet().forEach(key -> samples.put(key,new java.util.concurrent.ConcurrentLinkedQueue<>()));
        var entries=new java.util.ArrayList<>(fixture.paths().entrySet());
        refreshPerformanceMapTargets(fixture);
        long loadStarted=System.currentTimeMillis();
        try {
            for(int worker=0;worker<10;worker++) {
                final int first=worker;
                jobs.add(pool.submit(() -> {
                    started.countDown();
                    started.await();
                    int index=first;
                    while(running.get()) {
                        var entry=entries.get(index++%entries.size());
                        samples.get(entry.getKey()).add(readAndCheck(fixture,entry));
                    }
                    return null;
                }));
            }
            assertThat(started.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var manifest=new java.util.LinkedHashMap<String,Object>();
            manifest.put("port",port); manifest.put("org_id",fixture.org());
            manifest.put("target_id",fixture.target()); manifest.put("synthetic_fixture",true);
            manifest.put("database","isolated PostgreSQL random schema");
            manifest.put("historical_targets",10000); manifest.put("current_targets",100);
            manifest.put("concurrency",10); manifest.put("api_p95_limit_ms",2000);
            manifest.put("page_interactive_limit_ms",5000);
            manifest.put("page_measurement_status","browser_owner_must_measure");
            manifest.put("load_started_at",loadStarted); manifest.put("stage","READ_LOAD_RUNNING");
            manifest.put("map_observation_interval_ms",1000); manifest.put("synthetic_observation_delivery_delay_ms",2000);
            manifest.put("map_coordinate_system","WGS84"); manifest.put("map_grid","10 x 10 distinct positions, longitude 118.602..118.638, latitude 37.442..37.478");
            manifest.put("map_api_sample",directory.resolve("map-sample.json").toString());
            manifest.put("stop_file",stop.toString()); manifest.put("paths",fixture.paths());
            manifest.put("baseline",fixture.baseline());
            writePerformanceMapSample(fixture,directory.resolve("map-sample.json"));
            Files.writeString(directory.resolve("manifest.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            long deadline=System.nanoTime()+Duration.ofMinutes(20).toNanos();
            long nextObservation=0,nextMapSample=0;
            while(!Files.exists(stop)&&System.nanoTime()<deadline) {
                for(var job:jobs) if(job.isDone()) job.get();
                long now=System.nanoTime();
                if(now>=nextObservation) {
                    refreshPerformanceMapTargets(fixture);
                    nextObservation=now+Duration.ofSeconds(1).toNanos();
                }
                if(now>=nextMapSample) {
                    writePerformanceMapSample(fixture,directory.resolve("map-sample.json"));
                    nextMapSample=now+Duration.ofSeconds(5).toNanos();
                }
                Thread.sleep(250);
            }
            assertThat(Files.exists(stop)).as("Browser owner must explicitly finish page and map timing").isTrue();
            running.set(false);
            for(var job:jobs) job.get(30,java.util.concurrent.TimeUnit.SECONDS);
            var results=new java.util.LinkedHashMap<String,Object>();
            for(var entry:samples.entrySet()) results.put(entry.getKey(),performanceSummary(entry.getValue().stream().sorted().toList()));
            manifest.put("stage","STOPPED"); manifest.put("load_finished_at",System.currentTimeMillis());
            manifest.put("during_browser_load",results);
            Files.writeString(directory.resolve("final.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            for(var entry:results.entrySet()) assertThat(((Number)((Map<?,?>)entry.getValue()).get("p95_ms")).doubleValue())
                    .as("%s P95 during ten concurrent browser-background readers",entry.getKey()).isLessThanOrEqualTo(2000d);
        } finally {
            running.set(false);
            pool.shutdownNow();
        }
    }

    private PerformanceFixture prepareAndMeasureRepresentativeReads() throws Exception {
        String org=java.util.UUID.randomUUID().toString();
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,0,0,0)",org,org,"隔离性能样本");
        // Initial observations use the same two-second delivery lag as periodic refreshes.
        // Otherwise an immediate refresh could put last_seen_at before first_seen_at.
        var at=java.time.OffsetDateTime.now().minusSeconds(2);
        jdbc.update("""
                insert into target(target_id,target_no,object_type_code,first_seen_at,last_seen_at,source_mode,owner_org_id,district_id,created_at,updated_at)
                select md5(? || ':' || n)::uuid::text,? || '-' || n,'UAV',
                  case when n<=100 then ?::timestamptz else ?::timestamptz end,
                  case when n<=100 then ?::timestamptz else ?::timestamptz end,
                  'replay',?,'seed-stage3-district',?::timestamptz,?::timestamptz from generate_series(1,10100) n
                """,org,org,at,at.minusDays(1),at,at.minusDays(1),org,at,at);
        jdbc.update("""
                insert into target_latest_state(target_id,location,observed_at,received_at,created_at,updated_at)
                select target_id,ST_SetSRID(ST_MakePoint(118.602+((grid_index-1)%10)*0.004,
                  37.442+((grid_index-1)/10)*0.004),4326),last_seen_at,last_seen_at,created_at,updated_at
                from (select target_id,last_seen_at,created_at,updated_at,row_number() over(order by target_id) grid_index
                  from target where owner_org_id=? and last_seen_at>=?) current_targets
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
        var paths=Map.of("list","/api/v1/targets?"+scope,"filter","/api/v1/targets?"+scope+"&seen_from="+at.minusSeconds(10).toInstant().toEpochMilli()
                +"&seen_to="+at.plusMinutes(25).toInstant().toEpochMilli(),"detail","/api/v1/targets/"+id);
        var result=new java.util.LinkedHashMap<String,Object>();
        var fixture=new PerformanceFixture(org,id,origin,token,client,paths,result);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(10);
        try {
            for(var entry:paths.entrySet()) {
                var times=new java.util.concurrent.CopyOnWriteArrayList<Double>();
                var jobs=new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
                for(int i=0;i<50;i++) jobs.add(() -> {
                    times.add(readAndCheck(fixture,entry));
                    return null;
                });
                for(var job:pool.invokeAll(jobs)) job.get();
                var sorted=times.stream().sorted().toList();
                result.put(entry.getKey(),performanceSummary(sorted));
            }
        } finally { pool.shutdownNow(); }
        result.put("concurrency",10); result.put("historical_targets",10000); result.put("current_targets",100);
        result.put("api_p95_limit_ms",2000); result.put("threshold_status","user_confirmed_2026_09_30"); result.put("synthetic_fixture",true);
        Path directory=Path.of("target","representative-performance"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("results.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        for(String key:paths.keySet()) assertThat(((Number)((Map<?,?>)result.get(key)).get("p95_ms")).doubleValue())
                .as("%s P95 at ten concurrent readers",key).isLessThanOrEqualTo(2000d);
        return fixture;
    }

    private void refreshPerformanceMapTargets(PerformanceFixture fixture) {
        var received=java.time.OffsetDateTime.now();
        // Explicit synthetic delivery delay keeps observations behind the UI's frozen seen_to request boundary.
        // This remains inside the real 15-second map window; no production expiry rule is changed.
        var observed=received.minusSeconds(2);
        assertThat(jdbc.update("""
                update target t set last_seen_at=?,updated_at=? from target_latest_state s
                where t.target_id=s.target_id and t.owner_org_id=?
                """,observed,received,fixture.org())).isEqualTo(100);
        assertThat(jdbc.update("""
                update target_latest_state s set observed_at=?,received_at=?,updated_at=? from target t
                where t.target_id=s.target_id and t.owner_org_id=?
                """,observed,received,received,fixture.org())).isEqualTo(100);
    }

    private void writePerformanceMapSample(PerformanceFixture fixture,Path path) throws Exception {
        var response=fixture.client().send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(fixture.origin()+fixture.paths().get("filter")))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+fixture.token()).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var data=json.readTree(response.body()).path("data");
        assertThat(data.path("total").asInt()).isEqualTo(100);
        assertThat(data.path("items")).hasSize(100);
        long now=System.currentTimeMillis();
        var positions=new java.util.HashSet<String>();
        for(var target:data.path("items")) {
            var state=target.path("latest_state"); var location=state.path("location");
            assertThat(location.path("coordinate_system").asText()).isEqualTo("WGS84");
            assertThat(location.path("longitude").asDouble()).isBetween(118.602,118.638000001);
            assertThat(location.path("latitude").asDouble()).isBetween(37.442,37.478000001);
            assertThat(state.path("observed_at").asLong()).isLessThanOrEqualTo(now);
            assertThat(target.path("map_expires_at").asLong()).isGreaterThan(now);
            positions.add(location.path("longitude").asText()+","+location.path("latitude").asText());
        }
        assertThat(positions).hasSize(100);
        Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "recorded_at",now,"synthetic_fixture",true,"api_visible_current_targets",100,
                "distinct_wgs84_positions",positions.size(),"browser_rendering_verified",false,"response",data)));
    }

    private double readAndCheck(PerformanceFixture fixture,Map.Entry<String,String> entry) throws Exception {
        long start=System.nanoTime();
        var response=fixture.client().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(fixture.origin()+entry.getValue()))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+fixture.token()).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        double millis=(System.nanoTime()-start)/1_000_000d;
        assertThat(response.statusCode()).as("%s status",entry.getKey()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.path("ok").asBoolean()).isTrue();
        var data=body.path("data");
        if(!"detail".equals(entry.getKey())) {
            assertThat(data.path("total").asInt()).isEqualTo("list".equals(entry.getKey())?10100:100);
            assertThat(data.path("items")).hasSize(100);
        } else assertThat(data.path("target_id").asText()).isEqualTo(fixture.target());
        return millis;
    }

    private Map<String,Object> performanceSummary(List<Double> sorted) {
        assertThat(sorted).isNotEmpty();
        return Map.of("requests",sorted.size(),"p95_ms",sorted.get((int)Math.ceil(sorted.size()*0.95)-1),
                "max_ms",sorted.get(sorted.size()-1),"all_rows_checked",true);
    }

    private record PerformanceFixture(String org,String target,String origin,String token,
            java.net.http.HttpClient client,Map<String,String> paths,Map<String,Object> baseline) { }
}
