package com.uav.lowaltitude.modules.disposal.api;

import java.util.List;

public final class EmergencyStopDtos {
    private EmergencyStopDtos() { }
    public record Overview(String eventId, boolean applicable, boolean requiresDeviceStop, List<String> allowedActions, String blockReason,
            List<Authorization> authorizations, Stop latestStop) { }
    public record Authorization(String authorizationId, String actionType, String status, String deviceId,
            String channel) { }
    public record Stop(String stopId, long requestedAt, String requestedByName, boolean reasonPending,
            String note, List<Device> devices, List<Event> events) { }
    public record Device(String deviceId, String deviceName, String channel, String sourceMode, boolean simulated,
            String commandId, String commandStatus, String stopStatus, String detail, List<String> allowedActions,
            String confirmedByName, Long confirmedAt, String confirmationNote) { }
    public record Event(String eventId, String kind, String actorName, long occurredAt, String note) { }
}
