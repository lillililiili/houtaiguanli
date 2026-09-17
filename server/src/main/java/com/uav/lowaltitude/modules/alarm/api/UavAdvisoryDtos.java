package com.uav.lowaltitude.modules.alarm.api;

import java.util.List;

public final class UavAdvisoryDtos {
    private UavAdvisoryDtos() { }
    public record Record(String recordId, String kind, long createdAt, String actorName, String recipientName,
            String contactBasis, String content, String outcome, String danger, String note, boolean urgent,
            boolean simulated, String deliveryStatus, String triggerMode, String policyCode,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot) {
        public Record(String recordId,String kind,long createdAt,String actorName,String recipientName,String contactBasis,String content,String outcome,String danger,String note,boolean urgent,boolean simulated,String deliveryStatus,String triggerMode,String policyCode) {
            this(recordId,kind,createdAt,actorName,recipientName,contactBasis,content,outcome,danger,note,urgent,simulated,deliveryStatus,triggerMode,policyCode,null);
        }
        public Record(String recordId,String kind,long createdAt,String actorName,String recipientName,String contactBasis,String content,String outcome,String danger,String note,boolean urgent,boolean simulated,String deliveryStatus) {
            this(recordId,kind,createdAt,actorName,recipientName,contactBasis,content,outcome,danger,note,urgent,simulated,deliveryStatus,"MANUAL",null);
        }
    }
    public record Recipient(String name, String contactHint, String basis) { }
    public record Overview(String eventId, long eventVersion, String smsMode, boolean canWrite,
            boolean canRequestCounter, boolean canHandoff, String counterBlockReason, List<Record> records, Recipient recipient, AutoSms autoSms, String voiceMode, AutoVoice autoVoice) { }
    public record AutoSms(boolean enabled,String status,String reason,Long triggeredAt,Long updatedAt,boolean canRetry,
            int attemptCount,String policyCode,String triggerSource,Long evaluatedAt,Long dataUpdatedAt,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot) { }
    public record AutoVoice(boolean enabled,String status,String reason,Long triggeredAt,Long updatedAt,boolean canRetry,
            int attemptCount,String policyCode,String triggerSource,Long evaluatedAt,Long dataUpdatedAt,String recordingId,String recordingName,
            Long answeredAt,Long playbackCompletedAt,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot) { }
    public record Action(Long expectedVersion, String kind, String recipientName, String contactBasis, String content,
            String outcome, String danger, String note, Boolean urgent) { }
}
