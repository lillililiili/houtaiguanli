package com.uav.lowaltitude.platform.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class Wgs84CoordinatesTest {
    @ParameterizedTest
    @ValueSource(strings = {"WGS84", "WGS-84", "wgs84", " WGS-84 "})
    void acceptsOnlyKnownAliases(String declaration) {
        assertThat(Wgs84Coordinates.isWgs84(declaration)).isTrue();
        Wgs84Coordinates.requirePoint(new BigDecimal("-73.6"), new BigDecimal("40.8"), declaration);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "GCJ-02", "BD-09", "CGCS2000", "EPSG:4490", "not-WGS84", "WGS84/GCJ02"})
    void rejectsUnspecifiedOrDifferentDatumForCoordinates(String declaration) {
        assertThatThrownBy(() -> Wgs84Coordinates.requirePoint(
                new BigDecimal("118.25"), new BigDecimal("37.4"), declaration))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"180,90", "-180,-90", "0,0", "-122.4,37.8"})
    void acceptsGeographicBoundaries(double longitude, double latitude) {
        assertThat(Wgs84Coordinates.isValidPoint(longitude, latitude)).isTrue();
        Wgs84Coordinates.requirePoint(BigDecimal.valueOf(longitude), BigDecimal.valueOf(latitude), "WGS84");
    }

    @ParameterizedTest
    @CsvSource({"180.000001,0", "0,-90.000001", "NaN,0", "0,Infinity"})
    void rejectsInvalidNumbers(double longitude, double latitude) {
        assertThat(Wgs84Coordinates.isValidPoint(longitude, latitude)).isFalse();
    }

    @Test
    void absentAndIncompleteLocationsAreDifferent() {
        assertThatCode(() -> Wgs84Coordinates.requirePoint(null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> Wgs84Coordinates.requirePoint(null, null, "WGS84")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Wgs84Coordinates.requirePoint(BigDecimal.ZERO, null, "WGS84"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Wgs84Coordinates.requirePoint(null, null, "GCJ02"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Wgs84Coordinates.requirePoint(new BigDecimal("180.000000000001"),
                BigDecimal.ZERO, "WGS84")).isInstanceOf(IllegalArgumentException.class);
    }
}
