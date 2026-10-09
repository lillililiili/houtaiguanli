package com.uav.lowaltitude.modules.assessment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LegalityStatusProjectionTest {
    @ParameterizedTest
    @CsvSource({
        "UNDETERMINED,ACTIVE,OVERRIDDEN,LEGAL,LEGAL",
        "LEGAL,ACTIVE,OVERRIDDEN,ILLEGAL,ILLEGAL",
        "ABNORMAL,ACTIVE,CONFIRMED,UNDETERMINED,UNDETERMINED",
        "ILLEGAL,ACTIVE,SUPERSEDED,LEGAL,LEGAL",
        "ILLEGAL,ACTIVE,SUPERSEDED,,ILLEGAL",
        "ILLEGAL,ACTIVE,PENDING_REVIEW,LEGAL,ILLEGAL",
        "ILLEGAL,ACTIVE,REJECTED,,REJECTED",
        "UNDETERMINED,ACTIVE,REJECTED,,REJECTED",
        "LEGAL,ACTIVE,REJECTED,,REJECTED",
        "ABNORMAL,ACTIVE,REJECTED,,REJECTED",
        "ILLEGAL,SHADOW,REJECTED,,ILLEGAL",
        "ILLEGAL,SHADOW,OVERRIDDEN,LEGAL,ILLEGAL",
        "ABNORMAL,ACTIVE,,,UNDETERMINED",
        "LEGAL,ACTIVE,OVERRIDDEN,ABNORMAL,UNDETERMINED",
        "ILLEGAL,ACTIVE,OVERRIDDEN,NOT_APPLICABLE,ILLEGAL"
    })
    void onlyExplicitHumanDecisionsApplyToTheirOwnActiveEvaluation(String original, String mode, String review,
            String manual, String effective) {
        assertThat(LegalityStatusProjection.effective(original, mode, review, manual)).isEqualTo(effective);
    }
}
