package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.transaction.support.TransactionTemplate;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import io.moquette.broker.Server;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.mqtt.*;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.modules.fusion.application.FusionPipeline;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.device.application.MqttConfigurationService;
import com.uav.lowaltitude.modules.device.application.MqttIngressService;
import com.uav.lowaltitude.modules.device.infrastructure.MqttRepository;
import com.uav.lowaltitude.integration.device.EnvironmentCredentialResolver;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Real loopback MQTT -> receipt/inbox -> transactional production fusion -> PostgreSQL projections. No hardware claims. */
@SpringBootTest
@ActiveProfiles({"test","postgres-test"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:25432/stage456_verify_[a-z0-9_]+")
class FusionInputAcceptancePostgresTest {
    private static final DeviceMonitoringPostgresFixture DATABASE=new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        DATABASE.springProperties(p);
        p.add("app.automation-rules.enabled",()->true);
        p.add("app.rule-engine.allow-demo-active",()->true);
    }
    @AfterAll static void closeDatabase(){DATABASE.close();}
    @Autowired FusionInboxRepository fusionInbox;
    @Autowired MqttConfigurationService configuration;
    @Autowired MqttIngressService ingress;
    @Autowired MqttRepository repository;
    @Autowired MqttNetworkPolicy network;
    @Autowired EnvironmentCredentialResolver credentials;
    @Autowired AppClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path temporary;
    String org,district,brokerId;
    @Autowired FusionPipeline fusion;
    @Autowired RuleRunService rules;
    @Autowired ObjectMapper json;
    @Autowired com.uav.lowaltitude.modules.reporting.application.ReportingService reporting;
    @Autowired com.uav.lowaltitude.modules.target.application.TargetReadService targetReads;
    @Autowired com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskEvaluationService spatialRisks;
    @Autowired com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeWorker automation;
    @Autowired com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimePolicy automationPolicy;
    @Autowired com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository automationRuns;
    Server localBroker;MqttSessionSupervisor supervisor;MqttClient publisher;Binding sensing;
    final List<Map<String,Object>> inputs=new ArrayList<>();
    @BeforeEach void connectInput() throws Exception {
        String user=jdbc.queryForObject("select user_id from app_user where account='admin1'",String.class);
        AuthContext.set(new AuthUser(user,"admin1","QA fusion","ROLE-ADMIN",1,false,"ALL"));
        // Association is intentionally cross-source within a domain. Each independent scenario needs its own domain.
        org=id();district="seed-stage3-district";
        jdbc.update("insert into app_org(org_id,org_code,name,enabled,created_at,updated_at,version) values(?,?,?,true,?,?,0)",org,"QA-"+org,"QA fusion "+org,clock.nowMillis(),clock.nowMillis());
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        Properties props=new Properties();props.setProperty("host","127.0.0.1");props.setProperty("port",String.valueOf(port));
        props.setProperty("allow_anonymous","true");props.setProperty("persistence_enabled","false");
        props.setProperty("data_path",temporary.toString());props.setProperty("telemetry_enabled","false");
        localBroker=new Server();localBroker.startServer(props);
        brokerId=configuration.create(new BrokerInput("QA fusion input","127.0.0.1",port,false,null,null,"127.0.0.1/32","replay",org,district,null),id()).brokerId();
        configuration.enable(brokerId,0,true,id());
        sensing=repository.binding(configuration.register(new Registration(LingyunEnvelope.PROTOCOL,brokerId,"qa-fusion","qa-input","radar","replay",org,district,"QA-"+id(),"QA input",null,null,null),id()),false);
        supervisor=new MqttSessionSupervisor(repository,ingress,configuration,network,credentials,clock);
        publisher=new MqttClient("tcp://127.0.0.1:"+port,"qa-"+id(),new MemoryPersistence());publisher.connect();supervisor.reconcile();
        assertThat(repository.status(sensing.opsDeviceId()).get("subscribed")).isEqualTo(true);
    }
    @AfterEach void disconnectInput() throws Exception {
        // Graceful disposal of this in-process test fixture only; no crash/restart/OS process operation.
        if(supervisor!=null)supervisor.shutdown();
        if(publisher!=null){if(publisher.isConnected())publisher.disconnect();publisher.close();}
        if(localBroker!=null)localBroker.stopServer();
        if(brokerId!=null)jdbc.update("update mqtt_broker set enabled=false where broker_id=?",brokerId);
        AuthContext.clear();
    }

    @Test void identifying255StaysUnknownThroughMqttFusionAndActiveLegality() throws Exception {
        var beforeActions=actionCounts();
        long at=clock.nowMillis()-5000;
        for(int n=1;n<=3;n++)sendAndDrain(frame(n,at+n*500,255,118.63),n,"identifying-"+n);
        String target=target();
        String type=jdbc.queryForObject("select object_type_code from target where target_id=?",String.class,target);
        assertThat(type).isIn(null,"UNKNOWN");
        assertThat(jdbc.queryForObject("select count(*) from source_observation where source_id=? and class_code is not null",Long.class,sensing.sourceId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where source_id=? and quality->>'identifying'='true'",Long.class,sensing.sourceId())).isEqualTo(3);
        var asOf=Instant.ofEpochMilli(clock.nowMillis()).atOffset(ZoneOffset.UTC);
        var run=rules.start("LEGALITY-DEMO",RunMode.ACTIVE,"SCHEDULED",null,null,asOf);
        rules.evaluateOne(run,new Subject(SubjectKind.TARGET,target,org,district,"replay"),asOf,null);
        assertThat(jdbc.queryForObject("select legal_status from rule_evaluation where run_id=? and target_id=?",String.class,run.runId(),target)).isEqualTo("UNDETERMINED");
        jdbc.update("update rule_set set active_version_id='space-risk-demo-v1' where rule_set_code='SPACE-RISK-DEMO'");
        List<String> riskRuns=new ArrayList<>();
        for(String code:List.of("C04","C05")) {
            var riskRun=spatialRisks.evaluate(code,asOf.minusSeconds(20),asOf.plusSeconds(1),"SCHEDULED",null);
            assertThat(riskRun.status()).as(riskRun.message()).isEqualTo("SUCCESS");riskRuns.add(riskRun.runId());
        }
        assertThat(automationPolicy.enabled()).isTrue();
        long scheduledAt=clock.nowMillis();automation.poll();
        assertThat(automationRuns.heartbeat()).isGreaterThanOrEqualTo(scheduledAt);
        assertThat(automationRuns.lastError()).isNull();
        assertThat(actionCounts()).isEqualTo(beforeActions);
        assertThat(jdbc.queryForObject("select count(*) from alarm where target_id=?",Long.class,target)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flight_risk where target_id=?",Long.class,target)).isZero();
        writeEvidence("r23-mqtt-fusion",Map.of("target_id",target,"legal_status","UNDETERMINED","risk_run_ids",riskRuns,
                "automation_heartbeat_at",automationRuns.heartbeat(),"actions_before_and_after",beforeActions,
                "scope","真实本机MQTT与PG融合/合法性；实际C04/C05与已启用自动规则poll，未知目标不产告警/风险/通知/反制命令；无正向授权"));
    }

    @Test void duplicateLateAndFutureInputsPreserveTheRealFusedLatestState() throws Exception {
        long at=clock.nowMillis()-5000;
        for(int n=1;n<=3;n++)sendAndDrain(frame(n,at+n*500,30,118.63+n*0.00001),n,"baseline-"+n);
        String target=target();Map<String,Object> latest=latest(target);
        var asOf=clock.now().atOffset(ZoneOffset.UTC);
        List<Map<String,Object>> checkpoints=new ArrayList<>();
        checkpoints.add(inputCheckpoint("baseline",target,asOf));
        var alarms=alarmIds(target);var actions=actionCounts();
        long points=points();String duplicate=frame(3,at+1500,30,118.63+3*0.00001);
        publish(duplicate,"duplicate");
        await().pollInSameThread().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(((Number)configuration.status(sensing.opsDeviceId()).get("duplicate_count")).longValue()).isEqualTo(1));
        drain();assertThat(inboxCount()).isEqualTo(3);assertThat(points()).isEqualTo(points);assertThat(latest(target)).isEqualTo(latest);
        checkpoints.add(inputCheckpoint("duplicate",target,asOf));
        assertThat(alarmIds(target)).isEqualTo(alarms);assertThat(actionCounts()).isEqualTo(actions);
        sendAndDrain(frame(4,at+250,30,118.630005),4,"out-of-order");
        assertThat(jdbc.queryForObject("select count(*) from source_observation where source_id=? and observed_at=?",Long.class,sensing.sourceId(),new java.sql.Timestamp(at+250))).isEqualTo(1);
        assertThat(latest(target)).isEqualTo(latest);
        assertThat(jdbc.queryForObject("select CAST(quality AS VARCHAR) from source_observation where source_id=? and observed_at=?",String.class,sensing.sourceId(),new java.sql.Timestamp(at+250))).contains("out_of_order");
        checkpoints.add(inputCheckpoint("out-of-order",target,asOf));
        assertThat(alarmIds(target)).isEqualTo(alarms);assertThat(actionCounts()).isEqualTo(actions);
        long futureAt=clock.nowMillis()+86400000;
        publish(frame(5,futureAt,30,118.9),"future");
        await().pollInSameThread().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(jdbc.queryForObject("select count(*) from mqtt_receive_diagnostic where ops_device_id=? and reason='OBSERVATION_TIME_IN_FUTURE'",Long.class,sensing.opsDeviceId())).isEqualTo(1));
        drain();assertThat(inboxCount()).isEqualTo(4);assertThat(latest(target)).isEqualTo(latest);
        assertThat(jdbc.queryForObject("select count(*) from source_observation where source_id=? and observed_at=?",Long.class,sensing.sourceId(),new java.sql.Timestamp(futureAt))).isZero();
        checkpoints.add(inputCheckpoint("future-rejected",target,asOf));
        assertThat(alarmIds(target)).isEqualTo(alarms);assertThat(actionCounts()).isEqualTo(actions);
        // Explicit evaluation time, not an actual two-day wait. Source remains replay; SCHEDULED uses real freshness rules.
        var expired=inputCheckpoint("after-freshness-expiry",target,asOf.plusDays(2));
        assertThat(((Map<?,?>)expired.get("evaluation")).get("freshness_code")).isEqualTo("STALE");
        assertThat(((Map<?,?>)expired.get("evaluation")).get("legal_status")).isEqualTo("NOT_APPLICABLE");
        checkpoints.add(expired);assertThat(latest(target)).isEqualTo(latest);
        assertThat(alarmIds(target)).isEqualTo(alarms);assertThat(actionCounts()).isEqualTo(actions);
        var diagnostics=jdbc.queryForList("select payload_hash,received_at,outcome,reason from mqtt_receive_diagnostic where ops_device_id=? order by received_at,diagnostic_id",sensing.opsDeviceId());
        assertThat(diagnostics).hasSize(6);
        assertThat(diagnostics.stream().filter(row->"ACCEPTED".equals(row.get("outcome"))).count()).isEqualTo(4);
        assertThat(diagnostics.stream().filter(row->"DUPLICATE".equals(row.get("outcome"))).count()).isEqualTo(1);
        assertThat(diagnostics.stream().filter(row->"REJECTED".equals(row.get("outcome"))).count()).isEqualTo(1);
        writeEvidence("r29-mqtt-fusion",Map.of("target_id",target,"checkpoints",checkpoints,"input_diagnostics",diagnostics,
                "observations",jdbc.queryForList("select observed_at,received_at,CAST(quality AS VARCHAR) quality from source_observation where source_id=? order by received_at,observed_at",sensing.sourceId()),
                "scope","固定单轨迹真实本机MQTT输入；逐条原始/接收/处理结果、融合当前位置、实际C03及自动调度后告警/动作不误变；未来点隔离；过期项使用显式评估时间"));
    }

    Map<String,Object> inputCheckpoint(String label,String target,OffsetDateTime asOf) throws Exception {
        var run=rules.start("LEGALITY-DEMO",RunMode.ACTIVE,"SCHEDULED",null,null,asOf);
        rules.evaluateOne(run,new Subject(SubjectKind.TARGET,target,org,district,"replay"),asOf,null);
        var evaluation=jdbc.queryForMap("select evaluation_id,observed_at,as_of,freshness_code,legal_status from rule_evaluation where run_id=? and target_id=?",run.runId(),target);
        assertThat(automationPolicy.enabled()).isTrue();long scheduledAt=clock.nowMillis();automation.poll();
        assertThat(automationRuns.heartbeat()).isGreaterThanOrEqualTo(scheduledAt);assertThat(automationRuns.lastError()).isNull();
        return Map.of("step",label,"latest",latest(target),"evaluation",evaluation,"alarm_ids",alarmIds(target),"actions",actionCounts(),
                "automation_heartbeat_at",automationRuns.heartbeat(),"receipts",jdbc.queryForList("select source_msg_id,received_at,status,payload_hash from inbox_message where source=? order by received_at,source_msg_id",sensing.source()));
    }
    List<String> alarmIds(String target){return jdbc.queryForList("select alarm_id from alarm where target_id=? order by alarm_id",String.class,target);}

    @Test void uavAndControllerCountSeparatelyWithoutEnteringLiveOperationsStatistics() throws Exception {
        sensing=repository.binding(configuration.register(new Registration(LingyunEnvelope.PROTOCOL,brokerId,"qa-fusion","qa-input","tdoa","replay",org,district,"QA-"+id(),"QA TDOA",null,null,null),id()),false);
        supervisor.reconcile();assertThat(repository.status(sensing.opsDeviceId()).get("subscribed")).isEqualTo(true);
        String day=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        var before=reporting.operations(day,day,org).summary();
        assertThat(before.uav()).isNotNull();assertThat(before.total()).isNotNull();
        long at=clock.nowMillis()-5000;
        for(int n=1;n<=3;n++) {
            long observed=at+n*500;
            var uav=Map.of("objectId","qa-uav","time",observed,"longitude",118.63,"latitude",37.43,"speed",0,
                    "extension",Map.of("objectType",30,"uavSN","QA-UAV-"+sensing.sourceId(),"pilotLon",118.62,"pilotLat",37.43));
            var controller=Map.of("objectId","qa-controller","time",observed,"longitude",118.62,"latitude",37.43,"speed",0,
                    "extension",Map.of("objectType",100));
            sendAndDrain(json.writeValueAsString(Map.of("deviceId","qa-input","ptTime",observed,"msgCnt",n,"objects",List.of(uav,controller))),n,"uav-controller-"+n);
        }
        var linked=jdbc.queryForList("select l.external_target_id,t.target_id,t.object_type_code from target_source_link l join target t on t.target_id=l.target_id where l.source_id=? order by l.external_target_id",sensing.sourceId());
        assertThat(linked).hasSize(2);assertThat(linked.stream().map(row->row.get("object_type_code")).toList()).containsExactly("REMOTE_CONTROLLER","UAV");
        String uavTarget=linked.get(1).get("target_id").toString(),controllerTarget=linked.get(0).get("target_id").toString();
        assertThat(uavTarget).isNotEqualTo(controllerTarget);
        assertThat(jdbc.queryForObject("select ST_Equals(u.pilot_location,c.location) from target_latest_state u cross join target_latest_state c where u.target_id=? and c.target_id=?",Boolean.class,uavTarget,controllerTarget)).isTrue();
        var filters=new org.springframework.util.LinkedMultiValueMap<String,String>();
        filters.add("owner_org_id",org);
        filters.add("source_code",jdbc.queryForObject("select source_code from integration_source where source_id=?",String.class,sensing.sourceId()));
        var all=targetReads.targets(filters);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.items()).allSatisfy(item->assertThat(item.sourceMode()).isEqualTo("replay"));
        filters.set("object_type_code","UAV");var uavs=targetReads.targets(filters);
        assertThat(uavs.total()).isEqualTo(1);assertThat(uavs.items().get(0).targetId()).isEqualTo(uavTarget);
        filters.set("object_type_code","REMOTE_CONTROLLER");var controllers=targetReads.targets(filters);
        assertThat(controllers.total()).isEqualTo(1);assertThat(controllers.items().get(0).targetId()).isEqualTo(controllerTarget);
        var after=reporting.operations(day,day,org).summary();
        // Formal operations reports intentionally select source_mode=live. Replayed observations must not enter them.
        assertThat(after.uav()-before.uav()).isZero();assertThat(after.total()-before.total()).isZero();
        writeEvidence("r24-mqtt-fusion",Map.of("targets",linked,"before",before,"after",after,
                "target_list_counts",Map.of("all",all.total(),"uav",uavs.total(),"controller",controllers.total()),
                "source_mode","replay","formal_operations_scope","live-only: replay does not increment total or UAV counts",
                "association","同批原始报文中无人机pilot坐标与遥控器对象坐标一致；未虚构独立目标关系外键"));
    }
    String frame(int sequence,long observed,int objectType,double longitude) throws Exception {
        return json.writeValueAsString(Map.of("deviceId","qa-input","ptTime",observed,"msgCnt",sequence,"objects",List.of(Map.of(
                "objectId","qa-object","time",observed,"longitude",longitude,"latitude",37.43,"speed",0,
                "extension",Map.of("objectType",objectType)))));
    }
    void publish(String payload,String label) throws Exception {
        inputs.add(Map.of("label",label,"sent_at",clock.nowMillis(),"payload",json.readTree(payload),
                "payload_hash",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)))));
        publisher.publish(sensing.topic(true),payload.getBytes(StandardCharsets.UTF_8),1,false);
    }
    void sendAndDrain(String payload,int expected,String label) throws Exception {
        publish(payload,label);await().pollInSameThread().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(inboxCount()).isEqualTo(expected));drain();
    }
    void drain() {
        for(int round=0;round<100;round++) {
            var rows=fusionInbox.claim(clock.nowMillis(),50,30000);if(rows.isEmpty())return;
            for(var row:rows)new TransactionTemplate(transactions).executeWithoutResult(tx->{fusion.processFrame(row);fusionInbox.done(row.inboxId(),clock.nowMillis());});
        }
        throw new AssertionError("Fusion inbox did not drain");
    }
    long inboxCount(){return jdbc.queryForObject("select count(*) from inbox_message where source=?",Long.class,sensing.source());}
    Map<String,Long> actionCounts() {
        Map<String,Long> counts=new LinkedHashMap<>();
        for(String table:List.of("automation_runtime_action","uav_event_advisory","uav_event_voice_advisory","disposal_authorization","device_command","lingyun_control_command","countermeasure_4ch_command"))
            counts.put(table,jdbc.queryForObject("select count(*) from "+table,Long.class));
        return counts;
    }
    long points(){return jdbc.queryForObject("select count(*) from track_point p join track t on t.track_id=p.track_id join target_source_link l on l.target_id=t.target_id where l.source_id=?",Long.class,sensing.sourceId());}
    String target(){return jdbc.queryForObject("select target_id from target_source_link where source_id=? and external_target_id='qa-object'",String.class,sensing.sourceId());}
    Map<String,Object> latest(String target){return jdbc.queryForMap("select observed_at,received_at,ST_AsText(location) location from target_latest_state where target_id=?",target);}
    void writeEvidence(String name,Map<String,Object> result) throws Exception {
        Map<String,Object> out=new LinkedHashMap<>();out.put("transport","real loopback MQTT, replay-labelled input, isolated PostgreSQL schema");out.put("inputs",inputs);out.put("result",result);
        out.put("receipts",jdbc.queryForList("select received_at,status,source_msg_id,payload_hash from inbox_message where source=? order by received_at,source_msg_id",sensing.source()));
        Path path=Path.of("target","qa-unblock",name+".json");Files.createDirectories(path.getParent());Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }
    static String id(){return UUID.randomUUID().toString();}
}
