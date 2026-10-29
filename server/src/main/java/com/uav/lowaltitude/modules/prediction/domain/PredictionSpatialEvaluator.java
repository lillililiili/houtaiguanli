package com.uav.lowaltitude.modules.prediction.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Forecast;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Height;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine.Point;
import com.uav.lowaltitude.modules.prediction.domain.PredictionStability.Decision;

/**
 * Continuous measured-to-forecast space/time intersection, including polygon holes and multiple polygons.
 * Every segment is divided at temporal, vertical and moving uncertainty-disc/edge roots.
 * Thus a narrow region between the three displayed milestones cannot be skipped.
 * Metric calculations use the same local ENU projection as the forecasting engine.
 */
public final class PredictionSpatialEvaluator {
    private static final double EPS = 1e-6;
    public record GeoPoint(double longitude, double latitude) { }
    public record Polygon(List<GeoPoint> shell, List<List<GeoPoint>> holes) {
        public Polygon {
            shell = shell == null ? List.of() : List.copyOf(shell);
            holes = holes == null ? List.of() : holes.stream().map(List::copyOf).toList();
        }
    }
    public record AirspaceVersion(String airspaceId, String versionId, long validFrom, Long validTo, String kindCode,
            Double minAltitudeM, Double maxAltitudeM, String altitudeDatum, List<Polygon> polygons) {
        public AirspaceVersion { polygons = polygons == null ? List.of() : List.copyOf(polygons); }
    }
    public record Assessment(String airspaceId, Decision decision, Double entrySeconds, List<String> versionIds, List<String> reasons) {
        public Assessment { versionIds = List.copyOf(versionIds); reasons = List.copyOf(reasons); }
    }
    private record Vec(double x, double y) {
        Vec plus(Vec p) { return new Vec(x + p.x, y + p.y); }
        Vec minus(Vec p) { return new Vec(x - p.x, y - p.y); }
        Vec times(double t) { return new Vec(x * t, y * t); }
        double dot(Vec p) { return x * p.x + y * p.y; }
        double cross(Vec p) { return x * p.y - y * p.x; }
    }
    private record Shape(List<Vec> shell, List<List<Vec>> holes) {
        List<List<Vec>> rings() {
            List<List<Vec>> all = new ArrayList<>(); all.add(shell); all.addAll(holes); return all;
        }
    }
    private record Moment(Vec location, double radius, Height height) { }
    private record Classification(Decision decision, String reason) { }

    public List<Assessment> evaluate(Forecast forecast, List<AirspaceVersion> versions) {
        return evaluate(forecast, versions, 30);
    }

    /** Longer forecast windows are enabled only by the caller for explicitly simulated QA targets. */
    public List<Assessment> evaluate(Forecast forecast, List<AirspaceVersion> versions, int horizonSeconds) {
        Map<String, List<AirspaceVersion>> byAirspace = new LinkedHashMap<>();
        if (versions != null) for (AirspaceVersion v : versions) {
            if (v == null || v.airspaceId() == null) throw new IllegalArgumentException("Airspace identity is required");
            byAirspace.computeIfAbsent(v.airspaceId(), key -> new ArrayList<>()).add(v);
        }
        return byAirspace.entrySet().stream().map(e -> evaluateForAirspace(e.getKey(), forecast, e.getValue(), horizonSeconds)).toList();
    }

    public Assessment evaluateForAirspace(String airspaceId, Forecast forecast, List<AirspaceVersion> versions) {
        return evaluateForAirspace(airspaceId, forecast, versions, 30);
    }

