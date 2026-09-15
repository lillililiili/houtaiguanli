package com.uav.lowaltitude.modules.flight.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.target.api.TargetDtos.LocationDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackPointDto;

class FlightTrajectoryServiceTest {
    @Test void connectsOnlyConsecutiveMeasuredPointsWithinConfiguredGap() {
        assertThat(FlightTrajectoryService.continuous(point("t",1,1000,"MEAS"),point("t",2,2000,"MEAS"),2000L)).isTrue();
        assertThat(FlightTrajectoryService.continuous(point("t",1,1000,"MEAS"),point("t",3,2000,"MEAS"),2000L)).isFalse();
        assertThat(FlightTrajectoryService.continuous(point("t",1,1000,"MEAS"),point("t",2,5000,"MEAS"),2000L)).isFalse();
        assertThat(FlightTrajectoryService.continuous(point("a",1,1000,"MEAS"),point("b",2,2000,"MEAS"),2000L)).isFalse();
        assertThat(FlightTrajectoryService.continuous(point("t",1,1000,"MEAS"),point("t",2,2000,"MEAS"),null)).isFalse();
        assertThat(FlightTrajectoryService.continuous(point("t",1,1000,"MEAS"),point("t",2,1000,"MEAS"),2000L)).isFalse();
    }
    @Test void predictionsAndBridgesAreNeverTreatedAsMeasuredFlight() {
        assertThat(FlightTrajectoryService.measured(point("t",1,1000,"PRED"))).isFalse();
        assertThat(FlightTrajectoryService.measured(point("t",1,1000,"BRIDGE"))).isFalse();
        assertThat(FlightTrajectoryService.measured(point("t",1,1000,null))).isTrue();
    }
    private TrackPointDto point(String track,long seq,long at,String kind){return new TrackPointDto("p"+seq,track,seq,at,"OBSERVED",at,at,
        new LocationDto(new BigDecimal("118.5"),new BigDecimal("37.5"),"WGS84"),null,null,kind,null,null,false,null);}
}
