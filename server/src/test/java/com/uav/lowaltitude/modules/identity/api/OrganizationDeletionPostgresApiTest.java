package com.uav.lowaltitude.modules.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.sql.SQLException;
import com.uav.lowaltitude.modules.device.api.DeviceMonitoringPostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/stage456_verify_[a-z0-9_]+")
@Import(DeviceMonitoringPostgresFixture.NoScheduledJobs.class)
class OrganizationDeletionPostgresApiTest extends OrganizationDeletionApiTest {
    @Autowired com.uav.lowaltitude.modules.device.application.Countermeasure4ChControlService fourCh;
    @Autowired com.uav.lowaltitude.modules.device.application.LingyunControlService lingyun;
    @Autowired com.uav.lowaltitude.modules.device.application.MqttConfigurationService mqttConfiguration;
    @Autowired com.uav.lowaltitude.modules.device.infrastructure.MqttRepository mqtt;
    @Autowired com.uav.lowaltitude.platform.time.AppClock clock;
    private static final DeviceMonitoringPostgresFixture DATABASE = new DeviceMonitoringPostgresFixture();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        DATABASE.springProperties(registry);
        registry.add("app.network.allow-loopback-when-listed",()->true);
        registry.add("app.live-device.enabled",()->false);
        registry.add("app.mqtt.enabled",()->false);
    }
    @AfterAll static void cleanup() { DATABASE.close(); }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void aConcurrentCommittedChildReferencePreventsDeletion() throws Exception {
        String child = UUID.randomUUID().toString();
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO app_org(org_id,parent_id,org_code,name,enabled,created_at,updated_at,version)
                    VALUES(?,?,?, '并发新增下级单位',true,0,0,0)
                    """)) {
                insert.setString(1, child); insert.setString(2, org); insert.setString(3, child); insert.executeUpdate();
            }
            var deleting = pool.submit(() -> remove(platform, org, 0).andReturn().getResponse());
            connection.commit();
            var result = deleting.get(15, TimeUnit.SECONDS);
            assertThat(result.getStatus()).isEqualTo(409);
            assertThat(result.getContentAsString()).containsAnyOf("ORG_IN_USE", "ORG_DELETE_BUSY");
            assertThat(countOrg(org)).isEqualTo(1);
            assertThat(countOrg(child)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void retirementRowLockRejectsAnActiveWriteWithoutOrganizationForeignKey() throws Exception {
        String target = jdbc.queryForObject("SELECT target_id FROM target ORDER BY target_id LIMIT 1", String.class);
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT org_id FROM app_org WHERE org_id=? FOR UPDATE")) {
                statement.setString(1,org); statement.executeQuery();
            }
            assertThatThrownBy(() -> addEpisode(target,"ACTIVE"))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .rootCause().extracting(cause -> ((SQLException)cause).getSQLState()).isEqualTo("P7102");
            assertThat(countOrg(org)).isEqualTo(1);
            connection.rollback();
        }
        remove(platform, org, 0).andExpect(status().isOk());
        assertThat(countOrg(org)).isZero();
        assertThatThrownBy(() -> addEpisode(target,"ACTIVE"))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().extracting(cause -> ((SQLException)cause).getSQLState()).isEqualTo("P7101");
        addEpisode(target,"CLEARED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prediction_episode WHERE owner_org_id=? AND status='CLEARED'",Integer.class,org)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void anActiveNoForeignKeyWriterHoldsRetirementOffUntilItsWorkIsVisible() throws Exception {
        String target = jdbc.queryForObject("SELECT target_id FROM target ORDER BY target_id LIMIT 1", String.class);
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement(EPISODE_SQL)) {
                episodeParameters(statement,target,"ACTIVE"); statement.executeUpdate();
            }
            remove(platform,org,0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_DELETE_BUSY"));
            connection.commit();
        }
        remove(platform,org,0).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORG_IN_USE"));
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void retiredUnitAcceptsActualStopsAndReceiptsButRejectsNewStarts() throws Exception {
        String device=addDevice(org);
        remove(platform,org,0).andExpect(status().isOk());
        String stop=addCommand(device,"QUEUED","COUNTERMEASURE_4CH");
        jdbc.update("INSERT INTO countermeasure_4ch_command(command_id,action,mask,authorization_id) VALUES(?,'SET_MASK',0,'historical-stop')",stop);
        jdbc.update("UPDATE device_command SET status='SENT' WHERE command_id=?",stop);
        jdbc.update("UPDATE device_command SET status='SUCCEEDED',result_code='STOPPED' WHERE command_id=?",stop);
        assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?",String.class,stop)).isEqualTo("SUCCEEDED");
        String start=addCommand(device,"QUEUED","COUNTERMEASURE_4CH");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO countermeasure_4ch_command(command_id,action,mask,authorization_id) VALUES(?,'SET_MASK',15,'new-start')",start))
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause().extracting(cause -> ((SQLException)cause).getSQLState()).isEqualTo("P7101");
        String lingyunStop=addCommand(device,"QUEUED","LINGYUN_CONTROL");
        jdbc.update("INSERT INTO lingyun_control_command(command_id,msg_no,operation_type,operation_cmd,params_json,authorization_id) VALUES(?,?,0,60003,'{}','historical-stop')",lingyunStop,lingyunStop);
        jdbc.update("UPDATE device_command SET status='SUCCEEDED' WHERE command_id=?",lingyunStop);
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void actualFourChannelStopReplyCompletesAfterRetirement() throws Exception {
        try(var server=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            var reply=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try(var socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    var request=new java.io.ByteArrayOutputStream(); int b;
                    while((b=socket.getInputStream().read())!=-1) { request.write(b); if(b=='\n')break; }
                    socket.getOutputStream().write("22 01 13 00 00 00 00 36\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return request.toString(java.nio.charset.StandardCharsets.US_ASCII);
                } catch(Exception failure) { throw new RuntimeException(failure); }
            });
            String device=addDevice(org), source=UUID.randomUUID().toString();
            jdbc.update("""
                INSERT INTO ops_integration_source(source_id,source_code,name,protocol_code,protocol_version,source_mode,enabled,allowed_cidrs,simulated,version,created_at,updated_at)
                VALUES(?,?,?,'COUNTERMEASURE_TCP_4CH_V2_0','2.0','live',true,'127.0.0.1/32',true,0,0,0)
                """,source,source,"删除单位停机隔离模拟器");
            jdbc.update("UPDATE ops_device SET source_id=?,external_device_id=?,source_mode='live',device_type_code='countermeasure' WHERE device_id=?",source,device,device);
            jdbc.update("INSERT INTO device_connection_profile(device_id,transport,host,port,timeout_millis,retry_count,version,updated_at) VALUES(?,'TCP','127.0.0.1',?,1000,1,0,0)",device,server.getLocalPort());
            jdbc.update("INSERT INTO countermeasure_4ch_profile(device_id,device_address,wire_encoding,poll_interval_millis,version,updated_at) VALUES(?,1,'ASCII_HEX_SPACED',5000,0,0)",device);
            remove(platform,org,0).andExpect(status().isOk());
            String stop=addCommand(device,"QUEUED","COUNTERMEASURE_4CH");
            jdbc.update("INSERT INTO countermeasure_4ch_command(command_id,action,mask,authorization_id) VALUES(?,'SET_MASK',0,'historical-safety-stop')",stop);
            fourCh.dispatch(stop);
            assertThat(reply.get(5,TimeUnit.SECONDS)).contains("55 01 13 00 00 00 00 69");
            assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?",String.class,stop)).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM command_receipt WHERE command_id=?",Integer.class,stop)).isEqualTo(1);
        }
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void actualLingyunStopAndLateReceiptRemainProcessableAfterRetirement() throws Exception {
        String actor=jdbc.queryForObject("SELECT user_id FROM app_user WHERE account='admin1'",String.class);
        var principal=new com.uav.lowaltitude.platform.security.AuthUser(actor,"admin1","删除单位回执测试","ROLE-ADMIN",1,false,"ALL");
        com.uav.lowaltitude.platform.security.AuthContext.set(principal);
        String broker=null;
        try {
            String district=jdbc.queryForObject("SELECT district_id FROM app_district ORDER BY district_id LIMIT 1",String.class);
            broker=mqttConfiguration.create(new com.uav.lowaltitude.modules.device.domain.MqttConfiguration.BrokerInput(
                "删除单位回执隔离","127.0.0.1",1883,false,null,null,"127.0.0.1/32","replay",org,district,null),UUID.randomUUID().toString()).brokerId();
            mqttConfiguration.enable(broker,0,true,UUID.randomUUID().toString());
            String owner=UUID.randomUUID().toString();
            assertThat(mqtt.claim(broker,owner,clock.nowMillis())).isTrue();
            String device=mqttConfiguration.register(new com.uav.lowaltitude.modules.device.domain.MqttConfiguration.Registration(
                com.uav.lowaltitude.integration.mqtt.LingyunEnvelope.PROTOCOL,broker,"retirement-fixture","radar-"+UUID.randomUUID(),"radar","replay",
                org,district,"RET-"+UUID.randomUUID().toString().substring(0,8),"隔离雷达",null,null,null),UUID.randomUUID().toString());
            var binding=mqtt.binding(device,false);
            jdbc.update("UPDATE ops_device_state SET connectivity='ONLINE',health_code='GOOD',has_alarm=false WHERE device_id=?",device);
            remove(platform,org,0).andExpect(status().isOk());
            String stop=lingyun.enqueueUnchecked(principal,device,UUID.randomUUID().toString(),"historical-safety-stop",0,10000,java.util.Map.of(),"历史安全停止");
            String commandNo=jdbc.queryForObject("SELECT command_no FROM device_command WHERE command_id=?",String.class,stop);
            byte[] response=("{\"head\":{\"msgNo\":\""+commandNo+"\",\"deviceId\":\""+binding.externalDeviceId()+"\",\"time\":1},\"data\":{\"code\":0,\"msg\":\"stopped\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            lingyun.receive(broker,owner,binding.controlRespTopic(),response,1,false,clock.nowMillis());
            assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?",String.class,stop)).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM command_receipt WHERE command_id=?",Integer.class,stop)).isEqualTo(1);
            String late=lingyun.enqueueUnchecked(principal,device,UUID.randomUUID().toString(),"historical-safety-stop",0,10000,java.util.Map.of(),"迟到回执隔离测试");
            jdbc.update("UPDATE device_command SET status='TIMED_OUT',issued_at=?,result_code='ADAPTER_TIMEOUT' WHERE command_id=?",clock.nowMillis(),late);
            String lateNo=jdbc.queryForObject("SELECT command_no FROM device_command WHERE command_id=?",String.class,late);
            byte[] lateReply=new String(response,java.nio.charset.StandardCharsets.UTF_8).replace(commandNo,lateNo).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            lingyun.receive(broker,owner,binding.controlRespTopic(),lateReply,1,false,clock.nowMillis());
            assertThat(jdbc.queryForObject("SELECT status FROM device_command WHERE command_id=?",String.class,late)).isEqualTo("TIMED_OUT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM command_receipt WHERE command_id=?",Integer.class,late)).isEqualTo(1);
        } finally {
            if(broker!=null)jdbc.update("UPDATE mqtt_broker SET enabled=false WHERE broker_id=?",broker);
            com.uav.lowaltitude.platform.security.AuthContext.clear();
        }
    }

    private static final String EPISODE_SQL="""
        INSERT INTO prediction_episode(episode_id,target_id,airspace_id,source_mode,owner_org_id,district_id,status,
          valid_until,reason,started_at,stability_json,warning_json)
        VALUES(?,?,?,'replay',?,?,?,1,'隔离并发测试',0,'{}','{}')
        """;
    private void episodeParameters(java.sql.PreparedStatement statement,String target,String state) throws SQLException {
        statement.setString(1,UUID.randomUUID().toString()); statement.setString(2,target);
        statement.setString(3,UUID.randomUUID().toString()); statement.setString(4,org);
        statement.setString(5,UUID.randomUUID().toString()); statement.setString(6,state);
    }
    private void addEpisode(String target,String state) {
        jdbc.update(EPISODE_SQL, statement -> episodeParameters(statement,target,state));
    }
}
