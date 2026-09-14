package com.uav.lowaltitude.modules.fusion.domain;

import java.util.LinkedHashMap;
import java.util.Map;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;

/**
 * 单源 α-β 滤波：每条 RAW 轨迹一个状态 {x, y（局部 ENU 米）, vx, vy, t, acc_m}。
 * 参数 alpha / beta / max_dt_ms 与缺省精度表全部来自 fusion_config（不允许裸阈值）。
 * 米制换算用等距圆柱近似（以轨迹首点为原点），域层不依赖 ST_ 函数：H2 单测与 PostGIS 生产库必须得到同一套数值，
 * 且滤波是纯计算，不应把数据库当计算器。
 */
public final class AlphaBetaFilter {
    /** 单位换算常量：地球平均半径（米）与每秒毫秒数。 */
    public static final double EARTH_RADIUS_M = 6_371_008.8;
    public static final double MILLIS_PER_SECOND = 1000.0;
    private static final double DEGREES_PER_TURN = 360.0;

    private final double alpha;
    private final double beta;
    private final long maxDtMillis;
    private final Map<String, Double> accuracyDefaults;

    public AlphaBetaFilter(FusionParams params) {
        this.alpha = params.number("filter", "alpha");
        this.beta = params.number("filter", "beta");
        this.maxDtMillis = params.integer("filter", "max_dt_ms");
        this.accuracyDefaults = Map.copyOf(params.accuracyDefaults());
    }

    /** 滤波状态；lon0/lat0 是该轨迹的 ENU 原点，x/y 为相对原点的米。 */
    public record State(double lon0, double lat0, double x, double y, double vx, double vy, long tMillis, double accuracyM,
            boolean velocityKnown, int updates) {

        public double longitude() { return fromEnu(lon0, lat0, x, y)[0]; }
        public double latitude() { return fromEnu(lon0, lat0, x, y)[1]; }

        public Double speedMps() { return velocityKnown ? Math.hypot(vx, vy) : null; }

        public Double headingDeg() {
            if (!velocityKnown || (vx == 0 && vy == 0)) return null;
            double deg = Math.toDegrees(Math.atan2(vx, vy));
            return (deg % DEGREES_PER_TURN + DEGREES_PER_TURN) % DEGREES_PER_TURN;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("lon0", lon0); map.put("lat0", lat0); map.put("x", x); map.put("y", y); map.put("vx", vx); map.put("vy", vy);
            map.put("t", tMillis); map.put("acc_m", accuracyM); map.put("velocity_known", velocityKnown); map.put("updates", updates);
            return map;
        }

        public static State fromMap(Map<String, Object> map) {
            return new State(number(map, "lon0"), number(map, "lat0"), number(map, "x"), number(map, "y"), number(map, "vx"), number(map, "vy"),
                    (long) number(map, "t"), number(map, "acc_m"), Boolean.TRUE.equals(map.get("velocity_known")), (int) number(map, "updates"));
        }

        private static double number(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (value instanceof Number n) return n.doubleValue();
            throw new IllegalStateException("滤波状态缺少数值字段: " + key);
        }
    }

    /** 一次观测；accuracyM 为 null 表示来源没有给精度。 */
    public record Measurement(double longitude, double latitude, Double accuracyM, long tMillis) { }

    /** 一次更新的结果：新状态、是否重初始化、是否用了目录缺省精度、实际采用的观测精度、是否为迟到观测（状态未变）。 */
    public record Update(State state, boolean reinitialized, boolean accuracyDefaulted, double accuracyUsedM, boolean outOfOrder) { }

