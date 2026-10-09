package com.uav.lowaltitude.modules.assessment.infrastructure;

/** Historical abnormal results never become confirmed violations by relabeling. */
public final class LegalityStatusProjection {
    private LegalityStatusProjection() { }

    public static String current(String original) {
        return "ABNORMAL".equals(original) ? "UNDETERMINED" : original;
    }

    /** Manual escalation may raise an unresolved concern, but never a legal or rejected conclusion. */
    public static boolean canEscalate(String effectiveStatus) {
        return "ILLEGAL".equals(effectiveStatus) || "UNDETERMINED".equals(effectiveStatus);
    }

    public static String sql(String column) {
        return "(CASE WHEN " + column + "='ABNORMAL' THEN 'UNDETERMINED' ELSE " + column + " END)";
    }

    /** A review applies only to its evaluation; superseded records retain their historical decision. */
    public static String effective(String original, String mode, String reviewState, String manualStatus) {
        if ("ACTIVE".equals(mode) && "REJECTED".equals(reviewState)) return "REJECTED";
        boolean reviewed = "CONFIRMED".equals(reviewState) || "OVERRIDDEN".equals(reviewState) || "SUPERSEDED".equals(reviewState);
        boolean validManual = "LEGAL".equals(manualStatus) || "ILLEGAL".equals(manualStatus)
                || "UNDETERMINED".equals(manualStatus) || "ABNORMAL".equals(manualStatus);
        return current("ACTIVE".equals(mode) && reviewed && validManual ? manualStatus : original);
    }

    /** Uses the same projection before filtering, counting and pagination; never rewrites engine facts. */
    public static String effectiveSql(String evaluation, String review) {
        return sql("(CASE WHEN " + evaluation + ".mode='ACTIVE' AND " + rejectedSql(evaluation, review)
                + " THEN 'REJECTED' WHEN " + evaluation + ".mode='ACTIVE' AND " + review
                + ".review_state IN ('CONFIRMED','OVERRIDDEN','SUPERSEDED') AND " + review
                + ".manual_status IN ('LEGAL','ILLEGAL','UNDETERMINED','ABNORMAL') THEN " + review
                + ".manual_status ELSE " + evaluation + ".legal_status END)");
    }

    /** Recomputing changes the review header to SUPERSEDED, but cannot erase a saved rejection. */
    public static String rejectedSql(String evaluation, String review) {
        return "(" + review + ".review_state='REJECTED' OR (" + review + ".review_state='SUPERSEDED'"
                + " AND EXISTS (SELECT 1 FROM legality_review_history rejected_history WHERE rejected_history.evaluation_id="
                + evaluation + ".evaluation_id AND rejected_history.conclusion='REJECT')))";
    }
}
