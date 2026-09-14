package com.uav.lowaltitude.modules.workbench.api;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 工作台只暴露源事项的安全摘要：类型、等级、状态、设备编号/名称、风险原因文案；不含原始载荷、凭据或无权关联 ID。 */
public final class WorkbenchDtos {
    private WorkbenchDtos() { }

    public record ListDto(List<ItemDto> items, long total, int page, int size,
            /** 无权限或未配置的类别显式为 null，不能用 0 冒充“无事项”；因此覆盖全局 non_null。 */
            @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS) Map<String, Long> countsByKind,
            Map<String, String> sourceAvailability, long asOf) { }

    public record ItemDto(String kind, String sourceId, String sourceNo, String state, String severity, long receivedAt,
            Long occurredAt, Long updatedAt, Long version, String title, String summary, List<String> allowedActions,
            /** 契约固定字段：没有阻断原因时显式 null，与“字段缺失”区分。 */
            @JsonInclude(JsonInclude.Include.ALWAYS) String blockedReason,
            String sourceMode, Map<String, String> links) { }

    public record DetailDto(ItemDto item, List<TimelineEntryDto> timeline, Map<String, String> availability) { }

    /** 时间线条目按 entry_type 携带各自字段，其余字段省略；只有操作归属 ID，不扩展用户资料。 */
    public record TimelineEntryDto(String entryType, long at, Long version, String previousState, String resultingState,
            String conclusion, String note, String actorId, String actorName, String handoffId, String handoffType, String recipientId,
            String recipientName, Long sourceVersion, String deliveryStatus, String blockedReason, String stage,
            String incidentType, String deviceNo, String deviceName, String reason) {
        public static TimelineEntryDto verification(long at, long version, String previousState, String resultingState,
                String conclusion, String note, String actorId, String actorName) {
            return new TimelineEntryDto("VERIFICATION", at, version, previousState, resultingState, conclusion, note, actorId, actorName,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }
        public static TimelineEntryDto handoff(long at, String handoffId, String handoffType, String recipientId,
                String recipientName, long sourceVersion, String deliveryStatus, String blockedReason) {
            return new TimelineEntryDto("HANDOFF", at, null, null, null, null, null, null, null, handoffId, handoffType,
                    recipientId, recipientName, sourceVersion, deliveryStatus, blockedReason, null, null, null, null, null);
        }
        public static TimelineEntryDto deviceFact(String entryType, long at, String stage, String incidentType,
                String deviceNo, String deviceName, String reason) {
            return new TimelineEntryDto(entryType, at, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, stage, incidentType, deviceNo, deviceName, reason);
        }
    }
}
