package com.uav.lowaltitude.modules.device.api;

import java.util.List;

public final class DeviceMaintenanceDtos {
    private DeviceMaintenanceDtos() { }
    public record CreateRequest(String deviceId,String notificationSettingId) { public CreateRequest(String deviceId){this(deviceId,null);} }
    /** Business-device projection: no plan, notification recipient or backend controls are exposed. */
    public record DeviceReport(String deviceId,boolean canReport,String blockedReason,String abnormalReason,
            long checkedAt,CaseProgress task,boolean reused) { }
    public record CaseProgress(String taskId,String deviceName,String workflowState,long reportedAt,
            Long myReportedAt,boolean simulated) { }
    public record HandleRequest(Long expectedVersion, String note) { }
    public record ResendRequest(Integer expectedAttemptNo, String reason,String planId) {
        public ResendRequest(Integer expectedAttemptNo,String reason){this(expectedAttemptNo,reason,null);}
    }
    public record WorkflowAction(String action, Long expectedVersion, String note, String commissionId) { }
    public record WorkflowEvent(String eventId,String action,String note,String actorName,long occurredAt) { }
    public record CommissionLink(String commissionId,String status,boolean simulated) { }
    public record Recovery(String result,String reason,long checkedAt,List<String> reasonCodes,String ruleVersion,
            CurrentDeviceState evidence) {
        public Recovery(String result,String reason,long checkedAt) { this(result,reason,checkedAt,List.of(),"legacy",null); }
        public Recovery(String result,String reason,long checkedAt,List<String> reasonCodes,String ruleVersion) {
            this(result,reason,checkedAt,reasonCodes,ruleVersion,null);
        }
    }
    public record CurrentDeviceState(String connectivity,String healthCode,Boolean hasAlarm,boolean enabled,
            Long observedAt,Long lastHeartbeatAt,Long healthObservedAt,Long alarmObservedAt,String sourceMode,boolean simulated,
            String sourceId,String healthSourceId,String healthSourceMode,Boolean healthSimulated,
            String alarmSourceId,String alarmSourceMode,Boolean alarmSimulated) {
        public static CurrentDeviceState from(java.util.Map<String,Object> row) {
            if(row==null)return null;
            return new CurrentDeviceState(text(row,"connectivity"),text(row,"health_code"),
                    row.get("has_alarm") instanceof Boolean b?b:null,Boolean.TRUE.equals(row.get("enabled")),
                    number(row,"observed_at"),number(row,"last_heartbeat_at"),number(row,"health_observed_at"),
                    number(row,"alarm_observed_at"),text(row,"source_mode"),Boolean.TRUE.equals(row.get("simulated")),
                    text(row,"source_id"),text(row,"health_source_id"),text(row,"health_source_mode"),flag(row,"health_simulated"),
                    text(row,"alarm_source_id"),text(row,"alarm_source_mode"),flag(row,"alarm_simulated"));
        }
        private static String text(java.util.Map<String,Object> row,String key){return row.get(key)==null?null:row.get(key).toString();}
        private static Long number(java.util.Map<String,Object> row,String key){return row.get(key) instanceof Number n?n.longValue():null;}
        private static Boolean flag(java.util.Map<String,Object> row,String key){return row.get(key) instanceof Boolean b?b:null;}
    }
    public record RelatedPlan(String planId,String planNo) { }
    public record RelatedIncident(String incidentId,String reason,String stage,Long detectedAt,Long closedAt) { }
    public record RelatedReport(String reportId,String planId,String actorName,String reason,long reportedAt) { }
    public record ManualReport(String reportId,String actorName,long reportedAt,String planId,String planNo) { }
    public record ReportSummary(String origin,ManualReport latestManualReport,List<RelatedPlan> relatedPlans) { }
    public record OpenIncident(String incidentId,String reason,String stage,List<String> allowedActions) { }
    public record Workflow(Task task,String state,long version,String assignedToName,List<WorkflowEvent> events,
            List<CommissionLink> commissionTasks,Recovery recovery,List<String> allowedActions,String blockedReason,List<OpenIncident> openIncidents,
            int workflowVersion,CurrentDeviceState currentDeviceState,String primaryAction,String nextStep,
            List<RelatedPlan> relatedPlans,List<RelatedIncident> relatedIncidents,List<RelatedReport> relatedReports) { }
    public record Message(String taskId,String deviceId,String deviceName,String reason,long reportedAt,Long readAt,String workflowState) { }
    public record MessagePage(List<Message> items,long total,int page,int size,long unreadCount) { }
    public record ReadReceipt(String taskId,long readAt) { }
    public record NoticeAttempt(String attemptId, int attemptNo, long requestedAt, String requestedByName,
            String reason, com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot,
            String deliveryStatus, String receiptStatus, String receiptResult, String blockedReason,
            Long submittedAt, Long deliveredAt, Long acknowledgedAt, String outcomeState, boolean historical) { }
    public record Page(List<Task> items, int page, int size, long total) { }
    public record PlanTask(String planId,String taskId,String deviceName,String workflowState) { }
    public record Task(String taskId, String planId, String planNo, String deviceId, String deviceNo,
            String deviceName, String reason, String connectivity, String healthCode, Long observedAt,
            Long lastHeartbeatAt, boolean simulated, String status, String reportedByName, long reportedAt,
            String handledByName, Long handledAt, String handlingNote, long version, boolean canHandle,
            boolean reused,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot,
            String notificationDeliveryStatus,String notificationReceiptStatus,String notificationBlockedReason,
            List<NoticeAttempt> notificationAttempts, int latestNotificationAttemptNo,
            boolean canResendNotification, String resendBlockedReason, Long resendAvailableAt,String workflowState,
            int workflowVersion,CurrentDeviceState currentDeviceState,Recovery recovery,String primaryAction,String nextStep,
            ReportSummary reportSummary) { }
}