    public Assessment evaluateForAirspace(String airspaceId, Forecast forecast, List<AirspaceVersion> versions, int horizonSeconds) {
        if (horizonSeconds < 30 || horizonSeconds > 600 || !validForecast(forecast, horizonSeconds))
            return result(airspaceId, Decision.UNKNOWN, null, Set.of(), Set.of("PREDICTION_UNAVAILABLE"));
        long start = forecast.originAt(), end = start + horizonSeconds * 1000L;
        List<AirspaceVersion> relevant = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>(), reasons = new LinkedHashSet<>();
        Map<String, AirspaceVersion> unique = new LinkedHashMap<>();
        if (versions != null) for (AirspaceVersion version : versions) {
            if (version == null || !airspaceId.equals(version.airspaceId()) || version.versionId() == null
                    || version.validTo() != null && version.validTo() <= version.validFrom())
                return result(airspaceId, Decision.UNKNOWN, null, ids, Set.of("AIRSPACE_VERSION_INVALID"));
            AirspaceVersion previous = unique.putIfAbsent(version.versionId(), version);
            if (previous != null && !previous.equals(version))
                return result(airspaceId, Decision.UNKNOWN, null, ids, Set.of("AIRSPACE_VERSION_CONFLICT"));
        }
        for (AirspaceVersion v : unique.values()) if (v.validFrom() <= end && (v.validTo() == null || v.validTo() > start)) {
            relevant.add(v); ids.add(v.versionId());
        }
        relevant.sort(Comparator.comparingLong(AirspaceVersion::validFrom));
        // A spatial filter must never hide overlapping effective versions of the same root.
        for (int i = 0; i < relevant.size(); i++) for (int j = i + 1; j < relevant.size(); j++) {
            AirspaceVersion a = relevant.get(i), b = relevant.get(j);
            long lo = Math.max(Math.max(a.validFrom(), b.validFrom()), start);
            long hi = Math.min(a.validTo() == null ? Long.MAX_VALUE : a.validTo(), b.validTo() == null ? Long.MAX_VALUE : b.validTo());
            if (lo <= end && lo < hi) return result(airspaceId, Decision.UNKNOWN, null, ids, Set.of("AIRSPACE_VERSION_AMBIGUOUS"));
        }
        boolean unknown = false;
        Double enterAt = null;
        for (AirspaceVersion version : relevant) {
            if (!"PROHIBITED".equals(version.kindCode())) continue;
            List<Shape> shapes = shapes(version, forecast.originLongitude(), forecast.originLatitude());
            if (shapes == null) { unknown = true; reasons.add("AIRSPACE_GEOMETRY_UNKNOWN"); continue; }
            for (int i = 1; i < forecast.points().size(); i++) {
                Point first = forecast.points().get(i - 1), last = forecast.points().get(i);
                double firstAt = start + first.seconds() * 1000.0, lastAt = start + last.seconds() * 1000.0;
                double from = Math.max(firstAt, version.validFrom());
                double to = Math.min(Math.min(lastAt, end), version.validTo() == null ? Double.POSITIVE_INFINITY : version.validTo());
                if (from > to || from >= (version.validTo() == null ? Double.POSITIVE_INFINITY : version.validTo())) continue;
                double duration = lastAt - firstAt;
                double lo = (from - firstAt) / duration, hi = (to - firstAt) / duration;
                Moment a = moment(first, version.altitudeDatum(), forecast), b = moment(last, version.altitudeDatum(), forecast);
                TreeSet<Double> cuts = new TreeSet<>(); cuts.add(lo); cuts.add(hi);
                for (Shape shape : shapes) for (List<Vec> ring : shape.rings()) {
                    for (int k = 0; k < ring.size(); k++)
                        edgeRoots(a.location(), b.location(), a.radius(), b.radius(), ring.get(k), ring.get((k + 1) % ring.size()), cuts, lo, hi);
                }
                heightRoots(a.height(), b.height(), version.minAltitudeM(), version.maxAltitudeM(), cuts, lo, hi);
                List<Double> boundaries = new ArrayList<>(cuts), probes = new ArrayList<>(boundaries);
                for (int k = 1; k < boundaries.size(); k++) probes.add((boundaries.get(k - 1) + boundaries.get(k)) / 2);
                probes.sort(Double::compare);
                for (double u : probes) {
                    double at = firstAt + duration * u;
                    // Effective windows are half open, including a version beginning exactly at the horizon.
                    if (at < version.validFrom() || version.validTo() != null && at >= version.validTo()) continue;
                    Classification c = classify(interpolate(a, b, u), version, shapes);
                    if (c.decision() == Decision.ENTER) {
                        double seconds = (at - start) / 1000.0;
                        if (enterAt == null || seconds < enterAt) enterAt = seconds;
                    } else if (c.decision() == Decision.UNKNOWN) {
                        unknown = true; reasons.add(c.reason());
                    }
                }
            }
        }
        if (enterAt != null) return result(airspaceId, Decision.ENTER, enterAt, ids, Set.of("PREDICTED_ENTRY_CONFIRMED"));
        if (unknown) return result(airspaceId, Decision.UNKNOWN, null, ids, reasons);
        return result(airspaceId, Decision.CLEAR, null, ids, Set.of("FULL_HORIZON_DISJOINT"));
    }

