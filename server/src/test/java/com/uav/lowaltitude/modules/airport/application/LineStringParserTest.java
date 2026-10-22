package com.uav.lowaltitude.modules.airport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class LineStringParserTest {
    @Test void acceptsExplicitWgs84InBothHemispheresAndPreservesPointOrder() {
        assertThat(LineStringParser.parse("SRID=4326;LINESTRING (118.500 37.25,118.6 37.4)"))
                .isEqualTo("SRID=4326;LINESTRING (118.5 37.25,118.6 37.4)");
        assertThat(LineStringParser.parse("srid = 4326; linestring (-73.5 -23.1, -73.4 -23.2)"))
                .isEqualTo("SRID=4326;LINESTRING (-73.5 -23.1,-73.4 -23.2)");
        assertThat(LineStringParser.parse(null)).isNull();
    }

    @Test void rejectsUnknownDatumOtherGeometryDimensionsAndInvalidNumbers() {
        for (String value : new String[] {
                "LINESTRING (118 37,119 38)", "SRID=4490;LINESTRING (118 37,119 38)",
                "SRID=3857;LINESTRING (118 37,119 38)", "SRID=4326;POINT (118 37)",
                "SRID=4326;LINESTRING (118 37)", "SRID=4326;LINESTRING (118 37,)",
                "SRID=4326;LINESTRING (181 37,119 38)", "SRID=4326;LINESTRING (-73 -91,-72 -30)",
                "SRID=4326;LINESTRING (NaN 37,119 38)", "SRID=4326;LINESTRING (118 37 20,119 38 20)",
                "SRID=4326;LINESTRING (118 37,119 38);SRID=4326;POINT(1 2)" })
            assertThatIllegalArgumentException().as(value).isThrownBy(() -> LineStringParser.parse(value));
    }
}
