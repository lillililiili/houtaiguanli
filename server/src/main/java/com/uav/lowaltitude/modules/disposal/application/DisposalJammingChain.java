package com.uav.lowaltitude.modules.disposal.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationInsert;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 反制完成后自动接信号干扰。
 *
 * 必须在来源授权的完成事务提交之后跑：干扰创建或下发失败不能把「反制已完成」一起回滚。
 * 一次「联动反制」批准覆盖这条链，不再二次审批；下发用原反制执行人，没有登录会话。
 */
@Service
public class DisposalJammingChain {
    private static final Logger log = LoggerFactory.getLogger(DisposalJammingChain.class);

    private final DisposalRepository repository;
    private final DisposalPolicyRepository policies;
    private final DisposalExecutionGateway gateway;
    private final DeviceAccessPolicy devices;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public DisposalJammingChain(DisposalRepository repository, DisposalPolicyRepository policies,
            DisposalExecutionGateway gateway, DeviceAccessPolicy devices, AppClock clock, AuditService audit,
            ObjectMapper json, PlatformTransactionManager transactions) {
        this.repository = repository; this.policies = policies; this.gateway = gateway; this.devices = devices;
        this.clock = clock; this.audit = audit; this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    /** 登记到当前事务 afterCommit；无事务时立即执行。 */
    public void scheduleAfterComplete(String parentAuthorizationId) {
        if (parentAuthorizationId == null || parentAuthorizationId.isBlank()) return;
        Runnable run = () -> {
            try { tx.executeWithoutResult(status -> chain(parentAuthorizationId)); }
            catch (RuntimeException ex) {
                log.warn("countermeasure {} completed but auto jamming was not created: {}", parentAuthorizationId,
                        ex.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { run.run(); }
            });
        } else {
            run.run();
        }
    }

    void chain(String parentAuthorizationId) {
        AuthorizationRow parent = repository.findUnlocked(parentAuthorizationId);
        if (parent == null) return;
        if (!DisposalRules.COUNTERMEASURE.equals(parent.actionType())) return;
        if (!DisposalRules.COMPLETED.equals(parent.status())) return;
        if (!"UAV_EVENT".equals(parent.subjectKind())) return;
        if (repository.chainedFrom(parent.authorizationId())) return;
        if (repository.actionExists(parent.subjectKind(), parent.subjectId(), DisposalRules.JAMMING)) return;

        DisposalPolicy policy = policies.active();
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime until = at.plusMinutes(policy.timeLimitMinutes(DisposalRules.JAMMING));
        String id = UUID.randomUUID().toString();
        String no = DisposalRules.authorizationNo(dayKey(at), repository.nextSequence(dayKey(at)));
        String reason = "反制完成后自动发起信号干扰（来源 " + parent.authorizationNo() + "）";
        String approver = parent.approvedBy() != null ? parent.approvedBy() : parent.requestedBy();
        String note = "反制完成后自动批准，不再二次审批";
        try {
            repository.insertChainedApproved(new AuthorizationInsert(id, no, DisposalRules.JAMMING, parent.subjectKind(),
                    parent.subjectId(), parent.targetId(), parent.deviceId(), parent.channel(), reason,
                    parent.requestedBy(), at, DisposalRules.APPROVED, parent.policyVersion(), parent.ownerOrgId(),
                    parent.districtId(), parent.sourceMode()), parent.authorizationId(), approver, at, at, until, note);
        } catch (DataIntegrityViolationException raced) {
            return;
        }
        Map<String, Object> snap = Map.of("status", DisposalRules.APPROVED, "chained_from", parent.authorizationId(),
                "action_type", DisposalRules.JAMMING, "channel", parent.channel());
        event(id, "REQUEST", parent.requestedBy(), reason, snap, at);
        event(id, "APPROVE", approver, note, Map.of("status", DisposalRules.APPROVED,
                "valid_from", at.toInstant().toEpochMilli(), "valid_until", until.toInstant().toEpochMilli(),
                "chained_from", parent.authorizationId()), at.plusNanos(1_000_000));
        AuthUser requester = repository.actor(parent.requestedBy());
        audit.record(parent.requestedBy(), requester == null ? "" : requester.account(),
                requester == null ? null : requester.roleCode(), "disposal", "disposal_jamming_chained",
                "disposal_authorization", id, "authorization_no=" + no + "; chained_from=" + parent.authorizationNo(),
                "SUCCESS", "", "");

        if (DisposalRules.MANUAL.equals(parent.channel())) return;
        tryDispatch(id, parent, policy, reason, at);
    }

    private void tryDispatch(String id, AuthorizationRow parent, DisposalPolicy policy, String reason,
                             OffsetDateTime at) {
        String actorId = repository.latestExecuteActor(parent.authorizationId());
        if (actorId == null) actorId = parent.requestedBy();
        AuthUser executor = repository.actor(actorId);
        if (executor == null || !devices.canOperateDevices(executor)) return;
        AuthorizationRow row = repository.findUnlocked(id);
        if (row == null) return;
        DisposalExecutionGateway.Result dispatched;
        try {
            dispatched = DisposalRules.COUNTERMEASURE_4CH.equals(row.channel())
                    ? gateway.dispatch4chAs(executor, row.deviceId(), "disposal-chain-" + id, id, row.actionType(), reason)
                    : gateway.dispatchAs(executor, row.deviceId(), "disposal-chain-" + id, id, policy, row.actionType(),
                            Map.of(), reason);
        } catch (RuntimeException ex) {
            log.warn("auto jamming {} created but dispatch failed: {}", id, ex.getMessage());
            return;
        }
        if (dispatched instanceof DisposalExecutionGateway.Rejected rejected) {
            event(id, rejected.eventKind(), executor.userId(), rejected.detail(),
                    Map.of("status", row.status(), "channel", row.channel(), "device_id", nullToEmpty(row.deviceId())), at);
            return;
        }
        String commandId = ((DisposalExecutionGateway.Accepted) dispatched).commandId();
        if (repository.transition(id, row.version(), DisposalRules.EXECUTING, at, commandId, null, null) != 1) return;
        event(id, "EXECUTE", executor.userId(), "反制完成后自动下发", Map.of("status", DisposalRules.EXECUTING,
                "channel", row.channel(), "command_id", commandId), at);
    }

    private void event(String authorizationId, String kind, String actorId, String note,
                       Map<String, Object> snapshot, OffsetDateTime at) {
        repository.insertEvent(UUID.randomUUID().toString(), authorizationId, kind, actorId, note, write(snapshot), at);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize disposal event snapshot", ex); }
    }

    private static String dayKey(OffsetDateTime at) {
        return String.format("%04d%02d%02d", at.getYear(), at.getMonthValue(), at.getDayOfMonth());
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
