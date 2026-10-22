package com.uav.lowaltitude.modules.prediction.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.device.application.DeviceStopCoordinator;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.application.DisposalExecutionGateway;
import com.uav.lowaltitude.modules.disposal.application.EmergencyStopService;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

class PredictionSafetyStopTest {
    private final String event=UUID.randomUUID().toString(),auth=UUID.randomUUID().toString(),device=UUID.randomUUID().toString();
    private final Instant now=Instant.parse("2026-10-20T11:00:00Z");
    private PredictionEventGate gate;
    private EmergencyStopRepository stops;
    private DisposalRepository repository;
    private DeviceStopCoordinator commands;
    private DisposalExecutionGateway gateway;
    private DeviceRepository devices;
    private EmergencyStopService service;

    @BeforeEach void setup(){
        gate=mock(PredictionEventGate.class);stops=mock(EmergencyStopRepository.class);
        repository=mock(DisposalRepository.class);commands=mock(DeviceStopCoordinator.class);
        gateway=mock(DisposalExecutionGateway.class);devices=mock(DeviceRepository.class);
        var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service=new EmergencyStopService(mock(AccessControlService.class),mock(UavEventRepository.class),
                stops,repository,devices,gateway,commands,new AppClock(Clock.fixed(now,ZoneOffset.UTC)),
                mock(AuditService.class),new ObjectMapper(),tx);
        service.setPredictionEventGate(gate);
        when(gate.isPrediction(event)).thenReturn(true);
        when(stops.authorizationIds(event)).thenReturn(List.of(auth));
        // The repository returns null when no stop exists; Mockito otherwise returns an empty Map.
        when(stops.stop(eq(event),anyString())).thenReturn(null);
        when(devices.find(device)).thenReturn(Map.of("name","设备-"+device,"source_mode","live","simulated",true,
                "protocol_code",DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0));
        when(repository.transition(eq(auth),anyLong(),eq("STOPPED"),any(),isNull(),eq("STOPPED_BY_PREDICTION"),anyString())).thenReturn(1);
    }

    @ParameterizedTest @ValueSource(strings={"REQUESTED","APPROVED"})
    void unstartedAuthorizationIsPermanentlyRevokedWithoutClaimingPhysicalStop(String state){
        when(repository.lockForSystem(auth)).thenReturn(row(state,null));
        service.stopForPrediction(event,"连续新实测已不再预计进入",7);
        verify(commands).cancelAuthorizationStarts(auth,now.toEpochMilli());
        verify(stops).cover(anyString(),eq(auth));
        verify(repository).transition(eq(auth),eq(2L),eq("STOPPED"),any(),isNull(),eq("STOPPED_BY_PREDICTION"),anyString());
        verify(stops).task(anyString(),eq(device),eq(auth),anyString(),anyString(),eq("live"),eq(true),
                isNull(),eq("NOT_REQUIRED"),contains("永久撤销"));
        verifyNoInteractions(gateway);
    }

    @Test void unknownStopsRunningDeviceWithSystemOriginAndWaitsForActualReceipt(){
        when(repository.lockForSystem(auth)).thenReturn(row("EXECUTING","start-"+UUID.randomUUID()));
        when(gateway.stop4ch(isNull(),eq(device),anyString(),eq(auth),anyString()))
                .thenReturn(new DisposalExecutionGateway.Accepted("stop-command"));
        service.stopForPrediction(event,"观测已过期",8);
        verify(stops).insertPredictionStop(anyString(),eq(event),eq(now.toEpochMilli()),contains("观测已过期"));
        verify(stops).task(anyString(),eq(device),eq(auth),anyString(),anyString(),anyString(),eq(true),
                eq("stop-command"),eq("QUEUED"),contains("真实停止回执"));
        verify(repository,never()).transition(eq(auth),anyLong(),eq("COMPLETED"),any(),any(),any(),any());
        when(stops.covered(auth)).thenReturn(true);
        service.stopForPrediction(event,"观测恢复，旧授权不可恢复",9);
        verify(gateway,times(1)).stop4ch(isNull(),eq(device),anyString(),eq(auth),anyString());
    }

    @Test void failureToSendStopNeverRestoresAuthorizationOrReportsDeviceOff(){
        when(repository.lockForSystem(auth)).thenReturn(row("EXECUTING","start-"+UUID.randomUUID()));
        when(gateway.stop4ch(isNull(),eq(device),anyString(),eq(auth),anyString()))
                .thenReturn(new DisposalExecutionGateway.Rejected("DEVICE_OFFLINE","DEVICE_OFFLINE","设备离线，停止尚未确认"));
        service.stopForPrediction(event,"观测不可靠",11);
        verify(stops).task(anyString(),eq(device),eq(auth),anyString(),anyString(),anyString(),anyBoolean(),
                isNull(),eq("OFFLINE"),contains("尚未确认"));
        verify(commands).cancelAuthorizationStarts(auth,now.toEpochMilli());
        verify(stops).cover(anyString(),eq(auth));
        verify(repository).transition(eq(auth),eq(2L),eq("STOPPED"),any(),isNull(),eq("STOPPED_BY_PREDICTION"),anyString());
    }

    @Test void nonPredictionCannotUseInternalSystemStop(){
        when(gate.isPrediction(event)).thenReturn(false);
        assertThatThrownBy(()->service.stopForPrediction(event,"reason",1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(stops,repository,commands,gateway);
    }

    @Test void episodePauseDoesNotStopIndependentlyAuthorizedObservedEvent(){
        String actual=UUID.randomUUID().toString();
        when(gate.linkedEvents(event)).thenReturn(List.of(event,actual));
        var response=new PredictionResponseService(gate,service);
        when(repository.lockForSystem(auth)).thenReturn(row("APPROVED",null));
        response.pause(event,"预测已解除",12);
        verify(stops).lockEvent(event);
        verify(stops,never()).lockEvent(actual);
    }

    @Test void committedStopForSameRevisionIsNotIssuedAgain(){
        when(repository.lockForSystem(auth)).thenReturn(row("EXECUTING","start-"+UUID.randomUUID()));
        when(stops.stop(eq(event),anyString())).thenReturn(Map.of("stop_id",UUID.randomUUID().toString()));
        service.stopForPrediction(event,"同一生命周期版本重试",20);
        verify(stops,never()).insertPredictionStop(anyString(),anyString(),anyLong(),anyString());
        verify(repository,never()).transition(anyString(),anyLong(),anyString(),any(),any(),any(),any());
        verifyNoInteractions(commands,gateway);
    }

    private AuthorizationRow row(String status,String execution){
        var at=now.atOffset(ZoneOffset.UTC);
        return new AuthorizationRow(auth,"AUTH", "COUNTERMEASURE","UAV_EVENT",event,UUID.randomUUID().toString(),device,
                "COUNTERMEASURE_4CH","",UUID.randomUUID().toString(),at,null,null,null,at,at.plusSeconds(60),
                status,execution,null,null,"policy","org","district","live",2,null,null,"DIRECT");
    }
}
