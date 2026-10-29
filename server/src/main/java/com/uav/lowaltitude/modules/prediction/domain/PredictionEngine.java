package com.uav.lowaltitude.modules.prediction.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;

/**
 * K03 constant-velocity forecast from independently verified MEAS points.
 * Error values are conservative model bounds, not calibrated confidence probabilities.
 * The caller must verify source provenance, scope and MEAS kind before constructing inputs.
 */
public final class PredictionEngine {
    public static final String ALGORITHM_VERSION = "K03_CV_ROBUST_V1";

    public record Measurement(String pointId, long observedAt, long receivedAt, double longitude, double latitude,
            double accuracyM, Double altitudeAmslM, Double heightAglM) { }

    public record Config(long windowMillis, int minimumPoints, long minimumSpanMillis, long freshnessMillis,
            long maximumGapMillis, double maximumAccuracyM, double maximumDirectionChangeDeg,
            double maximumRelativeSpeedChange, double maximumNormalizedResidual, double minimumSpeedMps,
            double velocityErrorFloorMps, double accelerationErrorMps2, double altitudeModelErrorM) {
        public Config {
            if (windowMillis <= 0 || minimumPoints < 5 || minimumSpanMillis <= 0 || minimumSpanMillis > windowMillis
                    || freshnessMillis <= 0 || maximumGapMillis <= 0 || !positive(maximumAccuracyM)
                    || !positive(maximumDirectionChangeDeg) || maximumDirectionChangeDeg > 180
                    || !positive(maximumRelativeSpeedChange) || !positive(maximumNormalizedResidual)
                    || !positive(minimumSpeedMps) || !positive(velocityErrorFloorMps)
                    || !Double.isFinite(accelerationErrorMps2) || accelerationErrorMps2 < 0 || !positive(altitudeModelErrorM))
                throw new IllegalArgumentException("Invalid prediction configuration");
        }

        public static Config defaults() {
            return new Config(10_000, 5, 4_000, 3_000, 2_000, 150, 20, 0.30, 2, 0.5, 0.25, 0.05, 10);
        }
    }

    public record Height(double valueM, double uncertaintyM) { }
    public record Point(int seconds, double longitude, double latitude, double uncertaintyM, Height amsl, Height agl) { }
    public record Quality(int pointCount, long spanMillis, double speedMps, double headingDeg,
            double directionChangeDeg, double relativeSpeedChange, double normalizedResidual,
            double velocityUncertaintyMps, List<String> notes) {
        public Quality { notes = List.copyOf(notes); }
    }
    public record Forecast(long originAt, long lastMeasuredAt, String anchorPointId, double originLongitude,
            double originLatitude, double eastVelocityMps, double northVelocityMps, List<String> inputPointIds,
            List<Point> points, List<Point> milestones, Quality quality, String algorithmVersion) {
        public Forecast {
            inputPointIds = List.copyOf(inputPointIds);
            points = List.copyOf(points);
            milestones = List.copyOf(milestones);
        }
    }
    public record Result(boolean reliable, String reason, Forecast forecast) {
        public static Result unknown(String reason) { return new Result(false, reason, null); }
    }

    public Result forecast(List<Measurement> measurements, long asOf, Config config) {
        return forecast(measurements, asOf, config, 30);
    }