    private static Assessment result(String id, Decision decision, Double at, Set<String> versions, Set<String> reasons) {
        return new Assessment(id, decision, at, List.copyOf(versions), List.copyOf(reasons));
    }

    private static boolean validForecast(Forecast forecast, int horizonSeconds) {
        if (forecast == null || forecast.points() == null || forecast.points().size() < 2) return false;
        List<Point> points = forecast.points();
        if (points.get(0).seconds() != 0 || points.get(points.size() - 1).seconds() < horizonSeconds) return false;
        int prior = -1;
        for (Point p : points) {
            if (p.seconds() <= prior || !validCoordinate(p.longitude(), p.latitude()) || !Double.isFinite(p.uncertaintyM()) || p.uncertaintyM() < 0) return false;
            prior = p.seconds();
        }
        return validCoordinate(forecast.originLongitude(), forecast.originLatitude()) && Math.abs(forecast.originLatitude()) <= 85;
    }

    private static Moment moment(Point p, String datum, Forecast forecast) {
        return new Moment(vec(p.longitude(), p.latitude(), forecast.originLongitude(), forecast.originLatitude()), p.uncertaintyM(),
                "AMSL".equals(datum) ? p.amsl() : "AGL".equals(datum) ? p.agl() : null);
    }
    private static Moment interpolate(Moment a, Moment b, double u) {
        Height h = a.height() == null || b.height() == null ? null
                : new Height(lerp(a.height().valueM(), b.height().valueM(), u), lerp(a.height().uncertaintyM(), b.height().uncertaintyM(), u));
        return new Moment(a.location().plus(b.location().minus(a.location()).times(u)), lerp(a.radius(), b.radius(), u), h);
    }
    private static double lerp(double a, double b, double u) { return a + (b - a) * u; }

    private static Classification classify(Moment p, AirspaceVersion version, List<Shape> shapes) {
        boolean fullyInside = false, horizontalPossible = false;
        for (Shape shape : shapes) {
            double boundary = distance(p.location(), shape);
            boolean inside = inside(p.location(), shape);
            if (inside && boundary > p.radius() + EPS) fullyInside = true;
            if (inside || boundary <= p.radius() + EPS) horizontalPossible = true;
        }
        if (!horizontalPossible) return new Classification(Decision.CLEAR, "HORIZONTAL_DISJOINT");
        Height h = p.height();
        Double min = version.minAltitudeM(), max = version.maxAltitudeM();
        if (h == null || min == null || max == null || !Double.isFinite(min) || !Double.isFinite(max) || min > max
                || !Double.isFinite(h.valueM()) || !Double.isFinite(h.uncertaintyM()) || h.uncertaintyM() < 0)
            return new Classification(Decision.UNKNOWN, "ALTITUDE_RELATION_UNKNOWN");
        double low = h.valueM() - h.uncertaintyM(), high = h.valueM() + h.uncertaintyM();
        if (high < min - EPS || low > max + EPS) return new Classification(Decision.CLEAR, "VERTICAL_DISJOINT");
        if (fullyInside && low > min + EPS && high < max - EPS) return new Classification(Decision.ENTER, "PREDICTED_ENTRY_CONFIRMED");
        return new Classification(Decision.UNKNOWN, "UNCERTAINTY_OR_BOUNDARY_CONTACT");
    }

