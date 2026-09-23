package com.uav.lowaltitude.modules.disposal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort;

/** 干扰完成后把处罚材料送进交接。页面读取不触发写入。 */
@Component
public class PunishmentHandoffJob {
    private static final Logger log = LoggerFactory.getLogger(PunishmentHandoffJob.class);
    private final DisposalCompletionPort disposals;
    private final HandoffSubmissionService handoffs;

    public PunishmentHandoffJob(DisposalCompletionPort disposals, HandoffSubmissionService handoffs) {
        this.disposals = disposals;
        this.handoffs = handoffs;
    }

    @Scheduled(fixedDelayString = "${app.handoff.auto-punishment.interval-millis:15000}")
    public void sweep() {
        for (String eventId : disposals.jammingCompletedWithoutPunishment()) {
            try {
                handoffs.automaticAfterJamming(eventId);
            } catch (RuntimeException ex) {
                log.warn("automatic punishment handoff skipped for {}: {}", eventId, ex.getMessage());
            }
        }
    }
}
