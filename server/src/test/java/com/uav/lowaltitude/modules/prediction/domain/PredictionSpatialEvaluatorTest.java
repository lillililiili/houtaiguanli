package com.uav.lowaltitude.modules.prediction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Forecast;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Height;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Point;
import com.uav.lowaltitude.modules.prediction.domain.PredictionSpatialEvaluator.AirspaceVersion;
import com.uav.lowaltitude.modules.prediction.domain.PredictionSpatialEvaluator.GeoPoint;
import com.uav.lowaltitude.modules.prediction.domain.PredictionSpatialEvaluator.Polygon;
import com.uav.lowaltitude.modules.prediction.domain.PredictionStability.Decision;

class PredictionSpatialEvaluatorTest {
    private static final long NOW = 1_800_000_000_000L;
    private final PredictionSpatialEvaluator evaluator = new PredictionSpatialEvaluator();

    @ParameterizedTest
    @CsvSource({ "118.6,37.4", "120.1,31.2" })
    void findsNarrowRegionBetweenDisplayedAndEvenOneSecondPoints(double lon, double lat) {
        Forecast f = forecast(lon, lat, 0.05, true);
        var zone = zone("a", "v1", NOW, null, List.of(rectangle(lon, lat, 121.1, -10, 121.9, 10)));
        var result = evaluator.evaluateForAirspace("a", f, List.of(zone));
        assertThat(result.decision()).isEqualTo(Decision.ENTER);
        assertThat(result.entrySeconds()).isBetween(12.11, 12.19);
    }

    @Test
    void extendedSpatialRunwayIsOptInAndKeepsTheStandardThirtySecondWindow() {
        double lon = 118, lat = 37;
        Forecast extended = forecast(lon, lat, 0.05, true, 120);
        var zone = zone("a", "v1", NOW, null, List.of(rectangle(lon, lat, 900, -10, 1100, 10)));
        assertThat(evaluator.evaluateForAirspace("a", extended, List.of(zone)).decision()).isEqualTo(Decision.CLEAR);
        var qa = evaluator.evaluateForAirspace("a", extended, List.of(zone), 120);
        assertThat(qa.decision()).isEqualTo(Decision.ENTER);
        assertThat(qa.entrySeconds()).isBetween(90.4, 90.6);
    }

    @Test
    void holesAndMultipolygonsDoNotBecomeSolidBoundingBoxes() {
        double lon = 118, lat = 37;
        Polygon withHole = new Polygon(rectangle(lon, lat, -100, -100, 400, 100).shell(),
                List.of(rectangle(lon, lat, -20, -20, 320, 20).shell()));
        var clear = evaluator.evaluateForAirspace("a", forecast(lon, lat, 1, true),
                List.of(zone("a", "v", NOW, null, List.of(withHole))));
        assertThat(clear.decision()).isEqualTo(Decision.CLEAR);
        var hit = evaluator.evaluateForAirspace("a", forecast(lon, lat, 1, true),
                List.of(zone("a", "v", NOW, null, List.of(rectangle(lon, lat, 50, 50, 60, 60), rectangle(lon, lat, 150, -10, 180, 10)))));
        assertThat(hit.decision()).isEqualTo(Decision.ENTER);
    }

