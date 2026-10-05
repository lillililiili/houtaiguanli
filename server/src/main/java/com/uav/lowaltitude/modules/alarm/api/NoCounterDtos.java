package com.uav.lowaltitude.modules.alarm.api;

import com.uav.lowaltitude.modules.alarm.domain.NoCounterRules.Basis;

public final class NoCounterDtos {
    private NoCounterDtos() { }
    public record Decision(String decisionId, long decidedAt, String actorName, String reason, Basis basis) { }
    public record Status(String eventId, long eventVersion, boolean canDecide, String blockReason, Basis basis,
                         Decision decision, boolean decisionActive, boolean reviewRequired, String replayedDecisionId) {
        public Status(String eventId,long eventVersion,boolean canDecide,String blockReason,Basis basis,Decision decision,boolean decisionActive,boolean reviewRequired) {
            this(eventId,eventVersion,canDecide,blockReason,basis,decision,decisionActive,reviewRequired,null);
        }
    }
}
