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
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.integration.mqtt.LingyunControlEnvelope;
import com.uav.lowaltitude.modules.alarm.domain.NotifyFlow;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavAdvisoryRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.UavEventRepository.EventRow;
import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.application.DisposalExecutionGateway;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationInsert;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 反制规则通过后，在待反制阶段自动发起一次直接反制。
 * 不关闭人工按钮；证据、急停、策略上限和设备条件不满足时本轮跳过，下一轮再检查。
 */
@Service
public class AlarmRuleCounter {
    private static final Logger log = LoggerFactory.getLogger(AlarmRuleCounter.class);
    private static final AccessDecision SCOPE = new AccessDecision("system:auto-counter", ScopeMode.ALL);
    private static final String REASON = "反制规则已满足，系统自动发起。";
    private static final String NOTE = "反制规则已满足，系统按直接授权发起，未指定审批人。";

    private final UavEventRepository events;
    private final UavAdvisoryRepository advisory;
    private final UavAdvisoryService phases;
    private final DisposalRepository repository;
    private final DisposalPolicyRepository policies;
    private final DisposalExecutionGateway gateway;
    private final EmergencyStopRepository emergencyStops;
    private final DeviceRepository devices;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public AlarmRuleCounter(UavEventRepository events, UavAdvisoryRepository advisory, UavAdvisoryService phases,
            DisposalRepository repository, DisposalPolicyRepository policies, DisposalExecutionGateway gateway,
            EmergencyStopRepository emergencyStops, DeviceRepository devices, AuditService audit, AppClock clock,
            ObjectMapper json) {
        this.events = events;
        this.advisory = advisory;
        this.phases = phases;
        this.repository = repository;
        this.policies = policies;
        this.gateway = gateway;
        this.emergencyStops = emergencyStops;
        this.devices = devices;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    @Transactional
    public void launchIfPassed(String eventId, String runId) {
        if (eventId == null || eventId.isBlank() || runId == null || runId.isBlank()) return;
        AuthUser actor = repository.actor(AutomationPrincipal.USER_ID);
        if (actor == null) return;
        EventRow event = events.lock(eventId, SCOPE);
        if (event == null || event.sourceMode() == null || event.ownerOrgId() == null || event.districtId() == null) return;
        if (repository.actionExists("UAV_EVENT", event.eventId(), DisposalRules.COUNTERMEASURE)) return;
        String block = advisory.counterBlockReason(event.eventId());
        if (block != null && !block.isEmpty()) return;
        if (emergencyStops.unresolved(event.eventId())) return;
        if (phases.phaseForAutomation(event.eventId()) != NotifyFlow.Phase.AWAIT_COUNTER) return;
        DisposalPolicy policy = policies.active();
        if (repository.activeCount("UAV_EVENT", event.eventId(), DisposalRules.COUNTERMEASURE) >= policy.maxActivePerSubject()) return;
        Chosen chosen = choose(event, policy);
        if (chosen == null) return;
        emergencyStops.lockDevice(chosen.deviceId());
        if (emergencyStops.deviceUnresolved(chosen.deviceId())) return;

        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime until = at.plusMinutes(policy.timeLimitMinutes(DisposalRules.COUNTERMEASURE));
        if (!until.isAfter(at)) return;
        String id = UUID.randomUUID().toString();
        String no = DisposalRules.authorizationNo(dayKey(at), repository.nextSequence(dayKey(at)));
        repository.insert(new AuthorizationInsert(id, no, DisposalRules.COUNTERMEASURE, "UAV_EVENT", event.eventId(),
                event.targetId(), chosen.deviceId(), chosen.channel(), REASON, actor.userId(), at, DisposalRules.REQUESTED,
                policy.policyCode(), event.ownerOrgId(), event.districtId(), event.sourceMode()));
        if (repository.authorizeDirect(id, 0L, at, until, NOTE) != 1)
            throw new IllegalStateException("automatic counter authorization was not applied");
        event(id, "DIRECT_AUTHORIZE", actor.userId(), NOTE, Map.of("status", DisposalRules.APPROVED,
                "authorization_mode", "DIRECT", "automation_run_id", runId,
                "valid_from", at.toInstant().toEpochMilli(), "valid_until", until.toInstant().toEpochMilli()), at);
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "disposal", "disposal_direct_authorized",
                "disposal_authorization", id, "authorization_no=" + no + "; authorization_mode=DIRECT; approved_by=; automation_run_id=" + runId,
                "SUCCESS", "", "");

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

    /** 同一范围里只有一台能执行时才选它。四通道和凌云同时可用，或同通道有多台时，留给人工按钮。 */
    private Chosen choose(EventRow event, DisposalPolicy policy) {
        List<String> four = "live".equals(event.sourceMode())
                ? devices.operableCounterDevices("countermeasure", event.sourceMode(), event.ownerOrgId(), event.districtId(),
                        DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, null, false)
                : List.of();
        String family = LingyunControlEnvelope.family(policy.command(DisposalRules.COUNTERMEASURE).operationCmd());
        List<String> lingyun = family == null ? List.of()
                : devices.operableCounterDevices("ifr", event.sourceMode(), event.ownerOrgId(), event.districtId(), null, family, false);
        if (four.size() == 1 && lingyun.isEmpty()) return new Chosen(four.get(0), DisposalRules.COUNTERMEASURE_4CH);
        if (lingyun.size() == 1 && four.isEmpty()) return new Chosen(lingyun.get(0), DisposalRules.LINGYUN_B);
        // 模拟或回放事件没有同模式设备时，只用一台模拟的四通道。真实在线设备不拿来自动打模拟目标。
        if (!"live".equals(event.sourceMode()) && four.isEmpty() && lingyun.isEmpty()) {
            List<String> simulated = devices.operableCounterDevices("countermeasure", "live", event.ownerOrgId(), event.districtId(),
                    DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0, null, true);
            if (simulated.size() == 1) return new Chosen(simulated.get(0), DisposalRules.COUNTERMEASURE_4CH);
        }
        return null;
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
