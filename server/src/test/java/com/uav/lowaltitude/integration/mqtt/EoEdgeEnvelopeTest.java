package com.uav.lowaltitude.integration.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EoEdgeEnvelopeTest {
    private static final String TOPIC = "iot-reporting/cmlc/edge/1421840000010157";

    @Test void heartbeatUsesProtocolFieldsForSourceMsgIdAndDoesNotUseCommandMsgId() {
        String raw = heartbeat(1, "d0652e40-aa9c-4629-9bab-559e961c9030");
        var decoded = EoEdgeEnvelope.decode(TOPIC, raw.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.event()).isEqualTo("HeartBeat");
        assertThat(decoded.sourceMsgId()).isEqualTo("1731731424000:HeartBeat:4218400000101573981238947000262:d0652e40-aa9c-4629-9bab-559e961c9030");
        assertThat(decoded.hash()).hasSize(64);
        assertThat(decoded.trackingReport()).isFalse();
        assertThat(decoded.json()).isEqualTo(raw);
    }

    @Test void idleHeartbeatUsesDashWhenTaskIdIsNull() {
        String raw = heartbeat(0, null);
        var decoded = EoEdgeEnvelope.decode(TOPIC, raw.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.sourceMsgId()).endsWith(":-");
        assertThat(decoded.taskId()).isNull();
    }

    @Test void beginTrackingReportKeepsExtentionSpellingAndIsTrackingReport() {
        String raw = beginReport(200);
        var decoded = EoEdgeEnvelope.decode(TOPIC, raw.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.trackingReport()).isTrue();
        assertThat(decoded.json()).contains("\"extention\"");
        assertThat(decoded.json()).contains("\"aiStatus\"");
        assertThat(decoded.sourceMsgId()).startsWith("1731731306000:BeginTracking:");
        assertThat(decoded.sourceMsgId()).doesNotContain("20432e7b-2f33-4a1a-862d-e0f4d0a86e02");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AdjustDeviceInfo", "DetectToggle", "AbsMoveByAngle"})
    void unsupportedEventsDecodeButAreMarkedUnsupported(String event) {
        String raw = "{\"event\":\"" + event + "\",\"edgeId\":\"1421840000010157\",\"timestamp\":1731731306000,"
                + "\"metadata\":{\"deviceId\":\"4218400000101573981238947000262\",\"codeStatus\":200}}";
        var decoded = EoEdgeEnvelope.decode(TOPIC, raw.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.supported()).isFalse();
    }

    @Test void encodeBeginTrackingIncludesNullsAndProtocolKeys() {
        Map<String, Object> objectData = new LinkedHashMap<>();
        objectData.put("latitude", 30.5); objectData.put("longitude", 104.0); objectData.put("altitude", 42.1);
        objectData.put("speedX", 1.0); objectData.put("speedY", 0.0); objectData.put("speedZ", 0.0);
        objectData.put("dataId", "t1"); objectData.put("length", 0); objectData.put("width", 0); objectData.put("height", 0);
        objectData.put("objectType", 30); objectData.put("probability", 0.9);
        Map<String, Object> aiData = new LinkedHashMap<>();
        aiData.put("className", "drone"); aiData.put("isDetect", 1); aiData.put("isTrack", 1);
        Map<String, Object> extention = new LinkedHashMap<>();
        extention.put("mode", "full-auto"); extention.put("bootstrapSourceId", "t1");
        extention.put("bootstrapSourceType", 0); extention.put("msgId", "m1");
        byte[] bytes = EoEdgeEnvelope.encode("BeginTracking", "edge-1", 1L,
                EoEdgeEnvelope.beginMetadata("task-1", "dev-1", objectData, aiData, extention));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).contains("\"event\":\"BeginTracking\"");
        assertThat(json).contains("\"extention\"");
        assertThat(json).contains("\"speedX\"");
        var roundTrip = EoEdgeEnvelope.decode("iot-reporting/cmlc/edge/edge-1",
                "{\"event\":\"BeginTracking\",\"edgeId\":\"edge-1\",\"timestamp\":1,\"metadata\":{\"taskId\":\"task-1\",\"deviceId\":\"dev-1\",\"codeStatus\":200}}".getBytes(StandardCharsets.UTF_8));
        assertThat(roundTrip.taskId()).isEqualTo("task-1");
    }

    @Test void rejectsTopicMismatchAndInvalidUtf8() {
        assertThatThrownBy(() -> EoEdgeEnvelope.decode("bridge/a/device/radar/x", "{}".getBytes(StandardCharsets.UTF_8)))
                .hasMessage("INVALID_TOPIC");
        assertThatThrownBy(() -> EoEdgeEnvelope.decode(TOPIC, new byte[] {(byte) 0xff})).hasMessage("INVALID_JSON_UTF8");
        assertThatThrownBy(() -> EoEdgeEnvelope.decode("iot-reporting/cmlc/edge/other",
                heartbeat(1, null).getBytes(StandardCharsets.UTF_8))).hasMessage("IDENTITY_MISMATCH");
    }

    private static String heartbeat(int workState, String taskId) {
        String task = taskId == null ? "null" : "\"" + taskId + "\"";
        return "{\"event\":\"HeartBeat\",\"edgeId\":\"1421840000010157\",\"timestamp\":1731731424000,\"metadata\":{"
                + "\"deviceId\":\"4218400000101573981238947000262\",\"codeStatus\":200,\"message\":\"\",\"taskId\":" + task + ","
                + "\"workState\":" + workState + ",\"cameraStatus\":{\"hfov\":0.8,\"vfov\":0.4,\"panOrientAngle\":1.0,"
                + "\"tiltOrientAngle\":2.0,\"focalLen\":4.8,\"detectDist\":10.0,\"zoomIndex\":32}}}";
    }
    private static String beginReport(int code) {
        return "{\"event\":\"BeginTracking\",\"edgeId\":\"1421840000010157\",\"timestamp\":1731731306000,\"metadata\":{"
                + "\"taskId\":\"d0652e40-aa9c-4629-9bab-559e961c9030\",\"deviceId\":\"4218400000101573981238947000262\","
                + "\"codeStatus\":" + code + ",\"message\":\"\",\"workState\":1,"
                + "\"objectData\":{\"latitude\":1.0,\"longitude\":2.0,\"altitude\":3.0,\"speedX\":0,\"speedY\":0,\"speedZ\":0,"
                + "\"dataId\":\"583\",\"length\":1,\"width\":1,\"height\":1,\"objectType\":30,\"probability\":0.5},"
                + "\"cameraStatus\":{\"hfov\":1,\"vfov\":1,\"panOrientAngle\":1,\"tiltOrientAngle\":1,\"focalLen\":1,\"detectDist\":1},"
                + "\"aiStatus\":{\"className\":\"drone\",\"latitude\":1.1,\"longitude\":2.2,\"altitude\":3.3,\"width\":1,\"height\":1,"
                + "\"detectConfidence\":0.9,\"trackConfidence\":0.8},"
                + "\"extention\":{\"mode\":\"full-auto\",\"bootstrapSourceId\":\"t\",\"bootstrapSourceType\":0,"
                + "\"msgId\":\"20432e7b-2f33-4a1a-862d-e0f4d0a86e02\"}}}";
    }
}
