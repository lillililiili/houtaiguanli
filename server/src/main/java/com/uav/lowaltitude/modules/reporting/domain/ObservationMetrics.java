package com.uav.lowaltitude.modules.reporting.domain;

import java.time.*;
import java.util.*;
import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;

/** Read-only, conservative measured-segment statistics. Never infers flight start/end or changes fusion. */
public final class ObservationMetrics {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final String BASIS = "统计期内无人机融合实测连续片段，按目标累计；不是完整飞行时长或飞行里程。"
            + "排除预测、桥接、断流、来源切换、重复及质量不足片段；不跨轨迹连接。"
            + "仅累计时间连续、位置可信的片段，质量不足时可能少计；里程为已观测位置之间的水平折线距离。"
            + "跨日有效片段按时长比例分摊里程；不同目标同时被监测的时长分别累加。";
    public record Policy(String version, long gapMs, double gateSigma, double anomalyZ, boolean confirmed) { }
    public record Point(String target, String track, long at, String kind, Double lon, Double lat,
            Double accuracy, String observation, String source, String mode, boolean switched, Policy policy, String invalidReason) { }
    public record Day(String date, double durationSeconds, double distanceMeters) { }
    public record Exclusion(String code, String reason, long count) { }
    public record Result(String status, String reason, String basis, Double durationSeconds, Double distanceMeters,
            long observedTargets, long measuredTargets, long validSegments, List<String> sourceModes,
            List<String> configVersions, List<Exclusion> exclusions, List<Day> days) {
        public static Result unavailable(String reason) {
            return new Result("UNAVAILABLE", reason, BASIS, null, null, 0, 0, 0, List.of(), List.of(), List.of(), List.of());
        }
    }
    private record Segment(Point a, Point b, double distance) { }
    private static final Map<String,String> REASONS = Map.ofEntries(
            Map.entry("NON_MEASURED", "预测或桥接点"), Map.entry("MISSING_POSITION", "位置或精度不足"),
            Map.entry("CONFIG", "缺少适用的融合配置或真实来源配置未确认"), Map.entry("PROVENANCE", "实测来源、类别或权限依据不完整"),
            Map.entry("ANOMALY", "来源标记的异常观测"), Map.entry("DUPLICATE", "重复时刻或重复观测"),
            Map.entry("CONFLICT", "同一时刻位置冲突"), Map.entry("GAP", "超过连续监测间隔"),
            Map.entry("SOURCE_SWITCH", "位置来源切换"), Map.entry("SPATIAL_BREAK", "超出既有位置关联门限"),
            Map.entry("OVERLAP", "同一目标多轨迹时间重叠"));

