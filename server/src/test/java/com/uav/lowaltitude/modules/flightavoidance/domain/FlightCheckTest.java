package com.uav.lowaltitude.modules.flightavoidance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.uav.lowaltitude.modules.flightavoidance.domain.FlightAvoidancePolicy.CounterMode;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck.Aircraft;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck.Level;

class FlightCheckTest {
    private static final double LON = 118.614, LAT = 37.464;
    private static final FlightAvoidancePolicy POLICY = new FlightAvoidancePolicy(3, new BigDecimal("10"), new BigDecimal("30"),
            3000, new BigDecimal("5"), 1000, 0);

    /** 正东方向 km 公里处的航班。 */
    private static Aircraft east(String id, double km, Integer altitude, Double speed, Double track) {
        double lon = LON + km / (111.32 * Math.cos(Math.toRadians(LAT)));
        return new Aircraft(id, "CES" + id, "东方航空", lon, LAT, altitude, speed, track, 1000);
    }

    @Test
    void noReceiverMeansNoCheck() {
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 0, List.of()).level()).isEqualTo(Level.OFF);
    }

    @Test
    void staleDataIsNotTreatedAsEmptySky() {
        var result = FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1, null);
        assertThat(result.level()).isEqualTo(Level.NO_DATA);
        assertThat(result.needsConfirmation()).isTrue();
        assertThat(result.clearForAutomatic()).isFalse();
    }

    @Test
    void forceLandZonesFollowDistanceAndAltitude() {
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1, List.of(east("001", 8, 1200, null, null))).level())
                .isEqualTo(Level.BLOCK);
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1, List.of(east("002", 21, 1800, null, null))).level())
                .isEqualTo(Level.WARN);
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1, List.of(east("003", 8, 9000, null, null))).level())
                .isEqualTo(Level.CLEAR);
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1, List.of(east("004", 40, 1000, null, null))).level())
                .isEqualTo(Level.CLEAR);
    }

    @Test
    void unknownAltitudeCountsAsLow() {
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1, List.of(east("005", 20, null, null, null))).level())
                .isEqualTo(Level.WARN);
    }

    @Test
    void aircraftApproachingWithinFiveMinutesCounts() {
        // 15 公里处向西（朝设备）以 60 米/秒飞行，5 分钟后约 3 公里：算进禁止反制范围。
        var result = FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1, List.of(east("006", 15, 1500, 60.0, 270.0)));
        assertThat(result.level()).isEqualTo(Level.BLOCK);
        assertThat(result.aircraft().get(0).approaching()).isTrue();
        assertThat(result.aircraft().get(0).distanceKm()).isBetween(14.5, 15.5);
        // 同样位置向东飞离：只在提醒范围。
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1, List.of(east("007", 15, 1500, 60.0, 90.0))).level())
                .isEqualTo(Level.WARN);
    }

    @Test
    void driveAwayHasOnlyANarrowWarningZone() {
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.DRIVE_AWAY, LON, LAT, 1, List.of(east("008", 3, 800, null, null))).level())
                .isEqualTo(Level.WARN);
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.DRIVE_AWAY, LON, LAT, 1, List.of(east("009", 8, 800, null, null))).level())
                .isEqualTo(Level.CLEAR);
        assertThat(FlightCheck.evaluate(POLICY, CounterMode.DRIVE_AWAY, LON, LAT, 1, List.of(east("010", 3, 1500, null, null))).level())
                .isEqualTo(Level.CLEAR);
    }

    @Test
    void blockedAircraftListedFirst() {
        var result = FlightCheck.evaluate(POLICY, CounterMode.FORCE_LAND, LON, LAT, 1,
                List.of(east("011", 20, 1000, null, null), east("012", 9, 1000, null, null)));
        assertThat(result.aircraft()).extracting(FlightCheck.Nearby::icao24).containsExactly("012", "011");
    }
}
