package com.uav.lowaltitude.modules.device.api;

import java.util.List;

public final class DeviceMaintenanceDtos {
    private DeviceMaintenanceDtos() { }
    public record CreateRequest(String deviceId,String notificationSettingId) { public CreateRequest(String deviceId){this(deviceId,null);} }
    public record HandleRequest(Long expectedVersion, String note) { }
    public record ResendRequest(Integer expectedAttemptNo, String reason) { }
    public record NoticeAttempt(String attemptId, int attemptNo, long requestedAt, String requestedByName,
            String reason, com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot,
            String deliveryStatus, String receiptStatus, String receiptResult, String blockedReason,
            Long submittedAt, Long deliveredAt, Long acknowledgedAt, String outcomeState, boolean historical) { }
    public record Page(List<Task> items, int page, int size, long total) { }
    public record Task(String taskId, String planId, String planNo, String deviceId, String deviceNo,
            String deviceName, String reason, String connectivity, String healthCode, Long observedAt,
            Long lastHeartbeatAt, boolean simulated, String status, String reportedByName, long reportedAt,
            String handledByName, Long handledAt, String handlingNote, long version, boolean canHandle,
            boolean reused,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot,
            String notificationDeliveryStatus,String notificationReceiptStatus,String notificationBlockedReason,
            List<NoticeAttempt> notificationAttempts, int latestNotificationAttemptNo,
            boolean canResendNotification, String resendBlockedReason, Long resendAvailableAt) { }
}
