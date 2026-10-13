package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import io.moquette.broker.Server;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.integration.mqtt.*;
import com.uav.lowaltitude.modules.device.application.*;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.modules.device.infrastructure.*;
import com.uav.lowaltitude.modules.fusion.application.FusionPipeline;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.security.*;
import com.uav.lowaltitude.platform.time.AppClock;

/** Actual loopback MQTT -> ingress -> inbox -> production fusion -> PostgreSQL; no hardware claims. */
@SpringBootTest
@ActiveProfiles({"test", "postgres-test"})
@Import({DeviceMonitoringPostgresFixture.NoScheduledJobs.class,EoFusionChainPostgresTest.ControlledTime.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
class EoFusionChainPostgresTest {
    private static final java.util.concurrent.atomic.AtomicLong TEST_NOW = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ControlledTime {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        AppClock controlledClock() {
            return new AppClock(new java.time.Clock() {
                public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
                public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
                public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(TEST_NOW.get()); }
            });
        }
    }
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) { DATABASE.springProperties(p); }
    @AfterAll static void closeDatabase() { DATABASE.close(); }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AppClock clock;
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttRepository mqtt;
    @Autowired EoEdgeRepository edges;
    @Autowired MqttIngressService sensingIngress;
    @Autowired EoEdgeIngressService eoIngress;
    @Autowired MqttNetworkPolicy network;
    @Autowired EnvironmentCredentialResolver credentials;
    @Autowired FusionPipeline fusion;
    @Autowired TargetReadService reads;
    @Autowired TargetVideoService videos;
    @Autowired EoFusionObservationSummaryService eoSummary;
    @Autowired com.uav.lowaltitude.modules.fusion.application.EoFusionTaskContextPort taskContexts;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    Server broker;
    MqttClient publisher;
    MqttSessionSupervisor supervisor;
    Binding radar, tdoa;
    com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding eo;
    String org, district, brokerId, target, task;
    long base;

    @BeforeEach void prepare() throws Exception {
        TEST_NOW.set(System.currentTimeMillis());
        String user = jdbc.queryForObject("select user_id from app_user where account='admin1'", String.class);
        AuthContext.set(new AuthUser(user,"admin1","EO fusion QA","ROLE-ADMIN",1,false,"ALL"));
        org = id(); district = "seed-stage3-district";
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,?,?,0)",
                org,"EO-"+org,"EO fusion isolated",clock.nowMillis(),clock.nowMillis());
        int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        Properties settings = new Properties();
        settings.setProperty("host","127.0.0.1"); settings.setProperty("port",String.valueOf(port));
        settings.setProperty("allow_anonymous","true"); settings.setProperty("persistence_enabled","false");
        settings.setProperty("data_path",temporary.toString()); settings.setProperty("telemetry_enabled","false");
        broker = new Server(); broker.startServer(settings);
        brokerId = configuration.create(new BrokerInput("EO fusion QA","127.0.0.1",port,false,null,null,"127.0.0.1/32","replay",org,district,null),id()).brokerId();
        configuration.enable(brokerId,0,true,id());
        radar = sensor("radar"); tdoa = sensor("tdoa");
        eo = edges.binding(configuration.register(new Registration(EoEdgeEnvelope.PROTOCOL,brokerId,null,"eo-"+id().substring(0,20),null,
                "replay",org,district,"EO-"+id(),"QA optical",null,null,null,"edge-"+id().substring(0,20)),id()),false);
        supervisor = new MqttSessionSupervisor(mqtt,sensingIngress,configuration,network,credentials,clock,eoIngress,edges,30000);
        supervisor.reconcile();
        publisher = new MqttClient("tcp://127.0.0.1:"+port,"qa-"+id(),new MemoryPersistence()); publisher.connect();
        base = clock.nowMillis()-1000;
        for (int n=1;n<=3;n++) {
            sense(radar,n,base+n*200,118.63,"object-a");
            sense(tdoa,n,base+n*200,118.63,"object-a");
        }
        target = jdbc.queryForObject("select target_id from target_source_link where source_id=? and external_target_id='object-a'",String.class,radar.sourceId());
        assertThat(links()).isEqualTo(2);
        task=id(); String command=id();
        edges.insertCommand(command,"EO-"+command,eo.opsDeviceId(),null,"EO_BEGIN_TRACK","QA isolated","replay",true,clock.nowMillis()+30000,base);
        edges.updateCommand(command,"QUEUED","SENT",base,null,null);
        edges.insertTask(task,target,null,eo.opsDeviceId(),command,"QA isolated","{}",base);
        // Heartbeat is real protocol input, independently of target observations.
        heartbeat();
    }

    @AfterEach void cleanup() throws Exception {
        if (supervisor!=null) supervisor.shutdown();
        if (publisher!=null) { if(publisher.isConnected())publisher.disconnect(); publisher.close(); }
        if (broker!=null) broker.stopServer();
        if (brokerId!=null) jdbc.update("update mqtt_broker set enabled=false where broker_id=?",brokerId);
        AuthContext.clear();
    }

    @Test void taskBoundObservationAddsThirdSourceToSameTargetAndSurvivesReadback() throws Exception {
        report(base+800,ai(118.63001),true);
        assertThat(links()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select target_id from target_source_link where source_id=?",String.class,eo.sourceId())).isEqualTo(target);
        assertThat(targetCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from source_observation where source_id=? and class_source='EO_TRACKING'",Long.class,eo.sourceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select level from target_degradation where target_id=?",String.class,target)).isEqualTo("THREE_SOURCE");
        assertThat(json.valueToTree(reads.target(target)).path("source_links").size()).isEqualTo(3);
        assertThat(eoSourceStatus()).isEqualTo("CURRENT");
        assertThat(eoSummary.current(edges.task(task)).status()).isEqualTo("CURRENT");
        assertThat(videos.video(target).eoFusionObservation().status()).isEqualTo("CURRENT");
        long points=pointCount();
        // Same network message is duplicate, cannot append an observation or point.
        publish(eo.reportingTopic(),reportBytes(base+800,ai(118.63001)));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(((Number)edges.status(eo.opsDeviceId()).get("duplicate_count")).longValue()).isPositive());
        drain(eo.source()); assertThat(pointCount()).isEqualTo(points);
    }

    @Test void missingPositionAndStatusOnlyDoNotCreateGhostTargetsOrRefreshPosition() throws Exception {
        var before=latest();
        assertThat(eoSummary.current(edges.task(task)).status()).isEqualTo("NONE");
        assertThat(videos.video(target).eoFusionObservation().status()).isEqualTo("NONE");
        report(base+800,Map.of("className","drone","detectConfidence",0.9,"trackConfidence",0.8),true);
        assertThat(targetCount()).isEqualTo(1);
        assertThat(links()).isEqualTo(2); assertThat(latest()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from source_observation where source_id=?",Long.class,eo.sourceId())).isEqualTo(1);
        assertThat(eoSummary.current(edges.task(task)).status()).isEqualTo("REJECTED");
        assertThat(eoSummary.current(edges.task(task)).message()).contains("尚无可用位置");
        report(base+900,null,true);
        assertThat(targetCount()).isEqualTo(1); assertThat(links()).isEqualTo(2); assertThat(latest()).isEqualTo(before);
    }

    @Test void taskObservationCannotAttachToNearbyOtherTargetOrCreateAnother() throws Exception {
        for(int n=4;n<=6;n++)sense(radar,n,base+n*100,118.65,"object-b");
        assertThat(targetCount()).isEqualTo(2);
        report(base+800,ai(118.65),true);
        assertThat(targetCount()).isEqualTo(2); assertThat(links()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from target_source_link where source_id=?",Long.class,eo.sourceId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where source_id=?",Long.class,eo.sourceId())).isEqualTo(1);
    }

    @Test void endingWhileQueuedPreventsFusionEvenThoughIngressAcceptedTheReport() throws Exception {
        report(base+800,ai(118.63),false);
        edges.updateTask(task,"OPEN","ENDING",null,clock.nowMillis());
        drain(eo.source());
        assertThat(links()).isEqualTo(2); assertThat(targetCount()).isEqualTo(1);
    }

    @Test void endedTaskCannotContributeSavedEoSnapshotWhenRadarReports() throws Exception {
        report(base+800,ai(118.63),true); assertThat(links()).isEqualTo(3);
        edges.updateTask(task,"OPEN","ENDED",null,clock.nowMillis());
        sense(radar,4,base+1000,118.63,"object-a");
        assertThat(jdbc.queryForObject("select level from target_degradation where target_id=?",String.class,target)).isEqualTo("FUSION_BOX_ONLY");
        assertThat(links()).isEqualTo(3); // historical association preserved
    }

    @Test void aiSilenceExpiresOnlyItsContributionDespiteHealthyOpenTask() throws Exception {
        report(base+800,ai(118.63),true);
        TEST_NOW.addAndGet(3101);
        heartbeat();
        assertThat(eoSourceStatus()).isEqualTo("EXPIRED");
        assertThat(eoSummary.current(edges.task(task)).status()).isEqualTo("EXPIRED");
        assertThat(edges.task(task).get("status")).isEqualTo("OPEN");
        long now=clock.nowMillis();
        sense(radar,4,now,118.63,"object-a"); sense(tdoa,4,now,118.63,"object-a");
        assertThat(jdbc.queryForObject("select level from target_degradation where target_id=?",String.class,target)).isEqualTo("FUSION_BOX_ONLY");
        assertThat(links()).isEqualTo(3);
    }

    @Test void eoCanContinueFromSameTaskWhenOtherSourcesStop() throws Exception {
        report(base+800,ai(118.63),true);
        // Move from the deliberately delayed first sample to real current samples before waiting.
        report(clock.nowMillis(),ai(118.63),true);
        for(int n=0;n<4;n++) {
            TEST_NOW.addAndGet(1000);
            heartbeat();
            report(clock.nowMillis(),ai(118.63),true);
        }
        assertThat(eoSourceStatus()).as("read=%s summary=%s raw=%s",json.valueToTree(reads.target(target)),eoSummary.current(edges.task(task)),jdbc.queryForList("select observed_at, quality::text from source_observation where source_id=?",eo.sourceId())).isEqualTo("CURRENT");
        assertThat(jdbc.queryForObject("select level from target_degradation where target_id=?",String.class,target)).isEqualTo("SINGLE_SOURCE");
        assertThat(targetCount()).isEqualTo(1);
    }

    @Test void splitLineageAfterTaskCreationRejectsQueuedObservation() throws Exception {
        report(base+800,ai(118.63),false);
        jdbc.update("insert into target_lineage(lineage_id,op,origin_target_id,survivor_target_id,operator_kind,occurred_at,member_target_ids,algo_version,config_version,created_at) values(?,'SPLIT',?,?,'SYSTEM',?,'[]','qa-eo','demo-v1',CURRENT_TIMESTAMP)",
                id(),target,target,new java.sql.Timestamp(clock.nowMillis()));
        drain(eo.source());
        assertThat(links()).isEqualTo(2);
        assertThat(eoSummary.current(edges.task(task)).status()).isEqualTo("REJECTED");
    }

    @Test void taskContextRejectsWrongModeSourceDeviceAndOwnership() throws Exception {
        report(base+800,ai(118.63),true);
        for(String[] fields:List.of(
                new String[]{eo.sourceId(),eo.deviceId(),"live",org,district},
                new String[]{id(),eo.deviceId(),"replay",org,district},
                new String[]{eo.sourceId(),id(),"replay",org,district},
                new String[]{eo.sourceId(),eo.deviceId(),"replay",id(),district},
                new String[]{eo.sourceId(),eo.deviceId(),"replay",org,id()})) {
            var context=taskContexts.lookup(task,fields[0],fields[1],fields[2],fields[3],fields[4],base+800,clock.nowMillis());
            assertThat(context.accepted()).isFalse();
            assertThat(context.exclusionReason()).isEqualTo("EO_TASK_SOURCE_MISMATCH");
        }
    }

    private String eoSourceStatus() {
        for(var link:json.valueToTree(reads.target(target)).path("source_links"))
            if(eo.sourceId().equals(link.path("source_id").asText()))return link.path("fusion_status").asText();
        return "MISSING";
    }

    private Binding sensor(String type) {
        String ops=configuration.register(new Registration(LingyunEnvelope.PROTOCOL,brokerId,"eo-qa",type+"-"+id(),type,
                "replay",org,district,"QA-"+id(),"QA "+type,null,null,null),id());
        return mqtt.binding(ops,false);
    }
    private void sense(Binding b,int seq,long time,double lon,String external) throws Exception {
        long before=inboxes(b.source());
        publish(b.topic(true),json.writeValueAsBytes(Map.of("deviceId",b.externalDeviceId(),"ptTime",time,"msgCnt",seq,
                "objects",List.of(Map.of("objectId",external,"time",time,"longitude",lon,"latitude",37.43,"speed",0,
                        "extension",Map.of("objectType",30))))));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(inboxes(b.source())).isEqualTo(before+1)); drain(b.source());
    }
    private Map<String,Object> ai(double lon) { return Map.of("className","drone","longitude",lon,"latitude",37.43,"detectConfidence",0.9,"trackConfidence",0.8); }
    private byte[] reportBytes(long time,Map<String,Object> ai) {
        Map<String,Object> metadata=new LinkedHashMap<>(Map.of("taskId",task,"deviceId",eo.externalDeviceId(),"codeStatus",200,"workState",1,"cameraStatus",Map.of()));
        if(ai!=null)metadata.put("aiStatus",ai);
        metadata.put("objectData",Map.of("dataId",target,"longitude",118.63,"latitude",37.43));
        return EoEdgeEnvelope.encode("BeginTracking",eo.edgeId(),time,metadata);
    }
    private void report(long time,Map<String,Object> ai,boolean drain) throws Exception {
        long before=inboxes(eo.source()); publish(eo.reportingTopic(),reportBytes(time,ai));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(inboxes(eo.source())).isEqualTo(before+1));
        if(drain)drain(eo.source());
    }
    private void heartbeat() throws Exception {
        long now=clock.nowMillis();
        publish(eo.reportingTopic(),EoEdgeEnvelope.encode("HeartBeat",eo.edgeId(),now,Map.of(
                "deviceId",eo.externalDeviceId(),"codeStatus",200,"workState",1,"taskId",task,"cameraStatus",Map.of())));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(edges.binding(eo.opsDeviceId(),false).lastHeartbeatAt()).isGreaterThanOrEqualTo(now));
    }
    private void publish(String topic,byte[] bytes) throws Exception { publisher.publish(topic,bytes,1,false); }
    private long inboxes(String source) { return jdbc.queryForObject("select count(*) from inbox_message where source=?",Long.class,source); }
    private void drain(String source) {
        List<InboxRow> rows=jdbc.query("select inbox_id,source,source_msg_id,source_id,received_at,payload::text from inbox_message where source=? and status='RECEIVED' order by received_at,source_msg_id",
                (r,n)->new InboxRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5),r.getString(6)),source);
        for(var row:rows)new TransactionTemplate(transactions).executeWithoutResult(tx->{
            fusion.processFrame(row); jdbc.update("update inbox_message set status='DONE',processed_at=? where inbox_id=?",clock.nowMillis(),row.inboxId());
        });
    }
    private long links() { return jdbc.queryForObject("select count(*) from target_source_link where target_id=?",Long.class,target); }
    private long targetCount() { return jdbc.queryForObject("select count(*) from target where owner_org_id=?",Long.class,org); }
    private long pointCount() { return jdbc.queryForObject("select count(*) from track_point p join track t on t.track_id=p.track_id where t.target_id=?",Long.class,target); }
    private Map<String,Object> latest() { return jdbc.queryForMap("select observed_at,received_at,ST_AsText(location) location from target_latest_state where target_id=?",target); }
    private static String id() { return UUID.randomUUID().toString(); }
}
