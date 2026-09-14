package com.uav.lowaltitude.modules.fusion.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper.Frame;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper.Item;

/** 协议 C：光电只在跟踪任务期间有观测，引导源不入库。 */
class EoTrackingReportMapperTest {
    private static final long TIME = 1731731306000L;
    private final EoTrackingReportMapper mapper = new EoTrackingReportMapper(new ObjectMapper());

    private static InboxRow inbox(String payload) {
        return new InboxRow("inbox-1", "eo-edge:1421840000010157", "msg-1", "src-eo", TIME, payload);
    }

    private static String report(String event, int workState, String aiStatus) {
        return "{\"event\":\"" + event + "\",\"edgeId\":\"1421840000010157\",\"timestamp\":" + TIME + ",\"metadata\":{"
                + "\"taskId\":\"T-9030\",\"deviceId\":\"D-1\",\"codeStatus\":200,\"workState\":" + workState + ","
                + "\"objectData\":{\"latitude\":5.6,\"longitude\":3.15,\"altitude\":53.5,\"dataId\":\"583\",\"objectType\":40,\"probability\":0.567},"
                + "\"cameraStatus\":{\"hfov\":5.6,\"focalLen\":88,\"detectDist\":164.4}"
                + (aiStatus == null ? "" : ",\"aiStatus\":" + aiStatus)
                + ",\"extention\":{\"mode\":\"full-auto\",\"bootstrapSourceId\":\"Sf855715844Task1\",\"bootstrapSourceType\":0}}}";
    }

    private static final String AI_STATUS =
            "{\"className\":\"drone\",\"latitude\":37.61,\"longitude\":118.15,\"altitude\":53.57,\"detectConfidence\":0.9,\"trackConfidence\":0.8}";

    @Test
    void beginTrackingWhileWorkingProducesOneObservation() {
        Frame frame = mapper.map(inbox(report("BeginTracking", 1, AI_STATUS)));
        assertThat(frame.items()).hasSize(1);
        Item item = frame.items().get(0);
        assertThat(item.classCode()).isEqualTo("UAV");
        assertThat(item.classConfidence()).isEqualTo(0.9);
        assertThat(item.longitude()).isEqualTo(118.15);
        assertThat(item.latitude()).isEqualTo(37.61);
        assertThat(item.classSource()).isEqualTo("EO_TRACKING");
        assertThat(item.quality()).containsEntry("track_confidence", 0.8).containsEntry("edge_id", "1421840000010157");
        // 高度基准与协议 A 同样未确认，不进高度列。
        assertThat(item.altitudeAmslM()).isNull();
        assertThat(item.quality()).containsEntry("altitude_raw", 53.57).containsEntry("altitude_datum", "REFERENCE_UNKNOWN");
        // 一个跟踪任务就是光电眼里的一个目标。
        assertThat(frame.sessionKey()).isEqualTo("T-9030");
        assertThat(item.externalTargetId()).isEqualTo("T-9030");
    }

    @Test
    void endTrackingAndHeartBeatProduceNoObservationButAreNotErrors() {
        // 光电不是持续扫描的传感器：把心跳当观测会凭空造出"目标一直可见"。
        assertThat(mapper.map(inbox(report("EndTracking", 1, AI_STATUS))).items()).isEmpty();
        assertThat(mapper.map(inbox("{\"event\":\"HeartBeat\",\"edgeId\":\"E-1\",\"timestamp\":" + TIME
                + ",\"metadata\":{\"workState\":1,\"cameraStatus\":{}}}")).items()).isEmpty();
        // 上报了 BeginTracking 但设备不在跟踪态，同样没有观测。
        assertThat(mapper.map(inbox(report("BeginTracking", 2, AI_STATUS))).items()).isEmpty();
        assertThat(mapper.map(inbox(report("BeginTracking", 1, null))).items()).isEmpty();
    }

    @Test
    void bootstrapSourceIsRecordedButNeverIngestedAsAnObservation() {
        Frame frame = mapper.map(inbox(report("BeginTracking", 1, AI_STATUS)));
        assertThat(frame.items()).hasSize(1);
        Item item = frame.items().get(0);
        // 引导源是另一个来源已经上报过的同一个物理目标：再入一次库就会自己和自己配对。
        assertThat(item.quality()).containsEntry("bootstrap_source_id", "Sf855715844Task1")
                .containsEntry("bootstrap_source_type", 0L).containsEntry("bootstrap_source_data_id", "583");
        assertThat(item.longitude()).isNotEqualTo(3.15);
        assertThat(item.latitude()).isNotEqualTo(5.6);
        assertThat(item.quality()).doesNotContainKey("object_data");
    }

    @Test
    void unknownClassNamesStayEmptyWithTheRawStringKept() {
        Item item = mapper.map(inbox(report("BeginTracking", 1,
                "{\"className\":\"balloon\",\"latitude\":37.6,\"longitude\":118.1,\"detectConfidence\":0.4}"))).items().get(0);
        // className 的完整取值表还没拿到，猜一个映射比留空更危险。
        assertThat(item.classCode()).isNull();
        assertThat(item.quality()).containsEntry("class_name_raw", "balloon");
    }

    @Test
    void malformedReportsFailTheFrame() {
        assertThatThrownBy(() -> mapper.map(inbox("{\"edgeId\":\"E-1\",\"timestamp\":1}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("event");
        assertThatThrownBy(() -> mapper.map(inbox("{\"event\":\"BeginTracking\",\"edgeId\":\"E-1\"}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("timestamp");
    }
}
