package com.uav.lowaltitude.modules.alarm.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeEligibility;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.application.DisposalExecutionGateway;
import com.uav.lowaltitude.modules.disposal.application.AutomaticCounterAuthorization;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository.RunRow;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationInsert;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 反制规则通过后，在待反制阶段自动发起一次直接反制。
 * 不关闭人工按钮；证据、急停、策略上限和设备条件不满足时本轮跳过，下一轮再检查。
 * 设备正忙（指令在途，或正为别的反制开着）时这一轮什么都不建，等它空出来再发；
 * 以前设备忙时建好、没发出去的那条，设备空了就接着发（2026-10-08 第二批复验 S2）。
 * 以前的自动反制启动指令都没发出去时再发，一起事件最多 3 次（见 {@link CounterHistory}）。
 */
@Service
public class AlarmRuleCounter {
    private UavRiskCaseIdentity riskCases;
    private com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService responseOrder;
    @org.springframework.beans.factory.annotation.Autowired
    public void riskCases(UavRiskCaseIdentity cases,com.uav.lowaltitude.modules.automationrule.application.ResponseOrderService order){this.riskCases=cases;this.responseOrder=order;}
    private com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPredictionEventGate(com.uav.lowaltitude.modules.prediction.application.PredictionEventGate predictions) { this.predictions=predictions; }
    private com.uav.lowaltitude.modules.flightavoidance.application.FlightAvoidanceService flights;
    @org.springframework.beans.factory.annotation.Autowired
    public void setFlightAvoidance(com.uav.lowaltitude.modules.flightavoidance.application.FlightAvoidanceService flights) { this.flights = flights; }
    private static final Logger log = LoggerFactory.getLogger(AlarmRuleCounter.class);
    private static final AccessDecision SCOPE = new AccessDecision("system:auto-counter", ScopeMode.ALL);
    private static final String REASON = "反制规则已满足，系统自动发起。";
    private static final String NOTE = "反制规则已满足，系统按直接授权发起，未指定审批人。";
    private static final String QUEUED_REASON = "反制设备空出来了，系统接着下发这条排队的自动反制。";

    private final UavEventRepository events;
    private final UavAdvisoryRepository advisory;
    private final AutomaticCounterNotificationGate notifications;
    private final DisposalRepository repository;
    private final DisposalPolicyRepository policies;
    private final DisposalExecutionGateway gateway;
    private final EmergencyStopRepository emergencyStops;
    private final DeviceRepository devices;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;
    private final AutomationRuntimeEligibility eligibility;
    private final AutomaticCounterAuthorization automatic;

    public AlarmRuleCounter(UavEventRepository events, UavAdvisoryRepository advisory, AutomaticCounterNotificationGate notifications,
            DisposalRepository repository, DisposalPolicyRepository policies, DisposalExecutionGateway gateway,
            EmergencyStopRepository emergencyStops, DeviceRepository devices, AuditService audit, AppClock clock,
            ObjectMapper json, AutomationRuntimeEligibility eligibility, AutomaticCounterAuthorization automatic) {
        this.events = events;
        this.advisory = advisory;
        this.notifications = notifications;
        this.repository = repository;
        this.policies = policies;
        this.gateway = gateway;
        this.emergencyStops = emergencyStops;
        this.devices = devices;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
        this.eligibility = eligibility;
        this.automatic = automatic;
    }

