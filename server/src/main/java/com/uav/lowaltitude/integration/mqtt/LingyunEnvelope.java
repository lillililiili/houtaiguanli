package com.uav.lowaltitude.integration.mqtt;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;

/** Only the protocol envelope is interpreted here. Object semantics belong to fusion. */
public record LingyunEnvelope(String provider, String type, String externalId, boolean sensing,
                              String json, String hash, Long ptTime, Integer msgCnt, Integer workState,
                              Double longitude, Double latitude, Double altitude) {
    public static final String PROTOCOL = DeviceProtocolCodes.LINGYUN_MQTT_V8_6;
    /** 协议 A 附录探测类：只这些类型的 SenseData 进 inbox。 */
    public static final Map<String, Integer> SENSING_TYPES = Map.of(
            "radar", 1, "5ga", 0, "tdoa", 10, "aoa", 9, "dcd", 11, "rid", 102);
    /**
     * 工参可受理的附录缩写：探测六类 + 诱骗/干扰/驱鸟炮。
     * 不登记 cm/oe/isrs；光电工参走协议 C 心跳。
     */
    public static final Map<String, Integer> TYPES = Map.of(
            "radar", 1, "5ga", 0, "tdoa", 10, "aoa", 9, "dcd", 11, "rid", 102,
            "dec", 5, "ifr", 6, "bsc", 12);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public static LingyunEnvelope decode(String topic, byte[] bytes) {
        if (bytes.length > 1_048_576) throw new Rejected("PAYLOAD_TOO_LARGE");
        String[] parts = topic.split("/", -1);
        if (parts.length != 5 || !parts[0].equals("bridge")
                || !(parts[2].equals("device") || parts[2].equals("device_data")))
            throw new Rejected("INVALID_TOPIC");
        boolean sense = parts[2].equals("device_data");
        if (sense) {
            if (!SENSING_TYPES.containsKey(parts[3])) throw new Rejected("UNSUPPORTED_TYPE");
        } else if (!TYPES.containsKey(parts[3])) {
            throw new Rejected("UNSUPPORTED_TYPE");
        }
        final String raw;
        final JsonNode root;
        try {
            raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            root = JSON.readTree(raw);
        } catch (Exception ex) { throw new Rejected("INVALID_JSON_UTF8"); }
        if (root == null || !root.isObject()) throw new Rejected("INVALID_ENVELOPE");
        if (!root.path("deviceId").isTextual() || !root.path("deviceId").asText().equals(parts[4]))
            throw new Rejected("IDENTITY_MISMATCH");
        Long ptTime = root.has("ptTime") ? integer(root, "ptTime", Long.MAX_VALUE) : null;
        if (sense) {
            if (ptTime == null || !root.path("objects").isArray()) throw new Rejected("INVALID_ENVELOPE");
            int count = (int) integer(root, "msgCnt", Integer.MAX_VALUE);
            return new LingyunEnvelope(parts[1], parts[3], parts[4], true, raw, hash(bytes), ptTime, count, null,
                    null, null, null);
        }
        if (!root.path("providerCode").isTextual() || !root.path("providerCode").asText().equals(parts[1])
                || integer(root, "deviceType", Integer.MAX_VALUE) != TYPES.get(parts[3]))
            throw new Rejected("IDENTITY_MISMATCH");
        if (!root.path("deviceName").isTextual() || root.path("deviceName").asText().isBlank())
            throw new Rejected("INVALID_ENVELOPE");
        int state = (int) integer(root, "workState", 2);
        Double longitude = optionalNumber(root, "deviceLongitude");
        Double latitude = optionalNumber(root, "deviceLatitude");
        if ((longitude == null) != (latitude == null)) throw new Rejected("INVALID_ENVELOPE");
        if (longitude != null && (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90))
            throw new Rejected("INVALID_ENVELOPE");
        return new LingyunEnvelope(parts[1], parts[3], parts[4], false, raw, hash(bytes), ptTime, null, state,
                longitude, latitude, optionalNumber(root, "deviceAltitude"));
    }

    private static long integer(JsonNode root, String key, long max) {
        JsonNode value = root.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() > max)
            throw new Rejected("INVALID_" + key.toUpperCase(java.util.Locale.ROOT));
        return value.longValue();
    }

    private static Double optionalNumber(JsonNode root, String key) {
        if (!root.has(key) || root.get(key).isNull()) return null;
        JsonNode value = root.get(key);
        if (!value.isNumber()) throw new Rejected("INVALID_" + key.toUpperCase(java.util.Locale.ROOT));
        double number = value.doubleValue();
        if (!Double.isFinite(number)) throw new Rejected("INVALID_" + key.toUpperCase(java.util.Locale.ROOT));
        return number;
    }

    public static String hash(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public static class Rejected extends RuntimeException {
        public Rejected(String reason) { super(reason); }
    }
}
