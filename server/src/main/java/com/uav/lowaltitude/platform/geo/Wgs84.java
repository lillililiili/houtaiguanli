package com.uav.lowaltitude.platform.geo;

import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** Horizontal coordinate declarations only; recognizing a name never converts coordinates. */
public final class Wgs84 {
    public static final String DEVICE_NAME = "WGS-84";
    private static final Set<String> GEOJSON_NAMES = Set.of(
            "WGS84", "WGS-84", "CRS84", "OGC:CRS84", "EPSG:4326",
            "URN:OGC:DEF:CRS:EPSG::4326", "URN:OGC:DEF:CRS:EPSG:6.6:4326",
            "URN:OGC:DEF:CRS:OGC:1.3:CRS84",
            "HTTP://WWW.OPENGIS.NET/DEF/CRS/EPSG/0/4326",
            "HTTP://WWW.OPENGIS.NET/DEF/CRS/OGC/1.3/CRS84",
            "HTTP://WWW.OPENGIS.NET/GML/SRS/EPSG.XML#4326");

    private Wgs84() { }

    public static boolean isWgs84(String value) {
        String name = normalized(value);
        return "WGS84".equals(name) || DEVICE_NAME.equals(name);
    }

    /** Absent GeoJSON declarations use its WGS-84 default; explicit unknown/conflicting ones fail closed. */
    public static boolean supportsGeoJson(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        for (String field : new String[]{"coordinate_system", "coordinateSystem"}) {
            if (node.has(field) && (!node.get(field).isTextual() || !isWgs84(node.get(field).textValue()))) return false;
        }
        if (!node.has("crs")) return true;
        JsonNode crs = node.get("crs");
        if (!crs.isObject() || !"name".equals(crs.path("type").asText())) return false;
        JsonNode name = crs.path("properties").path("name");
        return name.isTextual() && GEOJSON_NAMES.contains(normalized(name.textValue()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
