package com.uav.lowaltitude.modules.assessment.engine.checks;
import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class ExecutionDistanceTest {
    @Test void wgs84UsesEllipsoidMetersAndWrapsLongitude(){
        assertThat(distance(0,0,1,0)).isCloseTo(111319.490793,org.assertj.core.data.Offset.offset(0.001));
        assertThat(distance(0,0,0,1)).isCloseTo(110574.388558,org.assertj.core.data.Offset.offset(0.001));
        assertThat(distance(179.5,0,-179.5,0)).isCloseTo(distance(0,0,1,0),org.assertj.core.data.Offset.offset(0.001));
        assertThat(distance(118,37,118,37)).isZero();
        assertThat(distance(0,0,180,0)).isNaN();
    }
    private double distance(double a,double b,double c,double d){return ExecutionFactCheck.distance(BigDecimal.valueOf(a),BigDecimal.valueOf(b),BigDecimal.valueOf(c),BigDecimal.valueOf(d));}
}
