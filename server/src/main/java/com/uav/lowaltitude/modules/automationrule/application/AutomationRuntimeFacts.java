package com.uav.lowaltitude.modules.automationrule.application;

import java.util.Map;
import java.util.Set;

/** Read-only observations. Unknown is null with a reason, never a successful default. */
public record AutomationRuntimeFacts(String eventId, String targetId, String alarmId, String ownerOrgId,
        String districtId, String sourceMode, String eventState, String objectType, Long observedAt,
        Set<String> airspaceIds, boolean airspaceKnown, Map<String, Fact> facts) {
    public AutomationRuntimeFacts {
        airspaceIds = Set.copyOf(airspaceIds);
        facts = Map.copyOf(facts);
    }
    public record Fact(String value, Long observedAt, String evidenceId, String unavailableReason) { }
}
