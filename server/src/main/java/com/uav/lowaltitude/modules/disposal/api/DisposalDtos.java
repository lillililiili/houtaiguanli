package com.uav.lowaltitude.modules.disposal.api;

import java.util.List;
import java.util.Map;

/** 处置授权接口的请求与响应形状。全局 SNAKE_CASE + non_null 序列化，null 字段不出现在响应里。 */
public final class DisposalDtos {

    private DisposalDtos() { }

    public record CreateRequest(String actionType, String subjectKind, String subjectId, String deviceId,
            String channel, String reason) { }

    public record DecisionRequest(Long expectedVersion, String note) { }

    public record ExecuteRequest(Long expectedVersion, Map<String, Object> operationParams) { }

    public record ManualResultRequest(Long expectedVersion, String result, String detail) { }

    /**
     * 两个只读派生值，都由事件流推导、都不加列：
     * - device_stop_result（13-10 / 13-11）：授权撤销了不等于设备停了，STOPPED 旁必须据此带限定语；
     * - execution_block_reason（13-14 / 13-22）：执行为何还没做成。四值各对应一个**不同的补救方**
     *   （换设备 / 等厂家 / 运维补登记 / 现场处理），页面要分开说，否则运维会把自己能修的事当成厂家的事挂着。
     * 列表与详情都给，不让调用方自己去翻事件流。
     */
    public record AuthorizationDto(String authorizationId, String authorizationNo, String actionType,
            String subjectKind, String subjectId, String targetId, String deviceId, String channel, String reason,
            String requestedBy, String requestedByName, Long requestedAt, String approvedBy, String approvedByName,
            Long approvedAt, String decisionNote, Long validFrom, Long validUntil, String status,
            String executionCommandId, String resultCode, String resultDetail, String deviceStopResult,
            String executionBlockReason, String policyVersion, String policyStatus, String ownerOrgId, String districtId, String sourceMode,
            long version, List<String> allowedActions) { }

    public record EventDto(String eventId, String eventKind, String actorId, String actorName, String note,
            Map<String, Object> snapshot, long occurredAt) { }

    public record CreatedDto(String authorizationId, String authorizationNo, String status, long version) { }

    public record ActionResultDto(String authorizationId, String status, long version, String deviceStopResult,
            String executionCommandId, String resultCode) { }

    /** 策略透出：页面要据此标注"演示策略，待业务确认"（决策 13-1）。 */
    public record PolicyDto(String policyCode, String schemaStatus, Map<String, Object> params) { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }
}