    private static List<Shape> shapes(AirspaceVersion version, double lon, double lat) {
        if (version.polygons().isEmpty()) return null;
        List<Shape> shapes = new ArrayList<>();
        for (Polygon polygon : version.polygons()) {
            List<Vec> shell = ring(polygon.shell(), lon, lat);
            if (shell == null) return null;
            List<List<Vec>> holes = new ArrayList<>();
            for (List<GeoPoint> coordinates : polygon.holes()) {
                List<Vec> hole = ring(coordinates, lon, lat);
                if (hole == null || !insideRing(hole.get(0), shell) || intersect(shell, hole)) return null;
                for (List<Vec> previous : holes)
                    if (intersect(previous, hole) || insideRing(hole.get(0), previous) || insideRing(previous.get(0), hole)) return null;
                holes.add(hole);
            }
            shapes.add(new Shape(shell, List.copyOf(holes)));
        }
        return shapes;
    }

    private static List<Vec> ring(List<GeoPoint> coordinates, double lon, double lat) {
        if (coordinates == null || coordinates.size() < 3) return null;
        List<Vec> points = new ArrayList<>();
        for (GeoPoint p : coordinates) {
            if (p == null || !validCoordinate(p.longitude(), p.latitude()) || Math.abs(p.longitude() - lon) > 180) return null;
            Vec next = vec(p.longitude(), p.latitude(), lon, lat);
            if (points.isEmpty() || squared(points.get(points.size() - 1).minus(next)) > EPS * EPS) points.add(next);
        }
        if (points.size() > 1 && squared(points.get(0).minus(points.get(points.size() - 1))) <= EPS * EPS) points.remove(points.size() - 1);
        if (points.size() < 3) return null;
        double area = 0;
        for (int i = 0; i < points.size(); i++) area += points.get(i).cross(points.get((i + 1) % points.size()));
        if (Math.abs(area) < EPS) return null;
        for (int i = 0; i < points.size(); i++) for (int j = i + 1; j < points.size(); j++) {
            if (j == i + 1 || i == 0 && j == points.size() - 1) continue;
            if (segmentsIntersect(points.get(i), points.get((i + 1) % points.size()), points.get(j), points.get((j + 1) % points.size()))) return null;
        }
        return List.copyOf(points);
    }

