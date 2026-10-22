package com.uav.lowaltitude.platform.geo;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** GeoJSON uses WGS-84 longitude/latitude by contract; conflicting declarations are forbidden. */
public final class Wgs84GeoJson {
    private static final Set<String> CRS_NAMES = Set.of(
            "CRS84", "OGC:CRS84", "EPSG:4326",
            "urn:ogc:def:crs:OGC:1.3:CRS84",
            "urn:ogc:def:crs:EPSG::4326",
            "http://www.opengis.net/def/crs/OGC/1.3/CRS84",
            "http://www.opengis.net/def/crs/EPSG/0/4326");

    private Wgs84GeoJson() { }

    /** Checks this node only; callers must also check feature/geometry nodes they consume. */
    public static boolean hasSupportedDeclarations(JsonNode node) {
        if (node == null || node.isNull()) return true;
        if (!node.isObject()) return false;
        for (String field : new String[] {"coordinate_system", "coordinateSystem"}) {
            if (node.has(field) && (!node.get(field).isTextual()
                    || !Wgs84Coordinates.isWgs84(node.get(field).textValue()))) return false;
        }
        if (!node.has("crs")) return true;
        JsonNode crs = node.get("crs");
        if (!crs.isObject() || !"name".equals(crs.path("type").asText())) return false;
        JsonNode name = crs.path("properties").path("name");
        return name.isTextual() && CRS_NAMES.contains(name.textValue().trim());
    }
}
