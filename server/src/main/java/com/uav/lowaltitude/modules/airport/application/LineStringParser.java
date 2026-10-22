package com.uav.lowaltitude.modules.airport.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.regex.Pattern;

import com.uav.lowaltitude.platform.geo.Wgs84Coordinates;

/** The airport API accepts an explicitly declared, two-dimensional WGS-84 EWKT line. */
final class LineStringParser {
    private static final Pattern LINE = Pattern.compile(
            "^SRID\\s*=\\s*4326\\s*;\\s*LINESTRING\\s*\\(([^()]+)\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private LineStringParser() { }

    static String parse(String ewkt) {
        if (ewkt == null) return null;
        var match = LINE.matcher(ewkt.trim());
        if (!match.matches()) throw new IllegalArgumentException("航线必须为声明 SRID=4326 的二维 LINESTRING");
        String[] pairs = match.group(1).split(",", -1);
        if (pairs.length < 2) throw new IllegalArgumentException("航线至少需要两个坐标点");
        var points = new ArrayList<String>();
        for (String pair : pairs) {
            String[] values = pair.trim().split("\\s+");
            if (values.length != 2 || !NUMBER.matcher(values[0]).matches() || !NUMBER.matcher(values[1]).matches())
                throw new IllegalArgumentException("航线必须包含有效的二维 WGS84 经纬度");
            try {
                BigDecimal longitude = new BigDecimal(values[0]), latitude = new BigDecimal(values[1]);
                Wgs84Coordinates.requirePoint(longitude, latitude, "WGS84");
                points.add(longitude.stripTrailingZeros().toString() + " " + latitude.stripTrailingZeros().toString());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("航线经纬度格式无效", ex);
            }
        }
        return "SRID=4326;LINESTRING (" + String.join(",", points) + ")";
    }
}