    public Update update(State previous, Measurement measurement, String sourceType) {
        boolean defaulted = measurement.accuracyM() == null;
        double accuracy = defaulted ? defaultAccuracy(sourceType) : measurement.accuracyM();
        if (previous == null) return new Update(initial(measurement, accuracy), true, defaulted, accuracy, false);
        long dt = measurement.tMillis() - previous.tMillis();
        // 迟到观测（时间早于状态）不能把状态倒退：原始层照写由管线负责，这里保持最新状态。
        if (dt <= 0) return new Update(previous, false, defaulted, accuracy, true);
        // 间隙超过 max_dt_ms 时旧速度不再可信，重新初始化而不是用过期速度外推。
        if (dt > maxDtMillis) return new Update(initial(measurement, accuracy), true, defaulted, accuracy, false);
        double[] enu = toEnu(previous.lon0(), previous.lat0(), measurement.longitude(), measurement.latitude());
        double dtSeconds = dt / MILLIS_PER_SECOND;
        double x, y, vx, vy;
        if (!previous.velocityKnown()) {
            // 第二个点：速度直接由两点差分给出，位置采用观测。
            x = enu[0]; y = enu[1];
            vx = (enu[0] - previous.x()) / dtSeconds; vy = (enu[1] - previous.y()) / dtSeconds;
        } else {
            double xp = previous.x() + previous.vx() * dtSeconds, yp = previous.y() + previous.vy() * dtSeconds;
            double rx = enu[0] - xp, ry = enu[1] - yp;
            x = xp + alpha * rx; y = yp + alpha * ry;
            vx = previous.vx() + beta * rx / dtSeconds; vy = previous.vy() + beta * ry / dtSeconds;
        }
        // 滤波后精度：观测与预测按 α 混合的误差传播（预测精度沿用上一状态，不做过程噪声增长假设）。
        double filteredAccuracy = Math.sqrt(alpha * alpha * accuracy * accuracy + (1 - alpha) * (1 - alpha) * previous.accuracyM() * previous.accuracyM());
        State next = new State(previous.lon0(), previous.lat0(), x, y, vx, vy, measurement.tMillis(), filteredAccuracy, true, previous.updates() + 1);
        return new Update(next, false, defaulted, accuracy, false);
    }

    /** 把状态外推到 tMillis（用于门限计算与 PRED 点）；速度未知时位置不动。 */
    public State predict(State state, long tMillis) {
        long dt = tMillis - state.tMillis();
        if (!state.velocityKnown() || dt <= 0) return new State(state.lon0(), state.lat0(), state.x(), state.y(), state.vx(), state.vy(), tMillis,
                state.accuracyM(), state.velocityKnown(), state.updates());
        double dtSeconds = dt / MILLIS_PER_SECOND;
        return new State(state.lon0(), state.lat0(), state.x() + state.vx() * dtSeconds, state.y() + state.vy() * dtSeconds, state.vx(), state.vy(),
                tMillis, state.accuracyM(), true, state.updates());
    }

    private State initial(Measurement measurement, double accuracy) {
        return new State(measurement.longitude(), measurement.latitude(), 0, 0, 0, 0, measurement.tMillis(), accuracy, false, 1);
    }

    private double defaultAccuracy(String sourceType) {
        Double value = sourceType == null ? null : accuracyDefaults.get(sourceType);
        if (value == null) throw new IllegalStateException("filter.accuracy_default_m 缺少来源类型 " + sourceType + " 的缺省精度");
        return value;
    }

    /** 大圆距离（米）。 */
    public static double distanceM(double lon1, double lat1, double lon2, double lat2) {
        double phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1, dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** WGS-84 → 以 (lon0, lat0) 为原点的局部 ENU（米，东为 x、北为 y）。 */
    public static double[] toEnu(double lon0, double lat0, double lon, double lat) {
        double x = Math.toRadians(lon - lon0) * Math.cos(Math.toRadians(lat0)) * EARTH_RADIUS_M;
        double y = Math.toRadians(lat - lat0) * EARTH_RADIUS_M;
        return new double[] { x, y };
    }

    /** 局部 ENU → WGS-84。 */
    public static double[] fromEnu(double lon0, double lat0, double x, double y) {
        double lon = lon0 + Math.toDegrees(x / (EARTH_RADIUS_M * Math.cos(Math.toRadians(lat0))));
        double lat = lat0 + Math.toDegrees(y / EARTH_RADIUS_M);
        return new double[] { lon, lat };
    }
}
