package com.uav.lowaltitude.modules.directory.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceCaseRepository;
import com.uav.lowaltitude.modules.device.application.DeviceMaintenanceService;
import com.uav.lowaltitude.modules.device.application.DeviceRecoveryService;
import com.uav.lowaltitude.platform.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Additional discovery boundaries share the independent API fixtures; no existing demo objects are used. */
class DeviceMaintenanceDiscoveryApiTest extends DeviceMaintenanceCaseApiTest {
    @Autowired DeviceMaintenanceCaseRepository cases;
    @Autowired DeviceMaintenanceService maintenance;
    @Autowired DeviceRecoveryService recovery;

    @Test void explicitHealthFaultWithNoPlanOrIncidentIsDiscoveredOnce() throws Exception {
        assertThat(count("device_incident","device_id",device)).isZero();
        assertThat(cases.undispatchedDevices()).contains(device);
        AuthContext.clear();maintenance.discover(device);maintenance.discover(device);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(1);
        var created=jdbc.queryForMap("SELECT plan_id,reason,reported_by,workflow_version FROM ops_device_maintenance_task WHERE device_id=?",device);
        assertThat(created.get("plan_id")).isNull();assertThat(created.get("reported_by")).isNull();
        assertThat(created.get("reason")).isEqualTo("设备健康异常且仍有设备告警");
        assertThat(cases.undispatchedDevices()).doesNotContain(device);
    }

    @ParameterizedTest @ValueSource(strings={"unknown","missing","stale","future","source","simulation"})
    void unprovenStateCannotProduceAnAutomaticFaultCase(String variant) {
        jdbc.update("UPDATE ops_device_state SET has_alarm=FALSE WHERE device_id=?",device);
        switch(variant){
            case "unknown" -> jdbc.update("UPDATE ops_device_state SET health_code='UNKNOWN' WHERE device_id=?",device);
            case "missing" -> jdbc.update("UPDATE ops_device_state SET health_observed_at=NULL WHERE device_id=?",device);
            case "stale" -> jdbc.update("UPDATE ops_device_state SET health_observed_at=? WHERE device_id=?",now-300001,device);
            case "future" -> jdbc.update("UPDATE ops_device_state SET health_observed_at=? WHERE device_id=?",now+1,device);
            case "source" -> jdbc.update("UPDATE ops_device_state SET health_source_mode='replay' WHERE device_id=?",device);
            default -> jdbc.update("UPDATE ops_device_state SET health_simulated=TRUE WHERE device_id=?",device);
        }
        assertThat(cases.undispatchedDevices()).doesNotContain(device);
        AuthContext.clear();maintenance.discover(device);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isZero();
    }

    @Test void alarmWithoutBadHealthCanProduceAnIndependentCase() {
        jdbc.update("UPDATE ops_device_state SET health_code='GOOD' WHERE device_id=?",device);
        AuthContext.clear();maintenance.discover(device);
        assertThat(jdbc.queryForObject("SELECT reason FROM ops_device_maintenance_task WHERE device_id=?",String.class,device))
                .isEqualTo("设备仍有告警");
    }

    @Test void completedCasesCannotBeRediscoveredFromOldFactsButNewFaultCreatesNewCase() throws Exception {
        String task=create();long oldFault=now;healthy();auto(task);action(task,"CONFIRM_COMPLETE",version(task),id());
        jdbc.update("UPDATE ops_device_state SET health_code='BAD',has_alarm=TRUE,health_observed_at=?,alarm_observed_at=? WHERE device_id=?",oldFault,oldFault,device);
        assertThat(cases.undispatchedDevices()).doesNotContain(device);
        AuthContext.clear();maintenance.discover(device);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(1);
        fault();assertThat(cases.undispatchedDevices()).contains(device);
        maintenance.discover(device);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(2);
    }

    @Test void changedSourceDoesNotMergeIntoOlderUnfinishedCase() throws Exception {
        String old=create();
        jdbc.update("UPDATE ops_device SET source_mode='replay',simulated=TRUE WHERE device_id=?",device);
        advance(1000);
        jdbc.update("UPDATE ops_device_state SET health_source_mode='replay',alarm_source_mode='replay',health_simulated=TRUE,alarm_simulated=TRUE,health_observed_at=?,alarm_observed_at=? WHERE device_id=?",now,now,device);
        assertThat(cases.undispatchedDevices()).contains(device);
        AuthContext.clear();maintenance.discover(device);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT workflow_source_mode FROM ops_device_maintenance_task WHERE task_id=?",String.class,old)).isEqualTo("live");
    }

    @Test void legacyUnfinishedReportRemainsSeparateFromNewCase() throws Exception {
        String old=create();
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_version=1,active_key=? WHERE task_id=?","legacy:"+old,old);
        assertThat(cases.undispatchedDevices()).contains(device);
        AuthContext.clear();maintenance.discover(device);
        assertThat(count("ops_device_maintenance_task","device_id",device)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT workflow_version FROM ops_device_maintenance_task WHERE task_id=?",Integer.class,old)).isEqualTo(1);
    }

    @Test void knownIncidentFromAnotherFrozenSourcePreventsCrossSourceRecovery() throws Exception {
        String task=create(),incident=incident();cases.attachIncidents(task);
        // A source migration with the same simulated flag is also distinct provenance.
        jdbc.update("UPDATE ops_device_maintenance_task SET workflow_source_mode='replay' WHERE task_id=?",task);
        healthy();
        var check=recovery.evaluate(device,"live",null,false,now-2000);
        assertThat(check.result()).isEqualTo("UNKNOWN");
        assertThat(check.reasonCodes()).contains("INCIDENT_SOURCE_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT stage FROM device_incident WHERE incident_id=?",String.class,incident)).isEqualTo("PENDING");
    }
}
