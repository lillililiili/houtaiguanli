package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.application.LiveRadarFrameIngestService;
import com.uav.lowaltitude.modules.device.application.LiveDeviceSupervisor;
import com.uav.lowaltitude.modules.device.infrastructure.ProtocolDataRepository;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;
import com.uav.lowaltitude.integration.device.radar.RadarV300PayloadDecoder.*;
import com.uav.lowaltitude.platform.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties="app.device-monitor-events.enabled=false")
@ActiveProfiles("test")
@Transactional
class TcpMonitoringEventTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LiveRadarFrameIngestService ingest;
    @Autowired ProtocolDataRepository protocol;
    @Autowired PlatformTransactionManager manager;
    String id,source;
    final java.util.List<String> diagnosticKeys = new java.util.ArrayList<>();
    @BeforeEach void setup() {
        id=jdbc.queryForObject("SELECT device_id FROM ops_device WHERE device_no='DEV-MOCK-001'",String.class);
        source=jdbc.queryForObject("SELECT source_id FROM ops_device WHERE device_id=?",String.class,id);
        jdbc.update("UPDATE ops_device_state SET connectivity='UNKNOWN',work_state_code=NULL WHERE device_id=?",id);
        jdbc.update("UPDATE protocol_runtime_state SET channel_state_json=NULL,radar_registers_json=NULL WHERE device_id=?",id);
    }
    @AfterEach void cleanupCommittedDiagnostics() {
        var independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(s -> diagnosticKeys.forEach(key -> jdbc.update(
                "DELETE FROM inbox_message WHERE source=? AND protocol_message_key=? AND processing_status='FAILED'",
                "live-device:"+id,key)));
    }
    long events(String type) { return jdbc.queryForObject("SELECT COUNT(*) FROM device_event_log WHERE device_id=? AND event_type=?",Long.class,id,type); }
    @Test void tcpDisconnectPreservesLastValidHeartbeatWithoutInventingOne() {
        jdbc.update("UPDATE ops_device_state SET last_heartbeat_at=NULL WHERE device_id=?",id);
        protocol.markConnection(id,"RADAR_TCP_V3_0_0","OFFLINE",null,"timeout",30000,false);
        assertThat(jdbc.queryForObject("SELECT last_heartbeat_at FROM ops_device_state WHERE device_id=?",Long.class,id)).isNull();
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",0,Map.of("2.4",false),31000);
        protocol.markConnection(id,"COUNTERMEASURE_TCP_4CH_V2_0","OFFLINE",null,"timeout",33000,false);
        protocol.markConnection(id,"COUNTERMEASURE_TCP_4CH_V2_0","OFFLINE",null,"timeout",34000,false);
        assertThat(jdbc.queryForObject("SELECT last_heartbeat_at FROM ops_device_state WHERE device_id=?",Long.class,id)).isEqualTo(31000);
        assertThat(jdbc.queryForObject("SELECT connectivity FROM ops_device_state WHERE device_id=?",String.class,id)).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT health_code FROM ops_device_state WHERE device_id=?",String.class,id)).isEqualTo("UNKNOWN");
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",0,Map.of("2.4",false),35000);
        assertThat(jdbc.queryForObject("SELECT last_heartbeat_at FROM ops_device_state WHERE device_id=?",Long.class,id)).isEqualTo(35000);
    }
    @Test void successiveRadarFramesUpdateOneTargetAndAppendDistinctPoints() {
        var item=new TrackItem("31",BigDecimal.valueOf(100),BigDecimal.valueOf(200),BigDecimal.valueOf(80),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.valueOf(15),
                new BigDecimal("0.01"),new BigDecimal("0.01"),3,"UAV",false);
        var first=new TrackBatch(31000000,"101",31000,BigDecimal.ZERO,BigDecimal.ONE,1,0,List.of(item));
        var second=new TrackBatch(31000000,"102",32000,BigDecimal.ZERO,BigDecimal.ONE,1,0,List.of(item));
        assertThat(ingest.ingestTrack(source,id,"DEV-MOCK-001",null,first,new byte[]{1},31000)).isTrue();
        assertThat(ingest.ingestTrack(source,id,"DEV-MOCK-001",null,second,new byte[]{2},32000)).isTrue();
        assertThat(ingest.ingestTrack(source,id,"DEV-MOCK-001",null,second,new byte[]{2},32001)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensing_target WHERE primary_device_id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_target_source_link WHERE device_id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_track WHERE device_id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ops_track_point p JOIN ops_track t ON t.track_id=p.track_id WHERE t.device_id=?",Long.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT frame_id FROM ops_target_latest_state s JOIN sensing_target t ON t.target_id=s.target_id WHERE t.primary_device_id=?",String.class,id)).isEqualTo("102");
        assertThat(jdbc.queryForObject("SELECT sensing_count FROM device_report_window WHERE device_id=?",Long.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source=? AND processing_status='PROCESSED'",Long.class,"live-device:"+id)).isEqualTo(2);
    }
    @Test void emptyRadarFramesAndRtkAndRegistersAreCategorizedAndDeduplicated() {
        var track=new TrackBatch(123,"monitor-1",31000,BigDecimal.ZERO,BigDecimal.ONE,0,0,List.of());
        assertThat(ingest.ingestTrack(source,id,"DEV-MOCK-001",null,track,new byte[]{1},31000)).isTrue();
        assertThat(ingest.ingestTrack(source,id,"DEV-MOCK-001",null,track,new byte[]{1},31001)).isFalse();
        var point=new PointBatch(123,"monitor-2",BigDecimal.ZERO,BigDecimal.ONE,0,List.of());
        assertThat(ingest.ingestPoints(source,id,"monitor-points",point,new byte[]{2},32000)).isTrue();
        assertThat(ingest.ingestPoints(source,id,"monitor-points",point,new byte[]{2},32001)).isFalse();
        var rtk=new Rtk(new BigDecimal("37.4"),new BigDecimal("118.6"),BigDecimal.ZERO,12,0);
        assertThat(ingest.ingestRtk(source,id,"monitor-rtk","monitor-rtk",rtk,new byte[]{3},33000)).isTrue();
        assertThat(ingest.ingestRegisters(source,id,"monitor-register","1",Map.of("work_mode_code",1),new byte[]{4},34000)).isTrue();
        assertThat(events("REPORTING_STARTED")).isEqualTo(1);
        assertThat(events("CONNECTED")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT sensing_count,parameters_count,status_count FROM device_report_window WHERE device_id=?",id))
                .containsEntry("sensing_count",2L).containsEntry("parameters_count",1L).containsEntry("status_count",1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source=? AND processing_status='PROCESSED'",Long.class,"live-device:"+id)).isEqualTo(4);
    }
    @Test void countermeasurePollingCountsEachResponseAndLogsConnectionChangesOnce() {
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",0,Map.of("2.4",false),31000);
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",0,Map.of("2.4",false),32000);
        protocol.markConnection(id,"COUNTERMEASURE_TCP_4CH_V2_0","OFFLINE",null,"timeout",33000,false);
        protocol.markConnection(id,"COUNTERMEASURE_TCP_4CH_V2_0","OFFLINE",null,"timeout",34000,false);
        protocol.markConnection(id,"COUNTERMEASURE_TCP_4CH_V2_0","ONLINE",null,null,35000,false);
        assertThat(events("CONNECTED")).isEqualTo(1); // socket callback alone is not device liveness
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",1,Map.of("2.4",true),36000);
        assertThat(events("DISCONNECTED")).isEqualTo(1);
        assertThat(events("RECOVERED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status_count FROM device_report_window WHERE device_id=?",Long.class,id)).isEqualTo(3);
    }
    @Test void failedPointIngestionRollsBackInboxAndMonitoringSideEffects() {
        var nested=new TransactionTemplate(manager);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.executeWithoutResult(s -> {
            var broken=new PointBatch(123,"broken",BigDecimal.ZERO,BigDecimal.ONE,0,null);
            ingest.ingestPoints(source,id,"broken-monitor",broken,new byte[]{5},31000);
        })).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE source_msg_id='broken-monitor'",Long.class)).isZero();
        assertThat(events("REPORTING_STARTED")).isZero();
        assertThat(jdbc.queryForObject("SELECT connectivity FROM ops_device_state WHERE device_id=?",String.class,id)).isEqualTo("UNKNOWN");
        diagnosticKeys.add("broken-monitor");
        protocol.recordInboxFailure(source,id,"broken-monitor",new byte[]{5},"point persistence failed",31000);
        assertThat(jdbc.queryForObject("SELECT processing_status FROM inbox_message WHERE protocol_message_key='broken-monitor'",String.class)).isEqualTo("FAILED");
        assertThat(events("REPORTING_STARTED")).isZero();
    }

    @Test void protocolWorkChangesUseActualReportsAndIgnoreRepeatedOrMissingValues() {
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",0,Map.of("2.4",false,"5.8",false),31000);
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",0,Map.of("5.8",false,"2.4",false),32000);
        assertThat(events("STATE_CHANGED")).isZero();
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",1,Map.of("2.4",true,"5.8",false),33000);
        protocol.saveCountermeasureState(id,"LITTLE_ENDIAN",1,Map.of("5.8",false,"2.4",true),34000);
        assertThat(events("STATE_CHANGED")).isEqualTo(1);
        protocol.saveRadarRegisters(id,"work-1",Map.of("work_mode_code",1),35000);
        protocol.saveRadarRegisters(id,"work-2",Map.of("work_mode_code",1),36000);
        protocol.saveRadarRegisters(id,"work-3",Map.of("work_mode_code",2),37000);
        protocol.saveRadarRegisters(id,"work-4",Map.of("work_mode_code",2),38000);
        assertThat(events("STATE_CHANGED")).isEqualTo(2);
        protocol.saveRadarRegisters(id,"work-missing",Map.of("frequency_code",10),39000);
        protocol.saveRadarRegisters(id,"work-new-baseline",Map.of("work_mode_code",0),40000);
        assertThat(events("STATE_CHANGED")).isEqualTo(2);
        var messages=jdbc.queryForList("SELECT message FROM device_event_log WHERE device_id=? AND event_type='STATE_CHANGED' ORDER BY event_seq",String.class,id);
        assertThat(messages.get(0)).contains("继电器通道","2.4=关","2.4=开");
        assertThat(messages.get(1)).contains("雷达工作模式码 1","雷达工作模式码 2");
    }

    @Test void supervisorPersistsFailureAfterParsedFrameAndNeverDowngradesSuccessfulInbox() {
        var failingIngest=mock(LiveRadarFrameIngestService.class);
        when(failingIngest.ingestRegisters(anyString(),anyString(),anyString(),anyString(),anyMap(),any(byte[].class),anyLong()))
                .thenThrow(new IllegalStateException("register persistence failed"));
        var supervisor=new LiveDeviceSupervisor(null,protocol,failingIngest,null,null,new ObjectMapper(),null,new AppProperties());
        String key=id+":registers:monitor-session:91";
        diagnosticKeys.add(key);
        byte[] raw=new byte[]{1,2,3};
        try {
            ReflectionTestUtils.invokeMethod(supervisor,"handleRadarFrame",source,id,"DEV-MOCK-001",null,"monitor-session",
                    new RadarV300Codec.RadarFrame(RadarV300Codec.COMMAND_GET_REGISTER,91,new byte[4],false),raw,31000L);
            assertThat(jdbc.queryForObject("SELECT processing_status FROM inbox_message WHERE protocol_message_key=?",String.class,key)).isEqualTo("FAILED");
            assertThat(protocol.insertInbox(source,id,key,raw,31000)).isTrue();
            protocol.inboxProcessed(id,key,31000);
            protocol.recordInboxFailure(source,id,key,raw,"concurrent attempt failed",31001);
            assertThat(jdbc.queryForObject("SELECT processing_status FROM inbox_message WHERE source=? AND source_msg_id=?",String.class,"live-device:"+id,key)).isEqualTo("PROCESSED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE protocol_message_key=? AND processing_status='FAILED'",Long.class,key)).isEqualTo(2);
        } finally { supervisor.close(); }
    }

    @Test void sameRegisterFrameWithinSessionIsDeduplicatedButNewSessionMayReuseFrameId() {
        var supervisor=new LiveDeviceSupervisor(null,protocol,ingest,null,null,new ObjectMapper(),null,new AppProperties());
        var frame=new RadarV300Codec.RadarFrame(RadarV300Codec.COMMAND_GET_REGISTER,92,new byte[4],false);
        try {
            ReflectionTestUtils.invokeMethod(supervisor,"handleRadarFrame",source,id,"DEV-MOCK-001",null,"session-a",frame,new byte[]{8},31000L);
            ReflectionTestUtils.invokeMethod(supervisor,"handleRadarFrame",source,id,"DEV-MOCK-001",null,"session-a",frame,new byte[]{8},32000L);
            assertThat(jdbc.queryForObject("SELECT parameters_count FROM device_report_window WHERE device_id=?",Long.class,id)).isEqualTo(1);
            ReflectionTestUtils.invokeMethod(supervisor,"handleRadarFrame",source,id,"DEV-MOCK-001",null,"session-b",frame,new byte[]{8},33000L);
            assertThat(jdbc.queryForObject("SELECT parameters_count FROM device_report_window WHERE device_id=?",Long.class,id)).isEqualTo(2);
        } finally { supervisor.close(); }
    }
}
