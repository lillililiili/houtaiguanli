package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.*;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;

/** R16/R18: actual wall time and real PostGIS presence; only the contact channels are local simulations. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test","postgres-test"})
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
class NotificationPresenceTimingPostgresTest {
    static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        DATABASE.springProperties(p);AutoVoiceApiTest.recording(p);
        p.add("app.advisory.auto-sms.enabled",()->true);p.add("app.advisory.auto-voice.enabled",()->true);
        p.add("app.notifications.transport",()->"mock");
    }
    @AfterAll static void close(){DATABASE.close();}
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AutoSmsService sms;
    @Autowired AutoVoiceService voice;
    @Autowired PilotDepartureWatch departure;
    @Autowired com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository advisory;
    @Autowired ConfigurableEnvironment env;
    String event,target,track,session,plan;
    int sequence;
    final List<Long> observations=new ArrayList<>();
    static final String ORG="seed-stage3-org",DISTRICT="seed-stage3-district";
    @AfterEach void clearScenario(){env.getPropertySources().remove("notification-timing-scenario");}

    @Test void unverifiedPilotAloneSkipsEnabledNotificationsWithCurrentSufficientCounterEvidence() throws Exception {
        fixture();observe();
        // Synthetic business prerequisite only; actual production qualification and notification services are exercised below.
        com.uav.lowaltitude.modules.disposal.api.CounterEvidenceFixture.seed(jdbc,event);
        var original=jdbc.queryForMap("select evaluation_id,evaluated_at from rule_evaluation where target_id=? order by evaluated_at desc,evaluation_id desc fetch first 1 rows only",target);
        jdbc.update("insert into rule_evaluation(evaluation_id,run_id,rule_set_version_id,mode,subject_kind,target_id,plan_id,observed_at,as_of,evaluated_at,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons,supersedes_evaluation_id) "
                +"select ?,run_id,rule_set_version_id,mode,subject_kind,target_id,?,observed_at,as_of,?,freshness_code,plan_match_code,legal_status,violation_reasons,hit_details,unknown_reasons,evidence_references,input_snapshot,alarm_id,owner_org_id,district_id,source_mode,created_at,decision_assurance_code,decision_algorithm_version,decision_assurance_reasons,evaluation_id from rule_evaluation where evaluation_id=?",
                id(),plan,ts(Math.max(System.currentTimeMillis(),((Timestamp)original.get("evaluated_at")).getTime()+1)),original.get("evaluation_id"));
        var before=overview();
        assertThat(before.path("auto_sms").path("enabled").asBoolean()).isTrue();
        assertThat(before.path("auto_voice").path("enabled").asBoolean()).isTrue();
        assertThat(before.path("auto_sms").path("recipient_snapshot").path("configured").asBoolean()).isTrue();
        assertThat(before.path("auto_sms").path("status").asText()).isEqualTo("WAITING");
        assertThat(advisory.counterBlockReason(event)).isEmpty();
        // Keep the same plan, enabled contact, phone, scope, current observation and assessment; only verification is missing.
        jdbc.update("update business_contact set verified_at=null where contact_id=(select pilot_contact_id from flight_plan where plan_id=?)",plan);
        observe();sms.process(event);voice.process(event);sms.process(event);voice.process(event);
        var after=overview();
        assertThat(after.path("auto_sms").path("enabled").asBoolean()).isTrue();
        assertThat(after.path("auto_voice").path("enabled").asBoolean()).isTrue();
        assertThat(after.path("auto_sms").path("status").asText()).isEqualTo("BLOCKED");
        assertThat(after.path("auto_sms").path("recipient_snapshot").path("configured").asBoolean()).isFalse();
        assertThat(after.path("auto_sms").path("reason").asText()).contains("尚未有效核验");
        assertThat(after.path("records").size()).isZero();
        assertThat(after.path("notify_phase").asText()).isEqualTo("AWAIT_COUNTER");
        String blockReason=advisory.counterBlockReason(event);assertThat(blockReason).isEmpty();
        assertThat(jdbc.queryForObject("select attempt_count from uav_auto_sms_task where event_id=?",Integer.class,event)).isZero();
        assertThat(jdbc.queryForObject("select attempt_count from uav_auto_voice_task where event_id=?",Integer.class,event)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from uav_event_advisory where event_id=?",Integer.class,event)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from uav_event_voice_advisory where event_id=?",Integer.class,event)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_setting where setting_id in ('advisory-sms','advisory-voice') and enabled=true",Integer.class)).isEqualTo(2);
        var evaluation=jdbc.queryForMap("select evaluation_id,alarm_id,target_id,plan_id,observed_at,evaluated_at,legal_status,decision_assurance_code,decision_algorithm_version from rule_evaluation where target_id=? order by evaluated_at desc,evaluation_id desc fetch first 1 rows only",target);
        assertThat(evaluation.get("legal_status")).isEqualTo("ILLEGAL");assertThat(evaluation.get("decision_assurance_code")).isEqualTo("SUFFICIENT");
        var evidence=new LinkedHashMap<String,Object>();evidence.put("row","R40");evidence.put("event_id",event);
        evidence.put("fixture_boundary","隔离合成当前SUFFICIENT/ILLEGAL研判前置；真实资格/目录/通知服务及API，非引擎生成结论验收");
        evidence.put("only_changed_precondition","关联执行飞手verified_at=null；通知设置与自动短信/电话保持开启");
        evidence.put("before",before);evidence.put("after",after);evidence.put("current_evaluation",evaluation);
        evidence.put("counterBlockReason",blockReason);evidence.put("sms_attempt_count",0);evidence.put("voice_attempt_count",0);
        Path output=Path.of("target","qa-unblock","r40-unverified-pilot.json");Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    @Test void effectiveInAreaObservationsGateRealThreeAndTenSecondWindows() throws Exception {
        assertThat(departure).isInstanceOf(PostgisPilotDepartureWatch.class);
        fixture();observe();sms.process(event);
        long delivered=jdbc.queryForObject("select updated_at from uav_auto_sms_task where event_id=? and status='SIMULATED_DELIVERED'",Long.class,event);
        observeUntil(delivered+2500);
        long beforeSms=System.currentTimeMillis();voice.process(event);
        assertThat(beforeSms-delivered).isLessThan(3000);
        assertThat(jdbc.queryForObject("select attempt_count from uav_auto_voice_task where event_id=?",Integer.class,event)).isZero();
        assertThat(phase()).isEqualTo("WATCHING");
        observeUntil(delivered+3000);observe();
        assertThat(departure.assess(event,delivered,System.currentTimeMillis())).isEqualTo(PilotDepartureWatch.Presence.STILL_PRESENT);
        env.getPropertySources().addFirst(new MapPropertySource("notification-timing-scenario",Map.of(
                "app.qa.advisory-scenario.enabled",true,"app.qa.advisory-scenario.event-id",event,
                "app.qa.advisory-scenario.voice-status","SIMULATED_PLAYED","app.qa.advisory-scenario.delay-ms",1500)));
        voice.process(event);
        var call=jdbc.queryForMap("select triggered_at,answered_at,playback_completed_at from uav_auto_voice_task where event_id=? and status='SIMULATED_PLAYED'",event);
        long started=((Number)call.get("triggered_at")).longValue(),played=((Number)call.get("playback_completed_at")).longValue();
        assertThat(started-delivered).isGreaterThanOrEqualTo(3000);
        assertThat(played-started).isGreaterThanOrEqualTo(1500);
        observeUntil(played+9000);observe();
        long nineCheck=System.currentTimeMillis();
        assertThat(nineCheck-started).isGreaterThanOrEqualTo(10000);
        assertThat(nineCheck-played).isBetween(9000L,9999L);
        assertThat(phase()).isEqualTo("WATCHING"); // Already >10s after dialing, but not after playback.
        assertThat(departure.assess(event,played,nineCheck)).isEqualTo(PilotDepartureWatch.Presence.STILL_PRESENT);
        observeUntil(played+10000);observe();
        long tenCheck=System.currentTimeMillis();
        assertThat(phase()).isEqualTo("AWAIT_COUNTER");
        assertThat(departure.assess(event,played,tenCheck)).isEqualTo(PilotDepartureWatch.Presence.STILL_PRESENT);
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?",String.class,event)).isEqualTo("CONFIRMED");
        Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("acceptance_rows",List.of("R16","R18"));evidence.put("event_id",event);
        evidence.put("channel","local simulated SMS/voice; no external channel acceptance");
        evidence.put("clock","actual wall time; no mocked clock or presence conclusion");
        evidence.put("sms_delivered_at",delivered);evidence.put("pre_sms_check_at",beforeSms);
        evidence.put("voice_triggered_at",started);evidence.put("answered_at",call.get("answered_at"));evidence.put("playback_completed_at",played);
        evidence.put("sms_scheduler_delay_ms",started-delivered-3000);evidence.put("nine_second_check_at",nineCheck);
        evidence.put("ten_second_check_at",tenCheck);evidence.put("ten_second_check_delay_ms",tenCheck-played-10000);
        evidence.put("observed_at",observations);evidence.put("presence","PostGIS STILL_PRESENT at all decision checkpoints");
        Path output=Path.of("target","qa-unblock","notification-presence-timing.json");Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    void fixture() {
        long now=System.currentTimeMillis();event=id();target=id();track=id();session=id();String alarm=id(),area=id();
        jdbc.update("insert into app_session(session_id,user_id,expire_at,ip,permission_version) select ?,user_id,?,'127.0.0.1',permission_version from app_user where account='admin1'",session,now+3600000);
        jdbc.update("insert into target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,'UAV','mock',?,?,?,?)",target,"QA-TIMING-"+target,ORG,DISTRICT,ts(now),ts(now));
        jdbc.update("insert into target_latest_state(target_id,location,observed_at,received_at,created_at,updated_at,unknown_fields) values(?,ST_SetSRID(ST_MakePoint(118.5,37.5),4326),?,?,?,?,CAST('[]' AS JSON))",target,ts(now),ts(now),ts(now),ts(now));
        jdbc.update("insert into track(track_id,target_id,link_id,external_track_id,layer,started_at,created_at) values(?,?,null,?,'FUSED',?,?)",track,target,track,ts(now),ts(now));
        jdbc.update("insert into alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) values(?,?,'seed-stage3-source',?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",alarm,target,alarm,ts(now),ts(now),ORG,DISTRICT,ts(now));
        jdbc.update("insert into uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) values(?,?,'CONFIRMED',?,?,?,?,0)",event,alarm,ORG,DISTRICT,ts(now),ts(now));
        jdbc.update("insert into airspace(airspace_id,airspace_no,name,source_id,source_mode,owner_org_id,district_id,created_at,updated_at) values(?,?,'QA current presence','seed-stage3-source','mock',?,?,?,?)",area,"QA-"+area,ORG,DISTRICT,ts(now),ts(now));
        jdbc.update("insert into airspace_version(airspace_version_id,airspace_id,version_no,kind_code,boundary,valid_from,created_at) values(?,?,1,'RESTRICTED',ST_Multi(ST_GeomFromText('POLYGON((118 37,119 37,119 38,118 38,118 37))',4326)),?,?)",id(),area,ts(now-60000),ts(now));
        plan=DirectoryAdvisoryFixture.create(jdbc,ORG,DISTRICT);
        DirectoryAdvisoryFixture.evaluation(jdbc,event,target,plan,ORG,DISTRICT,Instant.now().minusSeconds(1));
    }
    void observe() {
        long now=System.currentTimeMillis();observations.add(now);
        jdbc.update("insert into track_point(point_id,track_id,point_seq,observed_at,received_at,location,created_at,point_kind,position_accuracy_m) values(?,?,?,?,?,ST_SetSRID(ST_MakePoint(118.5,37.5),4326),?,'MEAS',5)",id(),track,++sequence,ts(now),ts(now),ts(now));
        jdbc.update("update target_latest_state set observed_at=?,received_at=?,updated_at=? where target_id=?",ts(now),ts(now),ts(now),target);
    }
    void observeUntil(long deadline) throws InterruptedException {
        while(System.currentTimeMillis()<deadline){observe();Thread.sleep(Math.min(200,Math.max(1,deadline-System.currentTimeMillis())));}
    }
    String phase() throws Exception {
        return overview().path("notify_phase").asText();
    }
    com.fasterxml.jackson.databind.JsonNode overview() throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/uav-events/"+event+"/advisory").header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    static String id(){return UUID.randomUUID().toString();}
    static Timestamp ts(long millis){return new Timestamp(millis);}
}
