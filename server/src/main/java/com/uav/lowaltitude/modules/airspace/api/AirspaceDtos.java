package com.uav.lowaltitude.modules.airspace.api;

import java.math.BigDecimal;
import java.util.List;

public final class AirspaceDtos {

    private AirspaceDtos() {
    }

    public record PageDto<T>(List<T> items, int page, int size, long total) {
    }

    public record FieldIssueDto(String field, String reasonCode) {
    }

    public record GeoJsonMultiPolygonDto(
            String type, List<List<List<List<BigDecimal>>>> coordinates, String coordinateSystem) {
    }

    public record AirspaceSummaryDto(
            String airspaceId, String airspaceNo, String name, String sourceMode,
            String ownerOrgId, String districtId, long createdAt, long updatedAt, long version,
            String ownerOrgName, String districtName, AirspaceVersionDto currentVersion) {
        public AirspaceSummaryDto(String airspaceId, String airspaceNo, String name, String sourceMode,
                String ownerOrgId, String districtId, long createdAt, long updatedAt, long version,
                String ownerOrgName, String districtName) {
            this(airspaceId, airspaceNo, name, sourceMode, ownerOrgId, districtId, createdAt, updatedAt, version,
                    ownerOrgName, districtName, null);
        }
    }
    public record AirspaceDetailDto(String airspaceId, String airspaceNo, String name, String sourceMode,
            String ownerOrgId, String districtId, long createdAt, long updatedAt, long version,
            AirspaceVersionDto currentVersion, String ownerOrgName, String districtName) { }

    public record AirspaceVersionDto(
            String airspaceVersionId, String airspaceId, int versionNo, String kindCode,
            GeoJsonMultiPolygonDto boundary, BigDecimal minAltitudeM, BigDecimal maxAltitudeM,
            String altitudeDatum, long validFrom, Long validTo, String changeReason,
            List<FieldIssueDto> fieldIssues, long createdAt) {
    }

    /** 边界与限制固定到历史版本；名称与管理单位为当前目录辨识资料。 */
    public record VersionContextDto(String airspaceId, String airspaceNo, String name, String sourceMode,
            String ownerOrgName, AirspaceVersionDto version) { }

    public record AirspaceConflictDto(
            String planId, String routeVersionId, String airspaceId, String airspaceVersionId,
            String horizontalRelation, String heightRelation, String timeRelation,
            String conflictCode, List<String> unknownReasons) {
    }
}
