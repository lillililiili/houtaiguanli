package com.uav.lowaltitude.modules.prediction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Config;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Measurement;

class PredictionEngineTest {
    private static final long BASE = 1_800_000_000_000L;
    private final PredictionEngine engine = new PredictionEngine();

    @ParameterizedTest
    @CsvSource({ "118.6,37.4,0", "120.1,31.2,7200000" })
    void forecastsTenTwentyThirtySecondsFromARealRecentWindow(double lon, double lat, long shift) {
        long now = BASE + shift;
        List<Measurement> points = measurements(lon, lat, now, i -> new double[] { i * 10, i * 2 });
        var result = engine.forecast(points, now, Config.defaults());
        assertThat(result.reliable()).isTrue();
        assertThat(result.forecast().milestones()).extracting(PredictionEngine.Point::seconds).containsExactly(10, 20, 30);
        assertThat(result.forecast().points()).hasSize(31);
        var endpoint = result.forecast().points().get(30);
        double[] expected = AlphaBetaFilter.fromEnu(lon, lat, 390, 78);
        assertThat(AlphaBetaFilter.distanceM(expected[0], expected[1], endpoint.longitude(), endpoint.latitude())).isLessThan(0.01);
        assertThat(endpoint.uncertaintyM()).isGreaterThan(result.forecast().points().get(0).uncertaintyM());
        assertThat(result.forecast().originAt()).isEqualTo(now);
        assertThat(result.forecast().lastMeasuredAt()).isEqualTo(now);
    }

    @Test
    void originAdvancesFromFreshMeasurementWithoutClaimingAnotherObservation() {
        var result = engine.forecast(measurements(118, 37, BASE, i -> new double[] { i * 10, 0 }), BASE + 2_000, Config.defaults());
        assertThat(result.reliable()).isTrue();
        assertThat(result.forecast().originAt()).isEqualTo(BASE + 2_000);
        assertThat(result.forecast().lastMeasuredAt()).isEqualTo(BASE);
        double[] expected = AlphaBetaFilter.fromEnu(118, 37, 110, 0);
        var origin = result.forecast().points().get(0);
        assertThat(AlphaBetaFilter.distanceM(expected[0], expected[1], origin.longitude(), origin.latitude())).isLessThan(0.01);
    }

    @Test
    void duplicateTimestampsDoNotSupplyFiveIndependentObservations() {
        Measurement one = measurements(118, 37, BASE, i -> new double[] { i * 10, 0 }).get(9);
        assertThat(engine.forecast(List.of(one, one, one, one, one), BASE, Config.defaults()).reason()).isEqualTo("INSUFFICIENT_POINTS");
    }

    @Test
    void staleUnknownAccuracyAndFutureInputsAreNotPredicted() {
        var data = measurements(118, 37, BASE, i -> new double[] { i * 10, 0 });
        assertThat(engine.forecast(data, BASE + 3_001, Config.defaults()).reason()).isEqualTo("STALE_MEASUREMENT");
        var bad = new ArrayList<>(data);
        Measurement p = bad.get(9);
        bad.set(9, new Measurement(p.pointId(), p.observedAt(), p.receivedAt(), p.longitude(), p.latitude(), 0, null, null));
        assertThat(engine.forecast(bad, BASE, Config.defaults()).reason()).isEqualTo("POSITION_ACCURACY_UNSUITABLE");
        assertThat(engine.forecast(data, BASE - 1, Config.defaults()).reason()).isEqualTo("TIME_UNTRUSTED");
    }

    @Test
    void rejectsGapAndDoesNotJoinDisconnectedSegments() {
        var data = new ArrayList<>(measurements(118, 37, BASE, i -> new double[] { i * 10, 0 }));
        data.subList(3, 6).clear();
        assertThat(engine.forecast(data, BASE, Config.defaults()).reason()).isEqualTo("DISCONTINUOUS_MEASUREMENTS");
    }

    @Test
    void rejectsTurnAccelerationAndNoiseOnlyHeading() {
        assertThat(engine.forecast(measurements(118, 37, BASE, i -> i < 5 ? new double[] { i * 10, 0 }
                : new double[] { 40, (i - 4) * 10 }), BASE, Config.defaults()).reliable()).isFalse();
        assertThat(engine.forecast(measurements(118, 37, BASE, i -> new double[] { i * i * 3, 0 }), BASE, Config.defaults()).reason()).isEqualTo("SPEED_UNSTABLE");
        assertThat(engine.forecast(measurements(118, 37, BASE, i -> new double[] { i * 0.01, 0 }), BASE, Config.defaults()).reason()).isEqualTo("DIRECTION_UNRESOLVED");
    }

    @Test
    void missingAglNeverUsesAmslAsAReplacement() {
        var points = measurements(118, 37, BASE, i -> new double[] { i * 10, 0 });
        var result = engine.forecast(points, BASE, Config.defaults());
        assertThat(result.forecast().points()).allSatisfy(p -> {
            assertThat(p.agl()).isNull();
            assertThat(p.amsl()).isNotNull();
        });
    }

    private static List<Measurement> measurements(double lon, double lat, long now, IntFunction<double[]> position) {
        List<Measurement> points = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double[] xy = position.apply(i), ll = AlphaBetaFilter.fromEnu(lon, lat, xy[0], xy[1]);
            long at = now - (9 - i) * 1_000L;
            points.add(new Measurement(UUID.randomUUID().toString(), at, at, ll[0], ll[1], 1, 100.0, null));
        }
        return points;
    }
}
