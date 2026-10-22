package com.uav.lowaltitude.modules.fusion.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HeadingDegreesTest {
    @ParameterizedTest
    @CsvSource({"0,0.00", "90,90.00", "180,180.00", "270,270.00", "45.126,45.13",
            "359.9949,359.99", "359.995,0.00", "359.999999,0.00", "360,0.00",
            "-0.001,0.00", "-0.006,359.99", "-90,270.00", "-360,0.00", "-720.006,359.99",
            "450.126,90.13", "720,0.00", "1079.999,0.00"})
    void circularRangeSurvivesQuantization(double input,String expected) {
        var result=HeadingDegrees.forStorage(input);
        assertThat(result).isEqualByComparingTo(expected).isGreaterThanOrEqualTo(java.math.BigDecimal.ZERO)
                .isLessThan(java.math.BigDecimal.valueOf(360));
        assertThat(result.scale()).isEqualTo(2);
    }
    @Test void unknownAndNonFiniteValuesNeverBecomeNorth() {
        for(Double value:new Double[]{null,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertThat(HeadingDegrees.forStorage(value)).isNull();
    }
}
