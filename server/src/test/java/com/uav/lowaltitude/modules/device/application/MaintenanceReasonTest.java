package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.device.application.DeviceService.Incident;
import com.uav.lowaltitude.modules.flight.application.FlightDeviceCheckService.DeviceRow;

/** CDX-P09：待办的异常说明先写设备现在的问题，已恢复上报的旧心跳超时只注一句待核验。 */
class MaintenanceReasonTest {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String HEARTBEAT="有效 MQTT 心跳超时；请检查设备电源、网络和发布进程";
    private final long now=at(2026,10,8,16,30,0);

    @Test void selfReportedFaultComesFirstAndARecoveredHeartbeatTimeoutIsOnlyNoted() {
        long reported=at(2026,10,8,16,21,18);
        assertThat(MaintenanceReason.text(row("ONLINE","BAD",reported,reported,heartbeat(null)),false,now))
                .isEqualTo("设备自报故障（工作状态：故障），状态上报时间 16:21:18；此前心跳超时，已恢复上报，待核验");
    }
    @Test void offlineDeviceStatesItsLastValidReportAndKeepsTheOpenHeartbeatRecord() {
        assertThat(MaintenanceReason.text(row("OFFLINE","BAD",at(2026,10,8,16,20,0),at(2026,10,8,16,20,5),heartbeat(null)),false,now))
                .isEqualTo("设备离线：最近一次有效上报 16:20:05；"+HEARTBEAT);
        assertThat(MaintenanceReason.text(row("OFFLINE","UNKNOWN",null,null),false,now)).isEqualTo("设备离线：尚未收到有效上报");
    }
    @Test void reportsFromAnotherDayCarryTheDateInBeijingTime() {
        assertThat(MaintenanceReason.text(row("OFFLINE","GOOD",at(2026,10,7,23,59,58),null),false,now))
                .isEqualTo("设备离线：最近一次有效上报 10月7日 23:59:58");
        // 北京时间 00:30 在 UTC 还是前一天，仍算当天。
        assertThat(MaintenanceReason.text(row("ONLINE","BAD",null,at(2026,10,8,0,30,0)),false,now))
                .isEqualTo("设备自报故障（工作状态：故障），状态上报时间 00:30:00");
    }
    @Test void otherCurrentProblemsAreNamedPlainly() {
        long reported=at(2026,10,8,16,21,18);
        assertThat(MaintenanceReason.text(row("ONLINE","DEGRADED",reported,reported),false,now))
                .isEqualTo("设备自报运行不稳定，状态上报时间 16:21:18");
        assertThat(MaintenanceReason.text(row("ABNORMAL","GOOD",reported,reported),false,now))
                .isEqualTo("设备状态异常，状态上报时间 16:21:18");
        assertThat(MaintenanceReason.text(row("ONLINE","GOOD",reported,reported),true,now))
                .isEqualTo("设备上报告警，状态上报时间 16:21:18");
        assertThat(MaintenanceReason.text(row("DISABLED","GOOD",reported,reported,heartbeat(null)),false,now))
                .isEqualTo("设备已停用；"+HEARTBEAT);
    }
    @Test void closedRecordsAreLeftOutAndRepeatedTextIsWrittenOnce() {
        long reported=at(2026,10,8,16,21,18);
        assertThat(MaintenanceReason.text(row("ONLINE","GOOD",reported,reported,heartbeat(reported)),true,now))
                .isEqualTo("设备上报告警，状态上报时间 16:21:18");
        assertThat(MaintenanceReason.text(row("ONLINE","GOOD",reported,reported,heartbeat(null),heartbeat(null),
                incident("COMMAND_TIMEOUT","设备未响应控制指令")),false,now))
                .isEqualTo("此前心跳超时，已恢复上报，待核验；设备未响应控制指令");
    }
    @Test void unknownStateDoesNotClaimTheDeviceRecovered() {
        assertThat(MaintenanceReason.text(row("UNKNOWN","UNKNOWN",null,null,heartbeat(null)),false,now)).isEqualTo(HEARTBEAT);
        assertThat(MaintenanceReason.text(row("ONLINE","GOOD",now,now),false,now)).isEqualTo(MaintenanceReason.UNEXPLAINED);
    }

    private static long at(int y,int mo,int d,int h,int mi,int s) {
        return LocalDateTime.of(y,mo,d,h,mi,s).atZone(ZONE).toInstant().toEpochMilli();
    }
    private static DeviceRow row(String connectivity,String health,Long heartbeatAt,Long observedAt,Incident... incidents) {
        return new DeviceRow("device-1","雷达-1",true,BigDecimal.ONE,connectivity,health,heartbeatAt,observedAt,true,true,List.of(incidents));
    }
    private static Incident heartbeat(Long closedAt) {
        return new Incident("incident-"+System.nanoTime(),"INC-1","device-1","D-1","雷达-1","MQTT_HEARTBEAT_TIMEOUT","HIGH","DETECTED",
                at(2026,10,8,15,0,0),HEARTBEAT,closedAt,null,true,null);
    }
    private static Incident incident(String type,String reason) {
        return new Incident("incident-"+System.nanoTime(),"INC-2","device-1","D-1","雷达-1",type,"MEDIUM","DETECTED",
                at(2026,10,8,15,0,0),reason,null,null,true,null);
    }
}
