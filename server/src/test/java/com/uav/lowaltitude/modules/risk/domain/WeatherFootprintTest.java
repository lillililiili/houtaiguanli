package com.uav.lowaltitude.modules.risk.domain;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeatherFootprintTest {
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
