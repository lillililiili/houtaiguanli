package com.uav.lowaltitude.modules.device.application;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** 超时更新由接入层负责；这里只把已确定的离线事实承接为独立运维事件。 */
@Service
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttConnectivityIncidentJob {
    private final DeviceRepository repository;
    private final AppClock clock;

    public MqttConnectivityIncidentJob(DeviceRepository repository, AppClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.mqtt.reconcile-millis:1000}")
    @Transactional
    public void reconcile() {
        long now = clock.nowMillis();
        for (var row : repository.lockMqttConnectivity()) {
            if (!"OFFLINE".equals(row.get("connectivity"))) continue;
            String id = (String) row.get("device_id");
            boolean simulated = !"live".equals(row.get("source_mode"));
            if (repository.openMqttIncident(id, now, simulated)) {
                repository.addEvent(UUID.randomUUID().toString(), id, "INCIDENT_OPENED", "WARN",
                        "MQTT 心跳超时，已创建异常事件", now, simulated);
            }
        }
    }
}
