package com.uav.lowaltitude.modules.airspace.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GeoJSON FeatureCollection → 每要素一条解析结果（MultiPolygon EWKT + 属性）。
 * 单个要素的问题只记进 {@link Issue}，不抛异常：导入是"先看清再决定"，一条坏要素不能让操作者
 * 失去整份文件的预览；只有整份文件不可读（不是 JSON、根不是 FeatureCollection、声明了别的坐标系、
 * 要素过多）才给出 fatalIssue 并放弃全部要素。
 */
@Component
public class GeoJsonParser {
    /** 契约上限：一次导入最多 200 个要素、2 MB 文本。 */
    public static final int MAX_FEATURES = 200;
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MIN_RING_POINTS = 4;
    private static final double LON_MIN = -180, LON_MAX = 180, LAT_MIN = -90, LAT_MAX = 90;

    private final ObjectMapper json;

    public GeoJsonParser(ObjectMapper json) { this.json = json; }

    public record Issue(String field, String reasonCode) { }

    public record Feature(int seq, String name, String airspaceNo, String kindCode, String boundaryEwkt, String boundaryGeoJson,
            BigDecimal minAltitudeM, BigDecimal maxAltitudeM, String altitudeDatum, Instant validFrom, Instant validTo,
            List<Issue> issues) {
        public boolean usable() { return issues.isEmpty() && boundaryEwkt != null; }
    }

    /** fatalIssue 非空表示整份文件不可用，features 为空。 */
    public record ParseResult(String fatalIssue, List<Feature> features) {
        public static ParseResult fatal(String reasonCode) { return new ParseResult(reasonCode, List.of()); }
    }

