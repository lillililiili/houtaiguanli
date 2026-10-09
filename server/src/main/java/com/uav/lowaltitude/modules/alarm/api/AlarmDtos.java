package com.uav.lowaltitude.modules.alarm.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;

/** 阶段 4 只暴露告警安全摘要，不返回来源原始 JSON、凭据或设备内部字段。 */
public final class AlarmDtos {

    /** 区域筛选项（决策 15-22）：只含调用者范围内出现过的区域。 */
    public record DistrictOptionDto(String districtId, String name) { }

    private AlarmDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }
    public static final class AlarmDto {
        private final String alarmId, state, alarmType, severity, sourceCode, sourceMode, ownerOrgId, districtId, targetId;
        /** 显示字段：业务编号与名称来自同一行的目录/来源联接，页面不得再把内部 ID 当编号展示。 */
        private final String alarmNo, sourceName, ownerOrgName, districtName, targetNo;
        private final EventId eventId;
        private final Long occurredAt;
        private final long receivedAt;
        /**
         * 告警升级（2026-10-06）：severity 是升级后的当前等级，original_severity 是告警产生时的等级；
         * violation_reasons 是累计的违规原因代码（没有记录时为空数组）；escalation_count 为 0 表示没升级过，
         * escalated_at 是最近一次升级时刻。升级明细见 GET /alarms/{alarm_id}/escalations。
         */
        private final String originalSeverity;
        private final List<String> violationReasons;
        private final String taskMatchNote;
        private final int escalationCount;
        private final Long escalatedAt;
        private final String observationStatus, attentionGroup;
        public AlarmDto(String alarmId, String eventId, String state, String alarmType, String severity, Long occurredAt,
                long receivedAt, String sourceCode, String sourceMode, String ownerOrgId, String districtId, String targetId,
                String alarmNo, String sourceName, String ownerOrgName, String districtName, String targetNo,
                String originalSeverity, List<String> violationReasons, int escalationCount, Long escalatedAt,
                String observationStatus, String attentionGroup, String taskMatchNote) {
            this.alarmId = alarmId; this.eventId = new EventId(eventId); this.state = state; this.alarmType = alarmType; this.severity = severity;
            this.occurredAt = occurredAt; this.receivedAt = receivedAt; this.sourceCode = sourceCode; this.sourceMode = sourceMode;
            this.ownerOrgId = ownerOrgId; this.districtId = districtId; this.targetId = targetId;
            this.alarmNo = alarmNo; this.sourceName = sourceName; this.ownerOrgName = ownerOrgName; this.districtName = districtName; this.targetNo = targetNo;
            this.originalSeverity = originalSeverity; this.violationReasons = violationReasons == null ? List.of() : List.copyOf(violationReasons);
            this.escalationCount = escalationCount; this.escalatedAt = escalatedAt;
            this.observationStatus = observationStatus; this.attentionGroup = attentionGroup;
            this.taskMatchNote = taskMatchNote;
        }
        public String getAlarmId() { return alarmId; }
        /** 无事件是稳定业务事实；包装值非空而序列化结果为 null，避免改全局 NON_NULL。 */
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonSerialize(using = EventIdSerializer.class) public EventId getEventId() { return eventId; }
        public String getState() { return state; }
        public String getAlarmType() { return alarmType; }
        public String getSeverity() { return severity; }
        public Long getOccurredAt() { return occurredAt; }
        public long getReceivedAt() { return receivedAt; }
        public String getSourceCode() { return sourceCode; }
        public String getSourceMode() { return sourceMode; }
        public String getOwnerOrgId() { return ownerOrgId; }
        public String getDistrictId() { return districtId; }
        public String getTargetId() { return targetId; }
        public String getAlarmNo() { return alarmNo; }
        public String getSourceName() { return sourceName; }
        public String getOwnerOrgName() { return ownerOrgName; }
        public String getDistrictName() { return districtName; }
        public String getTargetNo() { return targetNo; }
        public String getOriginalSeverity() { return originalSeverity; }
        public List<String> getViolationReasons() { return violationReasons; }
        public String getTaskMatchNote() { return taskMatchNote; }
        public int getEscalationCount() { return escalationCount; }
        public Long getEscalatedAt() { return escalatedAt; }
        public String getObservationStatus() { return observationStatus; }
        public String getAttentionGroup() { return attentionGroup; }
    }
    public record EventId(String value) { }
    public static final class EventIdSerializer extends JsonSerializer<EventId> {
        @Override public void serialize(EventId value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (value == null || value.value() == null) generator.writeNull(); else generator.writeString(value.value());
        }
    }
    public record UavEventDto(String eventId, String alarmId, String targetId, String state, long version,
            long createdAt, long updatedAt, List<String> allowedActions, VerificationBasisDto verificationBasis) { }
    /** 人工核实为属实的依据，只在待核实且当前用户可核实时返回；confirmable=false 时 message 写明缺什么，missing 为稳定代码。 */
    public record VerificationBasisDto(boolean confirmable, List<String> missing, String message, long checkedAt) { }
    public record VerificationDto(String historyId, String previousState, String resultingState, String conclusion,
            String note, long version, String actorId, long createdAt, String actorName) { }
    public record VerifyRequest(String conclusion, String note, Long expectedVersion) { }
    /**
     * 告警升级记录：trigger_kind 为 ENGINE（系统研判）或 MANUAL（人工转告警，带操作人与说明）；
     * reasons_added 是这次新增的违规原因，reasons_after 是升级后的全部原因。
     */
    public record EscalationDto(String escalationId, int seq, String triggerKind, String severityBefore, String severityAfter,
            List<String> reasonsAdded, List<String> reasonsAfter, String note, String actorId, String actorName, long createdAt) { }
}
