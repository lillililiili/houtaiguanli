package com.uav.lowaltitude.modules.risk.domain;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeatherFootprintTest {
    @ParameterizedTest @CsvSource({"118.6,37.4", "119.1,36.8"})
    void acceptsDetailedBoundariesAndKeepsGeometryAndSizeGuards(double x, double y) {
        for (int count : new int[]{659, 1000}) {
            var ring = circle(x, y, count);
            assertThatCode(() -> WeatherFootprint.validate(ring)).doesNotThrowAnyException();
            assertThat(WeatherFootprint.intersects(ring, List.of(List.of(x,y),List.of(x+.001,y)),0)).isTrue();
            assertThat(WeatherFootprint.intersects(ring, List.of(List.of(x+1,y),List.of(x+1,y+.01)),0)).isFalse();
            var crossed = new ArrayList<>(ring);
            java.util.Collections.swap(crossed, 2, count/2);
            assertThatThrownBy(() -> WeatherFootprint.validate(crossed)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WeatherFootprint.validate(circle(x,y,1001)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1000");
    }

    @Test void inputContractAcceptsTheSameBoundaryLimit() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (int count : new int[]{659, 1000, 1001}) {
                var input = new com.uav.lowaltitude.modules.integrationconfig.api.LocalInterfaceDtos.WeatherInput(
                    "boundary-test", null, "模拟区域", 1L, List.of(), null, circle(118.6,37.4,count), "WGS84");
                assertThat(validator.validateProperty(input,"polygon").isEmpty()).isEqualTo(count<=1000);
            }
        }
    }

    private List<List<Double>> circle(double x, double y, int count) {
        var ring = new ArrayList<List<Double>>();
        for (int i=0;i<count;i++) {
            double angle = 2*Math.PI*i/count;
            ring.add(List.of(x+.1*Math.cos(angle),y+.1*Math.sin(angle)));
        }
        ring.add(ring.get(0));
        return ring;
    }

    @ParameterizedTest @CsvSource({"118.6,37.4", "119.1,36.8"})
    void intersectsCrossingsInteriorAndCorridorButNotUnrelatedRoutes(double x,double y) {
        var ring=List.of(List.of(x,y),List.of(x+.01,y),List.of(x+.01,y+.01),List.of(x,y+.01),List.of(x,y));
        WeatherFootprint.validate(ring);
        assertThat(WeatherFootprint.intersects(ring,List.of(List.of(x-.01,y+.005),List.of(x+.02,y+.005)),0)).isTrue();
        assertThat(WeatherFootprint.intersects(ring,List.of(List.of(x+.002,y+.002),List.of(x+.005,y+.005)),0)).isTrue();
        var near=List.of(List.of(x,y-.0001),List.of(x+.01,y-.0001));
        assertThat(WeatherFootprint.intersects(ring,near,0)).isFalse();
        assertThat(WeatherFootprint.intersects(ring,near,100)).isTrue();
        assertThat(WeatherFootprint.intersects(ring,List.of(List.of(x+1,y),List.of(x+1,y+.01)),100)).isFalse();
        assertThat(WeatherFootprint.intersects(ring,null,100)).isFalse();
        assertThat(WeatherFootprint.intersects(null,null,100)).isTrue();
        assertThatThrownBy(()->WeatherFootprint.validate(List.of(List.of(0.,0.),List.of(2.,0.),List.of(1.,0.),List.of(1.,1.),List.of(0.,0.))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重叠");
    }
}
