package com.uav.lowaltitude.modules.fusion.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

class LiveRadarFrameMapperTest {
    private FrameMapper.Item movingItem(String fields) {
        String deviceId = UUID.randomUUID().toString();
        String targetId = UUID.randomUUID().toString();
        String payload = "{\"device_id\":\"" + deviceId + "\",\"boot_micros\":42,\"frame_id\":\"7\",\"items\":[{"
                + "\"external_track_id\":\"" + targetId + "\",\"classification\":\"UAV\","
                + "\"longitude\":119.25,\"latitude\":36.75," + fields + "}]}";
        return new LiveRadarFrameMapper(new ObjectMapper()).map(new InboxRow(UUID.randomUUID().toString(),
                "live-radar:" + deviceId, "7", deviceId, Instant.now().toEpochMilli(), payload)).items().get(0);
    }

    @ParameterizedTest
    @CsvSource({"3,4,5", "-12,5,13", "4,-3,5", "0,-8.5,8.5", "0,0,0"})
    void reportedHorizontalComponentsSupplySpeedOnTheFirstObservation(double vx, double vy, double expected) {
        FrameMapper.Item observation = movingItem("\"velocity_x_mps\":" + vx + ",\"velocity_y_mps\":" + vy
                + ",\"velocity_z_mps\":12");
        assertThat(observation.speedMps()).isEqualTo(expected);
        // 站址航向未给出，速度大小可用，但不能将体坐标方向冒充地理航向。
        assertThat(observation.headingDeg()).isNull();
        assertThat(observation.quality().get("speed_xyz")).isEqualTo(java.util.Map.of("x", vx, "y", vy, "z", 12.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"velocity_z_mps\":5", "\"velocity_x_mps\":3", "\"velocity_y_mps\":4",
            "\"velocity_x_mps\":null,\"velocity_y_mps\":4",
            "\"velocity_x_mps\":\"3\",\"velocity_y_mps\":4",
            "\"velocity_x_mps\":1e400,\"velocity_y_mps\":4",
            "\"velocity_x_mps\":3,\"velocity_y_mps\":-1e400"
    })
    void missingOrInvalidComponentsDoNotInventStationarySpeed(String fields) {
        assertThat(movingItem(fields).speedMps()).isNull();
    }

    private FrameMapper.Item item(String classification) {
        String payload = "{\"device_id\":\"radar\",\"boot_micros\":1,\"frame_id\":\"1\",\"items\":[{"
                + "\"external_track_id\":\"1\",\"classification\":\"" + classification + "\"}]}";
        return new LiveRadarFrameMapper(new ObjectMapper()).map(
                new InboxRow("1", "live-radar:radar", "1", "radar", 1000L, payload)).items().get(0);
    }

    @Test void decodedCategoriesReachFusionWithoutInventingConfidence() {
        assertThat(item("UAV").classCode()).isEqualTo("UAV");
        assertThat(item("BIRD").classCode()).isEqualTo("BIRD");
        assertThat(item("UAV").classConfidence()).isNull();
        assertThat(item("UAV").longitude()).isNull();
    }

    @Test void unidentifiedOrUndocumentedCategoriesRemainUnknown() {
        for (String code : new String[]{"PENDING_IDENTIFICATION", "UNIDENTIFIED", "BALLOON", "3"}) {
            assertThat(item(code).classCode()).isNull();
            assertThat(item(code).quality()).containsEntry("classification_raw", code);
        }
    }
}
