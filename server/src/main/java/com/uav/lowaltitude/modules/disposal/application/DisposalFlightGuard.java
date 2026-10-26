package com.uav.lowaltitude.modules.disposal.application;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.modules.flightavoidance.application.FlightAvoidanceService;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck;
import com.uav.lowaltitude.modules.flightavoidance.infrastructure.FlightAvoidanceRepository;

/**
 * 反制进行中每 5 秒查一次附近航班（2026-10-26 用户确认）：有航班飞进禁止反制范围时系统自动停止反制，
 * 告警上写明原因并提醒值班人员。只看提醒范围的航班不停止。没有在用的航班接收机时什么都不做。
 *
 * 只处理无人机告警上的反制；对目标直接派发的只有驱离，驱离不设禁止反制范围。
 */
@Component
public class DisposalFlightGuard {
    private static final Logger log = LoggerFactory.getLogger(DisposalFlightGuard.class);

    private final FlightAvoidanceRepository flights;
    private final FlightAvoidanceService checks;
    private final EmergencyStopService stops;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public DisposalFlightGuard(FlightAvoidanceRepository flights, FlightAvoidanceService checks, EmergencyStopService stops,
            PlatformTransactionManager transactions, @Value("${app.disposal.flight-guard.enabled:true}") boolean enabled) {
        this.flights = flights; this.checks = checks; this.stops = stops;
        this.tx = new TransactionTemplate(transactions);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.disposal.flight-guard.poll-millis:5000}")
    public void scheduledTick() {
        if (enabled) tick();
    }

    /** 一条出错不耽误别的。返回本轮停止的条数。 */
    public int tick() {
        if (!checks.hasActiveReceivers()) return 0;
        int stopped = 0;
        for (Map<String, Object> run : flights.executingCounters()) {
            if (!"UAV_EVENT".equals(run.get("subject_kind"))) continue;
            String eventId = String.valueOf(run.get("subject_id")), deviceId = String.valueOf(run.get("device_id"));
            String authorizationId = String.valueOf(run.get("authorization_id"));
            try {
                Boolean done = tx.execute(status -> {
                    FlightCheck.Result result = checks.whileRunning(eventId, authorizationId, deviceId, FlightAvoidanceService.modeOf(
                            String.valueOf(run.get("action_type")), (String) run.get("countermeasure_mode")));
                    if (!result.blocked()) return false;
                    return stops.stopForAircraft(eventId, deviceId, authorizationId + ":" + result.policyVersion()
                            + ":" + (result.aircraft().isEmpty() ? "" : result.aircraft().get(0).icao24()),
                            FlightAvoidanceService.stopNote(result));
                });
                if (Boolean.TRUE.equals(done)) stopped++;
            } catch (RuntimeException ex) {
                log.warn("flight guard did not stop authorization {} this round: {}", authorizationId, ex.toString());
            }
        }
        return stopped;
    }
}
