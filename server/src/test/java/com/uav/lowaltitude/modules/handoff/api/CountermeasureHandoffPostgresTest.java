package com.uav.lowaltitude.modules.handoff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import com.uav.lowaltitude.modules.disposal.application.PunishmentHandoffJob;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;

/** Administrative handoff only: synthetic stopped-device facts, no device dispatch or external delivery. */
@SpringBootTest(properties={"app.dev-seed.enabled=false", "app.outbox.enabled=false"})
@ActiveProfiles("test") @Transactional
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+")
class CountermeasureHandoffPostgresTest {
    private static final String SCHEMA = "counter_handoff_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate root;
    @Autowired JdbcTemplate jdbc;
    @Autowired DisposalRepository disposals;
    @Autowired HandoffSubmissionService handoffs;
    @Autowired PunishmentHandoffJob compensation;
    @SpyBean AutomationRuntimePolicy policy;
    @MockBean HandoffChannelPort channel;
    String org, district, actor, source, event, authorization, device, command;
    long now;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("POSTGRES_TEST_URL");
        if (url == null || !url.matches("jdbc:postgresql://[^/]+/advisory_verify_[a-z0-9_]+"))
            throw new IllegalStateException("Dedicated test database required");
        String user = System.getenv("POSTGRES_TEST_USER"), password = System.getenv("POSTGRES_TEST_PASSWORD");
        root = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        root.execute("CREATE SCHEMA " + SCHEMA);
        p.add("spring.datasource.url", () -> url + "?currentSchema=" + SCHEMA + ",public");
        p.add("spring.datasource.username", () -> user); p.add("spring.datasource.password", () -> password);
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
        p.add("spring.flyway.default-schema", () -> SCHEMA); p.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @AfterAll static void cleanup() {
        if (root != null) root.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @BeforeEach void fixture() {
        now = System.currentTimeMillis();
        org=id(); district=id(); actor=id(); source=id(); event=id(); authorization=id(); device=id(); command=id();
        String role="ROLE-HO-"+id().substring(0,8), alarm=id();
        Timestamp at=new Timestamp(now);
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",org,org,org);
        jdbc.update("INSERT INTO app_district(district_id,district_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,TRUE,0,0,0)",district,district,district);
        jdbc.update("INSERT INTO app_role(role_code,name,description,builtin,enabled,created_at,updated_at,version,system_role) VALUES(?,?,'',FALSE,TRUE,0,0,0,FALSE)",role,role);
        jdbc.update("INSERT INTO app_user(user_id,account,name,role_code,status,password_hash,fail_count,scope_mode,permission_version,created_at,updated_at,version) VALUES(?,?,?,?,'ACTIVE','unused',0,'NONE',0,0,0,0)",actor,actor,"移送隔离夹具",role);
        jdbc.update("INSERT INTO integration_source(source_id,source_code,name,enabled,source_mode,created_at,updated_at,version) VALUES(?,?,?,TRUE,'mock',?,?,0)",source,source,source,at,at);
        jdbc.update("INSERT INTO alarm(alarm_id,source_id,source_alarm_id,alarm_type,severity,occurred_at,received_at,source_mode,owner_org_id,district_id,created_at) VALUES(?,?,?,'UAV_INTRUSION','HIGH',?,?,'mock',?,?,?)",alarm,source,alarm,at,at,org,district,at);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) VALUES(?,?,'CONFIRMED',?,?,?,?,1)",event,alarm,org,district,at,at);
        jdbc.update("INSERT INTO uav_event_verification(history_id,event_id,version,previous_state,resulting_state,conclusion,note,actor_id,created_at) VALUES(?,?,1,'PENDING_VERIFICATION','CONFIRMED','CONFIRMED','测试夹具',?,?)",id(),event,actor,at);
        jdbc.update("UPDATE handoff_recipient SET enabled=FALSE WHERE handoff_type='UAV_PUNISHMENT'");
        String recipient=id();
        jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) VALUES(?,?,'UAV_PUNISHMENT',TRUE,?,?)",recipient,recipient,at,at);
        jdbc.update("INSERT INTO ops_device(device_id,device_no,name,device_type_name,channel,source_mode,simulated,created_at,updated_at) VALUES(?,?,?,'反制','测试','mock',TRUE,?,?)",device,device,device,now,now);
        jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,device_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,version,created_at,updated_at) VALUES(?,?,'COUNTERMEASURE','UAV_EVENT',?,?,'COUNTERMEASURE_4CH','隔离完成事实',?,?,'COMPLETED','demo-v1',?,?,'mock',0,?,?)",authorization,authorization.replace("-",""),event,device,actor,at,org,district,at,at);
        doReturn(true).when(policy).enabled(); // Freeze into WAITING_RULES; never send an external notice.
    }

    @ParameterizedTest @ValueSource(strings={"mock","replay","live"})
    void confirmedStopCreatesOneHandoffAndPreservesFrozenMaterials(String mode) {
        jdbc.update("UPDATE disposal_authorization SET source_mode=? WHERE authorization_id=?",mode,authorization);
        jdbc.update("UPDATE alarm SET source_mode=? WHERE alarm_id=(SELECT alarm_id FROM uav_event WHERE event_id=?)",mode,event);
        stopped();
        assertThat(disposals.completedWithoutPunishment()).contains(event);
        assertThat(disposals.punishmentCompletion(event).requestedBy()).isEqualTo(actor);
        assertThat(disposals.punishmentCompletion(event).triggerSource()).isEqualTo("COUNTERMEASURE_COMPLETED");
        compensation.sweep();
        String handoff=jdbc.queryForObject("SELECT handoff_id FROM handoff WHERE event_id=?",String.class,event);
        String snapshot=jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=?",String.class,handoff);
        assertThat(jdbc.queryForObject("SELECT trigger_source FROM handoff WHERE handoff_id=?",String.class,handoff)).isEqualTo("COUNTERMEASURE_COMPLETED");
        assertThat(jdbc.queryForObject("SELECT source_mode FROM handoff WHERE handoff_id=?",String.class,handoff)).isEqualTo(mode);
        assertThat(jdbc.queryForObject("SELECT submitted_by FROM handoff WHERE handoff_id=?",String.class,handoff)).isEqualTo(actor);
        handoffs.automaticAfterDisposal(event); compensation.sweep();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM handoff WHERE event_id=?",Integer.class,event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM handoff_delivery WHERE handoff_id=?",Integer.class,handoff)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=?",String.class,handoff)).isEqualTo(snapshot);
        assertThat(disposals.completedWithoutPunishment()).doesNotContain(event);
        verifyNoInteractions(channel);
    }

    @ParameterizedTest @ValueSource(strings={"no_run","start_success","stop_failed","stop_timeout","no_completion_time","wrong_authorization","wrong_device","wrong_current_command","executing","lingyun_start","old_child"})
    void incompleteOrUnrelatedStopFactsDoNotCreateHandoff(String defect) {
        stopped();
        switch (defect) {
            case "no_run" -> jdbc.update("DELETE FROM disposal_device_run WHERE authorization_id=?",authorization);
            case "start_success" -> jdbc.update("UPDATE countermeasure_4ch_command SET mask=13 WHERE command_id=?",command);
            case "stop_failed" -> jdbc.update("UPDATE device_command SET status='FAILED' WHERE command_id=?",command);
            case "stop_timeout" -> jdbc.update("UPDATE device_command SET status='TIMED_OUT' WHERE command_id=?",command);
            case "no_completion_time" -> jdbc.update("UPDATE device_command SET completed_at=NULL WHERE command_id=?",command);
            case "wrong_authorization" -> jdbc.update("UPDATE countermeasure_4ch_command SET authorization_id=? WHERE command_id=?",id(),command);
            case "wrong_device" -> jdbc.update("UPDATE disposal_device_run SET device_id=? WHERE authorization_id=?",id(),authorization);
            case "wrong_current_command" -> jdbc.update("UPDATE disposal_authorization SET execution_command_id=? WHERE authorization_id=?",id(),authorization);
            case "executing" -> jdbc.update("UPDATE disposal_authorization SET status='EXECUTING' WHERE authorization_id=?",authorization);
            case "lingyun_start" -> jdbc.update("UPDATE disposal_authorization SET channel='LINGYUN_B' WHERE authorization_id=?",authorization);
            case "old_child" -> child("EXECUTING");
            default -> throw new IllegalArgumentException(defect);
        }
        assertThat(disposals.punishmentCompletion(event)).isNull();
        assertThat(disposals.completedWithoutPunishment()).doesNotContain(event);
        handoffs.automaticAfterDisposal(event); compensation.sweep();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM handoff WHERE event_id=?",Integer.class,event)).isZero();
        verifyNoInteractions(channel);
    }

    @Test void oldCompletedJammingKeepsOriginalTriggerAndSnapshot() {
        child("COMPLETED");
        assertThat(disposals.punishmentCompletion(event).triggerSource()).isEqualTo("JAMMING_COMPLETED");
        handoffs.automaticAfterJamming(event);
        String old=jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=(SELECT handoff_id FROM handoff WHERE event_id=?)",String.class,event);
        handoffs.automaticAfterDisposal(event);
        assertThat(jdbc.queryForObject("SELECT trigger_source FROM handoff WHERE event_id=?",String.class,event)).isEqualTo("JAMMING_COMPLETED");
        assertThat(jdbc.queryForObject("SELECT CAST(snapshot AS VARCHAR) FROM handoff_material_snapshot WHERE handoff_id=(SELECT handoff_id FROM handoff WHERE event_id=?)",String.class,event)).isEqualTo(old);
        verifyNoInteractions(channel);
    }

    @Test void multipleRecipientsKeepManualChoiceAndCreateNoAutomaticRecord() {
        stopped(); String recipient=id();
        jdbc.update("INSERT INTO handoff_recipient(recipient_id,display_name,handoff_type,enabled,created_at,updated_at) VALUES(?,?,'UAV_PUNISHMENT',TRUE,current_timestamp,current_timestamp)",recipient,recipient);
        handoffs.automaticAfterDisposal(event);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM handoff WHERE event_id=?",Integer.class,event)).isZero();
        verifyNoInteractions(channel);
    }

    private void stopped() {
        jdbc.update("INSERT INTO device_command(command_id,command_no,device_id,requested_by,command_type,reason,status,source_mode,simulated,completed_at,created_at,updated_at) VALUES(?,?,?,?,'COUNTERMEASURE_4CH_SET','隔离测试完成事实','SUCCEEDED','mock',TRUE,?,?,?)",command,command,device,actor,now,now,now);
        jdbc.update("INSERT INTO countermeasure_4ch_command(command_id,action,mask,authorization_id) VALUES(?,'SET_MASK',0,?)",command,authorization);
        jdbc.update("UPDATE disposal_authorization SET execution_command_id=? WHERE authorization_id=?",command,authorization);
        jdbc.update("INSERT INTO disposal_device_run(authorization_id,device_id,on_command_id,on_at,off_due_at,off_command_id,off_attempts,created_at) VALUES(?,?,?,?,?,?,1,?)",authorization,device,id(),new Timestamp(now-100000),new Timestamp(now-1000),command,new Timestamp(now-100000));
    }

    private void child(String status) {
        String id=id();
        jdbc.update("INSERT INTO disposal_authorization(authorization_id,authorization_no,action_type,subject_kind,subject_id,device_id,channel,reason,requested_by,requested_at,status,policy_version,owner_org_id,district_id,source_mode,chained_from_authorization_id,version,created_at,updated_at) SELECT ?,?,'JAMMING',subject_kind,subject_id,device_id,channel,reason,requested_by,requested_at,?,policy_version,owner_org_id,district_id,source_mode,authorization_id,0,created_at,updated_at FROM disposal_authorization WHERE authorization_id=?",id,id.replace("-",""),status,authorization);
    }
    private static String id() { return UUID.randomUUID().toString(); }
}
