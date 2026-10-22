package com.uav.lowaltitude.modules.fusion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Circular heading at the precision shared by source_observation and target_latest_state. */
public final class HeadingDegrees {
    private static final BigDecimal FULL_TURN = BigDecimal.valueOf(360);
    private static final int STORAGE_SCALE = 2;
    private HeadingDegrees() { }

    public static BigDecimal forStorage(Double degrees) {
        if (degrees == null || !Double.isFinite(degrees)) return null;
        BigDecimal normalized = BigDecimal.valueOf(degrees).remainder(FULL_TURN);
        if (normalized.signum() < 0) normalized = normalized.add(FULL_TURN);
        // 359.995 is north at two decimal places. Rounding alone would violate [0, 360).
        return normalized.setScale(STORAGE_SCALE, RoundingMode.HALF_UP).remainder(FULL_TURN);
    }
}
