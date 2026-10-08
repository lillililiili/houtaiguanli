package com.uav.lowaltitude.modules.reporting.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.uav.lowaltitude.modules.reporting.domain.ObservationMetrics.*;

class ObservationMetricsTest {
    private final LocalDate day=LocalDate.of(2024,3,8);
    private final long midnight=day.atStartOfDay(ObservationMetrics.ZONE).toInstant().toEpochMilli();
    private final Policy policy=new Policy("test-config",3000,3,4,true);
    private Point p(String target,String track,long offset,double lon,String kind,String observation) {
        return new Point(target,track,midnight+offset,kind,lon,35d,10d,observation,"sensor","replay",false,policy,null);
    }
    private Result result(List<Point> points) { return ObservationMetrics.calculate(points,day,day,midnight+86_400_000); }
    @ParameterizedTest @CsvSource({"100,1000","110,7000","-65,12000"})
    void sumsMeasuredSegmentsAtDifferentLocationsAndTimes(double lon,long at) {
        String target=UUID.randomUUID().toString(),track=UUID.randomUUID().toString();
        var r=result(List.of(p(target,track,at,lon,"MEAS","a"),p(target,track,at+2000,lon+.0001,"MEAS","b")));
        assertThat(r.durationSeconds()).isEqualTo(2);
        assertThat(r.distanceMeters()).isBetween(8d,10d);
        assertThat(r.measuredTargets()).isEqualTo(1);
    }
    @Test void excludesGapsPredictionBridgeAndJumpWithoutJoiningAcrossThem() {
        var r=result(List.of(p("a","x",1000,110,"MEAS","1"),p("a","x",2000,110,"PRED","2"),
                p("a","x",3000,110,"MEAS","3"),p("a","x",4000,110,"BRIDGE","4"),p("a","x",5000,110,"MEAS","5"),
                p("a","x",10000,110,"MEAS","6"),p("a","x",11000,115,"MEAS","7")));
        assertThat(r.durationSeconds()).isNull();assertThat(r.status()).isEqualTo("INSUFFICIENT");
        assertThat(r.exclusions()).extracting(Exclusion::code).contains("NON_MEASURED","GAP","SPATIAL_BREAK");
    }
    @Test void exactDuplicatesDoNotDoubleCountAndConflictingTimestampBreaksContinuity() {
        var a=p("a","x",1000,110,"MEAS","1");
        var b=p("a","x",2000,110.0001,"MEAS","2");
        var r=result(List.of(b,a,a,p("a","x",3000,110.0002,"MEAS","3")));
        assertThat(r.durationSeconds()).isEqualTo(2);assertThat(r.validSegments()).isEqualTo(2);
        assertThat(result(List.of(a,b,p("a","x",2000,111,"MEAS","conflict"),p("a","x",3000,110,"MEAS","3"))).durationSeconds()).isNull();
    }
    @Test void noJoiningTracksOrCountingOverlappingTracksTwice() {
        assertThat(result(List.of(p("a","x",1000,110,"MEAS","1"),p("a","y",2000,110,"MEAS","2"))).durationSeconds()).isNull();
        var r=result(List.of(p("a","x",1000,110,"MEAS","1"),p("a","x",3000,110,"MEAS","2"),
                p("a","y",2000,110,"MEAS","3"),p("a","y",4000,110,"MEAS","4")));
        assertThat(r.durationSeconds()).isNull();assertThat(r.exclusions()).extracting(Exclusion::code).contains("OVERLAP");
    }
    @Test void separatesSimultaneousTargetsAndDoesNotAddFutureTail() {
        var r=result(List.of(p("a","x",1000,110,"MEAS","1"),p("a","x",3000,110,"MEAS","2"),
                p("b","y",1000,110,"MEAS","3"),p("b","y",3000,110,"MEAS","4")));
        assertThat(r.durationSeconds()).isEqualTo(4);assertThat(r.distanceMeters()).isZero();
        assertThat(r.measuredTargets()).isEqualTo(2);
        assertThat(ObservationMetrics.calculate(List.of(p("a","x",1000,110,"MEAS","1"),p("a","x",3000,110,"MEAS","2")),day,day,midnight+2000).durationSeconds()).isNull();
    }
    @Test void dailyClippingIsAdditiveAcrossMidnight() {
        List<Point> points=List.of(p("a","x",-1000,110,"MEAS","1"),p("a","x",1000,110.0001,"MEAS","2"));
        var today=result(points);
        var yesterday=ObservationMetrics.calculate(points,day.minusDays(1),day.minusDays(1),midnight+86_400_000);
        var both=ObservationMetrics.calculate(points,day.minusDays(1),day,midnight+86_400_000);
        assertThat(today.durationSeconds()).isEqualTo(1);assertThat(yesterday.durationSeconds()).isEqualTo(1);
        assertThat(today.distanceMeters()+yesterday.distanceMeters()).isCloseTo(both.distanceMeters(),within(1e-9));
    }
    @Test void unknownEvidenceOrUnconfirmedLiveConfigurationNeverBecomesZero() {
        Point a=p("a","x",1000,110,"MEAS","1"),b=p("a","x",2000,110,"MEAS","2");
        for(String reason:List.of("PROVENANCE","ANOMALY")) {
            var invalid=new Point(b.target(),b.track(),b.at(),b.kind(),b.lon(),b.lat(),b.accuracy(),b.observation(),b.source(),b.mode(),false,b.policy(),reason);
            assertThat(result(List.of(a,invalid)).durationSeconds()).isNull();
        }
        var live=new Point(a.target(),a.track(),a.at(),a.kind(),a.lon(),a.lat(),a.accuracy(),a.observation(),a.source(),"live",false,new Policy("demo",3000,3,4,false),null);
        assertThat(result(List.of(live,b)).exclusions()).extracting(Exclusion::code).contains("CONFIG");
        assertThat(result(List.of()).status()).isEqualTo("NO_DATA");
    }
    @Test void sourceSwitchAndRepeatedEvidenceAreBarriers() {
        Point a=p("a","x",1000,110,"MEAS","1"),b=p("a","x",2000,110,"MEAS","2");
        var switched=new Point(b.target(),b.track(),b.at(),b.kind(),b.lon(),b.lat(),b.accuracy(),b.observation(),"other",b.mode(),true,b.policy(),null);
        assertThat(result(List.of(a,switched)).durationSeconds()).isNull();
        assertThat(result(List.of(a,p("a","x",2000,110,"MEAS","1"))).durationSeconds()).isNull();
    }
}
