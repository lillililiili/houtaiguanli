package com.uav.lowaltitude.modules.fusion.ingest;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

class NormalizedObservationCountTest {
    private final ObjectMapper json = new ObjectMapper();
    private final NormalizedObservationMapper mapper = new NormalizedObservationMapper(json);

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 20, 25, 47})
    void preservesObservedCountWithoutInferringFlockFromClass(int count) throws Exception {
        var item = frame("\"object_count\":" + count).items().get(0);
        assertThat(item.quality()).containsEntry("object_count", count).containsEntry("source", "LOCAL_SIMULATOR");
        assertThat(item.classCode()).isEqualTo("BIRD");
    }

    @Test void absentCountStaysUnknown() throws Exception {
        assertThat(frame("\"object_count\":null").items().get(0).quality()).doesNotContainKey("object_count");
        assertThat(frame("\"speed_mps\":5").items().get(0).quality()).doesNotContainKey("object_count");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "2147483648", "\"25\""})
    void rejectsInvalidCountsInsteadOfRoundingOrDefaulting(String value) {
        assertThatThrownBy(() -> frame("\"object_count\":" + value)).isInstanceOf(IllegalStateException.class);
    }

    private FrameMapper.Frame frame(String countField) throws Exception {
        String source = UUID.randomUUID().toString();
        long at = java.time.Instant.now().toEpochMilli();
        String payload = "{\"source_id\":\"" + source + "\",\"owner_org_id\":\"org\",\"district_id\":\"district\","
                + "\"observed_at\":" + at + ",\"items\":[{\"external_target_id\":\"" + UUID.randomUUID()
                + "\",\"longitude\":117.5,\"latitude\":36.8,\"class_code\":\"BIRD\"," + countField + "}]}";
        return mapper.map(new InboxRow(UUID.randomUUID().toString(), NormalizedObservationMapper.PREFIX + source,
                UUID.randomUUID().toString(), source, at, payload));
    }
}