    private static boolean intersect(List<Vec> a, List<Vec> b) {
        for (int i = 0; i < a.size(); i++) for (int j = 0; j < b.size(); j++)
            if (segmentsIntersect(a.get(i), a.get((i + 1) % a.size()), b.get(j), b.get((j + 1) % b.size()))) return true;
        return false;
    }
    private static boolean segmentsIntersect(Vec a, Vec b, Vec c, Vec d) {
        double abC = b.minus(a).cross(c.minus(a)), abD = b.minus(a).cross(d.minus(a));
        double cdA = d.minus(c).cross(a.minus(c)), cdB = d.minus(c).cross(b.minus(c));
        if (abC * abD < 0 && cdA * cdB < 0) return true;
        return segmentDistance(a, c, d) <= EPS || segmentDistance(b, c, d) <= EPS || segmentDistance(c, a, b) <= EPS || segmentDistance(d, a, b) <= EPS;
    }
    private static Vec vec(double longitude, double latitude, double originLon, double originLat) {
        double[] xy = AlphaBetaFilter.toEnu(originLon, originLat, longitude, latitude);
        return new Vec(xy[0], xy[1]);
    }
    private static boolean validCoordinate(double lon, double lat) {
        return Double.isFinite(lon) && Double.isFinite(lat) && lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90;
    }
    private static boolean inside(Vec point, Shape shape) {
        if (!insideRing(point, shape.shell())) return false;
        for (List<Vec> hole : shape.holes()) if (insideRing(point, hole)) return false;
        return true;
    }
    private static boolean insideRing(Vec p, List<Vec> ring) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            Vec a = ring.get(i), b = ring.get(j);
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside;
        }
        return inside;
    }
    private static double distance(Vec point, Shape shape) {
        double distance = Double.POSITIVE_INFINITY;
        for (List<Vec> ring : shape.rings())
            for (int i = 0; i < ring.size(); i++) distance = Math.min(distance, segmentDistance(point, ring.get(i), ring.get((i + 1) % ring.size())));
        return distance;
    }
    private static double segmentDistance(Vec p, Vec a, Vec b) {
        Vec edge = b.minus(a);
        double t = Math.max(0, Math.min(1, p.minus(a).dot(edge) / squared(edge)));
        return Math.sqrt(squared(p.minus(a.plus(edge.times(t)))));
    }
    private static double squared(Vec p) { return p.dot(p); }

    private static void edgeRoots(Vec first, Vec last, double radiusFirst, double radiusLast, Vec a, Vec b,
            TreeSet<Double> cuts, double low, double high) {
        Vec speed = last.minus(first), edge = b.minus(a), relative = first.minus(a);
        double length = squared(edge), dr = radiusLast - radiusFirst;
        double projection = relative.dot(edge) / length, projectionSpeed = speed.dot(edge) / length;
        linearRoot(projection, projectionSpeed, cuts, low, high);
        linearRoot(projection - 1, projectionSpeed, cuts, low, high);
        // End point circles and infinite edge strip; extra roots outside the finite edge
        // are harmless partitions. All real capsule contacts are included.
        circleRoots(relative, speed, radiusFirst, dr, cuts, low, high);
        circleRoots(first.minus(b), speed, radiusFirst, dr, cuts, low, high);
        double cross = relative.cross(edge), crossSpeed = speed.cross(edge);
        roots(crossSpeed * crossSpeed / length - dr * dr,
                2 * cross * crossSpeed / length - 2 * radiusFirst * dr,
                cross * cross / length - radiusFirst * radiusFirst, cuts, low, high);
        linearRoot(cross, crossSpeed, cuts, low, high);
    }
    private static void circleRoots(Vec relative, Vec speed, double radius, double dr, TreeSet<Double> cuts, double low, double high) {
        roots(squared(speed) - dr * dr, 2 * relative.dot(speed) - 2 * radius * dr,
                squared(relative) - radius * radius, cuts, low, high);
    }
    private static void heightRoots(Height first, Height last, Double min, Double max, TreeSet<Double> cuts, double low, double high) {
        if (first == null || last == null || min == null || max == null) return;
        for (double sign : new double[] { -1, 1 }) {
            double a = first.valueM() + sign * first.uncertaintyM(), b = last.valueM() + sign * last.uncertaintyM();
            linearRoot(a - min, b - a, cuts, low, high);
            linearRoot(a - max, b - a, cuts, low, high);
        }
    }
    private static void linearRoot(double intercept, double slope, TreeSet<Double> cuts, double low, double high) {
        if (Math.abs(slope) > 1e-12) add(-intercept / slope, cuts, low, high);
    }
    private static void roots(double a, double b, double c, TreeSet<Double> cuts, double low, double high) {
        if (Math.abs(a) < 1e-12) { linearRoot(c, b, cuts, low, high); return; }
        double discriminant = b * b - 4 * a * c;
        if (discriminant < -1e-10) return;
        double sqrt = Math.sqrt(Math.max(0, discriminant));
        // Stable quadratic solution avoids catastrophic cancellation near tangency.
        double q = -0.5 * (b + Math.copySign(sqrt, b));
        if (Math.abs(q) < 1e-15) add(-b / (2 * a), cuts, low, high);
        else { add(q / a, cuts, low, high); add(c / q, cuts, low, high); }
    }
    private static void add(double root, TreeSet<Double> cuts, double low, double high) {
        if (Double.isFinite(root) && root > low && root < high) cuts.add(root);
    }
}
