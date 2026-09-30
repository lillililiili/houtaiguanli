package com.uav.lowaltitude.modules.assessment.infrastructure;

/** Historical abnormal results never become confirmed violations by relabeling. */
public final class LegalityStatusProjection {
    private LegalityStatusProjection() { }

    public static String current(String original) {
        return "ABNORMAL".equals(original) ? "UNDETERMINED" : original;
    }

    public static String sql(String column) {
        return "(CASE WHEN " + column + "='ABNORMAL' THEN 'UNDETERMINED' ELSE " + column + " END)";
    }
}
