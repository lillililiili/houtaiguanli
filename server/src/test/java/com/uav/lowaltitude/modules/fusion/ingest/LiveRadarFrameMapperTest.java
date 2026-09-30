package com.uav.lowaltitude.modules.fusion.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

class LiveRadarFrameMapperTest {
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
