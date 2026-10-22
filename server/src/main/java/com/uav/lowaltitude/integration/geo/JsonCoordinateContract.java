package com.uav.lowaltitude.integration.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.platform.geo.Wgs84Coordinates;

/**
 * Explicit coordinate declarations in JSON reports. A packet declaration may be
 * inherited by its children, but no child may override it with a different datum.
 */
public final class JsonCoordinateContract {
    private JsonCoordinateContract() { }

    public static boolean hasCoordinates(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) return false;
        for (String field : fields) if (node.hasNonNull(field)) return true;
        return false;
    }

    public static void requireWgs84(JsonNode... scopes) {
        boolean declared = false;
        for (JsonNode scope : scopes) {
            if (scope == null || !scope.isObject()) continue;
            for (String field : new String[] {"coordinate_system", "coordinateSystem"}) {
                if (!scope.has(field)) continue;
                JsonNode value = scope.get(field);
                if (!value.isTextual() || !Wgs84Coordinates.isWgs84(value.textValue()))
                    throw new IllegalArgumentException("COORDINATE_SYSTEM_NOT_SUPPORTED");
                declared = true;
            }
        }
        if (!declared) throw new IllegalArgumentException("COORDINATE_SYSTEM_REQUIRED");
    }

    /** Protocol A positions include the controller coordinates inside extension. */
    public static void requireSenseData(JsonNode root) {
        JsonNode objects = root == null ? null : root.get("objects");
        if (objects == null || !objects.isArray()) return;
        for (JsonNode object : objects) {
            JsonNode extension = object.get("extension");
            if (hasCoordinates(object, "longitude", "latitude")
                    || hasCoordinates(extension, "pilotLon", "pilotLat"))
                requireWgs84(root, object, extension);
        }
    }
}