    @Transactional
    public void launchIfPassed(String eventId, String runId) {
        if(riskCases!=null&&eventId!=null)riskCases.lockCases(eventId);
        if (predictions != null && predictions.blocksAutomatic("counter",eventId)) return;
        if (eventId == null || eventId.isBlank() || runId == null || runId.isBlank()) return;
        RunRow run = automatic.passingRun(eventId, runId);
        if (run == null) return;
        AuthUser actor = repository.actor(AutomationPrincipal.USER_ID);
        if (actor == null) return;
        EventRow event = events.lock(eventId, SCOPE);
        if (event == null || event.sourceMode() == null || event.ownerOrgId() == null || event.districtId() == null) return;
        if (!java.util.Objects.equals(run.targetId(), event.targetId())
                || !java.util.Objects.equals(run.mode(), event.sourceMode())) return;
        CounterHistory history = CounterHistory.of(repository.counterAttempts(event.eventId()),
                clock.now().atOffset(ZoneOffset.UTC), row -> automatic.belongsToVersion(row, run),
                repository::automaticStartNeverIssued);
        if (history.next() == CounterHistory.Next.NOTHING) return;
        String block = advisory.counterBlockReason(event.eventId());
        if (block != null && !block.isEmpty()) return;
        if(responseOrder!=null)responseOrder.ensureLocked(event);
        if (!notifications.allows(event.eventId())) return;
        DisposalPolicy policy = policies.active();
        if (history.next() == CounterHistory.Next.DISPATCH_QUEUED) {
            dispatchQueued(history.queued(), actor, eventId, runId, policy);
            return;
        }
        if (repository.activeCount("UAV_EVENT", event.eventId(), DisposalRules.COUNTERMEASURE) >= policy.maxActivePerSubject()) return;
        Chosen chosen = choose(event);
        if (chosen == null) return;
        emergencyStops.lockDevice(chosen.deviceId());
        if (busy(chosen.deviceId(), null) || gateway.requestBlockReason(chosen.deviceId()) != null) return;
        var available = automatic.devices(event.sourceMode(), event.ownerOrgId(), event.districtId());
        if (available.size() != 1 || !available.get(0).equals(chosen.deviceId())) return;
        if (!eligibility.allowsRun("counter", eventId, runId)) return;
        // 航班避让（2026-10-26）：附近有航班或收不到航班信息时自动反制不启动，转人工；原因记在告警上。
        if (flights != null && DisposalRules.COUNTERMEASURE_4CH.equals(chosen.channel())
                && !flights.allowsAutomatic(event.eventId(), chosen.deviceId())) return;

        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime until = at.plusMinutes(policy.timeLimitMinutes(DisposalRules.COUNTERMEASURE));
        if (!until.isAfter(at)) return;
        String id = UUID.randomUUID().toString();
        String no = DisposalRules.authorizationNo(dayKey(at), repository.nextSequence(dayKey(at)));
        repository.insert(new AuthorizationInsert(id, no, DisposalRules.COUNTERMEASURE, "UAV_EVENT", event.eventId(),
                event.targetId(), chosen.deviceId(), chosen.channel(), REASON, actor.userId(), at, DisposalRules.REQUESTED,
                policy.policyCode(), event.ownerOrgId(), event.districtId(), event.sourceMode()));
        if(riskCases!=null)riskCases.bindAuthorization(id,event.eventId());
        String note = history.attempt() > 1 ? NOTE + "前一次自动反制的启动指令没有发出去，这是第 " + history.attempt()
                + " 次自动发起（最多 " + CounterHistory.MAX_ATTEMPTS + " 次）。" : NOTE;
        if (repository.authorizeDirect(id, 0L, at, until, note) != 1)
            throw new IllegalStateException("automatic counter authorization was not applied");
        event(id, "DIRECT_AUTHORIZE", actor.userId(), note, Map.of("status", DisposalRules.APPROVED,
                "authorization_mode", "DIRECT", "automation_run_id", runId, "automatic_attempt", history.attempt(),
                "automation_contract", AutomaticCounterAuthorization.CONTRACT, "group_version", run.version(),
                "valid_from", at.toInstant().toEpochMilli(), "valid_until", until.toInstant().toEpochMilli()), at);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "disposal", "disposal_direct_authorized",
                "disposal_authorization", id, "authorization_no=" + no + "; authorization_mode=DIRECT; approved_by=; automation_run_id=" + runId
                        + "; automatic_attempt=" + history.attempt(), "SUCCESS", "", "");

