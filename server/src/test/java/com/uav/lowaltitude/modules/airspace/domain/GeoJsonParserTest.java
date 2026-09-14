package com.uav.lowaltitude.modules.airspace.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser.Feature;
import com.uav.lowaltitude.modules.airspace.domain.GeoJsonParser.ParseResult;

/**
 * GeoJSON 解析：纯 Java，不碰数据库。坏要素进 issues 而不是抛异常——导入是"先看清再决定"，
 * 一条坏要素不能让操作者失去整份文件的预览。
 */
class GeoJsonParserTest {
    private final GeoJsonParser parser = new GeoJsonParser(new ObjectMapper());

    private static String polygon(String coordinates) {
        return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"name\":\"甲区\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + coordinates + "}}]}";
    }

    private static final String SQUARE = "[[[118.0,37.0],[118.1,37.0],[118.1,37.1],[118.0,37.1],[118.0,37.0]]]";

    @Test
    void polygonIsPromotedToMultiPolygonEwkt() {
        ParseResult result = parser.parse(polygon(SQUARE));
        assertThat(result.features()).hasSize(1);
        Feature feature = result.features().get(0);
        assertThat(feature.issues()).isEmpty();
        assertThat(feature.name()).isEqualTo("甲区");
        // 库里统一存 MultiPolygon：单个 Polygon 被提升为只有一个面的 MultiPolygon，坐标顺序保持 [经度, 纬度]。
        assertThat(feature.boundaryEwkt()).startsWith("SRID=4326;MULTIPOLYGON(((118 37,");
        assertThat(feature.boundaryEwkt()).endsWith(")))");
        assertThat(result.fatalIssue()).isNull();
    }

    @Test
    void polygonHolesArePreserved() {
        String withHole = "[[[118.0,37.0],[118.4,37.0],[118.4,37.4],[118.0,37.4],[118.0,37.0]],"
                + "[[118.1,37.1],[118.2,37.1],[118.2,37.2],[118.1,37.2],[118.1,37.1]]]";
        Feature feature = parser.parse(polygon(withHole)).features().get(0);
        assertThat(feature.issues()).isEmpty();
        // 两个环都必须保留：丢掉内环会把"甜甜圈"变成实心面，禁飞范围被悄悄放大。
        assertThat(feature.boundaryEwkt()).contains("),(");
        assertThat(feature.boundaryEwkt().split("\\),\\(")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void multiPolygonIsAcceptedAsIs() {
        String multi = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
                + "\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[" + SQUARE + "]}}]}";
        Feature feature = parser.parse(multi).features().get(0);
        assertThat(feature.issues()).isEmpty();
        assertThat(feature.boundaryEwkt()).startsWith("SRID=4326;MULTIPOLYGON(((");
    }

    @Test
    void unclosedRingAndOutOfRangeCoordinatesBecomeIssues() {
        Feature unclosed = parser.parse(polygon("[[[118.0,37.0],[118.1,37.0],[118.1,37.1],[118.0,37.1]]]")).features().get(0);
        assertThat(unclosed.issues()).extracting(GeoJsonParser.Issue::reasonCode).contains("RING_NOT_CLOSED");
        assertThat(unclosed.boundaryEwkt()).isNull();

        Feature outOfRange = parser.parse(polygon("[[[181.0,37.0],[181.1,37.0],[181.1,37.1],[181.0,37.1],[181.0,37.0]]]")).features().get(0);
        assertThat(outOfRange.issues()).extracting(GeoJsonParser.Issue::reasonCode).contains("COORDINATE_OUT_OF_RANGE");

        Feature tooFewPoints = parser.parse(polygon("[[[118.0,37.0],[118.1,37.0],[118.0,37.0]]]")).features().get(0);
        assertThat(tooFewPoints.issues()).extracting(GeoJsonParser.Issue::reasonCode).contains("RING_TOO_SHORT");
    }

    @Test
    void unsupportedGeometryAndMissingGeometryBecomeIssues() {
        String point = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[118.0,37.0]}}]}";
        assertThat(parser.parse(point).features().get(0).issues()).extracting(GeoJsonParser.Issue::reasonCode).contains("GEOMETRY_NOT_SUPPORTED");
        String none = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}}]}";
        assertThat(parser.parse(none).features().get(0).issues()).extracting(GeoJsonParser.Issue::reasonCode).contains("GEOMETRY_MISSING");
    }

    @Test
    void geometryTypeIsEnforcedByTheParserNotByTheColumnType() {
        // PostGIS 往 geometry(MultiPolygon,4326) 里插单个 POLYGON 不会报错，它会自动提升为 MULTIPOLYGON，
        // 所以"只有面/多面能进来"这条必须由解析器把住；列类型挡不住 LineString 之外的其它写法也一样。
        Feature promoted = parser.parse(polygon(SQUARE)).features().get(0);
        assertThat(promoted.issues()).isEmpty();
        assertThat(promoted.boundaryEwkt()).startsWith("SRID=4326;MULTIPOLYGON(((");

        String lineString = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[118.0,37.0],[118.1,37.1]]}}]}";
        Feature line = parser.parse(lineString).features().get(0);
        assertThat(line.issues()).extracting(GeoJsonParser.Issue::reasonCode).contains("GEOMETRY_NOT_SUPPORTED");
        assertThat(line.boundaryEwkt()).isNull();

        String collection = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
                + "\"geometry\":{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}]}}]}";
        Feature mixed = parser.parse(collection).features().get(0);
        assertThat(mixed.issues()).extracting(GeoJsonParser.Issue::reasonCode).contains("GEOMETRY_NOT_SUPPORTED");
        assertThat(mixed.boundaryEwkt()).isNull();

        // MultiPolygon 里混进一条线：整条要素不可用，不能只把能读懂的那部分放进来。
        String badMember = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
                + "\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[" + SQUARE + ",[[[118.0,37.0],[118.1,37.1]]]]}}]}";
        Feature partial = parser.parse(badMember).features().get(0);
        assertThat(partial.issues()).isNotEmpty();
        assertThat(partial.boundaryEwkt()).isNull();
    }

    @Test
    void declaredNonWgs84CrsIsRejectedForTheWholeCollection() {
        String crs = "{\"type\":\"FeatureCollection\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:EPSG::3857\"}},"
                + "\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}}]}";
        ParseResult result = parser.parse(crs);
        // 声明了别的坐标系就不能按 WGS-84 读：数字看起来合法，落到地图上却是另一个地方。
        assertThat(result.fatalIssue()).isEqualTo("CRS_NOT_SUPPORTED");
        assertThat(result.features()).isEmpty();
    }

    @Test
    void tooManyFeaturesIsAFatalIssue() {
        StringBuilder builder = new StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[");
        for (int i = 0; i <= GeoJsonParser.MAX_FEATURES; i++) {
            if (i > 0) builder.append(',');
            builder.append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":").append(SQUARE).append("}}");
        }
        ParseResult result = parser.parse(builder.append("]}").toString());
        assertThat(result.fatalIssue()).isEqualTo("IMPORT_TOO_LARGE");
        assertThat(result.features()).isEmpty();
    }

    @Test
    void malformedJsonAndWrongRootTypeAreFatal() {
        assertThat(parser.parse("{not-json").fatalIssue()).isEqualTo("INVALID_GEOJSON");
        assertThat(parser.parse("{\"type\":\"Polygon\",\"coordinates\":[]}").fatalIssue()).isEqualTo("INVALID_GEOJSON");
        assertThat(parser.parse("").fatalIssue()).isEqualTo("INVALID_GEOJSON");
    }

    @Test
    void propertiesAreReadAndDefaultsApplyOnlyWhereMissing() {
        String withProperties = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":"
                + "{\"name\":\"乙区\",\"airspace_no\":\"KY-9-002\",\"kind_code\":\"RESTRICTED\",\"min_altitude_m\":10,\"max_altitude_m\":120,"
                + "\"altitude_datum\":\"AMSL\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}}]}";
        Feature feature = parser.parse(withProperties).features().get(0);
        assertThat(feature.airspaceNo()).isEqualTo("KY-9-002");
        assertThat(feature.kindCode()).isEqualTo("RESTRICTED");
        assertThat(feature.minAltitudeM()).isEqualByComparingTo("10");
        assertThat(feature.maxAltitudeM()).isEqualByComparingTo("120");
        assertThat(feature.altitudeDatum()).isEqualTo("AMSL");
        assertThat(feature.issues()).isEmpty();
        // 只给一半高度：没有基准就没有可比的高度事实，整组高度都不采用并记问题。
        String halfAltitude = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":"
                + "{\"min_altitude_m\":10},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}}]}";
        Feature partial = parser.parse(halfAltitude).features().get(0);
        assertThat(partial.issues()).extracting(GeoJsonParser.Issue::reasonCode).contains("ALTITUDE_INCOMPLETE");
        assertThat(partial.minAltitudeM()).isNull();
        assertThat(partial.altitudeDatum()).isNull();
    }

    @Test
    void featureSequenceStartsAtOneAndCountsEveryFeature() {
        String two = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}},"
                + "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,1]}}]}";
        List<Feature> features = parser.parse(two).features();
        assertThat(features).hasSize(2);
        assertThat(features).extracting(Feature::seq).containsExactly(1, 2);
        // 坏要素也留在结果里：操作者需要看到"第 2 条为什么没进来"。
        assertThat(features.get(1).issues()).isNotEmpty();
    }
}
