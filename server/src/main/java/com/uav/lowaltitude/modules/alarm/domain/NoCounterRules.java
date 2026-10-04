package com.uav.lowaltitude.modules.alarm.domain;

import java.util.List;
import java.util.Set;

/** A human decision closes one handling episode without erasing the event's factual conclusion. */
public final class NoCounterRules {
    private NoCounterRules() { }
    public record Basis(String evaluationId, Long observedAt, Long evaluatedAt, String legalStatus,
                        String grade, List<String> violationReasons) { }
    public static boolean fresh(Long at, long now, Integer seconds) {
        return at != null && seconds != null && seconds > 0 && at <= now && now - at <= seconds * 1000L;
    }
    public static boolean explicit(String legalStatus) { return Set.of("LEGAL", "ILLEGAL", "ABNORMAL").contains(legalStatus == null ? "" : legalStatus); }
    public static boolean newRisk(String previousLegal, String previousGrade, List<String> previousReasons,
                                  String legal, String grade, List<String> reasons) {
        if (!Set.of("ILLEGAL", "ABNORMAL").contains(legal == null ? "" : legal)) return false;
        return "LEGAL".equals(previousLegal) || ("ABNORMAL".equals(previousLegal) && "ILLEGAL".equals(legal))
                || rank(grade) > rank(previousGrade) || !previousReasons.containsAll(reasons);
    }
    private static int rank(String grade) { return "HIGH".equals(grade) ? 3 : "MEDIUM".equals(grade) ? 2 : "LOW".equals(grade) ? 1 : 0; }
}
