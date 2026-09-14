package com.uav.lowaltitude.integration.mqtt;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Protocol B control envelope. Semantic interpretation of params belongs to the device, not fusion. */
public final class LingyunControlEnvelope {
    public static final Set<Integer> COMMANDS = Set.of(
            10000, 30000, 30001, 30002, 30003,
            50002, 50003, 50005, 50100, 50101,
            60002, 60003, 60100, 60101, 70001,
            90000, 100000);
    public static final Set<String> PARAM_KEYS = Set.of(
            "direction", "angle", "induceLongitude", "induceLatitude", "bands",
            "targetId", "targetLongitude", "targetLatitude", "targetAltitude",
            "duration", "defenseZoneId", "cameraId");
    public static final Set<String> REGISTRABLE = Set.of(
            "radar", "5ga", "tdoa", "aoa", "dcd", "rid", "oe", "dec", "ifr", "bsc");
    public static final Set<Integer> REQUIRES_TARGET_ID = Set.of(30002, 50005);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private LingyunControlEnvelope() { }

    public static boolean registrable(String type) { return type != null && REGISTRABLE.contains(type); }
    public static boolean controllable(String type) {
        return type != null && COMMANDS.stream().anyMatch(cmd -> type.equals(family(cmd)));
    }

    public static String family(int cmd) {
        if (cmd == 10000) return "radar";
        if (cmd >= 30000 && cmd <= 30003) return "oe";
        if (cmd == 50002 || cmd == 50003 || cmd == 50005 || cmd == 50100 || cmd == 50101) return "dec";
        if (cmd == 60002 || cmd == 60003 || cmd == 60100 || cmd == 60101) return "ifr";
        if (cmd == 70001) return "bsc";
        if (cmd == 90000) return "aoa";
        if (cmd == 100000) return "tdoa";
        return null;
    }

    public static String controlTopic(String provider, String type, String externalId) {
        return "bridge/" + provider + "/device_control/" + type + "/" + externalId;
    }

    public static String controlRespTopic(String provider, String type, String externalId) {
        return "bridge/" + provider + "/device_control_resp/" + type + "/" + externalId;
    }

    public static boolean isControlRespTopic(String topic) {
        if (topic == null) return false;
        String[] parts = topic.split("/", -1);
        return parts.length == 5 && "bridge".equals(parts[0]) && "device_control_resp".equals(parts[2]);
    }

    public static byte[] encode(String msgNo, String externalId, long time, int operationType, int operationCmd,
                                Map<String, Object> params) {
        try {
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("msgNo", msgNo);
            head.put("deviceId", externalId);
            head.put("time", time);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operationType", operationType);
            data.put("operationCmd", operationCmd);
            if (operationType != 0) data.put("operationParams", params == null ? Map.of() : params);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("head", head);
            root.put("data", data);
            return JSON.writeValueAsBytes(root);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot encode protocol B command", ex);
        }
    }

    public static Response decodeResponse(String topic, byte[] bytes) {
        if (bytes == null || bytes.length > 1_048_576) throw new Rejected("PAYLOAD_TOO_LARGE");
        if (!isControlRespTopic(topic)) throw new Rejected("INVALID_TOPIC");
        String[] parts = topic.split("/", -1);
        final JsonNode root;
        try {
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            root = JSON.readTree(raw);
        } catch (Exception ex) { throw new Rejected("INVALID_JSON_UTF8"); }
        if (root == null || !root.isObject()) throw new Rejected("INVALID_ENVELOPE");
        JsonNode head = root.path("head");
        JsonNode data = root.path("data");
        String msgNo = text(head.isObject() ? head : root, "msgNo");
        String deviceId = text(head.isObject() ? head : root, "deviceId");
        if (msgNo == null) throw new Rejected("MISSING_MSG_NO");
        if (deviceId != null && !deviceId.equals(parts[4])) throw new Rejected("IDENTITY_MISMATCH");
        int code = (int) integer(data.isObject() ? data : root, "code", 1);
        String msg = optionalText(data.isObject() ? data : root, "msg");
        return new Response(msgNo, parts[1], parts[3], parts[4], code, msg == null ? "" : msg, new String(bytes, StandardCharsets.UTF_8));
    }

    public static Map<String, Object> protocolParams(Map<String, Object> rest) {
        if (rest == null || rest.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        rest.forEach((key, value) -> {
            String mapped = switch (key) {
                case "target_id" -> "targetId";
                case "target_longitude" -> "targetLongitude";
                case "target_latitude" -> "targetLatitude";
                case "target_altitude" -> "targetAltitude";
                case "induce_longitude" -> "induceLongitude";
                case "induce_latitude" -> "induceLatitude";
                case "defense_zone_id" -> "defenseZoneId";
                case "camera_id" -> "cameraId";
                case "direction", "angle", "bands", "duration",
                        "targetId", "targetLongitude", "targetLatitude", "targetAltitude",
                        "induceLongitude", "induceLatitude", "defenseZoneId", "cameraId" -> key;
                default -> null;
            };
            if (mapped == null) throw new Rejected("UNKNOWN_PARAM:" + key);
            if (!PARAM_KEYS.contains(mapped)) throw new Rejected("UNKNOWN_PARAM:" + key);
            out.put(mapped, value);
        });
        Object duration = out.get("duration");
        if (duration != null) {
            int value = duration instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(duration));
            if (value < 10 || value > 300) throw new Rejected("DURATION_RANGE");
            out.put("duration", value);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isTextual()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        return value.isTextual() || value.isNumber() ? value.asText() : null;
    }
    private static long integer(JsonNode node, String field, long max) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() > max)
            throw new Rejected("INVALID_" + field.toUpperCase(java.util.Locale.ROOT));
        return value.longValue();
    }

    public record Response(String msgNo, String provider, String type, String externalId, int code, String msg, String json) { }

    public static class Rejected extends RuntimeException {
        public Rejected(String reason) { super(reason); }
    }
}
