package com.uav.lowaltitude.modules.device.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeviceRecoveryServiceTest {
    final DeviceRepository repository=mock(DeviceRepository.class);
    final AuditService audit=mock(AuditService.class);
    final String device=UUID.randomUUID().toString(),source=UUID.randomUUID().toString();
    final long now=Instant.parse("2026-05-11T16:12:13Z").toEpochMilli(),since=now-20_000;
    final DeviceRecoveryService recovery=new DeviceRecoveryService(repository,
            new AppClock(Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)),audit,new ObjectMapper(),300,15000);
    Map<String,Object> state;

    @BeforeEach void healthy() {
        state=new HashMap<>();
        state.put("device_id",device);state.put("enabled",true);state.put("source_enabled",true);
        state.put("source_id",source);state.put("source_mode","replay");state.put("simulated",true);
        state.put("mqtt_heartbeat",true);state.put("connectivity","ONLINE");state.put("health_code","GOOD");state.put("has_alarm",false);
        state.put("last_heartbeat_at",now-1000);state.put("observed_at",now-1000);
        for(String field:List.of("health","alarm")) {
            state.put(field+"_observed_at",now-5000);state.put(field+"_source_id",source);
            state.put(field+"_source_mode","replay");state.put(field+"_simulated",true);
        }
        when(repository.findForRecovery(device)).thenReturn(state);
        when(repository.openIncidentsForRecovery(device,false)).thenReturn(List.of());
    }
    DeviceRecoveryService.Evaluation check(){return recovery.evaluate(device,"replay",source,true,since);}

    @Test void freshExplicitFactsPassWithoutAnyUserOrReboot() {
        var result=check();assertThat(result.passed()).isTrue();assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.currentState().get("health_observed_at")).isEqualTo(now-5000);
        verifyNoInteractions(audit);
        verify(repository,never()).closeIncident(anyString(),anyString(),anyLong());
    }
    @Test void groupedCasesShareDeviceReadsButKeepEachAnomalyBoundaryAndSource() {
        when(repository.incidentRecoverySources(device)).thenReturn(List.of());
        var facts=recovery.capture(device);
        for(int i=0;i<450;i++)assertThat(recovery.evaluate(facts,"replay",source,true,since-i).passed()).isTrue();
        assertThat(recovery.evaluate(facts,"replay",source,true,now-3000).reasonCodes())
                .contains("HEALTH_BEFORE_ANOMALY","ALARM_BEFORE_ANOMALY");
        assertThat(recovery.evaluate(facts,"replay",UUID.randomUUID().toString(),true,since).reasonCodes())
                .contains("SOURCE_CHANGED","HEALTH_SOURCE_MISMATCH","ALARM_SOURCE_MISMATCH");
        assertThat(recovery.evaluate(facts,"replay",source,false,since).passed()).isFalse();
        verify(repository,times(1)).findForRecovery(device);
        verify(repository,times(1)).openIncidentsForRecovery(device,false);
        verify(repository,times(1)).incidentRecoverySources(device);
        verify(repository,times(1)).hasActiveWork(device);
        verify(repository,times(1)).hasRecoveryBlockingWork(device,now-15000,now);
        verifyNoMoreInteractions(repository);
    }
    @Test void groupedFactsRetainFrozenIncidentSourceAndActiveOperationGuards() {
        when(repository.incidentRecoverySources(device)).thenReturn(List.of(
                Map.of("source_id",UUID.randomUUID().toString(),"source_mode","replay","simulated",true)));
        when(repository.hasActiveWork(device)).thenReturn(true);
        when(repository.hasRecoveryBlockingWork(device,now-15000,now)).thenReturn(true);
        var result=recovery.evaluate(recovery.capture(device),"replay",source,true,since);
        assertThat(result.result()).isEqualTo("UNKNOWN");
        assertThat(result.reasonCodes()).contains("INCIDENT_SOURCE_MISMATCH","ACTIVE_DEVICE_WORK");
    }
    @Test void freshHeartbeatCannotRenewOldHealthOrAlarm() {
        state.put("last_heartbeat_at",now);state.put("observed_at",now);
        state.put("health_observed_at",now-301_000);state.put("alarm_observed_at",now-301_000);
        var result=recovery.evaluate(device,"replay",source,true,now-600_000);
        assertThat(result.result()).isEqualTo("UNKNOWN");
        assertThat(result.reasonCodes()).contains("HEALTH_STALE","ALARM_STALE");
    }
    @Test void inferredGoodWithoutExplicitEvidenceIsUnknown() {
        state.remove("health_observed_at");state.remove("alarm_observed_at");
        assertThat(check().result()).isEqualTo("UNKNOWN");
        assertThat(check().reasonCodes()).contains("HEALTH_MISSING","ALARM_MISSING");
    }
    @ParameterizedTest @ValueSource(strings={"connectivity","health_code","has_alarm"})
    void missingStateIsUnknownEvenWhenEvidenceTimesAreFresh(String field) {
        state.remove(field);
        assertThat(check().result()).isEqualTo("UNKNOWN");
    }
    @ParameterizedTest @ValueSource(strings={"connectivity","health_code"})
    void explicitUnknownStateCannotBeClassifiedAsKnownFailure(String field) {
        state.put(field,"UNKNOWN");
        assertThat(check().result()).isEqualTo("UNKNOWN");
    }
    @ParameterizedTest @ValueSource(strings={"connectivity","health_code","has_alarm"})
    void explicitAbnormalStateWithFreshEvidenceIsFailure(String field) {
        state.put(field,field.equals("has_alarm")?true:field.equals("connectivity")?"OFFLINE":"BAD");
        assertThat(check().result()).isEqualTo("FAIL");
    }
    @ParameterizedTest @ValueSource(strings={"health","alarm","last_heartbeat"})
    void evidenceMustFollowTheLatestAnomalyAndCannotBeInTheFuture(String field) {
        String key=field.equals("last_heartbeat")?"last_heartbeat_at":field+"_observed_at";
        state.put(key,since);assertThat(check().result()).isEqualTo("UNKNOWN");
        state.put(key,now+1);assertThat(check().result()).isEqualTo("UNKNOWN");
    }
    @Test void mqttOnlineLabelDoesNotOverrideHeartbeatTimeout() {
        state.put("last_heartbeat_at",now-30_001);
        var result=recovery.evaluate(device,"replay",source,true,now-60_000);
        assertThat(result.reasonCodes()).contains("HEARTBEAT_STALE");
    }
    @ParameterizedTest @ValueSource(strings={"catalog","health","alarm","simulation"})
    void sourceChangesCannotBorrowHealthFromAnotherSource(String part) {
        if(part.equals("simulation"))state.put("health_simulated",false);
        else state.put(part.equals("catalog")?"source_id":part+"_source_id",UUID.randomUUID().toString());
        assertThat(check().passed()).isFalse();
    }
    @Test void runningDeviceOrPendingStopPreventsRecoveryEvenAfterStartCommandFinished() {
        when(repository.hasActiveWork(device)).thenReturn(true);
        when(repository.hasRecoveryBlockingWork(device,now-15000,now)).thenReturn(true);
        assertThat(check().reasonCodes()).contains("ACTIVE_DEVICE_WORK");
        assertThat(check().result()).isEqualTo("FAIL");
    }
    @Test void normalAutomaticTrackingIsRecordedAsActiveButDoesNotBlockRecovery() {
        when(repository.hasActiveWork(device)).thenReturn(true);
        var result=check();
        assertThat(result.passed()).isTrue();
        assertThat(result.currentState()).containsEntry("active_work",true).containsEntry("recovery_blocking_work",false);
        assertThat(result.reason()).doesNotContain("没有未结束操作");
    }
    @Test void newIncidentInvalidatesEarlierGoodEvidence() {
        when(repository.openIncidentsForRecovery(device,false)).thenReturn(List.of(
                Map.of("incident_id",UUID.randomUUID().toString(),"simulated",true,"detected_at",now-3000)));
        assertThat(check().reasonCodes()).contains("HEALTH_BEFORE_ANOMALY","ALARM_BEFORE_ANOMALY");
    }
    @Test void incidentFrozenToAnotherCaseSourceCannotBorrowCurrentDeviceEvidence() {
        when(repository.hasIncidentSourceConflict(device,"replay",source,true)).thenReturn(true);
        assertThat(check().result()).isEqualTo("UNKNOWN");
        assertThat(check().reasonCodes()).contains("INCIDENT_SOURCE_MISMATCH");
        assertThatThrownBy(()->recovery.recoverIncidents(device,"replay",source,true,since))
                .isInstanceOf(ApiException.class);
        verify(repository,never()).closeIncident(anyString(),anyString(),anyLong());
    }
    @Test void automaticRecoveryClosesOnlyProvenIncidentsAndAuditsSystemActor() {
        String incident=UUID.randomUUID().toString();
        var row=Map.<String,Object>of("incident_id",incident,"stage","PENDING","simulated",true,"detected_at",since);
        when(repository.openIncidentsForRecovery(device,false)).thenReturn(List.of(row));
        when(repository.openIncidentsForRecovery(device,true)).thenReturn(List.of(row));
        when(repository.closeIncident(incident,"PENDING",now)).thenReturn(1);
        assertThat(recovery.recoverIncidents(device,"replay",source,true,since)).containsExactly(incident);
        verify(repository).insertSystemRecoveryCheck(anyString(),eq(incident),eq(now),eq(DeviceRecoveryService.RULE_VERSION),anyString(),anyString());
        verify(audit).record(isNull(),eq("SYSTEM"),eq("device_incident_auto_recovered"),eq("device_incident"),eq(incident),anyString(),isNull());
    }
    @Test void unhealthyOrUnknownFactsNeverWriteAutomaticRecovery() {
        state.put("has_alarm",true);
        assertThatThrownBy(()->recovery.recoverIncidents(device,"replay",source,true,since))
                .isInstanceOf(ApiException.class)
                .extracting(error->((ApiException)error).getCode()).isEqualTo("MAINTENANCE_RECOVERY_BLOCKED");
        verify(repository,never()).closeIncident(anyString(),anyString(),anyLong());verifyNoInteractions(audit);
    }
    @Test void evidenceExpiringBetweenDisplayAndTransactionalRecoveryFailsClosed() {
        AppClock moving=mock(AppClock.class);
        when(moving.nowMillis()).thenReturn(now,now+300_001);
        var service=new DeviceRecoveryService(repository,moving,audit,new ObjectMapper(),300,15000);
        assertThat(service.evaluate(device,"replay",source,true,since).passed()).isTrue();
        assertThatThrownBy(()->service.recoverIncidents(device,"replay",source,true,since))
                .isInstanceOf(ApiException.class)
                .extracting(error->((ApiException)error).getCode()).isEqualTo("MAINTENANCE_RECOVERY_BLOCKED");
        verify(repository,never()).closeIncident(anyString(),anyString(),anyLong());verifyNoInteractions(audit);
    }
}
