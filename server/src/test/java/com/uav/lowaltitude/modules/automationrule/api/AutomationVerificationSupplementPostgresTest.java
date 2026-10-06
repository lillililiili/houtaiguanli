package com.uav.lowaltitude.modules.automationrule.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AlarmRuleVerification;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeService;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeWorker;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.platform.time.AppClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** R04/R07/R43 use committed synthetic observations, actual rule APIs, evaluator and verification worker. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({DeviceMonitoringPostgresFixture.NoScheduledJobs.class,AutomationVerificationSupplementPostgresTest.ClockConfiguration.class})
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class AutomationVerificationSupplementPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    private static final String BASE = "/api/v1/automation-rule-groups/verify";
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.automation-rules.enabled",()->true);
        registry.add("app.automation-rules.fact-max-age-ms",()->30000);
    }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
    @TestConfiguration static class ClockConfiguration {
        @Bean @Primary VerificationClock verificationClock() { return new VerificationClock(); }
    }
    static class VerificationClock extends AppClock {
        long now = System.currentTimeMillis();
        @Override public Instant now() { return Instant.ofEpochMilli(now); }
        @Override public long nowMillis() { return now; }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired VerificationClock clock;
    @Autowired AutomationRuntimeWorker worker;
    @Autowired AutomationRuntimeService decisions;
    @Autowired AutomationRuntimeRepository runs;
    @Autowired AlarmRuleVerification verification;
    String session;

    @BeforeEach void prepare() throws Exception {
        clock.now = System.currentTimeMillis();
        session = UUID.randomUUID().toString();
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) select ?,user_id,?,'127.0.0.1',permission_version from app_user where account='admin1'",session,clock.now+3600000);
        // Clean only disposable test configuration through its audited API, preserving prior history.
        JsonNode group = read(BASE);
        List<String> ids = new ArrayList<>();
        for (JsonNode rule : group.path("rules")) ids.add(rule.path("rule_id").asText());
        for (String id : ids) group = write(delete(BASE+"/rules/"+id),Map.of("expected_version",group.path("version").asLong()));
        var settings = new LinkedHashMap<String,Object>();
        settings.put("scope_mode","ALL"); settings.put("airspace_ids",List.of()); settings.put("schedule_mode","ALL_DAY");
        settings.put("start_time","08:00"); settings.put("end_time","20:00"); settings.put("timezone","Asia/Shanghai");
        settings.put("insufficient_wait_seconds",15); settings.put("actions",List.of()); settings.put("expected_version",group.path("version").asLong());
        write(put(BASE+"/settings"),settings);
        assertThat(jdbc.queryForObject("select count(*) from automation_rule_condition where category in ('counter','dispose') and enabled=true",Integer.class)).isZero();
    }

    @Test void r04ZeroVerificationRulesPauseAutomationButAuthorizedManualApiStillWritesHistory() throws Exception {
        JsonNode configured=read(BASE);
        assertThat(configured.path("rules").size()).isZero();
        Map<String,JsonNode> otherCategories=new LinkedHashMap<>();
        for(String category:List.of("counter","dispose"))otherCategories.put(category,read("/api/v1/automation-rule-groups/"+category));
        Fixture fixture=event("0.95",clock.now);
        worker.poll();worker.poll();
        assertThat(runs.heartbeat()).isEqualTo(clock.now);assertThat(runs.lastError()).isNull();
        JsonNode connected=read(BASE);
        assertThat(connected.path("execution_status").asText()).isEqualTo("CONNECTED");
        assertThat(connected.path("version")).isEqualTo(configured.path("version"));
        assertThat(connected.path("rules").size()).isZero();
        var paused=runs.state("verify",fixture.event());
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.version()).isEqualTo(configured.path("version").asLong());
        assertThat(json.readTree(runs.run(paused.runId()).conditions()).size()).isZero();
        assertUnverified(fixture.event());
        JsonNode runtimeHistory=read(BASE+"/runs?size=100");
        assertThat(runtimeHistory.path("items").findValuesAsText("run_id")).contains(paused.runId());
        // 人工核实为属实要有本次告警的合法性研判和仍在有效时长内的目标数据。
        com.uav.lowaltitude.modules.disposal.api.CounterEvidenceFixture.seed(jdbc,fixture.event(),Instant.ofEpochMilli(clock.now));
        JsonNode pending=read("/api/v1/uav-events/"+fixture.event());
        assertThat(pending.path("allowed_actions").toString()).contains("VERIFY");
        assertThat(pending.path("verification_basis").path("confirmable").asBoolean()).isTrue();
        JsonNode confirmed=write(post("/api/v1/uav-events/"+fixture.event()+"/verifications"),
                Map.of("conclusion","CONFIRMED","note","R04 authorized manual verification with automatic verification paused","expected_version",pending.path("version").asLong()));
        assertThat(confirmed.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(historyCount(fixture.event())).isEqualTo(1);
        var history=jdbc.queryForMap("select actor_id,automation_run_id,conclusion from uav_event_verification where event_id=?",fixture.event());
        assertThat(history.get("actor_id")).isEqualTo(jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class));
        assertThat(history.get("automation_run_id")).isNull();
        assertThat(history.get("conclusion")).isEqualTo("CONFIRMED");
        worker.poll();assertThat(historyCount(fixture.event())).isEqualTo(1);
        for(var entry:otherCategories.entrySet()) {
            JsonNode after=read("/api/v1/automation-rule-groups/"+entry.getKey());
            for(String field:List.of("version","rules","settings"))assertThat(after.path(field)).isEqualTo(entry.getValue().path(field));
        }
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?",Integer.class,fixture.event())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?",Integer.class,fixture.event())).isZero();
        save("r04",Map.of("synthetic_fixture",true,"configuration",connected,"runtime_history",runtimeHistory,
                "paused_run_id",paused.runId(),"pending_event",pending,"manual_api_result",confirmed,
                "verification_history",read("/api/v1/uav-events/"+fixture.event()+"/verifications"),"other_category_configuration",otherCategories));
    }

    @Test void r07TwoEnabledConditionsPersistSeparateFactsAndOnlyNewSustainedObservationsTriggerOnce() throws Exception {
        addRule("QA confidence","confidence","90",3);
        JsonNode configured = addRule("QA freshness","freshness","5",0);
        long version = configured.path("version").asLong();
        long start = clock.now;
        Fixture fixture = event("0.80",start);
        List<Object> observations = new ArrayList<>();
        worker.poll();
        observations.add(assertRun(fixture,"NOT_MATCHED",version,"FAIL","PASS"));
        assertUnverified(fixture.event());

        clock.now = start+1000; observation(fixture,"0.95",clock.now);
        worker.poll();
        observations.add(assertRun(fixture,"WAITING",version,"WAITING","PASS"));
        assertUnverified(fixture.event());
        // Scheduler time alone cannot accumulate the required three seconds of observation evidence.
        clock.now = start+3999; worker.poll();
        observations.add(assertRun(fixture,"WAITING",version,"WAITING","PASS"));
        assertUnverified(fixture.event());
        observation(fixture,"0.95",clock.now); worker.poll();
        observations.add(assertRun(fixture,"WAITING",version,"WAITING","PASS"));
        assertUnverified(fixture.event());
        clock.now = start+4000; observation(fixture,"0.95",clock.now); worker.poll();
        observations.add(assertRun(fixture,"PASS",version,"PASS","PASS"));
        assertThat(read("/api/v1/uav-events/"+fixture.event()).path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(historyCount(fixture.event())).isEqualTo(1);
        var history = jdbc.queryForMap("select created_at,automation_run_id,actor_id from uav_event_verification where event_id=?",fixture.event());
        assertThat(((Timestamp)history.get("created_at")).getTime()).isEqualTo(start+4000);
        assertThat(history.get("actor_id")).isNull();
        assertThat(history.get("automation_run_id")).isEqualTo(runs.state("verify",fixture.event()).runId());
        worker.poll(); worker.poll();
        assertThat(historyCount(fixture.event())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_id=?",Integer.class,fixture.event())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from handoff where event_id=?",Integer.class,fixture.event())).isZero();
        save("r07",Map.of("synthetic_fixture",true,"event_id",fixture.event(),"target_id",fixture.target(),
                "configuration",configured,"observations_and_decisions",observations,"verification_history",read("/api/v1/uav-events/"+fixture.event()+"/verifications")));
    }

    @Test void r43DisableBeforeVerificationBlocksOldQueuedPassAndNewBatchesAtTheSavedVersion() throws Exception {
        JsonNode enabled = addRule("QA confidence","confidence","90",0);
        long oldVersion = enabled.path("version").asLong();
        String rule = enabled.path("rules").get(0).path("rule_id").asText();
        Fixture before = event("0.95",clock.now);
        decisions.evaluate("verify",before.event());
        String oldRun = runs.state("verify",before.event()).runId();
        assertThat(runs.state("verify",before.event()).status()).isEqualTo("PASS");
        assertUnverified(before.event());
        clock.now += 1;
        long savedAt = clock.now;
        JsonNode disabled = write(patch(BASE+"/rules/"+rule+"/enabled"),Map.of("enabled",false,"expected_version",oldVersion));
        long currentVersion = disabled.path("version").asLong();
        assertThat(currentVersion).isEqualTo(oldVersion+1);
        assertThat(read(BASE).path("version").asLong()).isEqualTo(currentVersion);
        assertThat(read(BASE).path("rules").get(0).path("enabled").asBoolean()).isFalse();
        // Exercise the stale worker invocation directly after API commit; no fabricated verdict is supplied.
        verification.confirmIfPassed(before.event(),oldRun);
        assertUnverified(before.event());
        clock.now += 1;
        Fixture after = event("0.95",clock.now);
        worker.poll(); worker.poll();
        for (Fixture fixture : List.of(before,after)) {
            assertUnverified(fixture.event());
            assertThat(runs.state("verify",fixture.event()).status()).isEqualTo("PAUSED");
            assertThat(runs.state("verify",fixture.event()).version()).isEqualTo(currentVersion);
        }
        assertThat(jdbc.queryForObject("select created_at from automation_rule_change where category='verify' and version=?",Long.class,currentVersion)).isEqualTo(savedAt);
        save("r43",Map.of("synthetic_fixture",true,"enabled_configuration",enabled,"disabled_configuration",disabled,
                "configuration_saved_at",savedAt,"new_batch_created_at",clock.now,"old_run_id",oldRun,
                "before_event_id",before.event(),"after_event_id",after.event(),
                "before_history",read("/api/v1/uav-events/"+before.event()+"/verifications"),
                "after_history",read("/api/v1/uav-events/"+after.event()+"/verifications")));
    }

    private JsonNode addRule(String name,String item,String value,int hold) throws Exception {
        return write(post(BASE+"/rules"),Map.of("name",name,"item_code",item,"value",value,"hold_seconds",hold,
                "enabled",true,"expected_version",read(BASE).path("version").asLong()));
    }
    private Map<String,Object> assertRun(Fixture fixture,String status,long version,String confidence,String freshness) throws Exception {
        var state = runs.state("verify",fixture.event());
        assertThat(state.status()).isEqualTo(status); assertThat(state.version()).isEqualTo(version);
        var run = runs.run(state.runId());
        assertThat(run.at()).isEqualTo(clock.now); assertThat(run.targetId()).isEqualTo(fixture.target());
        var conditions = json.readTree(run.conditions());
        assertThat(conditions.size()).isEqualTo(2);
        var results = new LinkedHashMap<String,String>();
        for (JsonNode condition : conditions) results.put(condition.path("name").asText(),condition.path("result").asText());
        assertThat(results).containsEntry("QA confidence",confidence).containsEntry("QA freshness",freshness);
        var facts = json.readTree(jdbc.queryForObject("select CAST(facts_json AS VARCHAR) from automation_runtime_run where run_id=?",String.class,state.runId()));
        assertThat(facts.toString()).contains(fixture.target());
        return Map.of("run_id",state.runId(),"group_version",version,"evaluated_at",run.at(),"observed_at",run.observed(),
                "status",status,"conditions",conditions,"persisted_facts",facts);
    }
    private void assertUnverified(String event) throws Exception {
        assertThat(read("/api/v1/uav-events/"+event).path("state").asText()).isEqualTo("PENDING_VERIFICATION");
        assertThat(historyCount(event)).isZero();
    }
    private int historyCount(String event) { return jdbc.queryForObject("select count(*) from uav_event_verification where event_id=?",Integer.class,event); }
    private record Fixture(String event,String target) { }
    private Fixture event(String confidence,long observed) {
        String target=UUID.randomUUID().toString(),alarm=UUID.randomUUID().toString(),event=UUID.randomUUID().toString();
        Timestamp at=Timestamp.from(Instant.ofEpochMilli(observed));
        jdbc.update("insert into target(target_id,target_no,first_seen_at,last_seen_at,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,?,?,'UAV','mock','seed-stage3-org','seed-stage3-district',?,?)",target,"QA-VERIFY-"+target,at,at,at,at);
        jdbc.update("insert into target_latest_state(target_id,observed_at,received_at,classification_confidence,created_at,updated_at) values(?,?,?,?,?,?)",target,at,at,new BigDecimal(confidence),at,at);
        jdbc.update("insert into alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values(?,?,'seed-stage3-source',?,'UAV_INTRUSION','HIGH',?,?,'mock','seed-stage3-org','seed-stage3-district',?)",alarm,target,alarm,at,at,at);
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'PENDING_VERIFICATION','seed-stage3-org','seed-stage3-district',?,?,0)",event,alarm,at,at);
        return new Fixture(event,target);
    }
    private void observation(Fixture fixture,String confidence,long at) {
        Timestamp timestamp=Timestamp.from(Instant.ofEpochMilli(at));
        jdbc.update("update target_latest_state set observed_at=?,received_at=?,classification_confidence=?,updated_at=? where target_id=?",timestamp,timestamp,new BigDecimal(confidence),timestamp,fixture.target());
    }
    private JsonNode read(String path) throws Exception { return json.readTree(mvc.perform(get(path).header("Authorization","Bearer "+session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data"); }
    private JsonNode write(MockHttpServletRequestBuilder request,Object body) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization","Bearer "+session).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private void save(String name,Object value) throws Exception {
        Path output=Path.of("target","supplemental-verification");Files.createDirectories(output);
        Files.writeString(output.resolve(name+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
