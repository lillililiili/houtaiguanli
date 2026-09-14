package com.uav.lowaltitude.modules.airport.api;

import java.math.BigDecimal;
import java.util.List;

/** 机场基础数据 DTO。通报对象只有逻辑名与角色，不含号码、邮箱或任何凭据。 */
public final class AirportDtos {
    private AirportDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }

    public record AirportDto(String airportId, String icaoCode, String name, BigDecimal longitude, BigDecimal latitude,
            BigDecimal elevationAmslM, String ownerOrgId, String districtId, String ownerOrgName, String districtName,
            boolean enabled, String note, long createdAt, long version) { }

    public record RunwayDto(String runwayId, String airportId, String designator, BigDecimal headingDeg,
            BigDecimal lengthM, long createdAt) { }

    public record ProcedureRouteDto(String routeId, String airportId, String kind, String name,
            BigDecimal protectWidthM, BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum, long createdAt) { }

    public record ProtectedTargetDto(String protectedTargetId, String airportId, String name, String kind,
            BigDecimal longitude, BigDecimal latitude, BigDecimal radiusM, long createdAt) { }

    public record NotificationTargetDto(String notificationTargetId, String airportId, String name, String role,
            String channelKind, boolean enabled, long createdAt) { }

    public record AirportDetailDto(AirportDto airport, List<RunwayDto> runways, List<ProcedureRouteDto> procedureRoutes,
            List<ProtectedTargetDto> protectedTargets, List<NotificationTargetDto> notificationTargets) { }
}
