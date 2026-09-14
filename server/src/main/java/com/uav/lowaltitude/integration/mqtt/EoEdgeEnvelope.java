package com.uav.lowaltitude.integration.mqtt;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;

/**
 * Protocol C envelope only. Object/aiStatus semantics belong to fusion.
 * {@code extention} keeps the protocol spelling.
 */
public record EoEdgeEnvelope(String event, String edgeId, String deviceId, Long timestamp, String taskId,
                             Integer codeStatus, Integer workState, String json, String hash, String sourceMsgId,
                             boolean supported, boolean trackingReport) {
    public static final String PROTOCOL = DeviceProtocolCodes.EO_EDGE_MQTT_20250826;
    public static final Set<String> SUPPORTED = Set.of("HeartBeat", "BeginTracking", "EndTracking", "CameraStatus");
    public static final Set<String> UNSUPPORTED = Set.of(
            "AdjustDeviceInfo", "DetectToggle", "TrackToggle", "SetHome", "MoveHome", "AbsMoveByAngle");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static EoEdgeEnvelope decode(String topic, byte[] bytes) {
        if (bytes.length > 1_048_576) throw new Rejected("PAYLOAD_TOO_LARGE");
        String[] parts = topic.split("/", -1);
        if (parts.length != 4 || !"iot-reporting".equals(parts[0]) || !"cmlc".equals(parts[1]) || !"edge".equals(parts[2])
                || parts[3].isBlank())
            throw new Rejected("INVALID_TOPIC");
        final String raw;
        final JsonNode root;
        try {
            raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            root = JSON.readTree(raw);
        } catch (Exception ex) { throw new Rejected("INVALID_JSON_UTF8"); }
        if (root == null || !root.isObject()) throw new Rejected("INVALID_ENVELOPE");
        String event = text(root, "event");
        String edgeId = text(root, "edgeId");
        if (!parts[3].equals(edgeId)) throw new Rejected("IDENTITY_MISMATCH");
        long timestamp = integer(root, "timestamp", Long.MAX_VALUE);
        JsonNode metadata = root.get("metadata");
        if (metadata == null || !metadata.isObject()) throw new Rejected("INVALID_ENVELOPE");
        String deviceId = text(metadata, "deviceId");
        String taskId = optionalText(metadata, "taskId");
        Integer codeStatus = metadata.has("codeStatus") && !metadata.get("codeStatus").isNull()
                ? (int) integer(metadata, "codeStatus", Integer.MAX_VALUE) : null;
        Integer workState = metadata.has("workState") && !metadata.get("workState").isNull()
                ? (int) integer(metadata, "workState", 2) : null;
        boolean known = SUPPORTED.contains(event) || UNSUPPORTED.contains(event);
        if (!known) throw new Rejected("UNKNOWN_EVENT");
        boolean supported = SUPPORTED.contains(event);
        if ("HeartBeat".equals(event) && (codeStatus == null || workState == null || !metadata.path("cameraStatus").isObject()))
            throw new Rejected("INVALID_ENVELOPE");
        if (("BeginTracking".equals(event) || "EndTracking".equals(event)) && (codeStatus == null || taskId == null))
            throw new Rejected("INVALID_ENVELOPE");
        if ("CameraStatus".equals(event) && codeStatus == null) throw new Rejected("INVALID_ENVELOPE");
        boolean trackingReport = "BeginTracking".equals(event) && codeStatus != null;
        String key = timestamp + ":" + event + ":" + deviceId + ":" + (taskId == null ? "-" : taskId);
        return new EoEdgeEnvelope(event, edgeId, deviceId, timestamp, taskId, codeStatus, workState, raw, hash(bytes),
                key, supported, trackingReport);
    }

    public static byte[] encode(String event, String edgeId, long timestamp, Map<String, Object> metadata) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("event", event);
            root.put("edgeId", edgeId);
            root.put("timestamp", timestamp);
            root.set("metadata", JSON.valueToTree(withNulls(metadata)));
            return JSON.writeValueAsBytes(root);
        } catch (Exception ex) { throw new IllegalStateException("cannot encode protocol C event", ex); }
    }

    public static Map<String, Object> beginMetadata(String taskId, String deviceId, Map<String, Object> objectData,
            Map<String, Object> aiData, Map<String, Object> extention) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("deviceId", deviceId);
        metadata.put("objectData", objectData);
        metadata.put("aiData", aiData);
        metadata.put("extention", extention);
        return metadata;
    }

    public static Map<String, Object> endMetadata(String taskId, String deviceId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("deviceId", deviceId);
        return metadata;
    }

    public static Map<String, Object> cameraMetadata(String deviceId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("deviceId", deviceId);
        return metadata;
    }

    private static Map<String, Object> withNulls(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) copy.putAll(source);
        return copy;
    }

    private static String text(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new Rejected("INVALID_" + key.toUpperCase());
        return value.asText();
    }

    private static String optionalText(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) return null;
        return value.asText();
    }

    private static long integer(JsonNode root, String key, long max) {
        JsonNode value = root.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() > max)
            throw new Rejected("INVALID_" + key.toUpperCase(java.util.Locale.ROOT));
        return value.longValue();
    }

    public static String hash(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public static class Rejected extends RuntimeException {
        public Rejected(String reason) { super(reason); }
    }
}