    /** QA-only callers may request a longer horizon for explicitly simulated targets. */
    public Result forecast(List<Measurement> measurements, long asOf, Config config, int horizonSeconds) {
        if (horizonSeconds < 30 || horizonSeconds > 600)
            throw new IllegalArgumentException("Invalid prediction horizon");
        if (measurements == null || measurements.isEmpty()) return Result.unknown("NO_MEASUREMENTS");
        List<Measurement> ordered = measurements.stream().filter(m -> m != null && m.observedAt() >= asOf - config.windowMillis())
                .sorted(Comparator.comparingLong(Measurement::observedAt).thenComparingLong(Measurement::receivedAt)
                        .thenComparing(m -> m.pointId() == null ? "" : m.pointId())).toList();
        Map<Long, Measurement> distinct = new LinkedHashMap<>();
        for (Measurement m : ordered) {
            if (m.pointId() == null || m.pointId().isBlank() || !finitePosition(m.longitude(), m.latitude()))
                return Result.unknown("POSITION_UNKNOWN");
            if (m.observedAt() > asOf || m.receivedAt() > asOf || m.receivedAt() < m.observedAt()
                    || m.receivedAt() - m.observedAt() > config.freshnessMillis())
                return Result.unknown("TIME_UNTRUSTED");
            if (!positive(m.accuracyM()) || m.accuracyM() > config.maximumAccuracyM())
                return Result.unknown("POSITION_ACCURACY_UNSUITABLE");
            Measurement prior = distinct.get(m.observedAt());
            if (prior != null && AlphaBetaFilter.distanceM(prior.longitude(), prior.latitude(), m.longitude(), m.latitude())
                    > Math.min(prior.accuracyM(), m.accuracyM()))
                return Result.unknown("SIMULTANEOUS_POSITION_CONFLICT");
            distinct.put(m.observedAt(), m);
        }
        List<Measurement> samples = List.copyOf(distinct.values());
        if (samples.size() < config.minimumPoints()) return Result.unknown("INSUFFICIENT_POINTS");
        Measurement first = samples.get(0), latest = samples.get(samples.size() - 1);
        long span = latest.observedAt() - first.observedAt();
        if (span < config.minimumSpanMillis()) return Result.unknown("INSUFFICIENT_TIME_SPAN");
        if (asOf - latest.observedAt() > config.freshnessMillis()) return Result.unknown("STALE_MEASUREMENT");
        for (int i = 1; i < samples.size(); i++)
            if (samples.get(i).observedAt() - samples.get(i - 1).observedAt() > config.maximumGapMillis())
                return Result.unknown("DISCONTINUOUS_MEASUREMENTS");
        // The shared ENU conversion is only a local approximation; fail closed near its singularity.
        if (Math.abs(latest.latitude()) > 85) return Result.unknown("LOCAL_PROJECTION_UNSUPPORTED");

        List<Sample> values = new ArrayList<>();
        for (Measurement m : samples) {
            double[] xy = AlphaBetaFilter.toEnu(latest.longitude(), latest.latitude(), m.longitude(), m.latitude());
            if (Math.hypot(xy[0], xy[1]) > 20_000) return Result.unknown("POSITION_DISCONTINUITY");
            values.add(new Sample((m.observedAt() - latest.observedAt()) / 1000.0, xy[0], xy[1], m.accuracyM()));
        }
        Fit fit = robustFit(values);
        int middle = values.size() / 2;
        Fit early = robustFit(values.subList(0, middle + 1));
        Fit late = robustFit(values.subList(middle, values.size()));
        double speed = Math.hypot(fit.vx, fit.vy), earlySpeed = Math.hypot(early.vx, early.vy), lateSpeed = Math.hypot(late.vx, late.vy);
        if (!Double.isFinite(speed) || Math.min(Math.min(speed, earlySpeed), lateSpeed) < config.minimumSpeedMps()
                || speed * span / 1000.0 <= 2 * Math.max(first.accuracyM(), latest.accuracyM()))
            return Result.unknown("DIRECTION_UNRESOLVED");
        double direction = Math.toDegrees(Math.acos(clamp((early.vx * late.vx + early.vy * late.vy) / (earlySpeed * lateSpeed), -1, 1)));
        double speedChange = Math.abs(earlySpeed - lateSpeed) / Math.max(earlySpeed, lateSpeed);
        if (direction > config.maximumDirectionChangeDeg()) return Result.unknown("TURNING");
        if (speedChange > config.maximumRelativeSpeedChange()) return Result.unknown("SPEED_UNSTABLE");
        if (fit.normalizedResidual > config.maximumNormalizedResidual()
                || fit.maximumNormalizedResidual > 2 * config.maximumNormalizedResidual())
            return Result.unknown("FIT_UNRELIABLE");

        double baseError = Math.max(latest.accuracyM(), fit.residualM);
        double velocityError = Math.max(config.velocityErrorFloorMps(),
                (samples.stream().mapToDouble(Measurement::accuracyM).max().orElseThrow() + fit.residualM) / (span / 1000.0));
        double delay = (asOf - latest.observedAt()) / 1000.0;
        HeightFit amsl = heightFit(samples, true, config), agl = heightFit(samples, false, config);
        List<String> notes = new ArrayList<>();
        notes.add("ERROR_BOUNDS_ARE_MODEL_ESTIMATES");
        if (amsl != null || agl != null) notes.add("ALTITUDE_ERROR_IS_MODEL_ASSUMPTION");
        List<Point> points = new ArrayList<>(), milestones = new ArrayList<>();
        for (int second = 0; second <= horizonSeconds; second++) {
            double dt = delay + second;
            double[] lonLat = AlphaBetaFilter.fromEnu(latest.longitude(), latest.latitude(), fit.x + fit.vx * dt, fit.y + fit.vy * dt);
            double error = baseError + velocityError * dt + config.accelerationErrorMps2() * dt * dt / 2;
            Point p = new Point(second, lonLat[0], lonLat[1], error, heightAt(amsl, dt, config), heightAt(agl, dt, config));
            points.add(p);
            if (second == 10 || second == 20 || second == 30 || (second > 30 && second % 30 == 0)) milestones.add(p);
        }
        double heading = (Math.toDegrees(Math.atan2(fit.vx, fit.vy)) + 360) % 360;
        Quality quality = new Quality(samples.size(), span, speed, heading, direction, speedChange, fit.normalizedResidual, velocityError, notes);
        return new Result(true, "READY", new Forecast(asOf, latest.observedAt(), latest.pointId(), latest.longitude(), latest.latitude(),
                fit.vx, fit.vy, samples.stream().map(Measurement::pointId).toList(), points, milestones, quality, ALGORITHM_VERSION));
    }

