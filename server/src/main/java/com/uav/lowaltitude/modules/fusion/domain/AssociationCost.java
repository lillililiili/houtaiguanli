package com.uav.lowaltitude.modules.fusion.domain;

import java.util.ArrayList;
import java.util.List;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionDomainKey;
import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;

/**
 * 关联代价（契约 §算法规格）：
 * c = w_pos·(d/σ)² + w_alt·(Δalt/alt_scale)² + w_time·(Δt/time_scale)² + w_motion·[(Δv/speed_scale)² + (Δhdg/heading_scale)²]
 *     + w_class·mismatch + w_hist·(1 − stability)，σ = √(acc_o² + acc_T²)。
 * 门限：d ≤ gate_sigma·σ 且 c ≤ cost_max。所有权重与尺度来自 fusion_config；
 * AGL 与 AMSL 永不互比——基准不同时高度项为 0 并记 ALTITUDE_NOT_COMPARABLE，缺失事实不用默认值填。
 */
public final class AssociationCost {
    /** 契约固定：类别任一未知时 mismatch 取 0.5（两者都有且不同为 1，相同为 0）。 */
    public static final double CLASS_MISMATCH_UNKNOWN = 0.5;
    private static final double HALF_TURN_DEG = 180.0;
    private static final double FULL_TURN_DEG = 360.0;

    private final double gateSigma, wPos, wAlt, wTime, wMotion, wClass, wHist, altScaleM, timeScaleMs, speedScaleMps, headingScaleDeg, costMax;

    public AssociationCost(FusionParams params) {
        gateSigma = params.number("association", "gate_sigma");
        wPos = params.number("association", "w_pos");
        wAlt = params.number("association", "w_alt");
        wTime = params.number("association", "w_time");
        wMotion = params.number("association", "w_motion");
        wClass = params.number("association", "w_class");
        wHist = params.number("association", "w_hist");
        altScaleM = params.number("association", "alt_scale_m");
        timeScaleMs = params.number("association", "time_scale_ms");
        speedScaleMps = params.number("association", "speed_scale_mps");
        headingScaleDeg = params.number("association", "heading_scale_deg");
        costMax = params.number("association", "cost_max");
    }

    /** 候选目标在观测时刻的预测状态（由管线用 α-β 状态外推得到）。 */
    public record Candidate(String targetId, FusionDomainKey domain, double longitude, double latitude, double accuracyM,
            Double altitudeAmslM, Double heightAglM, Double speedMps, Double headingDeg, String classCode,
            long lastObservedMillis, int hits, int misses) { }

    public record Result(double cost, double distanceM, double sigmaM, boolean gated, List<String> unknowns) { }

    public Result evaluate(SourceObservation observation, double observationAccuracyM, Candidate candidate) {
        if (!observation.hasPosition()) return new Result(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN, false, List.of("POSITION_UNKNOWN"));
        List<String> unknowns = new ArrayList<>();
        double distance = AlphaBetaFilter.distanceM(observation.longitude(), observation.latitude(), candidate.longitude(), candidate.latitude());
        double sigma = Math.sqrt(observationAccuracyM * observationAccuracyM + candidate.accuracyM() * candidate.accuracyM());
        double cost = wPos * square(distance / sigma);

        Double altitudeDelta = altitudeDelta(observation, candidate);
        if (altitudeDelta == null) unknowns.add("ALTITUDE_NOT_COMPARABLE");
        else cost += wAlt * square(altitudeDelta / altScaleM);

        double dt = Math.abs(observation.observedMillis() - candidate.lastObservedMillis());
        cost += wTime * square(dt / timeScaleMs);

        if (observation.speedMps() != null && candidate.speedMps() != null && observation.headingDeg() != null && candidate.headingDeg() != null) {
            double dv = observation.speedMps() - candidate.speedMps();
            double dh = headingDelta(observation.headingDeg(), candidate.headingDeg());
            cost += wMotion * (square(dv / speedScaleMps) + square(dh / headingScaleDeg));
        } else {
            unknowns.add("MOTION_NOT_COMPARABLE");
        }

        if (observation.classCode() == null || candidate.classCode() == null) {
            cost += wClass * CLASS_MISMATCH_UNKNOWN;
            unknowns.add("CLASS_UNKNOWN");
        } else if (!observation.classCode().equals(candidate.classCode())) {
            cost += wClass;
        }

        int total = candidate.hits() + candidate.misses();
        double stability = total == 0 ? 0 : (double) candidate.hits() / total;
        cost += wHist * (1 - stability);

        boolean gated = distance <= gateSigma * sigma && cost <= costMax;
        return new Result(cost, distance, sigma, gated, List.copyOf(unknowns));
    }

    public double gateSigma() { return gateSigma; }
    public double costMax() { return costMax; }
    public double speedScaleMps() { return speedScaleMps; }
    public double headingScaleDeg() { return headingScaleDeg; }

    /** 只在同一高度基准下比较：AMSL 对 AMSL、AGL 对 AGL；否则 null。 */
    private static Double altitudeDelta(SourceObservation observation, Candidate candidate) {
        if (observation.altitudeAmslM() != null && candidate.altitudeAmslM() != null) return observation.altitudeAmslM() - candidate.altitudeAmslM();
        if (observation.heightAglM() != null && candidate.heightAglM() != null) return observation.heightAglM() - candidate.heightAglM();
        return null;
    }

    public static double headingDelta(double a, double b) {
        double diff = Math.abs(a - b) % FULL_TURN_DEG;
        return diff > HALF_TURN_DEG ? FULL_TURN_DEG - diff : diff;
    }

    private static double square(double value) { return value * value; }
}
