package com.uav.lowaltitude.modules.flight.api;

import java.math.BigDecimal;
import java.util.List;

public final class FlightDtos {

    private FlightDtos() {
    }

    public record PageDto<T>(List<T> items, int page, int size, long total) {
    }

    public record FieldIssueDto(String field, String reasonCode) {
    }

    public record SourceDto(String sourceId, String sourceCode, String sourceMode, String sourceName) {
    }

    /** maxAltitudeM 取自该计划引用的航线版本（列表页"最大高度"列）；航线版本未填时为 null。 */
    public record RouteReferenceDto(
            String routeVersionId, String routeId, String routeNo, String name, int versionNo, BigDecimal maxAltitudeM) {
    }

    public record GeoJsonLineStringDto(
            String type, List<List<BigDecimal>> coordinates, String coordinateSystem) {
    }

    /** attention 只在飞行任务列表按“需要处理”排序时给出；其他读取为 null，不出现在响应里。 */
    public record FlightPlanDto(
            String planId, String planNo, String statusCode, SourceDto source, String sourceMode, String uavSn,
            Long startAt, Long endAt, String ownerOrgId, String districtId, RouteReferenceDto route,
            List<FieldIssueDto> fieldIssues, long createdAt, long updatedAt, long version,
            String ownerOrgName, String districtName, PlanFilingDto filing, PlanAttentionDto attention) {
        public FlightPlanDto(String planId, String planNo, String statusCode, SourceDto source, String sourceMode, String uavSn,
                Long startAt, Long endAt, String ownerOrgId, String districtId, RouteReferenceDto route,
                List<FieldIssueDto> fieldIssues, long createdAt, long updatedAt, long version,
                String ownerOrgName, String districtName, PlanFilingDto filing) {
            this(planId, planNo, statusCode, source, sourceMode, uavSn, startAt, endAt, ownerOrgId, districtId, route,
                    fieldIssues, createdAt, updatedAt, version, ownerOrgName, districtName, filing, null);
        }
        public FlightPlanDto withAttention(PlanAttentionDto value) {
            return new FlightPlanDto(planId, planNo, statusCode, source, sourceMode, uavSn, startAt, endAt, ownerOrgId, districtId,
                    route, fieldIssues, createdAt, updatedAt, version, ownerOrgName, districtName, filing, value);
        }
    }

    /**
     * 飞行任务“需要处理”：kinds 仅取 DEVICE（覆盖航线的设备当前异常或处置单未办结）、RISK（风险待核验/待通知）。
     * matchIssue 保留只读匹配事实，不参与待处理分类、排序或计数，为 DEVIATION、NONE、NO_EVALUATION、
     * PARTIAL、UNDETERMINED 之一，没有问题或无权读研判时为 null。
     */
    public record PlanAttentionDto(List<String> kinds, String matchIssue, long pendingVerificationRisks,
            long pendingNotificationRisks, List<DeviceTaskDto> deviceTasks, List<RiskKindDto> pendingRiskKinds,
            List<DeviceCheckAttentionDto> deviceChecks) {
        public boolean needsAction() { return !kinds.isEmpty(); }
    }

    public record DeviceCheckAttentionDto(String deviceId,String deviceName,boolean abnormal,String connectivity,
            String healthCode,String taskId,String workflowState) { }

    public record DeviceTaskDto(String taskId, String deviceName, String workflowState) {
    }

    /** 待处理风险的种类（页面写“雷雨预警”“空中异物”等），按种类去重。 */
    public record RiskKindDto(String riskType, String reasonCode) {
    }

    /** 待处理任务去重计数，device/risk 可重叠；deviation/aircraft 为兼容字段固定为零；otherExecuting 为其余执行中数量。 */
    public record AttentionSummaryDto(long needsAction, long deviation, long aircraft, long device, long risk, long otherExecuting) {
    }

    public record PlanPageDto(List<FlightPlanDto> items, int page, int size, long total, AttentionSummaryDto attentionSummary) {
    }

    /** 仅计划详情返回报备事实；不把所属机构或航线端点代替未报备的信息。 */
    public record PlanFilingDto(String pilotName, String operatorName, String takeoffSiteName, String landingSiteName,
            BigDecimal takeoffLongitude, BigDecimal takeoffLatitude, BigDecimal landingLongitude, BigDecimal landingLatitude,
            String coordinateSystem) { }

    public record RouteDto(
            String routeId, String routeNo, String name, boolean enabled, SourceDto source, String sourceMode,
            String ownerOrgId, String districtId, long createdAt, long updatedAt, long version,
            String ownerOrgName, String districtName) {
    }

    public record RouteVersionDto(
            String routeVersionId, String routeId, int versionNo, GeoJsonLineStringDto centerline,
            BigDecimal corridorWidthM, BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum,
            long validFrom, Long validTo, String changeReason, List<FieldIssueDto> fieldIssues, long createdAt) {
    }
}
