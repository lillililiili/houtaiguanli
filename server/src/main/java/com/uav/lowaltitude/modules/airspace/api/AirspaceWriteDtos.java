package com.uav.lowaltitude.modules.airspace.api;

import java.math.BigDecimal;
import java.util.List;

/** 阶段 9 空域写接口的响应形状（snake_case 由全局 Jackson 策略转换）。 */
public final class AirspaceWriteDtos {

    private AirspaceWriteDtos() { }

    public record CreatedAirspaceDto(String airspaceId, String airspaceVersionId, int versionNo, long version) { }

    public record CreatedVersionDto(String airspaceId, String airspaceVersionId, int versionNo, long version,
            String supersededVersionId, Long supersededValidTo) { }

    public record ImportIssueDto(String field, String reasonCode) { }

    public record ImportItemDto(String itemId, int seq, String name, String airspaceNo, String kindCode,
            BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum, Long validFrom, Long validTo,
            List<ImportIssueDto> issues, boolean accepted, String targetAirspaceId, String resultAirspaceVersionId) { }

    public record ImportBatchDto(String batchId, String status, int featureCount, int acceptedCount, String ownerOrgId,
            String districtId, Long createdAt, Long decidedAt, long version, List<ImportItemDto> items) { }

    public record ImportDecisionDto(String batchId, String status, int createdAirspaces, int createdVersions, long version) { }

    public record DiffFieldDto(String field, String from, String to) { }

    public record DiffGeometryDto(boolean changed, BigDecimal areaDeltaM2, String availability) { }

    public record VersionDiffDto(String airspaceId, String fromVersionId, String toVersionId, Integer fromVersionNo, Integer toVersionNo,
            List<DiffFieldDto> fields, DiffGeometryDto geometry) { }
}