    public static Result calculate(List<Point> input, LocalDate from, LocalDate to, long now) {
        long start = from.atStartOfDay(ZONE).toInstant().toEpochMilli();
        long end = Math.min(to.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli(), now);
        Map<String,Long> excluded = new TreeMap<>();
        Set<String> observed = new HashSet<>(), measured = new HashSet<>(), modes = new TreeSet<>(), versions = new TreeSet<>();
        Map<String,List<Point>> tracks = new TreeMap<>();
        for (Point p : input) {
            if (p.at > now) continue;
            if (p.at >= start && p.at < end) { observed.add(p.target); modes.add(p.mode); }
            tracks.computeIfAbsent(p.track, k -> new ArrayList<>()).add(p);
        }
        Map<String,List<Segment>> targets = new TreeMap<>();
        for (List<Point> points : tracks.values()) {
            points.sort(Comparator.comparingLong(Point::at).thenComparing(p -> Objects.toString(p.observation, "")));
            Point previous = null;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < points.size();) {
                Point p = points.get(i++);
                boolean conflict = false;
                while (i < points.size() && points.get(i).at == p.at) {
                    Point other = points.get(i++);
                    if (!Objects.equals(p.lon, other.lon) || !Objects.equals(p.lat, other.lat)
                            || !Objects.equals(p.source, other.source) || invalid(other) != null) conflict = true;
                    count(excluded, "DUPLICATE", p.at >= start && p.at < end);
                }
                String invalid = conflict ? "CONFLICT" : invalid(p);
                if (invalid == null && !seen.add(p.observation)) invalid = "DUPLICATE";
                if (invalid != null) { count(excluded, invalid, p.at >= start && p.at < end); previous = null; continue; }
                if (previous != null && p.at > start && previous.at < end) {
                    long dt = p.at - previous.at;
                    String reject = null;
                    double distance = AlphaBetaFilter.distanceM(previous.lon, previous.lat, p.lon, p.lat);
                    if (dt > Math.min(previous.policy.gapMs, p.policy.gapMs)) reject = "GAP";
                    else if (p.switched || !Objects.equals(p.source, previous.source) || !p.mode.equals(previous.mode)) reject = "SOURCE_SWITCH";
                    else if (distance > Math.min(previous.policy.gateSigma, p.policy.gateSigma)
                            * Math.hypot(previous.accuracy, p.accuracy)) reject = "SPATIAL_BREAK";
                    if (reject != null) count(excluded, reject, true);
                    else targets.computeIfAbsent(p.target, k -> new ArrayList<>()).add(new Segment(previous, p, distance));
                }
                previous = p;
            }
        }
        Map<String,double[]> days = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) days.put(d.toString(), new double[2]);
        long segments = 0;
        for (var entry : targets.entrySet()) {
            List<Segment> list = entry.getValue();
            list.sort(Comparator.comparingLong(s -> s.a.at));
            // Reject the whole overlapping group; never choose an arbitrary source/track or double-count it.
            for (int i = 0; i < list.size();) {
                int j = i + 1; long groupEnd = list.get(i).b.at;
                while (j < list.size() && list.get(j).a.at < groupEnd) { groupEnd = Math.max(groupEnd, list.get(j++).b.at); }
                if (j > i + 1) { excluded.merge("OVERLAP", (long)(j - i), Long::sum); i = j; continue; }
                Segment s = list.get(i++);
                long a = Math.max(start, s.a.at), b = Math.min(end, s.b.at);
                if (a >= b) continue;
                measured.add(entry.getKey()); modes.add(s.a.mode); versions.add(s.a.policy.version); versions.add(s.b.policy.version); segments++;
                while (a < b) {
                    LocalDate day = Instant.ofEpochMilli(a).atZone(ZONE).toLocalDate();
                    long until = Math.min(b, day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
                    double[] totals = days.get(day.toString());
                    totals[0] += (until - a) / 1000.0;
                    totals[1] += s.distance * (until - a) / (s.b.at - s.a.at);
                    a = until;
                }
            }
        }
        double duration = days.values().stream().mapToDouble(v -> v[0]).sum();
        double distance = days.values().stream().mapToDouble(v -> v[1]).sum();
        String status = segments == 0 ? (observed.isEmpty() ? "NO_DATA" : "INSUFFICIENT") : excluded.isEmpty() ? "AVAILABLE" : "PARTIAL";
        String reason = switch (status) {
            case "NO_DATA" -> "所选范围暂无可读取的无人机融合轨迹";
            case "INSUFFICIENT" -> "已有观测，但没有符合统计条件的连续实测片段";
            case "PARTIAL" -> "仅累计有效片段，部分点或相邻片段未计入";
            default -> "按连续有效实测片段累计";
        };
        return new Result(status, reason, BASIS, segments == 0 ? null : duration, segments == 0 ? null : distance,
                observed.size(), measured.size(), segments, List.copyOf(modes), List.copyOf(versions),
                excluded.entrySet().stream().map(e -> new Exclusion(e.getKey(), REASONS.getOrDefault(e.getKey(), e.getKey()), e.getValue())).toList(),
                segments == 0 ? List.of() : days.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue()[0], e.getValue()[1])).toList());
    }
    private static void count(Map<String,Long> counts, String reason, boolean inRange) { if (inRange) counts.merge(reason, 1L, Long::sum); }
    private static String invalid(Point p) {
        if (!"MEAS".equals(p.kind)) return "NON_MEASURED";
        if (p.invalidReason != null) return p.invalidReason;
        if (p.policy == null || p.policy.gapMs <= 0 || !(p.policy.gateSigma > 0) || !(p.policy.anomalyZ > 0)
                || ("live".equals(p.mode) && !p.policy.confirmed)) return "CONFIG";
        if (p.lon == null || p.lat == null || !Double.isFinite(p.lon) || !Double.isFinite(p.lat)
                || Math.abs(p.lon) > 180 || Math.abs(p.lat) > 90 || p.accuracy == null || !Double.isFinite(p.accuracy) || p.accuracy <= 0) return "MISSING_POSITION";
        if (p.observation == null || p.source == null) return "PROVENANCE";
        return null;
    }
}
