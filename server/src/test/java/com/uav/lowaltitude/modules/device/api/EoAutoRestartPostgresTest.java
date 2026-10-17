package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.modules.device.application.EoDispatchService;
import com.uav.lowaltitude.modules.device.application.EoEdgeIngressService;
import com.uav.lowaltitude.modules.device.application.EoTrackingPolicy;
import com.uav.lowaltitude.modules.device.application.EoTrackingScheduler;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration;
import com.uav.lowaltitude.modules.device.infrastructure.EoDispatchRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/** Production scheduler and receipt ingress, with no transport or background jobs, in a disposable PG schema. */
@SpringBootTest(properties="app.network.allow-loopback-when-listed=true")
@ActiveProfiles("test")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
class EoAutoRestartPostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {DATABASE.springProperties(registry);}
    @AfterAll static void dropSchema() {DATABASE.close();}

    @Autowired JdbcTemplate jdbc;
    @Autowired MqttConfigurationService configuration;
    @Autowired EoEdgeRepository edges;
    @Autowired EoDispatchRepository queue;
    @Autowired MqttRepository mqtt;
    @Autowired EoEdgeIngressService ingress;
    @Autowired EoTrackingScheduler scheduler;
    @Autowired EoDispatchService dispatch;
    @MockitoSpyBean AppClock clock;
    @MockitoSpyBean EoTrackingPolicy policy;
    @MockitoSpyBean EnvironmentCredentialResolver credentials;
    private String org,district,broker,leaseOwner,actor;
    private Binding binding;
    private long now;
    private int packet;

    @BeforeEach void setup() {
        now=System.currentTimeMillis()+30_000;
        doReturn(now).when(clock).nowMillis();
        doReturn(true).when(policy).enabled();
        doReturn(id()).when(credentials).resolve("env:EO_RESTART_TEST");
        actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        AuthContext.set(new AuthUser(actor,"admin1","EO restart test","ROLE-ADMIN",1,false,"ALL"));
        org=jdbc.queryForObject("SELECT org_id FROM app_org ORDER BY org_id FETCH FIRST 1 ROW ONLY",String.class);
        district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id FETCH FIRST 1 ROW ONLY",String.class);
        leaseOwner=id();
    }

    @AfterEach void cleanup() {
        jdbc.update("UPDATE mqtt_broker SET enabled=FALSE");
        jdbc.update("UPDATE outbox_event SET processed_at=? WHERE processed_at IS NULL AND topic LIKE 'eo.%'",now);
        AuthContext.clear();
    }

    @ParameterizedTest @ValueSource(strings={"live","replay"})
    void naturalStopNeedsStrictlyNewObservationAndRepeatedPollsDoNotRestart(String mode) {
        device(mode);
        String target=target(mode,"BIRD"),first=start(target);
        advance(1_000);
        stop(first);
        assertThat(edges.task(first).get("end_command_id")).isNull();
        assertThat(queue.pending(target)).isNull();
        for(long observed:new long[]{now-1,now}) {
            observe(target,observed);
            scheduler.poll();scheduler.poll();
            assertThat(tasks(target)).isOne();
            assertThat(queue.pending(target)).isNull();
        }
        advance(1);
        observe(target,now);
        String second=start(target);
        assertThat(second).isNotEqualTo(first);
        assertThat(tasks(target)).isEqualTo(2);
        advance(1);
        stop(second);
        // The second stop is the relevant boundary; the same observation is newer only than the first stop.
        scheduler.poll();
        assertThat(tasks(target)).isEqualTo(2);
        assertThat(queue.pending(target)).isNull();
        assertThat(edges.task(first).get("status")).isEqualTo("ENDED");
    }

    @Test void queuedAutomaticRequestRechecksTheLatestEndBeforeCreatingAnyNewCommand() {
        device("replay");
        String target=target("replay","UAV"),task=start(target),request=id();
        queue.create(request,target,null,now);
        advance(1_000);
        stop(task);
        observe(target,now);
        long commandCount=commands();
        scheduler.poll();
        assertThat(tasks(target)).isOne();
        assertThat(commands()).isEqualTo(commandCount);
        assertThat(jdbc.queryForMap("SELECT status,message FROM eo_dispatch_request WHERE request_id=?",request))
                .containsEntry("status","CANCELLED").containsEntry("message","上次光电跟踪已结束，等待结束后的新有效观测再自动跟踪。");
        assertThat(queue.pending(target)).isNull();
        advance(1);
        observe(target,now);
        assertThat(start(target)).isNotEqualTo(task);
    }

    @Test void explicitManualRequestCanUseAnOtherwiseValidObservationFromBeforeTheStop() {
        device("replay");
        String target=target("replay","UAV"),task=start(target);
        advance(1_000);
        stop(task);
        dispatch.request(target,null,id());
        assertThat(queue.pending(target)).containsEntry("origin","MANUAL");
        scheduler.poll();
        var next=edges.openTaskByTarget(target);
        assertThat(next).isNotNull().containsEntry("origin","MANUAL");
        assertThat(next.get("task_id")).isNotEqualTo(task);
        assertThat(edges.command(String.valueOf(next.get("begin_command_id")))).containsEntry("requested_by",actor);
    }

    @Test void missingEndTimeDoesNotProveANewerObservationAndUnknownOrFuturePositionStillBlocks() {
        device("replay");
        String target=target("replay","BIRD"),task=start(target);
        advance(1_000);
        stop(task);
        long ended=now;
        jdbc.update("UPDATE eo_tracking_task SET ended_at=NULL WHERE task_id=?",task);
        advance(1);
        observe(target,now);
        scheduler.poll();
        assertThat(tasks(target)).isOne();
        jdbc.update("UPDATE eo_tracking_task SET ended_at=? WHERE task_id=?",ended,task);
        observe(target,now+1);
        scheduler.poll();
        assertThat(tasks(target)).isOne();
        observe(target,now);
        jdbc.update("UPDATE target_latest_state SET unknown_fields=CAST('[\"location\"]' AS JSON) WHERE target_id=?",target);
        scheduler.poll();
        assertThat(tasks(target)).isOne();
        jdbc.update("UPDATE target_latest_state SET unknown_fields=CAST('[]' AS JSON) WHERE target_id=?",target);
        assertThat(start(target)).isNotEqualTo(task);
    }

    private void device(String mode) {
        broker=configuration.create(new BrokerInput("EO restart "+id(),"127.0.0.1",1883,false,"test-user","env:EO_RESTART_TEST",
                "127.0.0.1/32",mode,org,district,null),id()).brokerId();
        configuration.enable(broker,0,true,id());
        assertThat(mqtt.claim(broker,leaseOwner,now)).isTrue();
        String device=configuration.register(new Registration(EoEdgeEnvelope.PROTOCOL,broker,null,id().substring(0,24),null,
                mode,org,district,"EO-"+id(),"EO restart fixture",null,null,null,"edge-"+id()),id());
        binding=edges.binding(device,false);
        jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',last_heartbeat_at=? WHERE device_id=?",now,device);
        jdbc.update("UPDATE eo_device_binding SET work_state=0,last_heartbeat_at=? WHERE ops_device_id=?",now,device);
    }

    private String target(String mode,String type) {
        String target=id(),alarm=id();
        jdbc.update("INSERT INTO target(target_id,target_no,object_type_code,source_mode,owner_org_id,district_id,created_at,updated_at,version) "
                +"VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",target,"EO-R-"+target.substring(0,8),type,mode,org,district);
        jdbc.update("INSERT INTO target_latest_state(target_id,location,altitude_amsl_m,speed_mps,heading_deg,observed_at,received_at,created_at,updated_at,version) "
                +"VALUES (?,ST_SetSRID(ST_MakePoint(?,?),4326),40,12,90,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",
                target,118.0+Math.random(),37.0+Math.random(),new Timestamp(now-1),new Timestamp(now-1));
        jdbc.update("INSERT INTO alarm(alarm_id,target_id,source_id,source_alarm_id,alarm_type,severity,received_at,source_mode,owner_org_id,district_id,created_at) "
                +"VALUES (?,?,?,?,'UAV_INTRUSION','HIGH',?,?,?,?,CURRENT_TIMESTAMP)",alarm,target,binding.sourceId(),alarm,new Timestamp(now-1),mode,org,district);
        jdbc.update("INSERT INTO uav_event(event_id,alarm_id,state_code,owner_org_id,district_id,created_at,updated_at,version) "
                +"VALUES (?,?,'PENDING_VERIFICATION',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",id(),alarm,org,district);
        return target;
    }

    private String start(String target) {
        scheduler.poll();
        var task=edges.openTaskByTarget(target);
        assertThat(task).isNotNull().containsEntry("status","OPEN");
        String taskId=String.valueOf(task.get("task_id")),command=String.valueOf(task.get("begin_command_id"));
        assertThat(edges.updateCommand(command,"QUEUED","SENT",now,null,null)).isOne();
        receipt("BeginTracking",taskId,1);
        assertThat(edges.command(command)).containsEntry("status","SUCCEEDED");
        return taskId;
    }

    private void stop(String task) {
        receipt("EndTracking",task,0);
        assertThat(edges.task(task)).containsEntry("status","ENDED").containsEntry("ended_at",now);
    }

    private void receipt(String event,String task,int workState) {
        byte[] payload=EoEdgeEnvelope.encode(event,binding.edgeId(),now,
                Map.of("taskId",task,"deviceId",binding.externalDeviceId(),"codeStatus",200,"workState",workState,"cameraStatus",Map.of()));
        ingress.receive(broker,leaseOwner,binding.reportingTopic(),payload,++packet,1,false,false,now);
    }

    private void observe(String target,long observed) {
        jdbc.update("UPDATE target_latest_state SET observed_at=?,received_at=? WHERE target_id=?",new Timestamp(observed),new Timestamp(now),target);
    }
    private void advance(long duration) {now+=duration;doReturn(now).when(clock).nowMillis();}
    private long tasks(String target) {return jdbc.queryForObject("SELECT COUNT(*) FROM eo_tracking_task WHERE target_id=?",Long.class,target);}
    private long commands() {return jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Long.class,binding.opsDeviceId());}
    private static String id() {return UUID.randomUUID().toString();}
}
