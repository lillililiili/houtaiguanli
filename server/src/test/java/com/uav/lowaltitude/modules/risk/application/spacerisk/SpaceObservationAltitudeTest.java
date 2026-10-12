package com.uav.lowaltitude.modules.risk.application.spacerisk;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import com.uav.lowaltitude.modules.risk.application.spacerisk.SpaceRiskSpatialPort.SpaceObservation;

class SpaceObservationAltitudeTest {
    @ParameterizedTest
    @CsvSource({"100,60,AMSL,100,AMSL", "235,135,AMSL,235,AMSL", "100,60,AGL,60,AGL", ",60,AMSL,60,AGL", "0,0,AMSL,0,AMSL"})
    void routeComparisonUsesReportedDatumWhileAglBandIsPreserved(BigDecimal amsl, BigDecimal agl,
            String routeDatum, BigDecimal expected, String expectedDatum) {
        var row = new SpaceObservation(UUID.randomUUID().toString(), "target", "BIRD_FLOCK", "plan", "route",
                BigDecimal.ONE, BigDecimal.TEN, agl, "AGL", routeDatum, 25, "UNKNOWN", "org", "district",
                null, null, null, null, amsl);
        assertThat(row.altitudeM()).isEqualTo(agl);
        assertThat(row.altitudeDatum()).isEqualTo("AGL");
        assertThat(row.comparisonAltitudeM()).isEqualTo(expected);
        assertThat(row.comparisonAltitudeDatum()).isEqualTo(expectedDatum);
    }
}
