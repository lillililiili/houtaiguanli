package com.uav.lowaltitude.modules.alarm.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository;
import com.uav.lowaltitude.platform.config.AdvisoryDispatchConfig;
import com.uav.lowaltitude.platform.time.AppClock;

/** Persist intent with verification; dispatch after commit using the normal sending guards. */
@Service
public class AutoSmsVerificationDispatch {
    private static final Logger log = LoggerFactory.getLogger(AutoSmsVerificationDispatch.class);
    private final AutoSmsRepository tasks;
    private final UavRiskCaseIdentity cases;
    private final AutoSmsPolicy policy;
    private final AppClock clock;
    private final ObjectProvider<AutoSmsService> sender;
    private final Executor executor;
    private final Set<Dispatch> pending = ConcurrentHashMap.newKeySet();

    public AutoSmsVerificationDispatch(AutoSmsRepository tasks, UavRiskCaseIdentity cases,
            AutoSmsPolicy policy, AppClock clock, ObjectProvider<AutoSmsService> sender,
            @Qualifier(AdvisoryDispatchConfig.SMS_EXECUTOR) Executor executor) {
        this.tasks = tasks; this.cases = cases; this.policy = policy; this.clock = clock;
        this.sender = sender; this.executor = executor;
    }

    /** Caller holds the episode/case/event locks. No qualification projection or channel I/O here. */
    public void verified(String eventId, String riskCaseId) {
        if (!policy.enabled()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Verification notification must join its transaction");
        String selected = riskCaseId == null ? cases.caseId(eventId) : riskCaseId;
        if (selected == null && cases.hasCases(eventId)) {
            log.warn("Verified SMS wake-up requires a regional identity for event {}; regional sweep retained",eventId);
            return;
        }
        cases.runWithCase(selected, () -> {
            String owner = cases.notificationTaskOwner(eventId, "SMS");
            // initialize preserves sent/unknown tasks and their stable provider keys. A missed
            // wake-up (shutdown, queue saturation) is recovered by the existing durable sweep.
            tasks.initialize(owner, clock.nowMillis(), AutoSmsPolicy.CODE);
            var dispatch = new Dispatch(owner, selected);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue(dispatch); }
            });
        });
    }

    private void enqueue(Dispatch dispatch) {
        if (!pending.add(dispatch)) return;
        try {
            executor.execute(() -> {
                try {
                    // The request/background case ThreadLocal ends before this worker starts.
                    // Explicitly restore only the verified regional identity, never an actor.
                    cases.runWithCase(dispatch.riskCaseId(), () -> sender.getObject().process(dispatch.eventId()));
                } catch (RuntimeException failed) {
                    log.warn("Verified SMS dispatch deferred for event {}, case {}: {}; durable task retained",
                            dispatch.eventId(), dispatch.riskCaseId(), failed.getClass().getSimpleName());
                } finally {
                    pending.remove(dispatch);
                }
            });
        } catch (RuntimeException rejected) {
            pending.remove(dispatch);
            log.warn("Verified SMS wake-up unavailable for event {}, case {}; durable task retained",
                    dispatch.eventId(), dispatch.riskCaseId());
        }
    }

    private record Dispatch(String eventId, String riskCaseId) { }
}
