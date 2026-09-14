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
            String ownerOrgName, String districtName) {
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

    public record AirspaceConflictDto(
            String planId, String routeVersionId, String airspaceId, String airspaceVersionId,
            String horizontalRelation, String heightRelation, String timeRelation,
            String conflictCode, List<String> unknownReasons) {
    }
}
