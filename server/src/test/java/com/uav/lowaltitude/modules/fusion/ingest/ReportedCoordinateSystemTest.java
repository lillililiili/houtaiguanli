package com.uav.lowaltitude.modules.fusion.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.integration.mqtt.EoEdgeEnvelope;
import com.uav.lowaltitude.integration.mqtt.LingyunEnvelope;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

class ReportedCoordinateSystemTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"WGS84", "WGS-84", "wgs84"})
    void explicitPackageDeclarationsReachEveryObservationMapper(String coordinateSystem) throws Exception {
        for (Example example : examples()) {
            example.root().put("coordinate_system", coordinateSystem);
            var item = example.mapper().map(inbox(example)).items().get(0);
            assertThat(item.longitude()).isEqualTo(121.375);
            assertThat(item.latitude()).isEqualTo(30.625);
        }
    }

    @Test
    void missingDeclarationsCannotCreateNewBusinessPositionsFromAnySource() throws Exception {
        for (Example example : examples()) {
            assertThatThrownBy(() -> example.mapper().map(inbox(example)))
                    .hasMessage("COORDINATE_SYSTEM_REQUIRED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GCJ02", "BD09", "CGCS2000", "", " ", "EPSG:4326", "xWGS84"})
    void anExplicitChildDeclarationCannotHideBehindThePackage(String wrong) throws Exception {
        for (Example example : examples()) {
            example.root().put("coordinate_system", "WGS84");
            example.position().put("coordinateSystem", wrong);
            assertThatThrownBy(() -> example.mapper().map(inbox(example)))
                    .hasMessage("COORDINATE_SYSTEM_NOT_SUPPORTED");
        }
    }

    @Test
    void declarationTypesAndConflictingAliasesAreRejected() throws Exception {
        for (Example example : examples()) {
            example.root().put("coordinate_system", "WGS84");
            example.position().put("coordinateSystem", 4326);
            assertThatThrownBy(() -> example.mapper().map(inbox(example)))
                    .hasMessage("COORDINATE_SYSTEM_NOT_SUPPORTED");
            example.position().put("coordinateSystem", "WGS84");
            example.position().put("coordinate_system", "GCJ02");
            assertThatThrownBy(() -> example.mapper().map(inbox(example)))
                    .hasMessage("COORDINATE_SYSTEM_NOT_SUPPORTED");
        }
    }

    @Test
    void controllerOnlyCoordinatesStillRequireADeclaration() throws Exception {
        Example example = examples().get(0);
        example.position().remove(List.of("longitude", "latitude"));
        var extension = example.position().putObject("extension");
        extension.put("pilotLon", 105.25).put("pilotLat", 29.75);
        assertThatThrownBy(() -> example.mapper().map(inbox(example))).hasMessage("COORDINATE_SYSTEM_REQUIRED");
        extension.put("coordinate_system", "WGS-84");
        var item = example.mapper().map(inbox(example)).items().get(0);
        assertThat(item.longitude()).isNull();
        assertThat(item.pilotLongitude()).isEqualTo(105.25);
    }

    @Test
    void extensionDeclarationCannotContradictItsTargetsCoordinates() throws Exception {
        Example example = examples().get(0);
        example.root().put("coordinate_system", "WGS84");
        example.position().putObject("extension").put("coordinate_system", "GCJ02");
        assertThatThrownBy(() -> example.mapper().map(inbox(example))).hasMessage("COORDINATE_SYSTEM_NOT_SUPPORTED");
    }

    @Test
    void mqttWorkParametersAndSenseDataValidateBeforeAcceptance() throws Exception {
        var work = json.createObjectNode().put("providerCode", "provider").put("deviceId", "device")
                .put("deviceName", "name").put("deviceType", 1).put("workState", 1)
                .put("deviceLongitude", 109.5).put("deviceLatitude", 24.5);
        assertThatThrownBy(() -> LingyunEnvelope.decode("bridge/provider/device/radar/device", json.writeValueAsBytes(work)))
                .hasMessage("COORDINATE_SYSTEM_REQUIRED");
        work.put("coordinate_system", "WGS84");
        assertThat(LingyunEnvelope.decode("bridge/provider/device/radar/device", json.writeValueAsBytes(work)).longitude())
                .isEqualTo(109.5);
        Example sense = examples().get(0);
        sense.root().put("deviceId", "device");
        assertThatThrownBy(() -> LingyunEnvelope.decode("bridge/provider/device_data/radar/device",
                json.writeValueAsBytes(sense.root()))).hasMessage("COORDINATE_SYSTEM_REQUIRED");
    }

    @Test
    void endAndFailedTrackingReceiptsRemainAvailableWithoutCoordinateDeclarations() throws Exception {
        Example eo = examples().get(1);
        eo.root().put("edgeId", "edge");
        ObjectNode metadata = (ObjectNode) eo.root().path("metadata");
        metadata.put("deviceId", "device");
        assertThat(EoEdgeEnvelope.decode("iot-reporting/cmlc/edge/edge", json.writeValueAsBytes(eo.root()))
                .observationCoordinateError()).isEqualTo("COORDINATE_SYSTEM_REQUIRED");
        eo.root().put("event", "EndTracking");
        assertThat(EoEdgeEnvelope.decode("iot-reporting/cmlc/edge/edge", json.writeValueAsBytes(eo.root())).event())
                .isEqualTo("EndTracking");
        assertThat(EoEdgeEnvelope.decode("iot-reporting/cmlc/edge/edge", json.writeValueAsBytes(eo.root()))
                .observationCoordinateError()).isNull();
        assertThat(eo.mapper().map(inbox(eo)).items()).isEmpty();
        eo.root().put("event", "BeginTracking");
        metadata.put("codeStatus", 500);
        assertThat(EoEdgeEnvelope.decode("iot-reporting/cmlc/edge/edge", json.writeValueAsBytes(eo.root())).codeStatus())
                .isEqualTo(500);
        assertThat(EoEdgeEnvelope.decode("iot-reporting/cmlc/edge/edge", json.writeValueAsBytes(eo.root()))
                .observationCoordinateError()).isNull();
        assertThat(eo.mapper().map(inbox(eo)).items()).isEmpty();
    }

    private List<Example> examples() throws Exception {
        var sense = (ObjectNode) json.readTree("{\"msgCnt\":1,\"ptTime\":1234,\"objects\":[{\"objectId\":\"" + UUID.randomUUID()
                + "\",\"time\":1234,\"longitude\":121.375,\"latitude\":30.625}]}");
        var eo = (ObjectNode) json.readTree("{\"event\":\"BeginTracking\",\"timestamp\":1234,\"metadata\":{"
                + "\"taskId\":\"" + UUID.randomUUID() + "\",\"workState\":1,\"codeStatus\":200,"
                + "\"aiStatus\":{\"longitude\":121.375,\"latitude\":30.625}}}");
        var radar = (ObjectNode) json.readTree("{\"device_id\":\"radar\",\"boot_micros\":1,\"frame_id\":1,"
                + "\"items\":[{\"external_track_id\":\"" + UUID.randomUUID() + "\",\"longitude\":121.375,\"latitude\":30.625}]}");
        var normalized = (ObjectNode) json.readTree("{\"source_id\":\"source\",\"owner_org_id\":\"org\",\"district_id\":\"district\","
                + "\"observed_at\":1234,\"items\":[{\"external_target_id\":\"" + UUID.randomUUID()
                + "\",\"longitude\":121.375,\"latitude\":30.625}]}");
        var replay = (ObjectNode) json.readTree("{\"dataset_id\":\"dataset\",\"record_no\":1,\"frame\":{\"source_code\":\"source\","
                + "\"observed_at\":1234,\"items\":[{\"external_target_id\":\"" + UUID.randomUUID()
                + "\",\"lon\":121.375,\"lat\":30.625}]}}");
        return List.of(new Example(new LingyunSenseDataMapper(json), "lingyun:radar:device", sense, (ObjectNode) sense.path("objects").get(0)),
                new Example(new EoTrackingReportMapper(json), "eo-edge:edge", eo, (ObjectNode) eo.path("metadata").path("aiStatus")),
                new Example(new LiveRadarFrameMapper(json), "live-radar:radar", radar, (ObjectNode) radar.path("items").get(0)),
                new Example(new NormalizedObservationMapper(json), "sim-normalized:source", normalized, (ObjectNode) normalized.path("items").get(0)),
                new Example(new ReplayFrameMapper(json), "replay:source:dataset", replay, (ObjectNode) replay.path("frame").path("items").get(0)));
    }

    private InboxRow inbox(Example example) {
        return new InboxRow(UUID.randomUUID().toString(), example.source(), UUID.randomUUID().toString(), "source",
                1234L, example.root().toString());
    }

    private record Example(FrameMapper mapper, String source, ObjectNode root, ObjectNode position) { }
}