    private record Sample(double t, double x, double y, double error) { }
    private record Fit(double x, double y, double vx, double vy, double residualM, double normalizedResidual, double maximumNormalizedResidual) { }
    private record HeightFit(double height, double speed, double error, double speedError) { }

    private static Fit robustFit(List<Sample> samples) {
        double[] weights = samples.stream().mapToDouble(p -> 1 / (p.error * p.error)).toArray();
        double[] x = null, y = null;
        for (int iteration = 0; iteration < 4; iteration++) {
            x = regression(samples, weights, true);
            y = regression(samples, weights, false);
            for (int i = 0; i < samples.size(); i++) {
                Sample p = samples.get(i);
                double normalized = Math.hypot(p.x - x[0] - x[1] * p.t, p.y - y[0] - y[1] * p.t) / p.error;
                weights[i] = Math.min(1, 1.5 / Math.max(normalized, 1e-12)) / (p.error * p.error);
            }
        }
        double residual = 0, normalized = 0, max = 0;
        for (Sample p : samples) {
            double r = Math.hypot(p.x - x[0] - x[1] * p.t, p.y - y[0] - y[1] * p.t);
            residual += r * r;
            normalized += r * r / (p.error * p.error);
            max = Math.max(max, r / p.error);
        }
        return new Fit(x[0], y[0], x[1], y[1], Math.sqrt(residual / samples.size()), Math.sqrt(normalized / samples.size()), max);
    }

    private static double[] regression(List<Sample> samples, double[] weights, boolean x) {
        double sum = 0, t = 0, v = 0;
        for (int i = 0; i < samples.size(); i++) {
            Sample p = samples.get(i); double w = weights[i];
            sum += w; t += w * p.t; v += w * (x ? p.x : p.y);
        }
        t /= sum; v /= sum;
        double numerator = 0, denominator = 0;
        for (int i = 0; i < samples.size(); i++) {
            Sample p = samples.get(i);
            numerator += weights[i] * (p.t - t) * ((x ? p.x : p.y) - v);
            denominator += weights[i] * (p.t - t) * (p.t - t);
        }
        double slope = denominator <= 1e-15 ? 0 : numerator / denominator;
        return new double[] { v - slope * t, slope };
    }

    private static HeightFit heightFit(List<Measurement> samples, boolean amsl, Config config) {
        List<Sample> values = new ArrayList<>();
        long anchor = samples.get(samples.size() - 1).observedAt();
        for (Measurement m : samples) {
            Double h = amsl ? m.altitudeAmslM() : m.heightAglM();
            if (h == null || !Double.isFinite(h)) return null;
            values.add(new Sample((m.observedAt() - anchor) / 1000.0, h, 0, config.altitudeModelErrorM()));
        }
        Fit fit = robustFit(values);
        if (fit.normalizedResidual > config.maximumNormalizedResidual()) return null;
        double span = -values.get(0).t;
        return new HeightFit(fit.x, fit.vx, Math.max(config.altitudeModelErrorM(), fit.residualM),
                Math.max(config.velocityErrorFloorMps(), (config.altitudeModelErrorM() + fit.residualM) / span));
    }

    private static Height heightAt(HeightFit fit, double dt, Config config) {
        return fit == null ? null : new Height(fit.height + fit.speed * dt,
                fit.error + fit.speedError * dt + config.accelerationErrorMps2() * dt * dt / 2);
    }
    private static boolean positive(double n) { return Double.isFinite(n) && n > 0; }
    private static boolean finitePosition(double lon, double lat) {
        return Double.isFinite(lon) && Double.isFinite(lat) && lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90;
    }
    private static double clamp(double x, double low, double high) { return Math.max(low, Math.min(high, x)); }
}
