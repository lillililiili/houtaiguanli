package com.uav.lowaltitude.modules.assessment.api;

import java.util.List;

public final class LegalityDtos {
    private LegalityDtos() { }

    public record PageDto<T>(List<T> items, int page, int size, long total) { }
    public record CheckDto(String ruleCode, String resultCode, String reasonCode) { }
    public record LegalityAssessmentDto(String assessmentId, String planId, String targetId, String trackId,
            String routeVersionId, String ruleVersionId, String ruleVersionCode, long assessedAt,
            String conclusionCode, List<CheckDto> checks, List<String> unknownReasons,
            List<String> evidenceReferences, String sourceMode) { }
}
