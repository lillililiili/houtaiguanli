package com.uav.lowaltitude.modules.device.api;

import java.util.List;

public final class DeviceMaintenanceDtos {
    private DeviceMaintenanceDtos() { }
    public record CreateRequest(String deviceId) { }
    public record HandleRequest(Long expectedVersion, String note) { }
    public record Page(List<Task> items, int page, int size, long total) { }
    public record Task(String taskId, String planId, String planNo, String deviceId, String deviceNo,
            String deviceName, String reason, String connectivity, String healthCode, Long observedAt,
            Long lastHeartbeatAt, boolean simulated, String status, String reportedByName, long reportedAt,
            String handledByName, Long handledAt, String handlingNote, long version, boolean canHandle,
            boolean reused) { }
}
