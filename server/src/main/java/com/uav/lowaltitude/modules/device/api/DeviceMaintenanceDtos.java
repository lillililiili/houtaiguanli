package com.uav.lowaltitude.modules.device.api;

import java.util.List;

public final class DeviceMaintenanceDtos {
    private DeviceMaintenanceDtos() { }
    public record CreateRequest(String deviceId,String notificationSettingId) { public CreateRequest(String deviceId){this(deviceId,null);} }
    public record HandleRequest(Long expectedVersion, String note) { }
    public record ResendRequest(Integer expectedAttemptNo, String reason) { }
    public record WorkflowAction(String action, Long expectedVersion, String note, String commissionId) { }
    public record WorkflowEvent(String eventId,String action,String note,String actorName,long occurredAt) { }
    public record CommissionLink(String commissionId,String status,boolean simulated) { }
    public record Recovery(String result,String reason,long checkedAt) { }
    public record OpenIncident(String incidentId,String reason,String stage,List<String> allowedActions) { }
    public record Workflow(Task task,String state,long version,String assignedToName,List<WorkflowEvent> events,
            List<CommissionLink> commissionTasks,Recovery recovery,List<String> allowedActions,String blockedReason,List<OpenIncident> openIncidents) { }
    public record Message(String taskId,String deviceId,String deviceName,String reason,long reportedAt,Long readAt,String workflowState) { }
    public record MessagePage(List<Message> items,long total,int page,int size,long unreadCount) { }
    public record ReadReceipt(String taskId,long readAt) { }
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
            boolean canResendNotification, String resendBlockedReason, Long resendAvailableAt,String workflowState) { }
}
