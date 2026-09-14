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
        public AlarmDto(String alarmId, String eventId, String state, String alarmType, String severity, Long occurredAt,
                long receivedAt, String sourceCode, String sourceMode, String ownerOrgId, String districtId, String targetId,
                String alarmNo, String sourceName, String ownerOrgName, String districtName, String targetNo) {
            this.alarmId = alarmId; this.eventId = new EventId(eventId); this.state = state; this.alarmType = alarmType; this.severity = severity;
            this.occurredAt = occurredAt; this.receivedAt = receivedAt; this.sourceCode = sourceCode; this.sourceMode = sourceMode;
            this.ownerOrgId = ownerOrgId; this.districtId = districtId; this.targetId = targetId;
            this.alarmNo = alarmNo; this.sourceName = sourceName; this.ownerOrgName = ownerOrgName; this.districtName = districtName; this.targetNo = targetNo;
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
    }
    public record EventId(String value) { }
    public static final class EventIdSerializer extends JsonSerializer<EventId> {
        @Override public void serialize(EventId value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (value == null || value.value() == null) generator.writeNull(); else generator.writeString(value.value());
        }
    }
    public record UavEventDto(String eventId, String alarmId, String targetId, String state, long version,
            long createdAt, long updatedAt, List<String> allowedActions) { }
    public record VerificationDto(String historyId, String previousState, String resultingState, String conclusion,
            String note, long version, String actorId, long createdAt, String actorName) { }
    public record VerifyRequest(String conclusion, String note, Long expectedVersion) { }
}