    @Test
    void touchingAndUncertaintyOverlapRemainUnknown() {
        double lon = 118, lat = 37;
        var v = zone("a", "v", NOW, null, List.of(rectangle(lon, lat, 100, 5, 200, 20)));
        assertThat(evaluator.evaluateForAirspace("a", forecast(lon, lat, 5, true), List.of(v)).decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(evaluator.evaluateForAirspace("a", forecast(lon, lat, 4, true), List.of(v)).decision()).isEqualTo(Decision.CLEAR);
        assertThat(evaluator.evaluateForAirspace("a", forecast(lon, lat, 6, true), List.of(v)).decision()).isEqualTo(Decision.UNKNOWN);
    }

    @Test
    void usesVersionAtTheTimeOfCrossingAndHalfOpenExpiry() {
        double lon = 118, lat = 37;
        var geometry = List.of(rectangle(lon, lat, 120, -10, 180, 10));
        Forecast f = forecast(lon, lat, 0.1, true);
        assertThat(evaluator.evaluateForAirspace("a", f, List.of(zone("a", "v1", NOW, NOW + 10_000, geometry))).decision()).isEqualTo(Decision.CLEAR);
        assertThat(evaluator.evaluateForAirspace("a", f, List.of(zone("a", "v1", NOW + 10_000, NOW + 20_000, geometry))).decision()).isEqualTo(Decision.ENTER);
        var old = zone("a", "old", NOW, NOW + 10_000, geometry);
        var next = zone("a", "new", NOW + 10_000, null, geometry);
        assertThat(evaluator.evaluateForAirspace("a", f, List.of(old, next)).decision()).isEqualTo(Decision.ENTER);
    }

    @Test
    void overlappingVersionsAreUnknownEvenWhenOneGeometryIsFarAway() {
        double lon = 118, lat = 37;
        var a = zone("a", "v1", NOW, NOW + 20_000, List.of(rectangle(lon, lat, 120, -10, 180, 10)));
        var b = zone("a", "v2", NOW + 15_000, null, List.of(rectangle(lon, lat, 1000, 1000, 1100, 1100)));
        assertThat(evaluator.evaluateForAirspace("a", forecast(lon, lat, 1, true), List.of(a, b)).reasons()).contains("AIRSPACE_VERSION_AMBIGUOUS");
    }

    @Test
    void heightCannotBeInferredButHorizontallyDisjointCanBeCleared() {
        double lon = 118, lat = 37;
        var near = zone("a", "v1", NOW, null, List.of(rectangle(lon, lat, 120, -10, 180, 10)));
        var far = zone("b", "v2", NOW, null, List.of(rectangle(lon, lat, 120, 100, 180, 110)));
        var result = evaluator.evaluate(forecast(lon, lat, 1, false), List.of(near, far));
        assertThat(result.get(0).decision()).isEqualTo(Decision.UNKNOWN);
        assertThat(result.get(1).decision()).isEqualTo(Decision.CLEAR);
        var agl = new AirspaceVersion("a", "v1", NOW, null, "PROHIBITED", 0.0, 200.0, "AGL", near.polygons());
        assertThat(evaluator.evaluateForAirspace("a", forecast(lon, lat, 1, true), List.of(agl)).decision()).isEqualTo(Decision.UNKNOWN);
    }

    @Test
    void eachAreaHasItsOwnResultAndNonProhibitedVersionsDoNotWarn() {
        double lon = 118, lat = 37;
        var a = zone("a", "v1", NOW, null, List.of(rectangle(lon, lat, 120, -10, 180, 10)));
        var b = new AirspaceVersion("b", "v2", NOW, null, "RESTRICTED", 0.0, 200.0, "AMSL", a.polygons());
        var result = evaluator.evaluate(forecast(lon, lat, 1, true), List.of(a, b));
        assertThat(result).extracting(PredictionSpatialEvaluator.Assessment::decision).containsExactly(Decision.ENTER, Decision.CLEAR);
    }

    private static Forecast forecast(double lon, double lat, double error, boolean height) {
        return forecast(lon, lat, error, height, 30);
    }
    private static Forecast forecast(double lon, double lat, double error, boolean height, int horizonSeconds) {
        List<Point> points = new ArrayList<>();
        List<Point> milestones = new ArrayList<>();
        for (int seconds = 0; seconds <= horizonSeconds; seconds++) {
            double[] ll = AlphaBetaFilter.fromEnu(lon, lat, seconds * 10, 0);
            Point point = new Point(seconds, ll[0], ll[1], error, height ? new Height(100, 5) : null, null);
            points.add(point);
            if (seconds == 10 || seconds == 20 || seconds == 30 || (seconds > 30 && seconds % 30 == 0))
                milestones.add(point);
        }
        return new Forecast(NOW, NOW, UUID.randomUUID().toString(), lon, lat, 10, 0, List.of(), points,
                milestones, null, PredictionEngine.ALGORITHM_VERSION);
    }
    private static Polygon rectangle(double lon, double lat, double x1, double y1, double x2, double y2) {
        List<GeoPoint> ring = new ArrayList<>();
        for (double[] p : new double[][] { {x1,y1}, {x2,y1}, {x2,y2}, {x1,y2}, {x1,y1} }) {
            double[] ll = AlphaBetaFilter.fromEnu(lon, lat, p[0], p[1]);
            ring.add(new GeoPoint(ll[0], ll[1]));
        }
        return new Polygon(ring, List.of());
    }
    private static AirspaceVersion zone(String id, String version, long from, Long to, List<Polygon> polygons) {
        return new AirspaceVersion(id, version, from, to, "PROHIBITED", 0.0, 200.0, "AMSL", polygons);
    }
}
