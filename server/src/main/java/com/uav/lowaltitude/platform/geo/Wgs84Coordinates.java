package com.uav.lowaltitude.platform.geo;

import java.math.BigDecimal;

/**
 * The business coordinate contract is WGS-84 longitude/latitude in degrees.
 * A declaration validates the contract, not the geodetic provenance of numbers.
 * No datum conversion or inference from numeric ranges is performed here.
 */
public final class Wgs84Coordinates {
    private Wgs84Coordinates() { }

    public static boolean isWgs84(String coordinateSystem) {
        return coordinateSystem != null && ("WGS84".equalsIgnoreCase(coordinateSystem.trim())
                || "WGS-84".equalsIgnoreCase(coordinateSystem.trim()));
    }

    public static boolean isValidPoint(double longitude, double latitude) {
        return Double.isFinite(longitude) && Double.isFinite(latitude)
                && longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90;
    }

    /** An absent location is allowed; a supplied location must have an explicit datum. */
    public static void requirePoint(BigDecimal longitude, BigDecimal latitude, String coordinateSystem) {
        if ((longitude == null) != (latitude == null))
            throw new IllegalArgumentException("经度和纬度必须同时填写或同时为空");
        if (coordinateSystem != null && !isWgs84(coordinateSystem))
            throw new IllegalArgumentException("坐标系仅支持 WGS-84，不支持其他坐标系或空白声明");
        if (longitude == null) return;
        if (!isWgs84(coordinateSystem))
            throw new IllegalArgumentException("提供经纬度时必须明确声明坐标系为 WGS-84");
        if (longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0)
            throw new IllegalArgumentException("WGS-84 经度必须在 -180 到 180 之间，纬度必须在 -90 到 90 之间");
    }
}