        OffsetDateTime executeAt = at.plusNanos(1_000_000);
        String key = "rule-counter-" + id;
        DisposalExecutionGateway.Result dispatched = DisposalRules.COUNTERMEASURE_4CH.equals(chosen.channel())
                ? gateway.dispatch4chAs(actor, chosen.deviceId(), key, id, DisposalRules.COUNTERMEASURE, REASON)
                : gateway.dispatchAs(actor, chosen.deviceId(), key, id, policy, DisposalRules.COUNTERMEASURE, Map.of(), REASON);
        if (dispatched instanceof DisposalExecutionGateway.Rejected rejected) {
            event(id, rejected.eventKind(), actor.userId(), rejected.detail(),
                    Map.of("status", DisposalRules.APPROVED, "channel", chosen.channel(), "device_id", chosen.deviceId()), executeAt);
            audit.record(actor.userId(), actor.account(), actor.roleCode(), "disposal", "disposal_executed",
                    "disposal_authorization", id, "authorization_no=" + no + "; channel=" + chosen.channel() + "; blocked=" + rejected.eventKind(),
                    "SUCCESS", "", "");
            log.info("automatic counter {} stayed approved because {}", no, rejected.eventKind());
            return;
        }
        String commandId = ((DisposalExecutionGateway.Accepted) dispatched).commandId();
        if (repository.transition(id, 1L, DisposalRules.EXECUTING, executeAt, commandId, null, null) != 1)
            throw new IllegalStateException("automatic counter did not enter execution");
        event(id, "EXECUTE", actor.userId(), REASON, Map.of("status", DisposalRules.EXECUTING,
                "channel", chosen.channel(), "command_id", commandId), executeAt);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "disposal", "disposal_executed",
                "disposal_authorization", id, "authorization_no=" + no + "; channel=" + chosen.channel() + "; command_id=" + commandId,
                "SUCCESS", "", "");
        log.info("automatic counter {} dispatched on {}", no, chosen.channel());
    }

    /**
     * 补发设备忙时建好、没发出去的那条自动反制。设备还忙或用不了就安静地等下一轮，不每 2 秒记一条“设备忙”；
     * 锁序与急停相同：事件→授权→设备。
     */
    private void dispatchQueued(AuthorizationRow queued, AuthUser actor, String eventId, String runId, DisposalPolicy policy) {
        AuthorizationRow row = repository.lockForSystem(queued.authorizationId());
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        if (row == null || !CounterHistory.waiting(row, at) || row.deviceId() == null
                || !repository.automaticStartNeverIssued(row)) return;
        emergencyStops.lockDevice(row.deviceId());
        if (busy(row.deviceId(), row.authorizationId())
                || gateway.requestBlockReason(row.deviceId()) != null) return;
        if (!automatic.mayStart(row) || !eligibility.allowsRun("counter", eventId, runId)) return;
        if (flights != null && DisposalRules.COUNTERMEASURE_4CH.equals(row.channel())
                && !flights.allowsAutomatic(eventId, row.deviceId())) return;
        String key = "rule-counter-" + row.authorizationId() + "-" + at.toInstant().toEpochMilli();
        DisposalExecutionGateway.Result dispatched = DisposalRules.COUNTERMEASURE_4CH.equals(row.channel())
                ? gateway.dispatch4chAs(actor, row.deviceId(), key, row.authorizationId(), DisposalRules.COUNTERMEASURE, REASON)
                : gateway.dispatchAs(actor, row.deviceId(), key, row.authorizationId(), policy, DisposalRules.COUNTERMEASURE,
                        Map.of(), REASON);
        if (dispatched instanceof DisposalExecutionGateway.Rejected rejected) {
            log.info("queued automatic counter {} still not dispatched because {}", row.authorizationNo(), rejected.eventKind());
            return;
        }
        String commandId = ((DisposalExecutionGateway.Accepted) dispatched).commandId();
        if (repository.transition(row.authorizationId(), row.version(), DisposalRules.EXECUTING, at, commandId, null, null) != 1)
            throw new IllegalStateException("queued automatic counter did not enter execution");
        event(row.authorizationId(), "EXECUTE", actor.userId(), QUEUED_REASON, Map.of("status", DisposalRules.EXECUTING,
                "channel", row.channel(), "command_id", commandId), at);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "disposal", "disposal_executed",
                "disposal_authorization", row.authorizationId(), "authorization_no=" + row.authorizationNo() + "; channel="
                        + row.channel() + "; command_id=" + commandId + "; queued=true", "SUCCESS", "", "");
        log.info("queued automatic counter {} dispatched on {}", row.authorizationNo(), row.channel());
    }

    /** 设备有指令在途，或正为别的反制开着（新-20）：这时再下发会被拒，也会改掉对方的输出。 */
    private boolean busy(String deviceId, String authorizationId) {
        return devices.hasActiveWork(deviceId) || repository.deviceRunningOther(deviceId, authorizationId);
    }

    /** Exactly one eligible controller is required; ambiguity stays available for an authorized operator. */
    private Chosen choose(EventRow event) {
        List<String> available = automatic.devices(event.sourceMode(), event.ownerOrgId(), event.districtId());
        return available.size() == 1 ? new Chosen(available.get(0), DisposalRules.COUNTERMEASURE_4CH) : null;
    }

    private void event(String authorizationId, String kind, String actorId, String note, Map<String, Object> snapshot, OffsetDateTime at) {
        repository.insertEvent(UUID.randomUUID().toString(), authorizationId, kind, actorId, note, write(snapshot), at);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize disposal event snapshot", ex); }
    }

    private static String dayKey(OffsetDateTime at) {
        return String.format("%04d%02d%02d", at.getYear(), at.getMonthValue(), at.getDayOfMonth());
    }

    private record Chosen(String deviceId, String channel) { }
}
