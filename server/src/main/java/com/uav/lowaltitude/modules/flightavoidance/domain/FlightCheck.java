package com.uav.lowaltitude.modules.flightavoidance.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.modules.flightavoidance.domain.FlightAvoidancePolicy.CounterMode;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightAvoidancePolicy.Zones;

/**
 * 以选中的反制设备为中心，看附近有没有低空民航航班。
 *
 * 只认最近 30 秒内收到的航班数据；没有在用的航班接收机时不检查（OFF），
 * 有接收机但都没有新数据时如实给出「收不到航班信息」（NO_DATA），不当作附近没有飞机。
 * 航班没报高度时按可能在低空处理，不把未知当成安全。
 */
public final class FlightCheck {
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private FlightCheck() { }

    public enum Level { OFF, CLEAR, WARN, NO_DATA, BLOCK }

    public enum Zone { BLOCK, WARN }

    public record Aircraft(String icao24, String callsign, String airline, double longitude, double latitude,
            Integer altitudeM, Double groundSpeedMps, Double trackDeg, long observedAt) { }

    public record Nearby(String icao24, String callsign, String airline, double distanceKm, Integer altitudeM,
            boolean approaching, Zone zone) { }

    public record Result(Level level, CounterMode mode, Zones zones, List<Nearby> aircraft, long policyVersion) {
        public boolean blocked() { return level == Level.BLOCK; }
        /** 提醒范围有航班或收不到航班信息：人工确认后可以反制，自动反制不启动。 */
        public boolean needsConfirmation() { return level == Level.WARN || level == Level.NO_DATA; }
        public boolean clearForAutomatic() { return level == Level.OFF || level == Level.CLEAR; }
    }

    /**
     * @param receivers 在用的航班接收机台数；为 0 时不检查
     * @param freshAircraft 最近 30 秒内收到的航班，没有新数据时传 null
     */
    public static Result evaluate(FlightAvoidancePolicy policy, CounterMode mode, double deviceLon, double deviceLat,
            int receivers, List<Aircraft> freshAircraft) {
        Zones zones = policy.zones(mode);
        if (receivers <= 0) return new Result(Level.OFF, mode, zones, List.of(), policy.version());
        if (freshAircraft == null) return new Result(Level.NO_DATA, mode, zones, List.of(), policy.version());
        Map<String, Aircraft> latest = new LinkedHashMap<>();
        for (Aircraft a : freshAircraft) latest.merge(a.icao24(), a, (x, y) -> y.observedAt() > x.observedAt() ? y : x);
        List<Nearby> nearby = new ArrayList<>();
        for (Aircraft a : latest.values()) {
            if (a.altitudeM() != null && a.altitudeM() > zones.maxAltitudeM()) continue;
            Nearby hit = classify(a, zones, deviceLon, deviceLat);
            if (hit != null) nearby.add(hit);
        }
        nearby.sort(Comparator.comparing((Nearby n) -> n.zone() != Zone.BLOCK).thenComparingDouble(Nearby::distanceKm));
        Level level = nearby.isEmpty() ? Level.CLEAR
                : nearby.get(0).zone() == Zone.BLOCK ? Level.BLOCK : Level.WARN;
        return new Result(level, mode, zones, List.copyOf(nearby), policy.version());
    }

    private static Nearby classify(Aircraft a, Zones zones, double lon0, double lat0) {
        double[] p = offsetKm(lon0, lat0, a.longitude(), a.latitude());
        double now = Math.hypot(p[0], p[1]);
        double soon = now;
        boolean approaching = false;
        if (a.groundSpeedMps() != null && a.trackDeg() != null && a.groundSpeedMps() > 0) {
            double speedKmS = a.groundSpeedMps() / 1000.0;
            double track = Math.toRadians(a.trackDeg());
            double vx = speedKmS * Math.sin(track), vy = speedKmS * Math.cos(track);
            double dot = p[0] * vx + p[1] * vy;
            approaching = dot < 0;
            double t = Math.max(0, Math.min(FlightAvoidancePolicy.APPROACH_SECONDS, -dot / (vx * vx + vy * vy)));
            soon = Math.hypot(p[0] + vx * t, p[1] + vy * t);
        }
        Zone zone = zones.blockKm() > 0 && Math.min(now, soon) <= zones.blockKm() ? Zone.BLOCK
                : Math.min(now, soon) <= zones.warnKm() ? Zone.WARN : null;
        if (zone == null) return null;
        return new Nearby(a.icao24(), a.callsign(), a.airline(), Math.round(now * 10) / 10.0, a.altitudeM(), approaching, zone);
    }

    /** 两点水平距离（公里）。 */
    public static double distanceKm(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** 以设备为原点的东、北向偏移（公里）。按大圆距离和方位换算，几百公里内误差可以忽略。 */
    static double[] offsetKm(double lon0, double lat0, double lon, double lat) {
        double d = distanceKm(lon0, lat0, lon, lat);
        if (d == 0) return new double[] {0, 0};
        double phi1 = Math.toRadians(lat0), phi2 = Math.toRadians(lat), dLon = Math.toRadians(lon - lon0);
        double bearing = Math.atan2(Math.sin(dLon) * Math.cos(phi2),
                Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon));
        return new double[] {d * Math.sin(bearing), d * Math.cos(bearing)};
    }
}
