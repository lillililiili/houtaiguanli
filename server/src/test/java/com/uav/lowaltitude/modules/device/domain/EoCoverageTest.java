package com.uav.lowaltitude.modules.device.domain;

import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class EoCoverageTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidPoints")
    void invalidPointFailsClosedEvenForCircle(double[] point) {
        var p=Map.<String,Object>of("longitude",0d,"latitude",0d,"coordinate_system","WGS84","coverage_kind","CIRCLE","radius_m",1000);
        assertThat(EoCoverage.contains(p,point)).isFalse();
    }
    static java.util.stream.Stream<double[]> invalidPoints() {
        return java.util.stream.Stream.of(null,new double[]{},new double[]{0},new double[]{Double.NaN,0},
                new double[]{0,Double.NaN},new double[]{Double.POSITIVE_INFINITY,0},new double[]{0,Double.NEGATIVE_INFINITY},
                new double[]{180.01,0},new double[]{0,-90.01});
    }
    @Test void configuredCoverageUsesCurrentPointAndTrueNorthSectorAcrossDifferentLocations() {
        for(double longitude:new double[]{118.5,-72.1,0}) {
            var p=new HashMap<String,Object>(Map.of("longitude",longitude,"latitude",37.4,"coordinate_system","WGS-84",
                    "coverage_kind","SECTOR","range_m",1500,"azimuth_deg",0,"fov_deg",120));
            assertThat(EoCoverage.contains(p,new double[]{longitude,37.405})).isTrue();
            assertThat(EoCoverage.contains(p,new double[]{longitude,37.395})).isFalse();
            assertThat(EoCoverage.contains(p,new double[]{longitude,37.42})).isFalse();
            p.put("azimuth_deg",180);
            assertThat(EoCoverage.contains(p,new double[]{longitude,37.395})).isTrue();
            p.put("coverage_kind","CIRCLE");p.put("radius_m",1500);
            assertThat(EoCoverage.contains(p,new double[]{longitude,37.405})).isTrue();
            p.remove("coordinate_system");
            assertThat(EoCoverage.contains(p,new double[]{longitude,37.405})).isFalse();
        }
        assertThat(EoCoverage.contains(null,new double[]{0,0})).isFalse();
    }
}
