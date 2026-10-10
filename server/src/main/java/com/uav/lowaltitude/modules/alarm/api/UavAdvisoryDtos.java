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
    public record DepartureObservation(String eventId, String channel, String status, String presence, Long startedAt, Long deadlineAt, long evaluatedAt) { }
    public record Recipient(String name, String contactHint, String basis) { }
    public record Overview(String eventId, long eventVersion, String smsMode, boolean canWrite,
            boolean canRequestCounter, boolean canDirectCounter, boolean canHandoff, String counterBlockReason, List<Record> records, Recipient recipient, AutoSms autoSms, String voiceMode, AutoVoice autoVoice, boolean counterLaunchVisible, String notifyPhase, AutoHandoff autoHandoff, NoCounterDtos.Status noCounter,
            /* BLOCK-03：事件精确关联的计划没有执行飞手或飞手电话时为 true，页面写明“缺飞手联系方式”。 */
            boolean pilotContactMissing) { }
    /**
     * 处罚移送进度。status：WAITING 等待干扰完成或后台自动移送；MANUAL_REQUIRED 启用了多个处罚接收单位，需有权限的人选定后移送；
     * BLOCKED 没有可用接收单位；PENDING 交接已建立但还没发出；SUBMITTED 已发出；FAILED 发送失败；NOT_REQUIRED 不需要移送（误报、已决定不反制）。
     * trigger_source：COUNTERMEASURE_COMPLETED 反制停止确认后自动建立，JAMMING_COMPLETED 旧干扰完成链，
     * MANUAL 有人选定接收单位后提交，旧记录可能为空。
     * party_status/party_reasons 只在 MANUAL_REQUIRED 时给出，提交前提示当事人是否明确。
     */
    public record AutoHandoff(boolean enabled, String status, String reason, String handoffId, String triggerSource, Long updatedAt,
            String partyStatus, List<String> partyReasons) { }
    public record AutoSms(boolean enabled,String status,String reason,Long triggeredAt,Long updatedAt,boolean canRetry,
            int attemptCount,String policyCode,String triggerSource,Long evaluatedAt,Long dataUpdatedAt,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot) { }
    public record AutoVoice(boolean enabled,String status,String reason,Long triggeredAt,Long updatedAt,boolean canRetry,
            int attemptCount,String policyCode,String triggerSource,Long evaluatedAt,Long dataUpdatedAt,String recordingId,String recordingName,
            Long answeredAt,Long playbackCompletedAt,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipientSnapshot) { }
    public record Action(Long expectedVersion, String kind, String recipientName, String contactBasis, String content,
            String outcome, String danger, String note, Boolean urgent) { }
}