    public ParseResult parse(String geoJson) {
        if (geoJson == null || geoJson.isBlank()) return ParseResult.fatal("INVALID_GEOJSON");
        if (geoJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) return ParseResult.fatal("IMPORT_TOO_LARGE");
        JsonNode root;
        try { root = json.readTree(geoJson); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { return ParseResult.fatal("INVALID_GEOJSON"); }
        if (root == null || !root.isObject() || !"FeatureCollection".equals(text(root, "type"))) return ParseResult.fatal("INVALID_GEOJSON");
        // 声明了别的坐标系就不能按 WGS-84 读：数字看起来合法，落到地图上却是另一个地方。
        if (!wgs84(root)) return ParseResult.fatal("CRS_NOT_SUPPORTED");
        JsonNode features = root.get("features");
        if (features == null || !features.isArray()) return ParseResult.fatal("INVALID_GEOJSON");
        if (features.size() > MAX_FEATURES) return ParseResult.fatal("IMPORT_TOO_LARGE");

        List<Feature> parsed = new ArrayList<>();
        int seq = 0;
        for (JsonNode node : features) parsed.add(feature(++seq, node));
        return new ParseResult(null, List.copyOf(parsed));
    }

    private Feature feature(int seq, JsonNode node) {
        List<Issue> issues = new ArrayList<>();
        JsonNode properties = node.get("properties");
        JsonNode geometry = node.get("geometry");
        String ewkt = null, geometryJson = null;
        if (geometry == null || geometry.isNull() || !geometry.isObject()) {
            issues.add(new Issue("geometry", "GEOMETRY_MISSING"));
        } else {
            geometryJson = geometry.toString();
            ewkt = geometry(geometry, issues);
        }
        String kindCode = text(properties, "kind_code");
        if (kindCode != null && !AirspaceKind.supported(kindCode)) {
            issues.add(new Issue("kind_code", "KIND_NOT_SUPPORTED"));
            kindCode = null;
        }
        Altitude altitude = altitude(properties, issues);
        Instant validFrom = time(properties, "valid_from", issues), validTo = time(properties, "valid_to", issues);
        if (validFrom != null && validTo != null && !validFrom.isBefore(validTo)) issues.add(new Issue("valid_to", "INVALID_VALIDITY"));
        return new Feature(seq, text(properties, "name"), text(properties, "airspace_no"), kindCode, ewkt, geometryJson,
                altitude.min(), altitude.max(), altitude.datum(), validFrom, validTo, List.copyOf(issues));
    }

    private record Altitude(BigDecimal min, BigDecimal max, String datum) { }

    /** 高度带三件套必须齐全：没有基准的数字不可比较，宁可整组不采用（AGL 与 AMSL 永不互推）。 */
    private Altitude altitude(JsonNode properties, List<Issue> issues) {
        BigDecimal min = decimal(properties, "min_altitude_m"), max = decimal(properties, "max_altitude_m");
        String datum = text(properties, "altitude_datum");
        if (min == null && max == null && datum == null) return new Altitude(null, null, null);
        if (min == null || max == null || datum == null) {
            issues.add(new Issue("altitude", "ALTITUDE_INCOMPLETE"));
            return new Altitude(null, null, null);
        }
        if (!"AGL".equals(datum) && !"AMSL".equals(datum)) {
            issues.add(new Issue("altitude_datum", "ALTITUDE_DATUM_NOT_SUPPORTED"));
            return new Altitude(null, null, null);
        }
        if (min.compareTo(max) > 0) {
            issues.add(new Issue("altitude", "ALTITUDE_RANGE_INVERTED"));
            return new Altitude(null, null, null);
        }
        return new Altitude(min, max, datum);
    }

    private String geometry(JsonNode geometry, List<Issue> issues) {
        String type = text(geometry, "type");
        // 先判类型再看坐标：GeometryCollection 之类根本没有 coordinates，先查坐标会把它报成"几何无效"，
        // 而操作者需要知道的是"这种几何类型不支持"。类型校验也不能交给列类型——
        // PostGIS 往 geometry(MultiPolygon,4326) 插单个 POLYGON 会自动提升，挡不住别的写法。
        if (!"Polygon".equals(type) && !"MultiPolygon".equals(type)) {
            issues.add(new Issue("geometry", "GEOMETRY_NOT_SUPPORTED"));
            return null;
        }
        JsonNode coordinates = geometry.get("coordinates");
        if (coordinates == null || !coordinates.isArray()) { issues.add(new Issue("geometry", "GEOMETRY_INVALID")); return null; }
        List<String> polygons = new ArrayList<>();
        if ("Polygon".equals(type)) {
            String polygon = polygon(coordinates, issues);
            if (polygon != null) polygons.add(polygon);
        } else {
            for (JsonNode polygonNode : coordinates) {
                String polygon = polygon(polygonNode, issues);
                if (polygon != null) polygons.add(polygon);
            }
        }
        if (polygons.isEmpty() || !issues.isEmpty()) return null;
        // 库里统一存 MultiPolygon：单个 Polygon 提升为一个面的 MultiPolygon，读写两侧只处理一种几何类型。
        return "SRID=4326;MULTIPOLYGON(" + String.join(",", polygons) + ")";
    }

    private String polygon(JsonNode polygon, List<Issue> issues) {
        if (!polygon.isArray() || polygon.isEmpty()) { issues.add(new Issue("geometry", "GEOMETRY_INVALID")); return null; }
        List<String> rings = new ArrayList<>();
        for (JsonNode ring : polygon) {
            String text = ring(ring, issues);
            if (text == null) return null;
            rings.add(text);
        }
        return "(" + String.join(",", rings) + ")";
    }

    private String ring(JsonNode ring, List<Issue> issues) {
        if (!ring.isArray() || ring.size() < MIN_RING_POINTS) { issues.add(new Issue("geometry", "RING_TOO_SHORT")); return null; }
        List<String> points = new ArrayList<>();
        Double firstLon = null, firstLat = null, lastLon = null, lastLat = null;
        for (JsonNode point : ring) {
            if (!point.isArray() || point.size() < 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                issues.add(new Issue("geometry", "COORDINATE_INVALID"));
                return null;
            }
            double longitude = point.get(0).doubleValue(), latitude = point.get(1).doubleValue();
            if (longitude < LON_MIN || longitude > LON_MAX || latitude < LAT_MIN || latitude > LAT_MAX) {
                issues.add(new Issue("geometry", "COORDINATE_OUT_OF_RANGE"));
                return null;
            }
            if (firstLon == null) { firstLon = longitude; firstLat = latitude; }
            lastLon = longitude; lastLat = latitude;
            points.add(number(longitude) + " " + number(latitude));
        }
        // 未闭合的环不是多边形：数据库的 ST_IsValid 也会拒绝，提前拦下才能在预览里说清原因。
        if (!firstLon.equals(lastLon) || !firstLat.equals(lastLat)) { issues.add(new Issue("geometry", "RING_NOT_CLOSED")); return null; }
        return "(" + String.join(",", points) + ")";
    }

    /** 未声明 crs 视为 WGS-84（GeoJSON 规范默认）；显式声明其他坐标系一律拒绝。 */
    private static boolean wgs84(JsonNode root) {
        JsonNode crs = root.get("crs");
        if (crs == null || crs.isNull()) return true;
        String name = crs.path("properties").path("name").asText("");
        return name.isEmpty() || name.contains("CRS84") || name.endsWith("4326");
    }

    private static String number(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        return decimal.scale() <= 0 ? decimal.toBigInteger().toString() : decimal.toPlainString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) return null;
        String text = value.textValue().trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? null : value.decimalValue();
    }

    private static Instant time(JsonNode node, String field, List<Issue> issues) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        try {
            if (value.isNumber()) return Instant.ofEpochMilli(value.longValue());
            if (value.isTextual()) return Instant.parse(value.textValue().trim());
        } catch (DateTimeParseException | ArithmeticException ignored) {
            // 落到下面统一记问题
        }
        issues.add(new Issue(field, "TIME_INVALID"));
        return null;
    }
}
